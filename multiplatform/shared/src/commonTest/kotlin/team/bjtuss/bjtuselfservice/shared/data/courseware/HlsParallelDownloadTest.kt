package team.bjtuss.bjtuselfservice.shared.data.courseware

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.files.StreamingFileSink
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

/**
 * 录播并发分片下载：写出的字节、进度口径、失败与取消的行为都必须和串行版一致。
 *
 * 全部用假数据源，不连真实录播服务器。
 */
class HlsParallelDownloadTest {
    private val base = "https://lmt6.bjtu.edu.cn:8880/vod11/_definst_//a/b.mp4/"

    @Test
    fun parallelFetchWritesTheSameBytesAndProgressAsSerial() = runBlocking {
        val count = 12
        val bodies = (0 until count).associate { index -> "$base/seg-$index.ts" to segmentBody(index) }

        // 串行基线：concurrency = 1 走逐个抓。
        val serial = RecordingDownload(base, bodies).record(concurrency = 1)
        // 并发版：靠后的分片先回来，交付仍必须按索引顺序。
        val parallel = RecordingDownload(base, bodies).record(concurrency = 4)

        assertEquals(serial.chunks.map { it.size }, parallel.chunks.map { it.size }, "分片边界也要一致")
        assertContentEquals(serial.flatBytes(), parallel.flatBytes(), "并发抓取写出的字节必须和串行版逐字节一致")
        assertEquals(serial.progress, parallel.progress, "进度口径不变：已完成分片数与累计字节数一致")
        assertEquals(0, parallel.progress.first().completedSegments)
        assertEquals(count, parallel.progress.last().completedSegments)
        assertEquals(
            parallel.progress.map { it.writtenBytes },
            parallel.progress.map { report -> parallel.chunks.take(report.completedSegments).sumOf { it.size.toLong() } },
            "累计字节数等于已写盘字节数",
        )
    }

    @Test
    fun parallelFetchKeepsRequestsInFlightBoundedAndNeverBuffersWholeRecording() = runBlocking {
        val count = 12
        val bodies = (0 until count).associate { index -> "$base/seg-$index.ts" to segmentBody(index) }
        val transport = FakeTransport(bodies) { index -> if (index < 0) 0L else (count - index) * 5L }
        val destination = FakeDestination()

        HlsRecordingDownloader(transport, retryDelayMillis = 0).download(
            "$base/playlist.m3u8",
            StreamingFileSink { bytes ->
                transport.deliveredOne()
                destination.write(bytes)
            },
        ) { }

        assertEquals(count, destination.chunks.size)
        assertTrue(transport.maxInFlight > 1, "应当真的并发抓取，而不是退化成串行")
        assertTrue(
            transport.maxInFlight <= DEFAULT_SEGMENT_CONCURRENCY,
            "在飞请求数不应超过并发上限，实际 ${transport.maxInFlight}",
        )
        assertTrue(
            transport.maxUndelivered <= DEFAULT_SEGMENT_CONCURRENCY + 2,
            "已取回但还没写盘的分片必须有界，实际 ${transport.maxUndelivered}",
        )
        assertTrue(transport.maxUndelivered < count, "不能把整路录像一次性读进内存")
    }

    @Test
    fun failedSegmentThrowsWithSegmentDetailAndLeavesNoPartialFile() = runBlocking {
        val failing = "$base/seg-2.ts?token=secret-value"
        val bodies = mapOf(
            "$base/seg-0.ts" to segmentBody(0),
            "$base/seg-1.ts" to segmentBody(1),
            failing to segmentBody(2),
        )
        // 失败分片拖到最后，前面两个分片一定已经写盘。
        val transport = FakeTransport(bodies, missing = setOf(failing)) { index -> if (index == 2) 200L else 0L }
        val destination = FakeDestination()

        val error = assertFailsWith<HlsDownloadException> {
            HlsRecordingDownloader(transport, retryDelayMillis = 0).download(
                "$base/playlist.m3u8",
                StreamingFileSink { bytes ->
                    transport.deliveredOne()
                    destination.write(bytes)
                },
            ) { }
        }

        assertEquals(HlsDownloadFailure.NETWORK, error.reason)
        assertTrue(error.message!!.contains("segment 3"), "失败信息要能看出是哪个分片：${error.message}")
        assertTrue(error.message!!.contains("$base/seg-2.ts"), "失败信息要带地址：${error.message}")
        assertFalse(error.message!!.contains("secret-value"), "失败信息不能带查询串里的 token：${error.message}")
        assertEquals(2, destination.chunks.size, "失败后不再继续写盘")
        assertEquals(3, transport.requests.count { it == failing }, "单个分片仍重试 3 次")

        // 调用方收尾（PlaybackTaskManager / HomeworkFileGateway 都是失败即 abort）：半成品不保留。
        destination.abort()
        assertTrue(destination.aborted)
        assertEquals(0, destination.size(), "失败后不留半成品")
    }

    @Test
    fun cancellingStopsTheDownloadAndLeavesNoPartialFile() = runBlocking {
        val count = 6
        val bodies = (0 until count).associate { index -> "$base/seg-$index.ts" to segmentBody(index) }
        // 第一个分片很快，后面的故意很慢，取消时还有请求在飞。
        val transport = FakeTransport(bodies) { index -> if (index <= 0) 0L else 10_000L }
        val destination = FakeDestination()

        val job = launch {
            HlsRecordingDownloader(transport, retryDelayMillis = 0).download(
                "$base/playlist.m3u8",
                StreamingFileSink { bytes ->
                    transport.deliveredOne()
                    destination.write(bytes)
                },
            ) { }
        }
        withTimeout(5_000) {
            while (destination.chunks.isEmpty()) delay(10)
        }
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, destination.chunks.size, "取消后不再写盘")
        assertEquals(0, transport.inFlight, "取消后在飞的请求都要停掉")
        destination.abort()
        assertEquals(0, destination.size(), "取消后不留半成品")
    }

    @Test
    fun sourceWithoutConcurrencySupportStillDownloadsInOrder() = runBlocking {
        // 平台自带后台下载会话的来源只实现 fetchInOrder，默认实现要能退回串行。
        val bodies = mapOf(
            "$base/seg-0.ts" to segmentBody(0),
            "$base/seg-1.ts" to segmentBody(1),
        )
        val transport = FakeTransport(bodies)
        val source = object : HlsSegmentSource {
            override suspend fun fetchInOrder(urls: List<String>, onSegment: suspend (index: Int, bytes: ByteArray) -> Unit) {
                urls.forEachIndexed { index, url -> onSegment(index, bodies.getValue(url)) }
            }
        }
        val destination = FakeDestination()

        HlsRecordingDownloader(transport, retryDelayMillis = 0, segmentSource = source).download(
            "$base/playlist.m3u8",
            StreamingFileSink { destination.write(it) },
        ) { }

        assertEquals(2, destination.chunks.size)
        assertContentEquals(segmentBody(0) + segmentBody(1), destination.chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk })
    }

    private fun segmentBody(index: Int): ByteArray = ("seg-$index:" + "x".repeat(index)).encodeToByteArray()

    /** 跑一遍下载，记录写出的每一片和每次进度。 */
    private class RecordingDownload(
        private val base: String,
        private val bodies: Map<String, ByteArray>,
    ) {
        val chunks = mutableListOf<ByteArray>()
        val progress = mutableListOf<HlsDownloadProgress>()

        suspend fun record(concurrency: Int): RecordingDownload {
            val transport = FakeTransport(bodies)
            HlsRecordingDownloader(transport, retryDelayMillis = 0, concurrency = concurrency).download(
                "$base/playlist.m3u8",
                StreamingFileSink { chunks += it },
            ) { progress += it }
            return this
        }

        fun flatBytes(): ByteArray = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
    }

    /** 模拟调用方的保存目标：失败/取消后 abort，半成品不保留。 */
    private class FakeDestination {
        val chunks = mutableListOf<ByteArray>()
        var aborted = false

        fun write(bytes: ByteArray) {
            chunks += bytes
        }

        fun abort() {
            chunks.clear()
            aborted = true
        }

        fun size(): Int = chunks.sumOf { it.size }
    }

    /**
     * 假传输：按地址返回分片（播放列表按分片地址现拼），并记录在飞请求数与
     * 「已取回但还没写盘」的分片数。计数都在同一个线程（runBlocking 的事件循环）上改，不需要加锁。
     */
    private class FakeTransport(
        private val bodies: Map<String, ByteArray>,
        private val missing: Set<String> = emptySet(),
        private val delayFor: (Int) -> Long = { 0L },
    ) : SchoolHttpTransport {
        val requests = mutableListOf<String>()
        var inFlight = 0
        var maxInFlight = 0
        var undeclared = 0
        var maxUndelivered = 0

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = executePublic(request)

        override suspend fun executePublic(request: SchoolHttpRequest): SchoolHttpResponse {
            requests += request.url
            inFlight++
            if (inFlight > maxInFlight) maxInFlight = inFlight
            try {
                val index = indexOf(request.url)
                delay(delayFor(index))
                if (request.url in missing) return SchoolHttpResponse(404, request.url)
                if (request.url.endsWith("playlist.m3u8")) {
                    return SchoolHttpResponse(200, request.url, body = playlistBody().encodeToByteArray())
                }
                val body = bodies[request.url] ?: return SchoolHttpResponse(404, request.url)
                undeclared++
                if (undeclared > maxUndelivered) maxUndelivered = undeclared
                return SchoolHttpResponse(200, request.url, body = body)
            } finally {
                inFlight--
            }
        }

        /** 写盘侧回调：取回但还没写盘的分片少了一个。 */
        fun deliveredOne() {
            undeclared--
        }

        override suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse = execute(request)

        override fun clearSession() = Unit

        private fun playlistBody(): String = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:10\n")
            bodies.keys.filter { it.contains("seg-") }.forEach { url ->
                append("#EXTINF:10.000,\n${url.substringAfterLast('/')}\n")
            }
            append("#EXT-X-ENDLIST\n")
        }

        private fun indexOf(url: String): Int =
            url.substringAfterLast("seg-").substringBefore(".ts").substringBefore("?").toIntOrNull() ?: -1
    }
}

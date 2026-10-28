package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

class FastMp4PartJoinerTest {
    // 由现有 64×48 测试 TS 无损封装生成，无真实课程画面或账号数据。
    private fun source(): Pair<ByteArray, Int> {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/media/synthetic-muxer.mp4")).use { it.readBytes() }
        return bytes to readTracks(bytes).getValue("soun").hashes.size
    }

    @Test fun joinsSamplePayloadsAndTimestampsWithoutReencoding() = runBlocking {
        val directory = Files.createTempDirectory("bjtu-fast-join-").toFile().canonicalFile
        try {
            val (firstBytes, firstAudioCount) = source()
            val (secondBytes, secondAudioCount) = source()
            val first = File(directory, "part0.mp4").apply { writeBytes(firstBytes) }
            val second = File(directory, "part1.mp4").apply { writeBytes(secondBytes) }
            val out = File(directory, "joined.mp4")
            val progress = mutableListOf<Float>()
            FastMp4PartJoiner.concatenate(listOf(
                Mp4JoinPart(first, 0, 2_000_000, firstAudioCount.toLong()),
                Mp4JoinPart(second, 2_000_000, 4_000_000, secondAudioCount.toLong()),
            ), 0, out, coroutineContext, progress::add)
            val sourceTracks = listOf(readTracks(firstBytes), readTracks(secondBytes))
            val joined = readTracks(out.readBytes())
            for (kind in listOf("vide", "soun")) {
                val left = sourceTracks[0].getValue(kind)
                val right = sourceTracks[1].getValue(kind)
                val result = joined.getValue(kind)
                assertEquals(left.scale, result.scale)
                assertEquals(left.hashes + right.hashes, result.hashes, "$kind 样本数据必须原样保留")
                if (kind == "vide") {
                    assertEquals(left.pts + right.pts.map { it + 2 * result.scale }, result.pts)
                    assertEquals(left.keys + right.keys.map { it + left.hashes.size }, result.keys)
                } else {
                    assertEquals(result.hashes.indices.map { it * 1024L }, result.pts)
                }
            }
            assertEquals(0f, progress.first())
            assertEquals(1f, progress.last())
            assertTrue(progress.zipWithNext().all { it.first <= it.second })
        } finally { removeDirectory(directory) }
    }

    @Test fun rejectsDiscontinuousPartsBeforeWritingOutput() = runBlocking {
        val directory = Files.createTempDirectory("bjtu-fast-join-").toFile().canonicalFile
        try {
            val (bytes, count) = source()
            val part = File(directory, "part.mp4").apply { writeBytes(bytes) }
            val output = File(directory, "joined.mp4")
            assertFailsWith<UnsupportedMp4Join> {
                FastMp4PartJoiner.concatenate(listOf(Mp4JoinPart(part, 1, 2_000_000, count.toLong())),
                    0, output, coroutineContext) {}
            }
            assertTrue(!output.exists())
        } finally { removeDirectory(directory) }
    }

    @Test fun cancellationRemovesPartialOutputAndKeepsSourceParts() = runBlocking {
        val directory = Files.createTempDirectory("bjtu-fast-join-").toFile().canonicalFile
        try {
            val (bytes, count) = source()
            val part = File(directory, "part.mp4").apply { writeBytes(bytes) }
            val output = File(directory, "joined.mp4")
            val cancellation = Job()
            assertFailsWith<CancellationException> {
                FastMp4PartJoiner.concatenate(listOf(
                    Mp4JoinPart(part, 0, 2_000_000, count.toLong()),
                    Mp4JoinPart(part, 2_000_000, 4_000_000, count.toLong()),
                ), 0, output, cancellation) { if (it > 0f && it < 1f) cancellation.cancel() }
            }
            assertTrue(!output.exists())
            assertContentEquals(bytes, part.readBytes())
        } finally { removeDirectory(directory) }
    }

    @Test fun rejectsAudioTimescaleMismatchBeforeWriting() = runBlocking {
        val directory = Files.createTempDirectory("bjtu-fast-join-").toFile().canonicalFile
        try {
            val (bytes, count) = source()
            val tag = "mp4a".encodeToByteArray()
            val at = (0..bytes.size - tag.size).first { index -> tag.indices.all { bytes[index + it] == tag[it] } }
            // 改样本描述中的采样率，保留 mdhd 时间尺度，模拟不兼容文件。
            val rate = 44_100 shl 16
            repeat(4) { offset -> bytes[at + 28 + offset] = (rate ushr (24 - offset * 8)).toByte() }
            val part = File(directory, "part.mp4").apply { writeBytes(bytes) }
            val output = File(directory, "joined.mp4")
            assertFailsWith<UnsupportedMp4Join> {
                FastMp4PartJoiner.concatenate(listOf(Mp4JoinPart(part, 0, 2_000_000, count.toLong())),
                    0, output, coroutineContext) {}
            }
            assertTrue(!output.exists())
        } finally { removeDirectory(directory) }
    }

    /** 可选的本机真实分段回归；数据只来自忽略目录，不进入仓库。 */
    @Test fun joinsCapturedAndroidPartsWhenConfigured() = runBlocking {
        val path = System.getenv("BJTU_ANDROID_PARTS_DIR")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val directory = File(path).canonicalFile
        require(directory.name == "parallel-probe-parts" && directory.parentFile.name == "android-full-acceptance")
        val record = Json.parseToJsonElement(File(directory, "index.json").readText()).jsonObject
        val parts = record.getValue("parts").jsonArray.mapIndexed { index, item ->
            val part = item.jsonObject
            Mp4JoinPart(File(directory, "part-$index.mp4"), part.getValue("startUs").jsonPrimitive.long,
                part.getValue("endUs").jsonPrimitive.long, part["audioFrames"]?.jsonPrimitive?.longOrNull)
        }
        val output = Files.createTempFile(directory.parentFile.toPath(), "fast-join-kotlin-", ".mp4").toFile()
        val start = System.nanoTime()
        FastMp4PartJoiner.concatenate(parts, record["audioOriginUs"]?.jsonPrimitive?.longOrNull, output, coroutineContext) {}
        println("captured-parts output=${output.name}, elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
        assertTrue(output.length() > 0)
    }

    @Test fun joinsManyCapturedPartsWhenConfigured() = runBlocking {
        if (System.getenv("BJTU_ANDROID_JOIN_STRESS") != "1") return@runBlocking
        val directory = File(checkNotNull(System.getenv("BJTU_ANDROID_PARTS_DIR"))).canonicalFile
        require(directory.name == "parallel-probe-parts" && directory.parentFile.name == "android-full-acceptance")
        val record = Json.parseToJsonElement(File(directory, "index.json").readText()).jsonObject
        val originals = record.getValue("parts").jsonArray
        var startUs = 0L
        val parts = List(102) { index ->
            val original = originals[index % originals.size].jsonObject
            val length = original.getValue("endUs").jsonPrimitive.long - original.getValue("startUs").jsonPrimitive.long
            Mp4JoinPart(File(directory, "part-${index % originals.size}.mp4"), startUs, startUs + length,
                original["audioFrames"]?.jsonPrimitive?.longOrNull).also { startUs += length }
        }
        val output = Files.createTempFile(directory.parentFile.toPath(), "fast-join-stress-", ".mp4").toFile()
        val start = System.nanoTime()
        FastMp4PartJoiner.concatenate(parts, 0L, output, coroutineContext) {}
        println("captured-stress output=${output.name}, parts=${parts.size}, elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
        assertTrue(output.length() > parts.sumOf { it.file.length() } * 9 / 10)
    }

    private fun removeDirectory(directory: File) {
        check(directory.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile &&
            directory.name.startsWith("bjtu-fast-join-"))
        directory.deleteRecursively()
    }

    private data class Box(val name: String, val start: Int, val size: Int, val head: Int)
    private data class Samples(val scale: Long, val pts: List<Long>, val keys: List<Int>, val hashes: List<String>)
    private fun readTracks(data: ByteArray): Map<String, Samples> {
        fun u32(at: Int): Long = (0..3).fold(0L) { result, i -> (result shl 8) or (data[at + i].toLong() and 255) }
        fun u64(at: Int) = (u32(at) shl 32) or u32(at + 4)
        fun boxes(start: Int, end: Int): List<Box> {
            val list = mutableListOf<Box>(); var at = start
            while (at < end) {
                val size32 = u32(at); val head = if (size32 == 1L) 16 else 8
                val size = (if (size32 == 1L) u64(at + 8) else size32).toInt()
                check(size >= head && at + size <= end)
                list += Box(data.copyOfRange(at + 4, at + 8).decodeToString(), at, size, head)
                at += size
            }
            return list
        }
        fun children(box: Box) = boxes(box.start + box.head, box.start + box.size)
        fun find(box: Box, name: String) = children(box).single { it.name == name }
        val moov = boxes(0, data.size).single { it.name == "moov" }
        return children(moov).filter { it.name == "trak" }.associate { trak ->
            val mdia = find(trak, "mdia")
            val hdlr = find(mdia, "hdlr")
            val kind = data.copyOfRange(hdlr.start + hdlr.head + 8, hdlr.start + hdlr.head + 12).decodeToString()
            val mdhd = find(mdia, "mdhd")
            val scale = u32(mdhd.start + mdhd.head + if (data[mdhd.start + mdhd.head].toInt() == 1) 20 else 12)
            val stbl = find(find(mdia, "minf"), "stbl")
            val tables = children(stbl).associateBy { it.name }
            val stsz = tables.getValue("stsz"); val sizeAt = stsz.start + stsz.head
            val sampleCount = u32(sizeAt + 8).toInt(); val commonSize = u32(sizeAt + 4).toInt()
            val sizes = List(sampleCount) { if (commonSize == 0) u32(sizeAt + 12 + it * 4).toInt() else commonSize }
            val stts = tables.getValue("stts"); val timeAt = stts.start + stts.head
            val durations = mutableListOf<Long>()
            repeat(u32(timeAt + 4).toInt()) { index ->
                val count = u32(timeAt + 8 + index * 8).toInt(); val duration = u32(timeAt + 12 + index * 8)
                repeat(count) { durations += duration }
            }
            var clock = 0L
            val pts = durations.map { duration -> clock.also { clock += duration } }
            val stsc = tables.getValue("stsc"); val chunkAt = stsc.start + stsc.head
            val chunks = List(u32(chunkAt + 4).toInt()) { index ->
                u32(chunkAt + 8 + index * 12).toInt() to u32(chunkAt + 12 + index * 12).toInt()
            }
            val offsetBox = tables["co64"] ?: tables.getValue("stco")
            val offsetAt = offsetBox.start + offsetBox.head; val width = if (offsetBox.name == "co64") 8 else 4
            val offsets = List(u32(offsetAt + 4).toInt()) { index ->
                (if (width == 8) u64(offsetAt + 8 + index * width) else u32(offsetAt + 8 + index * width)).toInt()
            }
            var sample = 0; var run = 0
            val hashes = mutableListOf<String>()
            offsets.forEachIndexed { index, offset ->
                while (run + 1 < chunks.size && chunks[run + 1].first <= index + 1) run++
                var pos = offset
                repeat(chunks[run].second) {
                    val size = sizes[sample++]
                    hashes += MessageDigest.getInstance("SHA-256").digest(data.copyOfRange(pos, pos + size)).joinToString("") { "%02x".format(it) }
                    pos += size
                }
            }
            assertEquals(sampleCount, hashes.size)
            val stss = tables["stss"]
            val keys = if (stss == null) (1..sampleCount).toList() else {
                val at = stss.start + stss.head
                List(u32(at + 4).toInt()) { u32(at + 8 + it * 4).toInt() }
            }
            kind to Samples(scale, pts, keys, hashes)
        }
    }
}

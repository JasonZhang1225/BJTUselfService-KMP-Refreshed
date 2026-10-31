package team.bjtuss.bjtuselfservice.shared.data.courseware

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.data.homework.SmartPlatformEndpoint
import team.bjtuss.bjtuselfservice.shared.data.homework.isSchoolPlaybackStreamUrl
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNode
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNodeKind
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStreamKind
import team.bjtuss.bjtuselfservice.shared.feature.courseware.buildPlaybackPlayerHtml
import team.bjtuss.bjtuselfservice.shared.files.StreamingFileSink
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

/** 字段与结构取自 2026-10-25 课程平台真实响应，值均为虚构。 */
class CoursewarePlaybackTest {
    @Test
    fun ignoredRangeResponseIsRejectedBeforeItsBodyIsConsumed() = runBlocking {
        val url = "http://123.121.147.7:88/ve/pdf/rp/2026/09/12/x.pptx"
        val queue = QueueTransport(
            *legacyInitializationResponses(),
            legacyResponse("""{"resMap":{"RP_PATH_1":"2026/09/12/x.pptx"},"STATUS":"0"}"""),
        )
        var bodyConsumed = false
        val transport = object : SchoolHttpTransport by queue {
            override suspend fun stream(request: SchoolHttpRequest, onHeaders: suspend (SchoolHttpResponse) -> Unit, onChunk: suspend (ByteArray) -> Unit) {
                onHeaders(SchoolHttpResponse(statusCode = 200, finalUrl = url, headers = mapOf("Content-Length" to listOf("500000000")), body = byteArrayOf()))
                bodyConsumed = true
                onChunk(byteArrayOf(1))
            }
        }
        val remote = SchoolCoursewareRemoteDataSource(transport, endpoint = SmartPlatformEndpoint.LegacyHttp)
        assertFailsWith<CoursewareRemoteException> { remote.planStoredDownload(restricted("课件", "pptx")) }
        assertFalse(bodyConsumed)
    }

    @Test
    fun failedTicketStreamsOriginalStorageFile() = runBlocking {
        val ticket = "http://123.121.147.7:88/ve/download.shtml?id=rp-9"
        val url = "http://123.121.147.7:88/ve/pdf/rp/2026/09/12/x.pdf"
        val transport = QueueTransport(
            *legacyInitializationResponses(),
            legacyResponse("""{"flag":true,"rpUrl":"$ticket","download_type":"0"}"""),
            SchoolHttpResponse(statusCode = 500, finalUrl = ticket, body = byteArrayOf()),
            legacyResponse("""{"resMap":{"RP_PATH_1":"2026/09/12/x.pdf"},"STATUS":"0"}"""),
            rangeResponse(url, 0, 4, "%".encodeToByteArray(), "application/pdf"),
            rangeResponse(url, 0, 4, "%PDF".encodeToByteArray(), "application/pdf"),
        )
        val remote = SchoolCoursewareRemoteDataSource(transport, endpoint = SmartPlatformEndpoint.LegacyHttp)
        val chunks = mutableListOf<Byte>()
        remote.streamResource(restricted("讲义", "pdf").copy(downloadAllowed = true), StreamingFileSink { chunks.addAll(it.toList()) }) { _, _ -> }
        assertEquals("%PDF", chunks.toByteArray().decodeToString())
    }

    @Test
    fun parsesStoredPathsFromResourceInfo() {
        val parsed = parseCoursewareStoredPaths(
            """{"resMap":{"uuid":"A1","rp_name":"unit 1","RP_PATH_1":"2026/09/12/2026091216.pptx","RP_PATH_2":"2026/09/12/swf/2026091216.pdf","stu_download":"1"},"STATUS":"0"}""",
        )
        val paths = assertIs<CoursewareJsonParseResult.Success<CoursewareStoredPaths>>(parsed).value
        assertEquals("2026/09/12/2026091216.pptx", paths.originalPath)
        assertEquals("2026/09/12/swf/2026091216.pdf", paths.previewPath)
        assertIs<CoursewareJsonParseResult.Failure>(parseCoursewareStoredPaths("""{"resMap":{},"STATUS":"0"}"""))
        assertIs<CoursewareJsonParseResult.Failure>(parseCoursewareStoredPaths("""{"STATUS":"4","message":"出现异常"}"""))
    }

    @Test
    fun storedResourceUrlStaysUnderSameOriginAndRejectsTraversal() {
        val legacy = SmartPlatformEndpoint.LegacyHttp
        assertEquals(
            "http://123.121.147.7:88/ve/pdf/rp/2026/09/12/x.pptx",
            legacy.storedResourceUrl("2026/09/12/x.pptx"),
        )
        assertEquals(
            "http://123.121.147.7:88/ve/pdf/rp/resource/7842/%E8%AF%BE%E4%BB%B6.mp4",
            legacy.storedResourceUrl("resource/7842/课件.mp4"),
        )
        listOf("../etc/passwd", "/abs/x.pdf", "a/../../b", "a//b", "a\\b", "a/%2e%2e/b", "a/b?x=1", "").forEach {
            assertNull(legacy.storedResourceUrl(it), it)
        }
        assertEquals(
            "https://bksycenter.bjtu.edu.cn/ve/pdf/rp/a/b.pdf",
            SmartPlatformEndpoint.VerifiedHttps.storedResourceUrl("a/b.pdf"),
        )
    }

    @Test
    fun restrictedResourceDownloadsOriginalFromStoragePath() = runBlocking {
        val url = "http://123.121.147.7:88/ve/pdf/rp/2026/09/12/x.pptx"
        val transport = QueueTransport(
            *legacyInitializationResponses(),
            legacyResponse("""{"resMap":{"RP_PATH_1":"2026/09/12/x.pptx","RP_PATH_2":"2026/09/12/swf/x.pdf"},"STATUS":"0"}"""),
            rangeResponse(url, start = 0, total = 4, body = byteArrayOf(0x50)),
            SchoolHttpResponse(statusCode = 200, finalUrl = url, body = byteArrayOf(0x50, 0x4b, 0x03, 0x04)),
        )
        val remote = SchoolCoursewareRemoteDataSource(transport, requestDelayMillis = 0, endpoint = SmartPlatformEndpoint.LegacyHttp)

        val file = remote.downloadResource(restricted("unit 1", "pptx"))

        assertEquals("unit 1.pptx", file.fileName)
        assertContentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), file.bytes)
        assertFalse(transport.requests.any { "rpinfoDownloadUrl" in it.url }, "未开放下载时不应再请求必然 500 的票据")
        assertTrue(transport.requests.any { "method=queryRpInfoById&rpId=rp-9" in it.url })
        assertEquals("bytes=0-0", transport.requests[transport.requests.size - 2].headers["Range"])
        assertEquals(url, transport.requests.last().url)
        assertTrue(transport.requests.last().largeTransfer)
        assertFalse(transport.requests.last().headers.containsKey("sessionid"))
    }

    @Test
    fun restrictedResourceFallsBackToConvertedCopyAndNamesItByRealExtension() = runBlocking {
        val transport = QueueTransport(
            *legacyInitializationResponses(),
            legacyResponse("""{"resMap":{"RP_PATH_1":"2026/09/12/x.pptx","RP_PATH_2":"2026/09/12/swf/x.pdf"},"STATUS":"0"}"""),
            SchoolHttpResponse(statusCode = 404, finalUrl = "http://123.121.147.7:88/ve/pdf/rp/2026/09/12/x.pptx", body = ByteArray(0)),
            rangeResponse("http://123.121.147.7:88/ve/pdf/rp/2026/09/12/swf/x.pdf", 0, 8, "%".encodeToByteArray(), "application/pdf"),
        )
        val remote = SchoolCoursewareRemoteDataSource(transport, requestDelayMillis = 0, endpoint = SmartPlatformEndpoint.LegacyHttp)

        val plan = remote.planStoredDownload(restricted("unit 1.pptx", "pptx"))

        assertEquals("unit 1.pdf", plan.fileName)
        assertEquals("application/pdf", plan.contentType)
        assertEquals(8L, plan.totalBytes)
        assertTrue(plan.isConvertedCopy)
    }

    @Test
    fun restrictedLargeFileStreamsInRangesAndIsNeverReadWhole() = runBlocking {
        val url = "http://123.121.147.7:88/ve/pdf/rp/resource/7842/C1/v.mp4"
        val total = 9L * 1024 * 1024
        val chunk = 4 * 1024 * 1024
        val transport = QueueTransport(
            *legacyInitializationResponses(),
            legacyResponse("""{"resMap":{"RP_PATH_1":"resource/7842/C1/v.mp4"},"STATUS":"0"}"""),
            rangeResponse(url, 0, total, byteArrayOf(1), "video/mp4"),
            rangeResponse(url, 0, total, ByteArray(chunk) { 1 }, "video/mp4"),
            SchoolHttpResponse(statusCode = 500, finalUrl = url, body = ByteArray(0)),
            rangeResponse(url, chunk.toLong(), total, ByteArray(chunk) { 2 }, "video/mp4"),
            rangeResponse(url, 2L * chunk, total, ByteArray((total - 2L * chunk).toInt()) { 3 }, "video/mp4"),
        )
        val remote = SchoolCoursewareRemoteDataSource(transport, requestDelayMillis = 0, endpoint = SmartPlatformEndpoint.LegacyHttp)
        val node = restricted("课堂演示", "mp4")
        val plan = remote.planStoredDownload(node)
        var written = 0L
        val firstBytes = mutableListOf<Byte>()
        val progress = mutableListOf<Long>()
        remote.streamStoredDownload(plan, StreamingFileSink { bytes -> written += bytes.size; firstBytes += bytes.first() }) { done, _ ->
            progress += done
        }
        assertEquals("课堂演示.mp4", plan.fileName)
        assertEquals(total, written)
        assertEquals(listOf<Byte>(1, 2, 3), firstBytes, "失败的区间重试后再写，顺序不乱")
        assertEquals(listOf(0L, chunk.toLong(), 2L * chunk, total), progress)
        assertEquals(
            listOf("bytes=0-4194303", "bytes=4194304-8388607", "bytes=4194304-8388607", "bytes=8388608-9437183"),
            transport.requests.takeLast(4).map { it.headers["Range"] },
        )
        // 9 MB 超过内存路径上限前也只走区间请求；目录导出的内存路径对超限文件直接拒绝。
        assertTrue(transport.requests.none { it.url == url && it.headers["Range"] == null })
    }

    @Test
    fun openResourceFallsBackToStoragePathWhenTicketDownloadFails() = runBlocking {
        val url = "http://123.121.147.7:88/ve/pdf/rp/2026/09/12/x.pdf"
        val transport = QueueTransport(
            *legacyInitializationResponses(),
            legacyResponse("""{"flag":true,"rpUrl":"http://123.121.147.7:88/ve/download.shtml?id=rp-9","download_type":"0"}"""),
            SchoolHttpResponse(statusCode = 500, finalUrl = "http://123.121.147.7:88/ve/download.shtml", body = ByteArray(0)),
            legacyResponse("""{"resMap":{"RP_PATH_1":"2026/09/12/x.pdf"},"STATUS":"0"}"""),
            rangeResponse(url, 0, 4, "%".encodeToByteArray(), "application/pdf"),
            SchoolHttpResponse(statusCode = 200, finalUrl = url, body = "%PDF".encodeToByteArray()),
        )
        val remote = SchoolCoursewareRemoteDataSource(transport, requestDelayMillis = 0, endpoint = SmartPlatformEndpoint.LegacyHttp)

        val file = remote.downloadResource(restricted("讲义", "pdf").copy(downloadAllowed = true))

        assertEquals("讲义.pdf", file.fileName)
        assertEquals("%PDF", file.bytes.decodeToString())
    }

    @Test
    fun parsesPlaybackLessonsNewestFirstAndEmptyCourse() {
        val parsed = parsePlaybackLessons(
            """{"courseSchedList":[
              {"videoId":"V1","id":1803841,"classRoom":"YF207","courseBetween":"2026-09-08 16:20-18:10","courseScheName":"","content":"","params":{"videoId":"V1"}},
              {"videoId":"","id":1939345,"classRoom":"YF207","courseBetween":"2026-10-20 16:20-18:10","courseScheName":"第六周","content":"队列","params":{"videoId":""}}
            ],"STATUS":"0","message":"成功"}""",
            courseId = 17,
        )
        val lessons = assertIs<CoursewareJsonParseResult.Success<List<team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson>>>(parsed).value
        assertEquals(listOf(1939345L, 1803841L), lessons.map { it.id })
        assertEquals("第六周", lessons.first().title)
        assertFalse(lessons.first().hasRecording)
        assertEquals("2026-09-08 16:20-18:10", lessons.last().title)
        assertTrue(lessons.last().hasRecording)

        val empty = parsePlaybackLessons("""{"STATUS":"2","message":"没有相关数据"}""", courseId = 17)
        assertTrue(assertIs<CoursewareJsonParseResult.Success<List<*>>>(empty).value.isEmpty())
    }

    @Test
    fun parsesPlaybackDetailKeepsOnlyHttpsSchoolStreamsAndWebDefaults() {
        val lesson = parsePlaybackLessons(
            """{"courseSchedList":[{"videoId":"V1","id":5,"courseBetween":"2026-10-20 16:20-18:10"}],"STATUS":"0"}""",
            17,
        ).let { assertIs<CoursewareJsonParseResult.Success<List<team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson>>>(it).value.single() }
        val detail = parsePlaybackDetail(
            """{"res":{"streamMap":{
                "haveStream":"1","publicRpType":"2,3","rpSize":"5229.72","rpId":"R1",
                "teaStreamHlsUrl":"https://lmt6.bjtu.edu.cn:8880/vod11/_definst_//a/教师.mp4/playlist.m3u8",
                "stuStreamHlsUrl":"https://lmt6.bjtu.edu.cn:8880/vod11/_definst_//a/学生.mp4/playlist.m3u8",
                "vgaStreamHlsUrl":"https://lmt6.bjtu.edu.cn:8880/vod11/_definst_//a/课件.mp4/playlist.m3u8",
                "teaStreamUrl":"rtmp://123.121.147.16:1935/vod11/mp4:/a",
                "movieStreamHlsUrl":"http://123.121.147.7:1936/vod/x/playlist.m3u8"
              },"pointStatus":"no","courseSched":{"id":5}},"STATUS":"0","message":"成功"}""",
            lesson,
            ::isSchoolPlaybackStreamUrl,
        ).let { assertIs<CoursewareJsonParseResult.Success<team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail>>(it).value }
        assertTrue(detail.permitted)
        assertEquals(
            listOf(PlaybackStreamKind.TEACHER, PlaybackStreamKind.SCREEN, PlaybackStreamKind.STUDENT),
            detail.streams.map { it.kind },
        )
        assertEquals(listOf(PlaybackStreamKind.TEACHER, PlaybackStreamKind.SCREEN), detail.defaultKinds)

        val denied = parsePlaybackDetail(
            """{"res":{"streamMap":{"haveStream":"0","teaStreamHlsUrl":"https://lmt6.bjtu.edu.cn:8880/a.m3u8"}},"STATUS":"0"}""",
            lesson,
            ::isSchoolPlaybackStreamUrl,
        ).let { assertIs<CoursewareJsonParseResult.Success<team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail>>(it).value }
        assertFalse(denied.permitted)
        assertTrue(denied.streams.isEmpty())

        assertIs<CoursewareJsonParseResult.Failure>(
            parsePlaybackDetail("""{"STATUS":"4","message":"出现异常"}""", lesson, ::isSchoolPlaybackStreamUrl),
        )
    }

    @Test
    fun playbackStreamUrlPolicyOnlyAllowsSchoolHttps() {
        assertTrue(isSchoolPlaybackStreamUrl("https://lmt2.bjtu.edu.cn:8880/vod11/_definst_//a/b.mp4/playlist.m3u8"))
        assertFalse(isSchoolPlaybackStreamUrl("http://lmt2.bjtu.edu.cn:8880/a.m3u8"))
        assertFalse(isSchoolPlaybackStreamUrl("https://evil.example/bjtu.edu.cn/a.m3u8"))
        assertFalse(isSchoolPlaybackStreamUrl("https://bjtu.edu.cn.evil.example/a.m3u8"))
        assertFalse(isSchoolPlaybackStreamUrl("rtmp://123.121.147.16:1935/vod11/a"))
    }

    @Test
    fun readsPlatformUserIdFromCoursePage() {
        assertEquals("100001", parseCoursePlatformUserId("""var courseId = 1;\n    var uId = "100001";\n"""))
        assertNull(parseCoursePlatformUserId("<html>会话结束</html>"))
    }

    @Test
    fun hlsDownloaderWritesSegmentsInOrderFromTheSameHttpsHost() = runBlocking {
        val base = "https://lmt6.bjtu.edu.cn:8880/vod11/_definst_//a/b.mp4/"
        val transport = MapTransport(
            mapOf(
                base + "playlist.m3u8" to "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.000,\nseg-1-v1-a1.ts\n#EXTINF:4.5,\nseg-2-v1-a1.ts\n#EXT-X-ENDLIST\n",
                base + "seg-1-v1-a1.ts" to "AAA",
                base + "seg-2-v1-a1.ts" to "BB",
            ),
        )
        val written = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val bytes = mutableListOf<Long>()
        HlsRecordingDownloader(transport, retryDelayMillis = 0).download(
            base + "playlist.m3u8",
            StreamingFileSink { written += it.decodeToString() },
        ) { report -> progress += report.completedSegments to report.totalSegments; bytes += report.writtenBytes }
        assertEquals(listOf("AAA", "BB"), written)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        // 字节数随分片累加（"AAA" 3 字节 + "BB" 2 字节），界面据此显示已下载体积与速度。
        assertEquals(listOf(0L, 3L, 5L), bytes)
        assertTrue(transport.publicRequests == transport.requests, "录播分片走无 Cookie 的公开通道")
    }

    @Test
    fun hlsDownloaderRejectsForeignSegmentsAndEncryptionAndRetriesFailures() = runBlocking {
        val base = "https://lmt6.bjtu.edu.cn:8880/a/"
        val foreign = MapTransport(mapOf(base + "p.m3u8" to "#EXTM3U\n#EXTINF:10,\nhttps://example.com/seg.ts\n"))
        assertEquals(
            HlsDownloadFailure.UNSUPPORTED,
            assertFailsWith<HlsDownloadException> {
                HlsRecordingDownloader(foreign, 0).download(base + "p.m3u8", StreamingFileSink { }) { }
            }.reason,
        )
        val encrypted = MapTransport(mapOf(base + "p.m3u8" to "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:10,\ns.ts\n"))
        assertEquals(
            HlsDownloadFailure.UNSUPPORTED,
            assertFailsWith<HlsDownloadException> {
                HlsRecordingDownloader(encrypted, 0).download(base + "p.m3u8", StreamingFileSink { }) { }
            }.reason,
        )
        val missing = MapTransport(mapOf(base + "p.m3u8" to "#EXTM3U\n#EXTINF:10,\ns.ts\n"))
        assertEquals(
            HlsDownloadFailure.NETWORK,
            assertFailsWith<HlsDownloadException> {
                HlsRecordingDownloader(missing, 0).download(base + "p.m3u8", StreamingFileSink { }) { }
            }.reason,
        )
        assertEquals(4, missing.requests.count { it.endsWith("s.ts") })
    }

    @Test
    fun playerPageEscapesTitleAndUrlsAndLoadsNoExternalScripts() {
        val lesson = parsePlaybackLessons(
            """{"courseSchedList":[{"videoId":"V","id":1,"courseBetween":"2026","courseScheName":"</script><b>x"}],"STATUS":"0"}""",
            1,
        ).let { assertIs<CoursewareJsonParseResult.Success<List<team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson>>>(it).value.single() }
        val detail = team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail(
            lesson = lesson,
            permitted = true,
            streams = listOf(
                team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream(
                    PlaybackStreamKind.TEACHER,
                    "https://lmt6.bjtu.edu.cn:8880/a/\"</script>.m3u8",
                ),
                team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream(
                    PlaybackStreamKind.SCREEN,
                    "https://lmt6.bjtu.edu.cn:8880/a/screen.m3u8",
                ),
            ),
            defaultKinds = listOf(PlaybackStreamKind.TEACHER, PlaybackStreamKind.SCREEN),
            sizeMegabytes = "",
        )
        val html = buildPlaybackPlayerHtml(detail)
        assertFalse("</script><b>" in html)
        assertTrue("&lt;/script&gt;&lt;b&gt;x" in html)
        assertTrue("\\u003c/script>.m3u8" in html)
        assertTrue("screen.m3u8" in html)
        assertFalse(Regex("""<script[^>]+src=""").containsMatchIn(html))
        assertTrue("media-src https:" in html)
    }

    private fun restricted(name: String, extension: String) = CoursewareNode(
        id = 9,
        courseId = 17,
        name = name,
        kind = CoursewareNodeKind.RESOURCE,
        rpId = "rp-9",
        extension = extension,
        downloadAllowed = false,
    )

    private class QueueTransport(vararg responses: SchoolHttpResponse) : SchoolHttpTransport {
        private val queue = responses.toMutableList()
        val requests = mutableListOf<SchoolHttpRequest>()

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            requests += request
            return queue.removeFirst()
        }

        override suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse = execute(request)

        override fun clearSession() = Unit
    }

    private class MapTransport(private val bodies: Map<String, String>) : SchoolHttpTransport {
        val requests = mutableListOf<String>()
        val publicRequests = mutableListOf<String>()

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            requests += request.url
            val body = bodies[request.url]
            return SchoolHttpResponse(
                statusCode = if (body == null) 404 else 200,
                finalUrl = request.url,
                body = body?.encodeToByteArray() ?: ByteArray(0),
            )
        }

        override suspend fun executePublic(request: SchoolHttpRequest): SchoolHttpResponse {
            publicRequests += request.url
            return execute(request)
        }

        override suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse = execute(request)

        override fun clearSession() = Unit
    }

    private fun legacyInitializationResponses() = arrayOf(
        legacyResponse("<html></html>"),
        legacyResponse("""{"sessionId":"session-value"}"""),
        legacyResponse("""{"STATUS":"0","result":[{"xqCode":"2026-1"}]}"""),
        legacyResponse(
            """{"STATUS":"0","courseList":[{"id":17,"name":"程序设计","teacher_id":28,"course_num":"CS101","fz_id":"G1","xq_code":"2026-1"}]}""",
        ),
    )

    private fun rangeResponse(url: String, start: Long, total: Long, body: ByteArray, contentType: String = "application/octet-stream") =
        SchoolHttpResponse(
            statusCode = 206,
            finalUrl = url,
            headers = mapOf(
                "Content-Range" to listOf("bytes $start-${start + body.size - 1}/$total"),
                "Content-Type" to listOf(contentType),
            ),
            body = body,
        )

    private fun legacyResponse(body: String) = SchoolHttpResponse(
        statusCode = 200,
        finalUrl = "http://123.121.147.7:88/ve/",
        body = body.encodeToByteArray(),
    )

    @Suppress("unused")
    private fun course() = CoursewareCourse(17, "程序设计", "CS101", "G1", "2026-1", 28, emptyList())
}

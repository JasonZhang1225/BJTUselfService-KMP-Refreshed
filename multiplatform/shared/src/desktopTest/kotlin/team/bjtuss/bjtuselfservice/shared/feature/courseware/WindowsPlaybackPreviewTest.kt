package team.bjtuss.bjtuselfservice.shared.feature.courseware

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.Assume.assumeTrue
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsMediaPlaylist
import team.bjtuss.bjtuselfservice.shared.network.*

class WindowsPlaybackPreviewTest {
    @Test fun selectsTenMinuteSegmentAndMidpointOfShortRecordings() {
        fun playlist(seconds: Double, count: Int) = HlsMediaPlaylist(List(count) { "segment-$it.ts" }, seconds)
        assertEquals(60, playbackPreviewSegmentIndex(playlist(7200.0, 720)))
        assertEquals(4, playbackPreviewSegmentIndex(playlist(80.0, 8)))
        assertEquals(0, playbackPreviewSegmentIndex(playlist(2.0, 1)))
    }

    @Test fun nativePreviewDecodesOneSegmentWithoutDownloadingWholeRecording() = runBlocking {
        assumeTrue(System.getenv("BJTU_PLAYBACK_PREVIEW_ACCEPT") == "1")
        val project = File(System.getProperty("user.dir")).canonicalFile
        val helper = File(project.parentFile, "windowsApp/build/generated/windows-native/native/BJTUPlaybackCompositor.exe")
        check(helper.isFile)
        val requested = mutableListOf<String>()
        val root = "https://lmt6.bjtu.edu.cn:8880/vod/preview-fixture/"
        val segment = checkNotNull(javaClass.getResourceAsStream("/media/synthetic-0.ts")).use { it.readBytes() }
        val transport = object : SchoolHttpTransport {
            override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
                requested += request.url
                val body = if (request.url.endsWith(".m3u8")) {
                    ("#EXTM3U\n" + (0 until 120).joinToString("") { "#EXTINF:10,\nsegment-$it.ts\n" } + "#EXT-X-ENDLIST\n").encodeToByteArray()
                } else segment
                return SchoolHttpResponse(200, request.url, body = body)
            }
            override fun clearSession() = Unit
        }
        val image = captureWindowsPlaybackPreview(root + "playlist.m3u8", helper, transport)
        assertTrue(image.width > 0 && image.height > 0)
        assertEquals(2, requested.size)
        assertEquals(root + "segment-60.ts", requested.last())
        assertFailsWith<IllegalArgumentException> { captureWindowsPlaybackPreview("https://example.com/private", helper, transport) }
        assertEquals(2, requested.size, "非白名单地址不能发出请求")
    }
}

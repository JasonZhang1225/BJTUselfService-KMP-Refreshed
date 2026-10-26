package team.bjtuss.bjtuselfservice.shared.diagnostics

import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.auth.AutomaticLoginCoordinator
import team.bjtuss.bjtuselfservice.shared.auth.AutomaticLoginResult
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.auth.UnavailableCaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.data.courseware.SchoolCoursewareRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.homework.SmartPlatformEndpoint
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNode
import team.bjtuss.bjtuselfservice.shared.files.StreamingFileSink
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport

/**
 * 仅在本机手动诊断时运行（不打印账号、Cookie、uId、文件名与响应正文）：
 * BJTU_LIVE_PLAYBACK_PROBE=1 BJTU_MIS_SECRET=/path/MisSecret.env \
 *   ./gradlew :shared:desktopTest --tests '*LiveSmartPlaybackProbeTest'
 *
 * 注意：同一账号的智慧教学只保留一个会话，运行后浏览器里已打开的课程平台会被顶掉。
 */
class LiveSmartPlaybackProbeTest {
    @Test
    fun probeRestrictedCoursewareAndPlayback() = runBlocking {
        if (System.getenv("BJTU_LIVE_PLAYBACK_PROBE") != "1") return@runBlocking
        val transport = createSchoolHttpTransport()
        val login = AutomaticLoginCoordinator(SchoolLoginProtocol(transport), UnavailableCaptchaRecognizer)
            .login(loadCredentials())
        check(login is AutomaticLoginResult.Authenticated || login is AutomaticLoginResult.SessionActive) {
            "Live probe needs an authenticated session"
        }
        val remote = SchoolCoursewareRemoteDataSource(transport, endpoint = SmartPlatformEndpoint.LegacyHttp)
        val courses = remote.fetchSnapshot().courses
        println("COURSES: ${courses.size}")

        val restricted = mutableListOf<CoursewareNode>()
        suspend fun walk(course: CoursewareCourse, parentId: Int, depth: Int) {
            if (depth > 3) return
            runCatching { remote.fetchChildren(course, parentId) }.getOrDefault(emptyList()).forEach { node ->
                if (node.isFolder) walk(course, node.id, depth + 1) else if (!node.downloadAllowed) restricted += node
            }
        }
        courses.forEach { walk(it, 0, 0) }
        println("RESTRICTED: ${restricted.size}")
        restricted.distinctBy { it.extension.lowercase() }.take(5).forEach { node ->
            // 单个文件走与 App 一致的“先定地址、再分段写盘”路径；只读前 12 MB。
            runCatching {
                val plan = remote.planStoredDownload(node)
                var written = 0L
                var head = ""
                val stop = IllegalStateException("probe-stop")
                val streamed = runCatching {
                    remote.streamStoredDownload(plan, StreamingFileSink { bytes ->
                        if (written == 0L) head = bytes.take(8).joinToString("") { "%02x".format(it) }
                        written += bytes.size
                        if (written >= 12L * 1024 * 1024) throw stop
                    }) { _, _ -> }
                }
                check(streamed.isSuccess || streamed.exceptionOrNull() === stop) { "stream failed: ${streamed.exceptionOrNull()}" }
                println(
                    "STREAM_OK ext=${node.extension} saved_ext=${plan.fileName.substringAfterLast('.')} converted=${plan.isConvertedCopy} " +
                        "total=${plan.totalBytes} written=$written type=${plan.contentType} head=$head",
                )
            }.onFailure { println("STREAM_FAIL ext=${node.extension} $it") }
            runCatching { remote.downloadResource(node) }
                .onSuccess { file ->
                    val magic = file.bytes.take(4).joinToString("") { "%02x".format(it) }
                    println(
                        "RESTRICTED_OK ext=${node.extension} saved_ext=${file.fileName.substringAfterLast('.')} " +
                            "bytes=${file.bytes.size} type=${file.contentType} magic=$magic",
                    )
                }
                .onFailure { println("RESTRICTED_FAIL ext=${node.extension} $it") }
        }

        val downloader = HlsRecordingDownloader(transport)
        var segmentProbeDone = false
        for (course in courses) {
            val lessons = runCatching { remote.fetchPlaybackLessons(course) }
                .onFailure { println("LESSONS_FAIL course=${course.id} $it") }
                .getOrNull() ?: continue
            val recorded = lessons.firstOrNull { it.hasRecording }
            println("LESSONS course=${course.id} total=${lessons.size} recorded=${lessons.count { it.hasRecording }}")
            if (recorded == null) continue
            val detail = runCatching { remote.fetchPlaybackDetail(course, recorded) }
                .onFailure { println("  DETAIL_FAIL $it") }
                .getOrNull() ?: continue
            println(
                "  DETAIL permitted=${detail.permitted} streams=${detail.streams.map { it.kind }} " +
                    "defaults=${detail.defaultKinds} hosts=${detail.streams.map { it.hlsUrl.substringAfter("://").substringBefore('/') }.distinct()}",
            )
            if (!segmentProbeDone && detail.streams.isNotEmpty()) {
                segmentProbeDone = true
                var bytes = 0L
                var segments = 0
                var total = 0
                val stop = IllegalStateException("probe-stop")
                val result = runCatching {
                    downloader.download(
                        detail.streams.first().hlsUrl,
                        StreamingFileSink { chunk ->
                            bytes += chunk.size
                            segments++
                            if (segments == 1) println("  HLS first segment sync byte=0x%02x".format(chunk.first()))
                            if (segments >= 3) throw stop
                        },
                    ) { report -> total = report.totalSegments }
                }
                println("  HLS segments_written=$segments of $total bytes=$bytes stoppedByProbe=${result.exceptionOrNull() === stop}")
            }
        }
    }

    private fun loadCredentials(): Credentials {
        val text = File(System.getenv("BJTU_MIS_SECRET") ?: "../../MisSecret.env").readText()
        fun field(name: String) = Regex("""(?m)^$name=(\S+)""").find(text)!!.groupValues[1].trim('"', '\'')
        return Credentials(field("MIS_USERNAME"), field("MIS_PASSWORD"))
    }
}

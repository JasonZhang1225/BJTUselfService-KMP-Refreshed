package team.bjtuss.bjtuselfservice.shared.diagnostics

import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.bjtuss.bjtuselfservice.shared.auth.AutomaticLoginCoordinator
import team.bjtuss.bjtuselfservice.shared.auth.AutomaticLoginResult
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.auth.UnavailableCaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.data.courseware.SchoolCoursewareRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.homework.SmartPlatformEndpoint
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStreamKind
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport

/**
 * 登录后导出两路 HLS 地址到隔离验收 JSON（不打印 URL/账号）。
 *
 * BJTU_LIVE_PLAYBACK_STREAMS=1 ./gradlew :shared:desktopTest --tests '*LivePlaybackStreamsExportTest'
 */
class LivePlaybackStreamsExportTest {
    @Test
    fun exportTeacherAndScreenStreamsForHarness() = runBlocking {
        if (System.getenv("BJTU_LIVE_PLAYBACK_STREAMS") != "1") return@runBlocking
        val out = File(
            System.getenv("BJTU_PLAYBACK_STREAMS_OUT")
                ?: "../../.artifacts/macos-playback-p1/ui-harness-live/streams.json",
        ).canonicalFile
        check(out.parentFile.name.startsWith("ui-harness") && out.parentFile.parentFile.name == "macos-playback-p1")
        check(out.parentFile.isDirectory || out.parentFile.mkdirs())

        val transport = createSchoolHttpTransport()
        val login = AutomaticLoginCoordinator(SchoolLoginProtocol(transport), UnavailableCaptchaRecognizer)
            .login(loadCredentials())
        check(login is AutomaticLoginResult.Authenticated || login is AutomaticLoginResult.SessionActive) {
            "需要已认证会话才能导出回放地址"
        }
        val remote = SchoolCoursewareRemoteDataSource(transport, endpoint = SmartPlatformEndpoint.LegacyHttp)
        val courses = remote.fetchSnapshot().courses
        val preferred = courses.firstOrNull { it.name.contains("大学物理") } ?: courses.firstOrNull()
        checkNotNull(preferred) { "没有课程" }
        val lessons = remote.fetchPlaybackLessons(preferred)
        val recorded = lessons.firstOrNull { it.hasRecording && it.timeRange.contains("2026-10-28") }
            ?: lessons.firstOrNull { it.hasRecording }
        checkNotNull(recorded) { "没有带录像的课节" }
        val detail = remote.fetchPlaybackDetail(preferred, recorded)
        check(detail.permitted) { "课节未开放观看" }
        val teacher = detail.streams.firstOrNull { it.kind == PlaybackStreamKind.TEACHER }
        val screen = detail.streams.firstOrNull { it.kind == PlaybackStreamKind.SCREEN }
        check(teacher != null && screen != null) { "缺少教师/课件两路" }

        val json = buildJsonObject {
            put("courseName", preferred.name)
            put("lessonTitle", recorded.title)
            put("timeRange", recorded.timeRange)
            put("streams", buildJsonArray {
                add(buildJsonObject {
                    put("kind", teacher.kind.name)
                    put("url", teacher.hlsUrl)
                })
                add(buildJsonObject {
                    put("kind", screen.kind.name)
                    put("url", screen.hlsUrl)
                })
            })
        }
        out.writeText(Json { prettyPrint = true }.encodeToString(json))
        println(
            "EXPORTED courseChars=${preferred.name.length} lessonChars=${recorded.title.length} " +
                "hosts=${listOf(teacher, screen).map { it.hlsUrl.substringAfter("://").substringBefore('/') }.distinct()} " +
                "out=${out.name}",
        )
    }

    private fun loadCredentials(): Credentials {
        val candidates = listOfNotNull(
            System.getenv("BJTU_MIS_SECRET")?.let(::File),
            File("../../MisSecret.env"),
            File("../MisSecret.env"),
            File("MisSecret.env"),
            File("../../MisSecret.md"),
            File("../MisSecret.md"),
            File("MisSecret.md"),
        ).filter(File::isFile)
        val file = candidates.firstOrNull() ?: error("未找到 MisSecret.env / MisSecret.md")
        val text = file.readText()
        val user = Regex("""(?im)^MIS_USERNAME\s*=\s*(\S+)""").find(text)?.groupValues?.get(1)?.trim('"', '\'')
            ?: Regex("""(?im)^account/student_id\s*=\s*(\S+)""").find(text)?.groupValues?.get(1)
        val pass = Regex("""(?im)^MIS_PASSWORD\s*=\s*(\S+)""").find(text)?.groupValues?.get(1)?.trim('"', '\'')
            ?: Regex("""(?im)^password\s*=\s*(\S+)""").find(text)?.groupValues?.get(1)
        check(!user.isNullOrBlank() && !pass.isNullOrBlank()) { "凭据字段缺失" }
        return Credentials(user, pass)
    }
}

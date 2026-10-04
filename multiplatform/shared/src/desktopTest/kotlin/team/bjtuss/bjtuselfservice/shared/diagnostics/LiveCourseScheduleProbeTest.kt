package team.bjtuss.bjtuselfservice.shared.diagnostics

import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.auth.AutomaticLoginCoordinator
import team.bjtuss.bjtuselfservice.shared.auth.AutomaticLoginResult
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.DesktopCoreMlCaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleRemoteException
import team.bjtuss.bjtuselfservice.shared.data.course.SchoolCourseScheduleRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport

/**
 * 仅在本机手动诊断时运行：
 * BJTU_LIVE_PROBE=1 ./gradlew :shared:desktopTest --tests '*LiveCourseScheduleProbeTest'
 *
 * 从仓库根 MisSecret.md 读账号（不打印密码），登录后逐步探测课表四个 URL 与完整 fetch。
 */
class LiveCourseScheduleProbeTest {
    @Test
    fun probeScheduleEndpointsAfterLogin() = runBlocking {
        if (System.getenv("BJTU_LIVE_PROBE") != "1") {
            println("SKIP LiveCourseScheduleProbeTest (set BJTU_LIVE_PROBE=1 to run)")
            return@runBlocking
        }

        val credentials = loadMisSecretCredentials()
        // desktopTest 工作目录通常是 multiplatform/shared。
        val helper = sequenceOf(
            File("../desktopApp/build/generated/captcha/BJTUCaptchaHelper"),
            File("desktopApp/build/generated/captcha/BJTUCaptchaHelper"),
            File("../../desktopApp/build/generated/captcha/BJTUCaptchaHelper"),
        ).map { it.absoluteFile }.firstOrNull { it.canExecute() }
            ?: error("BJTUCaptchaHelper not found")
        val model = sequenceOf(
            File("../desktopApp/build/generated/captcha/model/BJTUCaptcha.mlmodelc"),
            File("desktopApp/build/generated/captcha/model/BJTUCaptcha.mlmodelc"),
            File("../../desktopApp/build/generated/captcha/model/BJTUCaptcha.mlmodelc"),
        ).map { it.absoluteFile }.firstOrNull { it.isDirectory }
            ?: error("BJTUCaptcha.mlmodelc not found")
        println("CAPTCHA helper=${helper.absolutePath} model=${model.absolutePath}")
        System.setProperty("bjtu.captcha.helper", helper.absolutePath)
        System.setProperty("bjtu.captcha.model", model.absolutePath)

        val transport = createSchoolHttpTransport()
        val protocol = SchoolLoginProtocol(transport)
        val captcha = DesktopCoreMlCaptchaRecognizer()

        val login = AutomaticLoginCoordinator(protocol, captcha).login(credentials)
        when (login) {
            is AutomaticLoginResult.SessionActive -> println("LOGIN: session already active")
            is AutomaticLoginResult.Authenticated -> println("LOGIN: ok attempts=${login.attempts}")
            is AutomaticLoginResult.ManualRequired -> {
                println("LOGIN: manual required reason=${login.reason} attempts=${login.attempts}")
                return@runBlocking
            }
        }

        val linked = protocol.linkAcademicSystem()
        println("AA_LINK: $linked")

        val urls = listOf(
            "teacher" to "https://aa.bjtu.edu.cn/course_selection/courseselectabsent/absent_list/",
            "current" to "https://aa.bjtu.edu.cn/course_selection/courseselect/stuschedule/",
            "selection" to "https://aa.bjtu.edu.cn/course_selection/courseselecttask/schedule/",
            "week" to "https://aa.bjtu.edu.cn/classroom/timeholdresult/room_view/",
        )
        for ((name, url) in urls) {
            try {
                val response = transport.execute(
                    SchoolHttpRequest(
                        method = SchoolHttpMethod.GET,
                        url = url,
                        headers = mapOf("Host" to "aa.bjtu.edu.cn"),
                    ),
                )
                val body = response.bodyText()
                val hasTable = body.contains("<table", ignoreCase = true)
                println(
                    "STEP $name: status=${response.statusCode} finalHost=${hostOf(response.finalUrl)} " +
                        "bodyChars=${body.length} hasTable=$hasTable",
                )
            } catch (error: Exception) {
                println("STEP $name: EXCEPTION ${error::class.simpleName}: ${error.message}")
            }
        }

        try {
            val snapshot = SchoolCourseScheduleRemoteDataSource(
                transport,
                requestDelayMillis = 100,
            ).fetchSchedule()
            println(
                "FETCH_OK: courses=${snapshot.courses.size} currentWeek=${snapshot.currentWeek}",
            )
        } catch (error: CourseScheduleRemoteException) {
            println("FETCH_FAIL: reason=${error.reason}")
        } catch (error: Exception) {
            println("FETCH_FAIL: ${error::class.simpleName}: ${error.message}")
        }

        // 模拟登录后并行：连打三次完整 fetch，看是否偶发失败
        var ok = 0
        var fail = 0
        repeat(3) { i ->
            try {
                SchoolCourseScheduleRemoteDataSource(transport, requestDelayMillis = 50).fetchSchedule()
                ok += 1
                println("RETRY_BURST #${i + 1}: ok")
            } catch (error: Exception) {
                fail += 1
                val reason = (error as? CourseScheduleRemoteException)?.reason?.name
                    ?: error::class.simpleName
                println("RETRY_BURST #${i + 1}: fail $reason")
            }
        }
        println("BURST_SUMMARY: ok=$ok fail=$fail")

        // 模拟登录后 homework/exam/course/home 同时打学校接口时的 Cookie 竞态
        val concurrentOk = java.util.concurrent.atomic.AtomicInteger()
        val concurrentFail = java.util.concurrent.atomic.AtomicInteger()
        coroutineScope {
            val jobs = List(8) { index ->
                async {
                    try {
                        if (index % 2 == 0) {
                            SchoolCourseScheduleRemoteDataSource(transport, requestDelayMillis = 0).fetchSchedule()
                        } else {
                            // 其它模块同时访问 MIS / AA 相关路径
                            transport.execute(
                                SchoolHttpRequest(
                                    method = SchoolHttpMethod.GET,
                                    url = "https://mis.bjtu.edu.cn/home/",
                                ),
                            )
                            transport.execute(
                                SchoolHttpRequest(
                                    method = SchoolHttpMethod.GET,
                                    url = "https://aa.bjtu.edu.cn/notice/item/",
                                    headers = mapOf("Host" to "aa.bjtu.edu.cn"),
                                ),
                            )
                        }
                        concurrentOk.incrementAndGet()
                        println("CONCURRENT #$index: ok")
                    } catch (error: Exception) {
                        concurrentFail.incrementAndGet()
                        val reason = (error as? CourseScheduleRemoteException)?.reason?.name
                            ?: "${error::class.simpleName}:${error.message}"
                        println("CONCURRENT #$index: fail $reason")
                    }
                }
            }
            jobs.forEach { it.await() }
        }
        println("CONCURRENT_SUMMARY: ok=${concurrentOk.get()} fail=${concurrentFail.get()}")
    }

    @Test
    fun probeHomeworkSyncAfterLogin() = runBlocking {
        if (System.getenv("BJTU_LIVE_HOMEWORK_PROBE") != "1") return@runBlocking
        val base = File("../desktopApp/build/generated/captcha").absoluteFile
        System.setProperty("bjtu.captcha.helper", File(base, "BJTUCaptchaHelper").absolutePath)
        System.setProperty("bjtu.captcha.model", File(base, "model/BJTUCaptcha.mlmodelc").absolutePath)
        val delegate = createSchoolHttpTransport()
        val protocol = SchoolLoginProtocol(delegate)
        val login = AutomaticLoginCoordinator(protocol, DesktopCoreMlCaptchaRecognizer())
            .login(loadMisSecretCredentials())
        check(login is AutomaticLoginResult.Authenticated || login is AutomaticLoginResult.SessionActive) {
            "Live probe needs an authenticated session"
        }
        val transport = object : team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport {
            override fun clearSession() = delegate.clearSession()
            override suspend fun execute(request: SchoolHttpRequest) = trace(request, false)
            override suspend fun executeWithoutRedirects(request: SchoolHttpRequest) = trace(request, true)
            override suspend fun sessionCookiesFor(url: String) = delegate.sessionCookiesFor(url)
            private suspend fun trace(request: SchoolHttpRequest, raw: Boolean): team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse {
                val response = if (raw) delegate.executeWithoutRedirects(request) else delegate.execute(request)
                // No bodies, credentials, cookies or OAuth query values enter the diagnostic log.
                println("HOMEWORK_STEP: path=${request.url.substringBefore('?')} status=${response.statusCode} " +
                    "final=${response.finalUrl.substringBefore('?')} " +
                    "location=${response.header("Location")?.substringBefore('?')} bytes=${response.body.size} " +
                    "queryKeys=${io.ktor.http.Url(request.url).parameters.entries().associate { it.key to it.value.size }} " +
                    "locationQueryKeys=${response.header("Location")?.substringAfter('?', "")?.split('&')?.map { it.substringBefore('=') }}")
                return response
            }
        }
        val homework = team.bjtuss.bjtuselfservice.shared.data.homework.SchoolHomeworkRemoteDataSource(
            transport, endpoint = team.bjtuss.bjtuselfservice.shared.data.homework.SmartPlatformEndpoint.LegacyHttp,
        ).fetchHomework()
        println("HOMEWORK_OK: count=${homework.size}")
    }

    private fun loadMisSecretCredentials(): Credentials {
        val file = sequenceOf(
            File("../MisSecret.md"),
            File("MisSecret.md"),
            File("../../MisSecret.md"),
        ).firstOrNull(File::isFile) ?: error("MisSecret.md not found")
        val text = file.readText()
        val account = Regex("""(?im)^account/student_id\s*=\s*(\S+)""")
            .find(text)?.groupValues?.get(1)
            ?: error("account missing")
        val password = Regex("""(?im)^password\s*=\s*(\S+)""")
            .find(text)?.groupValues?.get(1)
            ?: error("password missing")
        return Credentials(account, password)
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?')
}

package team.bjtuss.bjtuselfservice.shared.auth

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class SchoolSessionRecoveryTest {
    @Test
    fun successfulRecoveryRelinksAcademicSystem() = runSuspend {
        val transport = QueueTransport(
            response("https://cas.bjtu.edu.cn/auth/login/?next=%2Fauth%2Fsso%2F%3Fnext%3D%2F"),
            response(
                "https://cas.bjtu.edu.cn/auth/login/?next=%2Fauth%2Fsso%2F%3Fnext%3D%2F",
                """
                    <form id="login">
                      <input name="csrfmiddlewaretoken" value="csrf">
                      <input id="id_captcha_0" value="cap">
                    </form>
                """.trimIndent(),
            ),
            response("https://cas.bjtu.edu.cn/image/cap/", body = "fake-image"),
            response(
                "https://mis.bjtu.edu.cn/home",
                """
                    <section class="name_right"><h3><a>测试用户，欢迎</a></h3>
                      <div class="nr_con"><span>身份：学生</span><span>部门：测试学院</span></div>
                    </section>
                """.trimIndent(),
            ),
            response(
                "https://mis.bjtu.edu.cn/module/module/10/",
                "<form id=\"redirect\" action=\"https://aa.bjtu.edu.cn/sso\"></form>",
            ),
            response("https://aa.bjtu.edu.cn/notice/item?source=sso"),
        )
        val recovery = SchoolSessionRecovery(
            protocol = SchoolLoginProtocol(transport),
            captchaRecognizer = CaptchaRecognizer {
                CaptchaRecognitionResult.Success(CaptchaRecognition("1+1=", "2", 1f))
            },
            credentialsProvider = { Credentials("student", "secret") },
        )

        assertTrue(recovery.attempt())
        assertEquals(6, transport.requests.size)
        assertTrue(transport.requests[4].url.contains("/module/module/10/"))
    }

    @Test
    fun recoveryDoesNotStartWithoutCredentials() = runSuspend {
        val transport = QueueTransport()
        val recovery = SchoolSessionRecovery(
            protocol = SchoolLoginProtocol(transport),
            captchaRecognizer = CaptchaRecognizer { error("recognizer should not run") },
            credentialsProvider = { null },
        )

        assertEquals(false, recovery.attempt())
        assertEquals(0, transport.requests.size)
    }

    private class QueueTransport(vararg responses: SchoolHttpResponse) : SchoolHttpTransport {
        private val queue = responses.toMutableList()
        val requests = mutableListOf<SchoolHttpRequest>()

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            requests += request
            return queue.removeFirst()
        }

        override fun clearSession() = Unit
    }

    private fun response(finalUrl: String, body: String = "") = SchoolHttpResponse(
        statusCode = 200,
        finalUrl = finalUrl,
        body = body.encodeToByteArray(),
    )
}

private fun runSuspend(block: suspend () -> Unit) {
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                failure = result.exceptionOrNull()
            }
        },
    )
    failure?.let { throw it }
}

package team.bjtuss.bjtuselfservice.shared.auth

import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.coroutines.startCoroutine

class SchoolLoginProtocolTest {
    @Test
    fun challengeAndAuthenticationFollowExpectedRedirectChain() = runSuspend {
        val transport = QueueTransport(
            listOf(
                response("https://cas.bjtu.edu.cn/auth/login/?next=%2Fhome%2F"),
                response(
                    "https://cas.bjtu.edu.cn/auth/login/?next=%2Fhome%2F",
                    """<input name="csrfmiddlewaretoken" value="csrf"><input id="id_captcha_0" value="cap">""",
                ),
                response("https://cas.bjtu.edu.cn/image/cap/", body = "fake-image"),
                response(
                    "https://mis.bjtu.edu.cn/home?source=cas",
                    """<section class="name_right"><h3><a>测试用户，欢迎</a></h3><div class="nr_con"><span>身份：学生</span><span>部门：测试学院</span></div></section>""",
                ),
            ),
        )
        val protocol = SchoolLoginProtocol(transport)
        val challenge = assertIs<ChallengeResult.Ready>(
            protocol.requestCaptchaChallenge("student"),
        ).challenge
        val authenticated = assertIs<AuthenticationResult.Success>(
            protocol.authenticateMis(Credentials("student", "secret"), challenge, "42"),
        )

        assertEquals("student", authenticated.profile.studentId)
        assertEquals("42", transport.requests.last().formFields["captcha_1"])
        assertEquals("secret", transport.requests.last().formFields["password"])
    }

    @Test
    fun activeMisSessionReturnsProfileWithoutRequestingCaptcha() = runSuspend {
        val transport = QueueTransport(
            listOf(
                response(
                    "https://mis.bjtu.edu.cn/home?source=session",
                    """<section class="name_right"><h3><a>测试用户，欢迎</a></h3><div class="nr_con"><span>身份：学生</span><span>部门：测试学院</span></div></section>""",
                ),
            ),
        )

        val result = assertIs<ChallengeResult.SessionActive>(
            SchoolLoginProtocol(transport).requestCaptchaChallenge("student"),
        )

        assertEquals("student", result.profile.studentId)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun freshChallengeStartsAtCasRefreshEntryEvenWhenMisSessionMayBeActive() = runSuspend {
        val transport = QueueTransport(
            listOf(
                response(
                    "https://cas.bjtu.edu.cn/auth/login/?next=%2Fauth%2Fsso%2F%3Fnext%3D%2F",
                ),
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
            ),
        )

        val challenge = assertIs<ChallengeResult.Ready>(
            SchoolLoginProtocol(transport).requestFreshCaptchaChallenge("student"),
        ).challenge

        assertEquals("cap", challenge.captchaId)
        assertTrue(transport.requests.first().url.contains("auth%2Fsso"))
        assertEquals("https://cas.bjtu.edu.cn/image/cap/", transport.requests.last().url)
    }

    @Test
    fun ambiguousCasResponseRecoversEstablishedMisSession() = runSuspend {
        val transport = QueueTransport(
            listOf(
                response("https://cas.bjtu.edu.cn/auth/login/?next=%2Fhome%2F"),
                response(
                    "https://mis.bjtu.edu.cn/home",
                    """<section class="name_right"><h3><a>测试用户，欢迎</a></h3><div class="nr_con"><span>身份：学生</span><span>部门：测试学院</span></div></section>""",
                ),
            ),
        )
        val protocol = SchoolLoginProtocol(transport)

        val result = assertIs<AuthenticationResult.Success>(
            protocol.authenticateMis(
                credentials = Credentials("student", "secret"),
                challenge = CaptchaChallenge(
                    loginPageUrl = "https://cas.bjtu.edu.cn/auth/login/?next=%2Fhome%2F",
                    csrfToken = "csrf",
                    captchaId = "captcha",
                    imageBytes = byteArrayOf(1),
                ),
                captchaAnswer = "42",
            ),
        )

        assertEquals("student", result.profile.studentId)
        assertEquals("https://mis.bjtu.edu.cn/home/", transport.requests.last().url)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun academicLinkUsesRedirectFormAndLogoutClearsCookies() = runSuspend {
        val transport = QueueTransport(
            listOf(
                response(
                    "https://mis.bjtu.edu.cn/module/module/10/",
                    """<form id="redirect" action="https://aa.bjtu.edu.cn/sso"></form>""",
                ),
                response("https://aa.bjtu.edu.cn/notice/item?source=sso"),
                response("https://cas.bjtu.edu.cn/auth/login/"),
            ),
        )
        val protocol = SchoolLoginProtocol(transport)

        assertTrue(protocol.linkAcademicSystem())
        assertTrue(protocol.logout())
        assertEquals(1, transport.clearCount)
        assertEquals("https://cas.bjtu.edu.cn/auth/logout/", transport.requests.last().url)
    }

    @Test
    fun logoutAlwaysClearsLocalCookiesWhenServerLogoutFails() = runSuspend {
        val transport = QueueTransport(emptyList())
        val protocol = SchoolLoginProtocol(transport)

        assertFalse(protocol.logout())
        assertEquals(1, transport.clearCount)
        assertEquals("https://cas.bjtu.edu.cn/auth/logout/", transport.requests.single().url)
    }

    private fun response(finalUrl: String, body: String = "", status: Int = 200) = SchoolHttpResponse(
        statusCode = status,
        finalUrl = finalUrl,
        body = body.encodeToByteArray(),
    )
}

private class QueueTransport(
    responses: List<SchoolHttpResponse>,
) : SchoolHttpTransport {
    private val queue = responses.toMutableList()
    val requests = mutableListOf<SchoolHttpRequest>()
    var clearCount = 0

    override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
        requests += request
        return queue.removeAt(0)
    }

    override fun clearSession() {
        clearCount++
    }
}

private fun runSuspend(block: suspend () -> Unit) {
    var failure: Throwable? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Unit> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                failure = result.exceptionOrNull()
            }
        },
    )
    failure?.let { throw it }
}

package team.bjtuss.bjtuselfservice.shared.data.phyvlab

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault
import team.bjtuss.bjtuselfservice.shared.network.PhyVlabLoginSession
import team.bjtuss.bjtuselfservice.shared.network.SchoolSessionCookie
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class PhyVlabSessionProtocolTest {
    @Test
    fun authenticatedLoginPageIsReadyWithoutCredentials() = runBlocking {
        val transport = ProtocolQueueTransport(page("https://phyvlab.bjtu.edu.cn/login/index.php", authenticatedHtml()))
        val result = PhyVlabSessionProtocol(transport) { error("credentials should not be read") }.establishSession()

        assertIs<PhyVlabSessionResult.Ready>(result)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun missingAccountRequiresIndependentCredentials() = runBlocking {
        val transport = ProtocolQueueTransport(page("https://phyvlab.bjtu.edu.cn/login/index.php", loginPageHtml()))

        val result = PhyVlabSessionProtocol(transport) { null }.establishSession()

        assertEquals(PhyVlabSessionResult.AccountRequired, result)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun configuredAccountPostsMoodleLoginFormAndFollowsTestSession() = runBlocking {
        val transport = ProtocolQueueTransport(
            page("https://phyvlab.bjtu.edu.cn/login/index.php", loginPageHtml()),
            redirect("https://phyvlab.bjtu.edu.cn/login/index.php", "https://phyvlab.bjtu.edu.cn/login/index.php?testsession=7"),
            redirect("https://phyvlab.bjtu.edu.cn/login/index.php?testsession=7", "https://phyvlab.bjtu.edu.cn/"),
            page("https://phyvlab.bjtu.edu.cn/", authenticatedHtml()),
        )

        val result = PhyVlabSessionProtocol(transport) { Credentials("lab-user", "lab-secret") }.establishSession()

        assertIs<PhyVlabSessionResult.Ready>(result)
        val post = transport.requests[1]
        assertEquals(SchoolHttpMethod.POST, post.method)
        assertEquals("https://phyvlab.bjtu.edu.cn/login/index.php", post.url)
        assertEquals("token-fixture", post.formFields["logintoken"])
        assertEquals("", post.formFields["anchor"])
        assertEquals("lab-user", post.formFields["username"])
        assertEquals("lab-secret", post.formFields["password"])
        assertEquals("https://phyvlab.bjtu.edu.cn/", transport.requests.last().url)
    }

    @Test
    fun rejectedAccountLandsBackOnLoginPage() = runBlocking {
        val transport = ProtocolQueueTransport(
            page("https://phyvlab.bjtu.edu.cn/login/index.php", loginPageHtml()),
            redirect("https://phyvlab.bjtu.edu.cn/login/index.php", "https://phyvlab.bjtu.edu.cn/login/index.php"),
            page("https://phyvlab.bjtu.edu.cn/login/index.php", loginPageHtml(error = "登录无效，请重试")),
        )

        val result = PhyVlabSessionProtocol(transport) { Credentials("lab-user", "wrong") }.establishSession()

        assertEquals(PhyVlabSessionResult.CredentialsRejected, result)
    }

    @Test
    fun loginRedirectToAnotherHostStopsHandshake() = runBlocking {
        val transport = ProtocolQueueTransport(
            page("https://phyvlab.bjtu.edu.cn/login/index.php", loginPageHtml()),
            redirect("https://phyvlab.bjtu.edu.cn/login/index.php", "https://cas.bjtu.edu.cn/o/authorize/?client_id=x"),
        )

        val result = PhyVlabSessionProtocol(transport) { Credentials("lab-user", "lab-secret") }.establishSession()

        assertEquals(PhyVlabRemoteFailure.SESSION_EXPIRED, assertIs<PhyVlabSessionResult.Failed>(result).reason)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun signInWithLogsOutExistingMoodleSessionBeforeVerifyingNewAccount() = runBlocking {
        val transport = ProtocolQueueTransport(
            page("https://phyvlab.bjtu.edu.cn/login/index.php", authenticatedHtml()),
            redirect("https://phyvlab.bjtu.edu.cn/login/logout.php?sesskey=abc", "https://phyvlab.bjtu.edu.cn/"),
            page("https://phyvlab.bjtu.edu.cn/", "<html><body>访客</body></html>"),
            page("https://phyvlab.bjtu.edu.cn/login/index.php", loginPageHtml()),
            redirect("https://phyvlab.bjtu.edu.cn/login/index.php", "https://phyvlab.bjtu.edu.cn/"),
            page("https://phyvlab.bjtu.edu.cn/", authenticatedHtml()),
        )

        val result = PhyVlabSessionProtocol(transport).signInWith(Credentials("new-user", "new-secret"))

        assertIs<PhyVlabSessionResult.Ready>(result)
        assertTrue(transport.requests[1].url.startsWith("https://phyvlab.bjtu.edu.cn/login/logout.php"))
        assertEquals("new-user", transport.requests[4].formFields["username"])
    }

    @Test
    fun configuredAccountWithCaptchaDoesNotSendPasswordInBackground() = runBlocking {
        val transport = ProtocolQueueTransport(page(LOGIN, captchaLoginPage()))
        val protocol = PhyVlabSessionProtocol(transport) { Credentials("lab-user", "lab-secret") }

        assertEquals(PhyVlabSessionResult.CaptchaRequired, protocol.establishSession())
        assertEquals(listOf(SchoolHttpMethod.GET), transport.requests.map { it.method })
    }

    @Test
    fun rejectedCaptchaDoesNotSubmitOrReplayPassword() = runBlocking {
        val isolated = ProtocolQueueTransport(page(LOGIN, captchaLoginPage()), page(VERIFY, """{"ok":false}"""))
        val target = ProtocolQueueTransport()
        val login = PhyVlabSessionProtocol(target).prepareManualLogin(isolated) {}
        val account = Credentials("lab-user", "lab-secret")

        assertEquals(PhyVlabSessionResult.CaptchaRejected, login.complete(account, proof()))
        assertEquals(PhyVlabSessionResult.CaptchaRejected, login.complete(account, proof()))
        assertEquals(listOf(LOGIN, VERIFY), isolated.requests.map { it.url })
        assertTrue(isolated.requests.none { "password" in it.formFields })
        assertEquals(0, target.adoptionCount)
    }

    @Test
    fun confirmedCaptchaSubmitsPasswordOnceAndVerifiesCoursesBeforeAdoptingSession() = runBlocking {
        val isolated = ProtocolQueueTransport(
            page(LOGIN, captchaLoginPage()),
            page(VERIFY, """{"ok":true}"""),
            page(LOGIN, authenticatedHtml()),
        ).apply { cookies = listOf(SchoolSessionCookie("MoodleSession", "session-fixture", domain = "phyvlab.bjtu.edu.cn")) }
        val target = ProtocolQueueTransport().apply {
            adoptionProbe = page("https://phyvlab.bjtu.edu.cn/my/courses.php", authenticatedHtml())
        }
        val login = PhyVlabSessionProtocol(target).prepareManualLogin(isolated) {}

        assertIs<PhyVlabSessionResult.Ready>(login.complete(Credentials("lab-user", "lab-secret"), proof()))
        assertEquals(listOf(LOGIN, VERIFY, LOGIN), isolated.requests.map { it.url })
        assertEquals("lab-secret", isolated.requests.last().formFields["password"])
        assertEquals(1, target.adoptionCount)
    }

    @Test
    fun newTransportReusesSavedSessionWithoutPasswordPost() = runBlocking {
        val vault = SessionVault()
        val authenticatedTransport = ProtocolQueueTransport().apply {
            cookies = listOf(SchoolSessionCookie("MoodleSession", "session-fixture", domain = "phyvlab.bjtu.edu.cn"))
        }
        PhyVlabSessionProtocol(authenticatedTransport, vault).rememberCurrentSession("lab-user")

        val restartedTransport = ProtocolQueueTransport(page(LOGIN, captchaLoginPage())).apply {
            adoptionProbe = page(COURSES, authenticatedHtml())
        }
        val restarted = PhyVlabSessionProtocol(restartedTransport, vault) { Credentials("lab-user", "lab-secret") }

        assertIs<PhyVlabSessionResult.Ready>(restarted.establishSession())
        assertEquals(1, restartedTransport.adoptionCount)
        assertEquals(listOf(SchoolHttpMethod.GET), restartedTransport.requests.map { it.method })
    }

    @Test
    fun savedSessionIsClearedOnlyWhenSchoolReturnsToLogin() = runBlocking {
        val vault = SessionVault(Credentials("lab-user", "session-fixture"))
        val transport = ProtocolQueueTransport(page(LOGIN, captchaLoginPage()), page(LOGIN, captchaLoginPage())).apply {
            adoptionProbe = SchoolHttpResponse(503, COURSES)
        }
        val protocol = PhyVlabSessionProtocol(transport, vault) { Credentials("lab-user", "lab-secret") }

        assertEquals(PhyVlabRemoteFailure.NETWORK, assertIs<PhyVlabSessionResult.Failed>(protocol.establishSession()).reason)
        assertEquals(0, vault.clearCount)

        transport.adoptionProbe = redirect(COURSES, LOGIN)
        assertEquals(PhyVlabSessionResult.CaptchaRequired, protocol.establishSession())
        assertEquals(1, vault.clearCount)
        assertNull(vault.entry)
        assertTrue(transport.requests.all { it.method == SchoolHttpMethod.GET })
    }

    @Test
    fun savedSessionForDifferentPhysicalAccountIsNeverSent() = runBlocking {
        val vault = SessionVault(Credentials("other-user", "session-fixture"))
        val transport = ProtocolQueueTransport(page(LOGIN, captchaLoginPage())).apply {
            adoptionProbe = page(COURSES, authenticatedHtml())
        }
        val protocol = PhyVlabSessionProtocol(transport, vault) { Credentials("lab-user", "lab-secret") }

        assertEquals(PhyVlabSessionResult.CaptchaRequired, protocol.establishSession())
        assertEquals(0, transport.adoptionCount)
    }

    @Test
    fun verifiedManualSessionOwnedByAnotherAccountIsNeverAttributedToConfiguredAccount() = runBlocking {
        val isolated = ProtocolQueueTransport(
            page(LOGIN, captchaLoginPage()),
            page(VERIFY, """{"ok":true}"""),
            page(LOGIN, authenticatedHtml()),
        ).apply { cookies = listOf(SchoolSessionCookie("MoodleSession", "user-b-session", domain = "phyvlab.bjtu.edu.cn")) }
        val target = ProtocolQueueTransport(
            page(LOGIN, authenticatedHtml()),
            page(LOGIN, captchaLoginPage()),
        ).apply {
            adoptionProbe = page(COURSES, authenticatedHtml())
        }
        val sessionVault = SessionVault()
        // 外层配置仍是 account-a（模拟 account-b 校验成功后写入安全存储失败）。
        val protocol = PhyVlabSessionProtocol(target, sessionVault) { Credentials("account-a", "pass-a") }
        val login = protocol.prepareManualLogin(isolated) {}
        assertIs<PhyVlabSessionResult.Ready>(login.complete(Credentials("account-b", "pass-b"), proof()))

        // 下次刷新时应清掉未落盘的 account-b 会话，绝不把 account-b 的 Cookie 记到 account-a 名下。
        assertEquals(PhyVlabSessionResult.CaptchaRequired, protocol.establishSession())
        assertEquals(1, target.clearPhyVlabCalls)
        assertNull(sessionVault.entry)
    }

    @Test
    fun prepareManualLoginSupportsNoCaptchaFallbackAndRejectsBrokenCaptchaWidget() = runBlocking {
        val noCaptchaIsolated = ProtocolQueueTransport(
            page(LOGIN, loginPageHtml()),
            page(LOGIN, authenticatedHtml()),
        ).apply { cookies = listOf(SchoolSessionCookie("MoodleSession", "plain-session", domain = "phyvlab.bjtu.edu.cn")) }
        val target = ProtocolQueueTransport().apply {
            adoptionProbe = page(COURSES, authenticatedHtml())
        }
        val direct = PhyVlabSessionProtocol(target).prepareManualLogin(noCaptchaIsolated) {}
        assertNull(direct.captchaId)
        assertIs<PhyVlabSessionResult.Ready>(direct.complete(Credentials("lab-user", "lab-secret")))
        assertEquals(listOf(LOGIN, LOGIN), noCaptchaIsolated.requests.map { it.url })
        assertEquals(1, target.adoptionCount)

        val brokenHtml = loginPageHtml().replace("</form>", "<div id='aliyun-captcha-element'></div></form>")
        val brokenIsolated = ProtocolQueueTransport(page(LOGIN, brokenHtml))
        val failed = runCatching { PhyVlabSessionProtocol(target).prepareManualLogin(brokenIsolated) {} }.exceptionOrNull()
        assertIs<PhyVlabRemoteException>(failed)
        assertEquals(PhyVlabRemoteFailure.PARSE, failed.reason)
    }

    private class SessionVault(var entry: Credentials? = null) : CredentialVault {
        var clearCount = 0
        override suspend fun save(credentials: Credentials) { entry = credentials }
        override suspend fun load() = entry
        override suspend fun clear() { clearCount++; entry = null }
    }

    private fun proof(): PhyVlabCaptchaProof = requireNotNull(PhyVlabCaptchaProof.parse(
        """{"lot_number":"fixture","captcha_output":"fixture","pass_token":"fixture","gen_time":"1"}""",
    ))

    private fun captchaLoginPage() = loginPageHtml()
        .replace("</form>", "<div id='aliyun-captcha-element'></div></form>")
        .replace("</body>", "<script>initAlicom4({captchaId: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'})</script></body>")

    private fun page(url: String, html: String) = SchoolHttpResponse(200, url, body = html.encodeToByteArray())

    private fun redirect(from: String, to: String) = SchoolHttpResponse(303, from, headers = mapOf("Location" to listOf(to)))

    private fun authenticatedHtml(): String = """
        <html><body>
          <a href="/login/logout.php?sesskey=abc">退出登录</a>
          <a href="/my">个人主页</a>
        </body></html>
    """.trimIndent()

    /** 结构取自真实物理在线登录页（脱敏）。 */
    private fun loginPageHtml(error: String? = null): String = """
        <html><body>
          ${error?.let { "<div class=\"alert alert-danger\" role=\"alert\">$it</div>" }.orEmpty()}
          <form class="login-form" action="https://phyvlab.bjtu.edu.cn/login/index.php" method="post" id="login">
            <input id="anchor" type="hidden" name="anchor" value="">
            <input type="hidden" name="logintoken" value="token-fixture">
            <input type="text" name="username" id="username" value="" placeholder="用户名或邮箱">
            <input type="password" name="password" id="password" value="" placeholder="密码">
          </form>
          <form action="https://phyvlab.bjtu.edu.cn/login/index.php" method="post" id="guestlogin">
            <input type="hidden" name="logintoken" value="token-fixture">
            <input type="hidden" name="username" value="guest" />
          </form>
        </body></html>
    """.trimIndent()

    private companion object {
        const val LOGIN = "https://phyvlab.bjtu.edu.cn/login/index.php"
        const val VERIFY = "https://phyvlab.bjtu.edu.cn/login/captcha_verify.php"
        const val COURSES = "https://phyvlab.bjtu.edu.cn/my/courses.php"
    }

    private class ProtocolQueueTransport(vararg responses: SchoolHttpResponse) : SchoolHttpTransport {
        private val queue = responses.toMutableList()
        val requests = mutableListOf<SchoolHttpRequest>()
        var cookies: List<SchoolSessionCookie> = emptyList()
        var adoptionProbe: SchoolHttpResponse? = null
        var adoptionCount = 0
        var clearPhyVlabCalls = 0

        override suspend fun sessionCookiesFor(url: String) = cookies

        override suspend fun clearPhyVlabSession() {
            clearPhyVlabCalls++
            cookies = emptyList()
        }

        override suspend fun adoptPhyVlabSession(
            session: PhyVlabLoginSession,
            verify: suspend (SchoolHttpResponse) -> Boolean,
        ): Boolean {
            val accepted = adoptionProbe?.let { verify(it) } == true
            if (accepted) adoptionCount++
            return accepted
        }

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = executeWithoutRedirects(request)

        override suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse {
            requests += request
            return queue.removeFirst()
        }

        override fun clearSession() = Unit
    }
}

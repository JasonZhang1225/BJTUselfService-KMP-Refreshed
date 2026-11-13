package team.bjtuss.bjtuselfservice.shared.data.phyvlab

import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.network.SchoolEndpoints
import team.bjtuss.bjtuselfservice.shared.network.PhyVlabLoginSession

import com.fleeksoft.ksoup.Ksoup
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import team.bjtuss.bjtuselfservice.shared.network.KtorSchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.schoolHttpEngineFactory
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.parseOffMain

internal val PHYVLAB_LOGIN_URL = "${SchoolEndpoints.PHYVLAB_ORIGIN}/login/index.php"
private val PHYVLAB_HOME_URL = "${SchoolEndpoints.PHYVLAB_ORIGIN}/"
private val PHYVLAB_COURSES_URL = "${SchoolEndpoints.PHYVLAB_ORIGIN}/my/courses.php"

private const val PHYVLAB_HOST = "phyvlab.bjtu.edu.cn"

sealed interface PhyVlabSessionResult {
    data class Ready(val homeUrl: String) : PhyVlabSessionResult
    /** 尚未配置物理在线独立账号；需要用户在设置里填写。 */
    data object AccountRequired : PhyVlabSessionResult
    /** 学校要求用户在登录页手动完成点选。 */
    data object CaptchaRequired : PhyVlabSessionResult
    data object CaptchaRejected : PhyVlabSessionResult
    /** 已配置的独立账号被 Moodle 拒绝（“登录无效”）。 */
    data object CredentialsRejected : PhyVlabSessionResult
    /**
     * `detail` is deliberately a coarse, non-sensitive diagnostic label.  It
     * never contains a URL query, cookie, or Moodle session key.
     */
    data class Failed(
        val reason: PhyVlabRemoteFailure,
        val detail: String? = null,
    ) : PhyVlabSessionResult
}

/**
 * 物理在线 Moodle 会话。
 *
 * 学校统一身份认证迁移后，物理在线的 OAuth 回跳暂不可用，当前使用独立账号密码：
 * 已有 MoodleSession 时直接复用；否则取 `/login/index.php` 的 `form#login`（含 logintoken），
 * 旧版无验证码表单可直接提交；有点选验证码时，等待用户完成学校网页登录。
 *
 * 安全边界：只访问 phyvlab 的 HTTPS 443，跳转到任何其它主机、HTTP 或未知端口立即停止。
 * 凭据只由调用方从系统安全存储读出后传入，本类不保存、不记录。
 */
class PhyVlabSessionProtocol(
    private val transport: SchoolHttpTransport,
    private val sessionVault: CredentialVault? = null,
    private val credentials: suspend () -> Credentials? = { null },
) {
    private val sessionVaultMutex = Mutex()
    private var lastSavedSession: Credentials? = null
    @Volatile
    private var activeSessionUsername: String? = null

    suspend fun establishSession(): PhyVlabSessionResult = try {
        val loginPage = loadLoginPage()
        if (parseOffMain { loginPage.pageMarks() }.authenticated) {
            val owner = activeSessionUsername
            val account = if (sessionVault != null || owner != null) credentials() else null
            if (owner != null && (account == null || owner != account.username)) {
                clearRememberedSession()
                if (account == null) PhyVlabSessionResult.AccountRequired
                else restoreSessionOrLogin(loadLoginPage(), account)
            } else {
                if (sessionVault != null && account != null) rememberCurrentSession(account.username)
                PhyVlabSessionResult.Ready(PHYVLAB_HOME_URL)
            }
        } else {
            val account = credentials()?.takeIf { it.isValid }
            if (account == null) PhyVlabSessionResult.AccountRequired
            else restoreSessionOrLogin(loginPage, account)
        }
    } catch (error: PhyVlabRemoteException) {
        PhyVlabSessionResult.Failed(error.reason, detail = error.reason.name.lowercase())
    }

    private suspend fun restoreSessionOrLogin(
        loginPage: SchoolHttpResponse,
        account: Credentials,
    ): PhyVlabSessionResult {
        val saved = readRememberedSession(account.username)
        val cookie = saved?.password?.let(PhyVlabLoginSession::fromCookie)
        if (cookie != null) {
            when (probeAndAdoptSession(cookie)) {
                SessionProbe.READY -> {
                    activeSessionUsername = account.username
                    rememberCurrentSession(account.username)
                    return PhyVlabSessionResult.Ready(PHYVLAB_HOME_URL)
                }
                SessionProbe.EXPIRED -> forgetExpiredSession(saved)
                SessionProbe.UNAVAILABLE ->
                    return PhyVlabSessionResult.Failed(PhyVlabRemoteFailure.NETWORK, "saved-session-unconfirmed")
            }
        }
        return submitLogin(loginPage, account).also {
            if (it is PhyVlabSessionResult.Ready) {
                activeSessionUsername = account.username
                rememberCurrentSession(account.username)
            }
        }
    }

    internal suspend fun rememberCurrentSession(username: String) {
        activeSessionUsername = username
        val vault = sessionVault ?: return
        try {
            sessionVaultMutex.withLock {
                val cookie = transport.phyVlabSessionCookie() ?: return
                val entry = Credentials(username, cookie.value)
                if (entry != lastSavedSession) {
                    vault.save(entry)
                    lastSavedSession = entry
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            phyVlabDebug("secure session save unavailable")
        }
    }

    internal suspend fun clearRememberedSession() {
        sessionVaultMutex.withLock {
            try {
                activeSessionUsername = null
                sessionVault?.clear()
                lastSavedSession = null
            } finally {
                transport.clearPhyVlabSession()
            }
        }
    }

    private suspend fun readRememberedSession(username: String): Credentials? {
        val vault = sessionVault ?: return null
        return try {
            sessionVaultMutex.withLock {
                val entry = vault.load()
                lastSavedSession = entry
                entry?.takeIf { it.username == username }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            phyVlabDebug("secure session read unavailable")
            null
        }
    }

    private suspend fun forgetExpiredSession(saved: Credentials?) {
        try {
            sessionVaultMutex.withLock {
                if (saved == lastSavedSession) {
                    sessionVault?.clear()
                    lastSavedSession = null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            phyVlabDebug("expired secure session clear unavailable")
        }
    }

    /**
     * 设置页“保存”时校验新账号：即使当前 MoodleSession 属于旧账号，也先注销再用新账号登录，
     * 只有登录成功才算校验通过。
     */
    suspend fun signInWith(account: Credentials): PhyVlabSessionResult = try {
        var loginPage = loadLoginPage()
        if (parseOffMain { loginPage.pageMarks() }.authenticated) {
            activeSessionUsername = null
            parseOffMain { parsePhyVlabLogoutUrl(loginPage.bodyText()) }?.let { logout ->
                val response = executeRaw(logout, referer = loginPage.finalUrl)
                if (response.statusCode in 300..399) followHandshake(response)
            }
            loginPage = loadLoginPage()
        }
        submitLogin(loginPage, account).also { result ->
            if (result is PhyVlabSessionResult.Ready) activeSessionUsername = account.username
        }
    } catch (error: PhyVlabRemoteException) {
        PhyVlabSessionResult.Failed(error.reason, detail = error.reason.name.lowercase())
    }

    private enum class SessionProbe { READY, EXPIRED, UNAVAILABLE }

    /** 只在课程页明确确认登录后接入应用会话。网络错误保留已保存的 Cookie。 */
    private suspend fun probeAndAdoptSession(session: PhyVlabLoginSession): SessionProbe = try {
        var expired = false
        val accepted = transport.adoptPhyVlabSession(session) { page ->
            val marks = if (page.statusCode in 200..299) parseOffMain { page.pageMarks() } else null
            expired = marks?.phyVlabLoginPage == true || (
                page.statusCode in 300..399 && page.header("Location")?.let {
                    resolveAllowedTarget(page.finalUrl, it)?.substringBefore('?') == PHYVLAB_LOGIN_URL
                } == true
            )
            page.finalUrl.substringBefore('?') == PHYVLAB_COURSES_URL &&
                marks?.let { it.authenticated && !it.phyVlabLoginPage } == true
        }
        when {
            accepted -> SessionProbe.READY
            expired -> SessionProbe.EXPIRED
            else -> SessionProbe.UNAVAILABLE
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        SessionProbe.UNAVAILABLE
    }

    internal suspend fun prepareManualLogin(): PhyVlabManualLogin {
        // A failed first configuration must leave any previously working session alone.
        val isolated = KtorSchoolHttpTransport(schoolHttpEngineFactory())
        return prepareManualLogin(isolated, isolated::close)
    }

    internal suspend fun prepareManualLogin(
        isolated: SchoolHttpTransport,
        close: () -> Unit,
    ): PhyVlabManualLogin {
        try {
            val protocol = PhyVlabSessionProtocol(isolated)
            val page = protocol.loadLoginPage()
            val body = page.bodyText()
            val requiresCaptcha = parseOffMain { phyVlabLoginRequiresCaptcha(body) }
            val captchaId = parseOffMain { parsePhyVlabCaptchaId(body) }
            if (requiresCaptcha) {
                captchaId ?: throw PhyVlabRemoteException(PhyVlabRemoteFailure.PARSE)
            } else if (parseOffMain { parsePhyVlabLoginForm(body) } == null) {
                throw PhyVlabRemoteException(PhyVlabRemoteFailure.PARSE)
            }
            return PhyVlabManualLogin(captchaId, close) { account, proof ->
                try {
                    if (captchaId != null) {
                        if (proof == null) return@PhyVlabManualLogin PhyVlabSessionResult.CaptchaRejected
                        val verified = isolated.executeWithoutRedirects(
                            SchoolHttpRequest(
                                SchoolHttpMethod.POST,
                                "${SchoolEndpoints.PHYVLAB_ORIGIN}/login/captcha_verify.php",
                                headers = mapOf("Referer" to PHYVLAB_LOGIN_URL, "Origin" to SchoolEndpoints.PHYVLAB_ORIGIN),
                                rawBody = proof.toJson().encodeToByteArray(),
                                rawBodyContentType = "application/json",
                            ),
                        )
                        val passed = verified.statusCode == 200 && parseOffMain {
                            runCatching {
                                val json = Json.parseToJsonElement(verified.bodyText()) as? JsonObject
                                (json?.get("ok") as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true
                            }.getOrDefault(false)
                        }
                        if (!passed) {
                            return@PhyVlabManualLogin if (verified.statusCode == 200) PhyVlabSessionResult.CaptchaRejected
                            else PhyVlabSessionResult.Failed(PhyVlabRemoteFailure.NETWORK)
                        }
                    }
                    when (val result = protocol.submitLogin(page, account, captchaVerified = captchaId != null)) {
                        is PhyVlabSessionResult.Ready -> {
                            val session = isolated.phyVlabSessionCookie()
                            when (session?.let { probeAndAdoptSession(it) }) {
                                SessionProbe.READY -> {
                                    activeSessionUsername = account.username
                                    result
                                }
                                SessionProbe.UNAVAILABLE -> PhyVlabSessionResult.Failed(PhyVlabRemoteFailure.NETWORK, "manual-session-probe")
                                SessionProbe.EXPIRED, null ->
                                    PhyVlabSessionResult.Failed(PhyVlabRemoteFailure.SESSION_EXPIRED, "manual-session-unconfirmed")
                            }
                        }
                        else -> result
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    PhyVlabSessionResult.Failed(PhyVlabRemoteFailure.NETWORK)
                }
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private suspend fun loadLoginPage(): SchoolHttpResponse {
        val login = executeRaw(PHYVLAB_LOGIN_URL, referer = PHYVLAB_HOME_URL)
        // Moodle/反向代理有时会先把登录入口规范化到带语言或 redirect 参数的地址。
        val page = if (login.statusCode in 300..399) followHandshake(login) else login
        if (page.statusCode !in 200..299) {
            throw PhyVlabRemoteException(PhyVlabRemoteFailure.NETWORK)
        }
        return page
    }

    private suspend fun submitLogin(
        loginPage: SchoolHttpResponse,
        account: Credentials,
        captchaVerified: Boolean = false,
    ): PhyVlabSessionResult {
        if (!account.isValid) return PhyVlabSessionResult.AccountRequired
        if (!captchaVerified && parseOffMain { phyVlabLoginRequiresCaptcha(loginPage.bodyText()) }) {
            return PhyVlabSessionResult.CaptchaRequired
        }
        val fields = parseOffMain { parsePhyVlabLoginForm(loginPage.bodyText()) }
            ?: return PhyVlabSessionResult.Failed(PhyVlabRemoteFailure.PARSE, detail = "login-form-missing")
        val submitted = try {
            transport.executeWithoutRedirects(
                SchoolHttpRequest(
                    method = SchoolHttpMethod.POST,
                    url = PHYVLAB_LOGIN_URL,
                    headers = mapOf(
                        "Referer" to PHYVLAB_LOGIN_URL,
                        "Origin" to SchoolEndpoints.PHYVLAB_ORIGIN,
                    ),
                    formFields = fields + mapOf(
                        "username" to account.username,
                        "password" to account.password,
                    ),
                ),
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            throw PhyVlabRemoteException(PhyVlabRemoteFailure.NETWORK)
        }
        phyVlabDebug("login POST -> ${submitted.statusCode}")
        val landing = if (submitted.statusCode in 300..399) followHandshake(submitted) else submitted
        val marks = parseOffMain { landing.pageMarks() }
        if (marks.authenticated) return PhyVlabSessionResult.Ready(PHYVLAB_HOME_URL)
        if (marks.phyVlabLoginPage) return loginRejection(landing)

        // 登录后的落地页可能只是无导航的中间页；再探测一次“我的课程”确认登录态。
        val probe = executeRaw(PHYVLAB_COURSES_URL, referer = landing.finalUrl)
        val settled = if (probe.statusCode in 300..399) followHandshake(probe) else probe
        val settledMarks = parseOffMain { settled.pageMarks() }
        phyVlabDebug(
            "login landing ${safePhyVlabEndpoint(settled.finalUrl)} " +
                "auth=${settledMarks.authenticated} loginPage=${settledMarks.phyVlabLoginPage}",
        )
        return when {
            settledMarks.authenticated -> PhyVlabSessionResult.Ready(PHYVLAB_HOME_URL)
            settledMarks.phyVlabLoginPage -> loginRejection(settled)
            else -> PhyVlabSessionResult.Failed(PhyVlabRemoteFailure.SESSION_EXPIRED, detail = "phyvlab-session-unconfirmed")
        }
    }

    private suspend fun loginRejection(page: SchoolHttpResponse): PhyVlabSessionResult = parseOffMain {
        val html = page.bodyText()
        val message = Ksoup.parse(html).select("[role=alert], #loginerrormessage").text()
        when {
            "登录无效" in message || "Invalid login" in message -> PhyVlabSessionResult.CredentialsRejected
            phyVlabLoginRequiresCaptcha(html) -> PhyVlabSessionResult.CaptchaRequired
            else -> PhyVlabSessionResult.Failed(PhyVlabRemoteFailure.SESSION_EXPIRED, "login-result-unconfirmed")
        }
    }

    /** 依次处理 Moodle 的 303 跳转（testsession、wantsurl），只在 phyvlab 内跟随。 */
    private suspend fun followHandshake(first: SchoolHttpResponse): SchoolHttpResponse {
        var current = first
        repeat(MAX_HOPS) {
            if (current.statusCode !in 300..399) return current
            val location = current.header("Location")?.trim().orEmpty()
            if (location.isEmpty()) return current
            val target = resolveAllowedTarget(current.finalUrl, location)
                ?: throw PhyVlabRemoteException(PhyVlabRemoteFailure.SESSION_EXPIRED)
            current = executeRaw(url = target, referer = current.finalUrl)
            if (current.statusCode !in 300..399) return current
        }
        return current
    }

    private suspend fun executeRaw(url: String, referer: String): SchoolHttpResponse = try {
        val request = SchoolHttpRequest(
            method = SchoolHttpMethod.GET,
            url = url,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
                "Referer" to referer,
            ),
        )
        val response = transport.executeWithoutRedirects(request)
        phyVlabDebug(
            "raw GET ${safePhyVlabEndpoint(url)} -> ${response.statusCode} " +
                "${safePhyVlabEndpoint(response.finalUrl)} bytes=${response.body.size}",
        )
        response
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        phyVlabDebug("raw GET ${safePhyVlabEndpoint(url)} -> exception")
        throw PhyVlabRemoteException(PhyVlabRemoteFailure.NETWORK)
    }
}

private suspend fun SchoolHttpTransport.phyVlabSessionCookie(): PhyVlabLoginSession? =
    sessionCookiesFor(PHYVLAB_COURSES_URL).singleOrNull {
        it.name == "MoodleSession" && it.domain == PHYVLAB_HOST && it.secure && it.path == "/"
    }?.let { PhyVlabLoginSession.fromCookie(it.value) }

private fun resolveAllowedTarget(base: String, location: String): String? {
    if (location.any(Char::isISOControl) || '#' in location) return null
    val parsed = runCatching {
        // takeFrom 会把 Location 的查询追加到旧查询后面；先清空，避免 testsession 等参数串到下一跳。
        URLBuilder(Url(base)).apply { parameters.clear() }.takeFrom(location).build()
    }.getOrNull() ?: return null
    if (parsed.protocol.name != "https") return null
    if (parsed.host == PHYVLAB_HOST && (parsed.port == 443 || parsed.port == 0)) {
        return parsed.toString()
    }
    return null
}

private fun phyVlabLoginRequiresCaptcha(html: String): Boolean =
    Ksoup.parse(html).selectFirst("form#login #aliyun-captcha-element") != null ||
        "/login/captcha_verify.php" in html

internal fun parsePhyVlabCaptchaId(html: String): String? {
    if (!phyVlabLoginRequiresCaptcha(html)) return null
    return Regex("""initAlicom4\s*\(\s*\{\s*captchaId\s*:\s*['"]([a-fA-F0-9]{32})['"]""")
        .find(html)?.groupValues?.get(1)
}

/** Moodle 登录表单：保留 logintoken/anchor 等隐藏字段，账号密码由调用方填入。 */
internal fun parsePhyVlabLoginForm(html: String): Map<String, String>? {
    val form = Ksoup.parse(html).selectFirst("form#login") ?: return null
    val action = form.attr("action")
    if (action.isNotBlank() && resolveAllowedTarget(PHYVLAB_LOGIN_URL, action)?.substringBefore('?') != PHYVLAB_LOGIN_URL) {
        return null
    }
    if (form.selectFirst("input[name=username]") == null || form.selectFirst("input[name=password]") == null) return null
    val fields = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
    if (fields["logintoken"].isNullOrBlank()) return null
    return fields
}

/** 已登录页面右上角菜单里的注销链接（带 sesskey），只接受 phyvlab 自己的地址。 */
internal fun parsePhyVlabLogoutUrl(html: String): String? =
    Ksoup.parse(html).select("a[href*='/login/logout.php']")
        .asSequence()
        .mapNotNull { resolveAllowedTarget(PHYVLAB_LOGIN_URL, it.attr("href")) }
        .firstOrNull { Url(it).parameters["sesskey"].orEmpty().isNotBlank() }

/** 握手页面的登录态标记：一次在后台算齐，调试日志与判断共用，不再逐项重复解析整页。 */
private class PhyVlabPageMarks(
    val authenticated: Boolean,
    val phyVlabLoginPage: Boolean,
)

private fun SchoolHttpResponse.pageMarks(): PhyVlabPageMarks {
    val body = bodyText()
    return PhyVlabPageMarks(
        authenticated = isPhyVlabAuthenticatedPage(finalUrl, body),
        phyVlabLoginPage = looksLikePhyVlabLoginPage(body),
    )
}

private fun isPhyVlabAuthenticatedPage(url: String, html: String): Boolean {
    if (!url.startsWith(SchoolEndpoints.PHYVLAB_ORIGIN)) return false
    val doc = Ksoup.parse(html)
    val logout = doc.selectFirst("a[href*='/login/logout.php']") != null
    // “我的课程/个人主页”链接在访客页也可能出现，不能单独作为登录态凭据；
    // Moodle 登录后的全局菜单会稳定提供退出链接。
    val userMenu = doc.selectFirst("[data-region='usermenu'], a[href='/user/profile.php']") != null
    val body = doc.title() + " " + doc.text()
    val confirmedLoggedIn = "您已经以" in body && "身份登录" in body
    return logout || userMenu || confirmedLoggedIn
}

private fun looksLikePhyVlabLoginPage(html: String): Boolean {
    val document = Ksoup.parse(html)
    return document.selectFirst("a[href*='/auth/oauth2/login.php']") != null ||
        document.selectFirst("form[action*='/login/index.php']") != null ||
        (document.text().contains("用户名或邮箱") && document.text().contains("密码"))
}

private const val MAX_HOPS = 12


internal class PhyVlabManualLogin(
    val captchaId: String?,
    val close: () -> Unit,
    private val submit: suspend (Credentials, PhyVlabCaptchaProof?) -> PhyVlabSessionResult,
) {
    private val submitted = Mutex()

    suspend fun complete(account: Credentials, proof: PhyVlabCaptchaProof? = null): PhyVlabSessionResult {
        if (!submitted.tryLock()) return PhyVlabSessionResult.CaptchaRejected
        return submit(account, proof)
    }
}

internal class PhyVlabCaptchaProof private constructor(private val fields: JsonObject) {
    fun toJson(): String = fields.toString()
    override fun toString(): String = "PhyVlabCaptchaProof(<redacted>)"

    companion object {
        private val names = setOf("lot_number", "captcha_output", "pass_token", "gen_time")

        fun parse(value: String): PhyVlabCaptchaProof? {
            if (value.length > 8192) return null
            val json = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull() ?: return null
            if (json.keys != names || json.values.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() }) return null
            return PhyVlabCaptchaProof(json)
        }
    }
}

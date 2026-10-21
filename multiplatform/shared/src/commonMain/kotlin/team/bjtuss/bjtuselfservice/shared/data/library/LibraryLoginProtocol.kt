package team.bjtuss.bjtuselfservice.shared.data.library

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.ParseResult
import team.bjtuss.bjtuselfservice.shared.auth.SsoSm2
import team.bjtuss.bjtuselfservice.shared.auth.parseSsoLoginPage
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.SchoolNetworkException
import team.bjtuss.bjtuselfservice.shared.network.parseOffMain

/** 回调页内嵌的登录参数；`data`/`enc` 是学校每次登录现场生成的一次性值。 */
internal data class LibraryCallbackParams(
    val data: String,
    val time: String,
    val enc: String,
    val displayName: String,
    val firstReferer: String?,
) {
    override fun toString(): String =
        "LibraryCallbackParams(data=<redacted>, time=$time, enc=<redacted>, firstReferer=${firstReferer?.substringBefore('?')})"
}

/**
 * 从 OAuth 回调页 HTML 里提取 `/OAuth2/bjtu/login` 所需的参数。
 *
 * 学校页面结构（真实抓包）：
 * ```
 * var firstReferer = "https:\/\/opac.bjtu.edu.cn\/find\/sso\/login\/bjtu\/0";
 * data: { data: "d892...", time: 1792567061413, enc: "48845894...", displayName: "\u59DC...", userRole: 3 },
 * ```
 * 解析失败必须报错，不能拿空值去请求（学校会回 {"status":false} 并让人误判为网络问题）。
 */
internal fun parseLibraryCallback(html: String): LibraryCallbackParams? {
    val data = Regex("""\bdata:\s*"([^"]{8,})"""").find(html)?.groupValues?.get(1) ?: return null
    val time = Regex("""\btime:\s*(\d{10,})""").find(html)?.groupValues?.get(1) ?: return null
    val enc = Regex("""\benc:\s*"([0-9A-Fa-f]{16,})"""").find(html)?.groupValues?.get(1) ?: return null
    val displayName = Regex("""\bdisplayName:\s*"((?:[^"\\]|\\.)*)"""")
        .find(html)?.groupValues?.get(1)?.let(::unescapeJsString).orEmpty()
    val firstReferer = Regex("""var\s+firstReferer\s*=\s*"((?:[^"\\]|\\.)*)"""")
        .find(html)?.groupValues?.get(1)?.let(::unescapeJsString)?.takeIf { it.isNotBlank() }
    return LibraryCallbackParams(data = data, time = time, enc = enc, displayName = displayName, firstReferer = firstReferer)
}

private fun unescapeJsString(raw: String): String = buildString {
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char == '\\' && index + 1 < raw.length) {
            val next = raw[index + 1]
            when (next) {
                '/' -> append('/')
                '\\' -> append('\\')
                '"' -> append('"')
                'u' -> {
                    val hex = raw.substring(index + 2, minOf(index + 6, raw.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) {
                        append(code.toChar())
                        index += 4
                    } else {
                        append(next)
                    }
                }
                else -> append(next)
            }
            index += 2
        } else {
            append(char)
            index += 1
        }
    }
}

/**
 * 图书馆登录：SSO 统一身份认证 OAuth2 授权 + 图书馆服务端回调页换会话。
 *
 * 流程（与浏览器一致，已用真实抓包验证）：
 * 1. GET SSO 授权地址。SSO 会话仍有效时直接 302 回 `opac.bjtu.edu.cn/OAuth2/bjtu/index?code=...`；
 *    无效时落在 SSO 登录页，此时用应用内存中已有的统一身份认证凭据提交一次（密码 SM2 加密）。
 * 2. 回调页是服务端渲染 HTML，内含一次性的 `data/time/enc`。
 * 3. GET `/OAuth2/bjtu/login?...`，成功返回 `{"status":true}` 并写入 opac 会话 Cookie（`p_auth_token` 等）。
 *    之后所有 `/find/...` 接口只靠这份 Cookie 鉴权（抓包确认：不带动态 JWT 头也能读取借阅）。
 */
class LibraryLoginProtocol(
    private val transport: SchoolHttpTransport,
    private val credentialsProvider: () -> Credentials? = { null },
) : LibraryAuthenticator {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 登录成功后动态 JWT 头（若回调链给出）；目前仅用于诊断，接口鉴权依赖 Cookie。 */
    var sessionHeaderName: String? = null
        private set
    var sessionHeaderValue: String? = null
        private set

    override suspend fun ensureSession(): LibraryFailure? = mutex.withLock {
        try {
            handshake()
        } catch (error: CancellationException) {
            throw error
        } catch (_: SchoolNetworkException) {
            LibraryFailure(LibraryFailureKind.NETWORK)
        } catch (_: Exception) {
            LibraryFailure(LibraryFailureKind.NETWORK)
        }
    }

    private suspend fun handshake(): LibraryFailure? {
        val authorized = followAllowed(LibraryEndpoints.SSO_AUTHORIZE_URL)
            ?: return LibraryFailure(LibraryFailureKind.NETWORK)
        val callbackPage: SchoolHttpResponse = if (isSsoLoginPage(authorized)) {
            val landing = loginWithStoredCredentials(authorized)
                ?: return ssoFailure ?: LibraryFailure(LibraryFailureKind.MALFORMED_RESPONSE)
            landing
        } else {
            authorized
        }
        if (!callbackPage.finalUrl.startsWith("${LibraryEndpoints.ORIGIN}/OAuth2/bjtu/index")) {
            return LibraryFailure(LibraryFailureKind.MALFORMED_RESPONSE)
        }
        val callback = parseLibraryCallback(callbackPage.bodyText())
            ?: return LibraryFailure(LibraryFailureKind.MALFORMED_RESPONSE)

        // 学校要求先换会话；失败时页面只回 {"status":false}，没有更多原因。
        val loginUrl = LibraryEndpoints.OAUTH_LOGIN +
            "?data=" + callback.data.encodeURLParameter() +
            "&time=" + callback.time +
            "&enc=" + callback.enc +
            "&displayName=" + callback.displayName.encodeURLParameter() +
            "&userRole=3"
        // 不自动跟随重定向：一次性 data/time/enc 只能发给图书馆主机；这里只接受 2xx JSON，
        // 任何 3xx 都按失败处理，避免被带去 followAllowed 白名单以外的站点。
        val exchanged = transport.executeWithoutRedirects(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = loginUrl,
                headers = mapOf(
                    "Referer" to callbackPage.finalUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
            ),
        )
        if (exchanged.statusCode !in 200..299) return LibraryFailure(LibraryFailureKind.NETWORK)
        val returned = runCatching {
            json.parseToJsonElement(exchanged.bodyText()) as? JsonObject
        }.getOrNull()
        if (returned?.get("status")?.toString() != "true") {
            return LibraryFailure(LibraryFailureKind.SERVER, "图书馆登录未通过，请稍后重试。")
        }

        // 浏览器此时会跳到 firstReferer 完成收尾：这一步才会下发 API 鉴权用的 SESSION Cookie
        //（只做 /OAuth2/bjtu/login 时 /find/* 会全部 401，已实测）。失败按网络失败处理，
        // 让 repository 的“会话过期重登一次”策略再试一轮。
        val finishUrl = callback.firstReferer?.takeIf { it.startsWith(LibraryEndpoints.ORIGIN) }
            ?: LibraryEndpoints.LOGIN_REFER
        val finished = followAllowed(
            finishUrl + (if ('?' in finishUrl) "&" else "?") +
                "data=" + callback.data.encodeURLParameter() +
                "&time=" + callback.time + "&enc=" + callback.enc,
        ) ?: return LibraryFailure(LibraryFailureKind.NETWORK)
        captureSessionHeader(finished.finalUrl)
        captureSessionHeader(finished.bodyText())
        return null
    }

    /** SSO 授权页在未登录时返回 200 登录表单；已登录则已被重定向到回调页。 */
    private fun isSsoLoginPage(response: SchoolHttpResponse): Boolean =
        response.statusCode in 200..299 &&
            response.bodyText().contains("id=\"loginForm\"", ignoreCase = true)

    /** 成功返回回调页；失败把原因写进 [ssoFailure] 供调用方返回。 */
    private var ssoFailure: LibraryFailure? = null

    private suspend fun loginWithStoredCredentials(loginPage: SchoolHttpResponse): SchoolHttpResponse? {
        ssoFailure = null
        val parsed = parseOffMain { parseSsoLoginPage(loginPage.bodyText()) }
        val page = (parsed as? ParseResult.Success)?.value
        if (page == null) {
            ssoFailure = LibraryFailure(LibraryFailureKind.MALFORMED_RESPONSE)
            return null
        }
        if (page.error?.requiresCaptcha == true) {
            ssoFailure = LibraryFailure(LibraryFailureKind.CAPTCHA_REQUIRED)
            return null
        }
        val credentials = credentialsProvider()?.takeIf { it.isValid }
        if (credentials == null) {
            ssoFailure = LibraryFailure(LibraryFailureKind.LOGIN_REQUIRED)
            return null
        }
        val password = if (page.sm2PublicKey.isBlank()) {
            credentials.password
        } else {
            runCatching { SsoSm2.encryptPassword(credentials.password, page.sm2PublicKey) }
                .getOrElse {
                    ssoFailure = LibraryFailure(LibraryFailureKind.MALFORMED_RESPONSE)
                    return null
                }
        }
        val fields = page.fields.toMutableMap().apply {
            put("username", credentials.username)
            put("password", password)
            put("loginType", "username_password")
            put("captcha", "")
        }
        val submitted = transport.executeWithoutRedirects(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = LibraryEndpoints.SSO_LOGIN_URL,
                headers = mapOf(
                    "Referer" to loginPage.finalUrl,
                    "Origin" to LibraryEndpoints.SSO_ORIGIN,
                ),
                formFields = fields,
            ),
        )
        val landing = if (submitted.statusCode in 300..399) {
            val location = submitted.header("Location")
                ?: run {
                    ssoFailure = LibraryFailure(LibraryFailureKind.MALFORMED_RESPONSE)
                    return null
                }
            followAllowed(resolveAllowed(submitted.finalUrl, location) ?: return null)
                ?: run {
                    ssoFailure = LibraryFailure(LibraryFailureKind.NETWORK)
                    return null
                }
        } else {
            submitted
        }
        if (landing.finalUrl.startsWith(LibraryEndpoints.SSO_ORIGIN)) {
            val error = (parseOffMain { parseSsoLoginPage(landing.bodyText()) } as? ParseResult.Success)?.value?.error
            ssoFailure = when {
                error == null -> LibraryFailure(LibraryFailureKind.MALFORMED_RESPONSE)
                error.requiresCaptcha -> LibraryFailure(LibraryFailureKind.CAPTCHA_REQUIRED)
                error.wrongCredentials -> LibraryFailure(LibraryFailureKind.LOGIN_REJECTED)
                else -> LibraryFailure(LibraryFailureKind.SERVER, error.message)
            }
            return null
        }
        return landing
    }

    /** 逐跳跟随，只允许 SSO 与图书馆主机的 HTTPS 443，避免被登录链带去任意站点。 */
    private suspend fun followAllowed(url: String, maxHops: Int = 10): SchoolHttpResponse? {
        var current = url
        var hops = 0
        while (hops < maxHops) {
            hops += 1
            val response = transport.executeWithoutRedirects(
                SchoolHttpRequest(
                    method = SchoolHttpMethod.GET,
                    url = current,
                    headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
                ),
            )
            if (response.statusCode !in 300..399) return response
            val location = response.header("Location") ?: return null
            current = resolveAllowed(response.finalUrl, location) ?: return null
        }
        return null
    }

    private fun captureSessionHeader(text: String) {
        val match = Regex("""loginExternal/([^/?#"'\s<>]+)/([^/?#"'\s<>]+)""").find(text) ?: return
        val token = match.groupValues[1]
        val name = match.groupValues[2]
        if (token.isNotBlank() && name.isNotBlank()) {
            sessionHeaderValue = token
            sessionHeaderName = name
        }
    }

    private companion object {
        val ALLOWED_HOSTS = setOf("opac.bjtu.edu.cn", "sso.bjtu.edu.cn")
    }

    private fun resolveAllowed(base: String, location: String): String? {
        if (location.any(Char::isISOControl)) return null
        val target = runCatching {
            URLBuilder(Url(base)).apply { parameters.clear() }.takeFrom(location).build()
        }.getOrNull() ?: return null
        if (target.protocol.name != "https") return null
        if (target.port != 443 && target.port != 0) return null
        if (target.host !in ALLOWED_HOSTS) return null
        return target.toString()
    }
}

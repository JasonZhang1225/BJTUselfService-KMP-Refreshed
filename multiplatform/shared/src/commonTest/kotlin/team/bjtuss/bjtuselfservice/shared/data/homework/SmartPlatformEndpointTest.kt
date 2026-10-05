package team.bjtuss.bjtuselfservice.shared.data.homework

import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartPlatformEndpointTest {
    @Test
    fun verifiedHttpsNeverAcceptsLegacyOrigin() {
        val endpoint = SmartPlatformEndpoint.VerifiedHttps

        assertTrue(endpoint.acceptsApiUrl("https://bksycenter.bjtu.edu.cn/ve/back/course.shtml"))
        assertFalse(endpoint.acceptsApiUrl("http://123.121.147.7:88/ve/back/course.shtml"))
        assertFalse(endpoint.acceptsApiUrl("https://bksycenter.bjtu.edu.cn.evil.test/ve/back/course.shtml"))
    }

    @Test
    fun legacyEndpointAllowsOnlyExactApiOriginAndVePath() {
        val endpoint = SmartPlatformEndpoint.LegacyHttp

        assertTrue(endpoint.acceptsApiUrl("http://123.121.147.7:88/ve/back/course.shtml"))
        assertFalse(endpoint.acceptsApiUrl("http://123.121.147.7/ve/back/course.shtml"))
        assertFalse(endpoint.acceptsApiUrl("http://123.121.147.7:1936/ve/back/course.shtml"))
        assertFalse(endpoint.acceptsApiUrl("https://123.121.147.7:88/ve/back/course.shtml"))
        assertFalse(endpoint.acceptsApiUrl("http://123.121.147.7:88/admin/course.shtml"))
        assertFalse(endpoint.acceptsApiUrl("http://123.121.147.7:88/ve/%2e%2e/admin"))
    }

    @Test
    fun legacyCalendarIsRebuiltAtFixedOriginFromFiveSafeSegments() {
        val endpoint = SmartPlatformEndpoint.LegacyHttp

        val resolved = endpoint.resolveTeachingCalendarUrl(
            "https://untrusted.example/root/2026/term/course/teacher/calendar.pdf?token=ignored",
        )

        assertEquals(
            "http://123.121.147.7:1936/kk/rp/2026/term/course/teacher/calendar.pdf",
            resolved,
        )
        assertTrue(endpoint.acceptsCalendarUrl(resolved!!))
        assertEquals(
            "http://123.121.147.7:1936/kk/rp/a/b/c/d/calendar.pdf",
            endpoint.resolveTeachingCalendarUrl("/a/b/c/d/calendar.pdf"),
        )
        assertFalse(endpoint.acceptsCalendarUrl("http://123.121.147.7:88/kk/rp/a/b/c/d/e.pdf"))
        assertNull(endpoint.resolveTeachingCalendarUrl("https://example.test/a/b/%2e%2e/c/d/e.pdf"))
        assertNull(endpoint.resolveTeachingCalendarUrl("https://example.test/a/b/%252e%252e/c/d/e.pdf"))
        assertNull(endpoint.resolveTeachingCalendarUrl("https://example.test/a/b/c/d/e.pdf#fragment"))
    }

    @Test
    fun legacyRedirectTargetOnlyFollowsWhitelistedCleartextApi() {
        val endpoint = SmartPlatformEndpoint.LegacyHttp

        // 裸 302 指向明文第三方登录入口（/oauth/，非 /ve/）：握手阶段放行
        assertEquals(
            "http://123.121.147.7:88/oauth/api/user/thirdLogin?ticket=<redacted>",
            endpoint.allowedRedirectTarget(
                302,
                "http://123.121.147.7:88/oauth/api/user/thirdLogin?ticket=<redacted>",
            ),
        )
        // origin 内任意路径在握手阶段放行，但业务 API 仍须 /ve/（见 acceptsApiUrl 测试）
        assertTrue(endpoint.acceptsHandshakeUrl("http://123.121.147.7:88/ve/back/course.shtml"))
        assertTrue(endpoint.acceptsHandshakeUrl("http://123.121.147.7:88/oauth/api/user/thirdLogin"))
        // 非 3xx 状态不放行
        assertNull(endpoint.allowedRedirectTarget(200, "http://123.121.147.7:88/ve/x.shtml"))
        // 空 Location 不放行
        assertNull(endpoint.allowedRedirectTarget(302, null))
        assertNull(endpoint.allowedRedirectTarget(302, "   "))
        // 降级绝不扩散到白名单外的主机/端口/协议/路径
        assertNull(endpoint.allowedRedirectTarget(302, "http://evil.example/ve/back/x.shtml"))
        assertNull(endpoint.allowedRedirectTarget(302, "http://123.121.147.7/ve/back/x.shtml"))
        assertNull(endpoint.allowedRedirectTarget(302, "https://123.121.147.7:88/ve/back/x.shtml"))
        // 教学日历 origin（:1936）不在握手放行范围
        assertNull(endpoint.allowedRedirectTarget(302, "http://123.121.147.7:1936/kk/rp/a/b/c/d/e.pdf"))
        assertFalse(endpoint.acceptsHandshakeUrl("http://123.121.147.7:1936/oauth/x"))
    }

    @Test
    fun httpsRedirectTargetStillRequiresWhitelistedApi() {
        val endpoint = SmartPlatformEndpoint.VerifiedHttps

        assertEquals(
            "https://bksycenter.bjtu.edu.cn/ve/back/course.shtml",
            endpoint.allowedRedirectTarget(301, "https://bksycenter.bjtu.edu.cn/ve/back/course.shtml"),
        )
        assertNull(endpoint.allowedRedirectTarget(301, "https://bksycenter.bjtu.edu.cn.evil.test/ve/x.shtml"))
        assertNull(endpoint.allowedRedirectTarget(301, "http://123.121.147.7:88/ve/x.shtml"))
    }

    @Test
    fun handshakeDoesNotRequestOffOriginLocation() = runBlocking {
        val endpoint = SmartPlatformEndpoint.LegacyHttp
        val requested = mutableListOf<String>()
        val first = redirectResponse(
            from = "https://mis.bjtu.edu.cn/module/module/28/",
            to = "https://evil.example/steal",
        )

        val settled = endpoint.followSmartHandshakeRedirects(
            first = first,
            referer = "https://mis.bjtu.edu.cn/module/module/28/",
        ) { request ->
            requested += request.url
            error("off-origin Location must not be requested")
        }

        assertTrue(requested.isEmpty())
        assertEquals(first, settled)
    }

    @Test
    fun handshakeStopsBeforeTheEleventhHop() = runBlocking {
        val endpoint = SmartPlatformEndpoint.LegacyHttp
        val requested = mutableListOf<String>()
        val first = redirectResponse(
            from = "https://mis.bjtu.edu.cn/module/module/28/",
            to = "http://123.121.147.7:88/oauth/hop-0",
        )

        val settled = endpoint.followSmartHandshakeRedirects(
            first = first,
            referer = "https://mis.bjtu.edu.cn/module/module/28/",
        ) { request ->
            requested += request.url
            val hop = requested.size
            redirectResponse(
                from = request.url,
                to = "http://123.121.147.7:88/oauth/hop-$hop",
            )
        }

        assertEquals(10, requested.size)
        assertEquals("http://123.121.147.7:88/oauth/hop-0", requested.first())
        assertEquals("http://123.121.147.7:88/oauth/hop-9", requested.last())
        assertEquals(302, settled.statusCode)
        assertEquals("http://123.121.147.7:88/oauth/hop-10", settled.header("Location"))
    }

    @Test
    fun handshakeFollowsSameSchemeHttpsOneHopAtATime() = runBlocking {
        val endpoint = SmartPlatformEndpoint.LegacyHttp
        val requested = mutableListOf<String>()
        val first = redirectResponse(
            from = "https://mis.bjtu.edu.cn/module/module/28/",
            to = "https://cas.bjtu.edu.cn/auth/authorize",
        )

        val settled = endpoint.followSmartHandshakeRedirects(
            first = first,
            referer = "https://mis.bjtu.edu.cn/module/module/28/",
        ) { request ->
            requested += request.url
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = request.url,
                body = "<html></html>".encodeToByteArray(),
            )
        }

        assertEquals(listOf("https://cas.bjtu.edu.cn/auth/authorize"), requested)
        assertEquals(200, settled.statusCode)
        assertEquals("https://cas.bjtu.edu.cn/auth/authorize", settled.finalUrl)
    }

    @Test
    fun handshakeRelativeRedirectReplacesOAuthQueryRatherThanAppendingIt() = runBlocking {
        val query = "response_type=code&client_id=example&redirect_uri=http%3A%2F%2F123.121.147.7%3A88%2Foauth%2Fcallback"
        val base = "https://cas.bjtu.edu.cn/o/authorize?$query"
        val location = "/o/authorize/?$query"
        var requested: SchoolHttpRequest? = null
        SmartPlatformEndpoint.LegacyHttp.followSmartHandshakeRedirects(
            first = redirectResponse(base, location),
            referer = "https://mis.bjtu.edu.cn/module/module/28/",
        ) { request ->
            requested = request
            SchoolHttpResponse(statusCode = 200, finalUrl = request.url)
        }
        assertEquals("https://cas.bjtu.edu.cn$location", requested?.url)
    }

    private fun redirectResponse(from: String, to: String): SchoolHttpResponse = SchoolHttpResponse(
        statusCode = 302,
        finalUrl = from,
        headers = mapOf("Location" to listOf(to)),
    )
}

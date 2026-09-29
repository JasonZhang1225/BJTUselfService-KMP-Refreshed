package team.bjtuss.bjtuselfservice.shared.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebPageModelsTest {

    private fun cookie(domain: String) = WebCookie(
        name = "sessionid",
        value = "secret",
        domain = domain,
    )

    @Test
    fun allowsSchoolHostWithMatchingCookie() {
        val request = WebPageRequest(
            url = "https://mis.bjtu.edu.cn/module/1/?a=b",
            title = "校园信息门户",
            cookies = listOf(cookie("mis.bjtu.edu.cn")),
        )
        assertEquals(WebPageValidation.Allowed, SchoolWebDomainPolicy.validate(request))
    }

    @Test
    fun rejectsNonSchoolHost() {
        val request = WebPageRequest(url = "https://example.com/x", title = "外部")
        val result = SchoolWebDomainPolicy.validate(request)
        assertIs<WebPageValidation.Rejected>(result)
    }

    @Test
    fun rejectsCookieForDifferentHost() {
        val request = WebPageRequest(
            url = "https://mis.bjtu.edu.cn/home/",
            title = "门户",
            cookies = listOf(cookie("attacker.example.com")),
        )
        assertIs<WebPageValidation.Rejected>(SchoolWebDomainPolicy.validate(request))
    }

    @Test
    fun rejectsCrossSchoolHostCookie() {
        // 即便都是学校域名，aa 的 Cookie 也不能注入到 mis 页面。
        val request = WebPageRequest(
            url = "https://mis.bjtu.edu.cn/home/",
            title = "门户",
            cookies = listOf(cookie("aa.bjtu.edu.cn")),
        )
        assertIs<WebPageValidation.Rejected>(SchoolWebDomainPolicy.validate(request))
    }

    @Test
    fun rejectsBroadParentDomainCookie() {
        val request = WebPageRequest(
            url = "https://mis.bjtu.edu.cn/home/",
            title = "门户",
            cookies = listOf(cookie(".bjtu.edu.cn")),
        )
        assertIs<WebPageValidation.Rejected>(SchoolWebDomainPolicy.validate(request))
    }

    @Test
    fun allowsKnownMailHostWithoutInjectingMisCookie() {
        val request = WebPageRequest(url = "https://mail.bjtu.edu.cn/", title = "邮箱")
        assertEquals(WebPageValidation.Allowed, SchoolWebDomainPolicy.validate(request))
    }

    @Test
    fun allowsPhyVlabHostWithMatchingCookieDomain() {
        val request = WebPageRequest(
            url = "https://phyvlab.bjtu.edu.cn/course/view.php?id=74",
            title = "物理在线",
            cookies = listOf(cookie("phyvlab.bjtu.edu.cn")),
        )
        assertEquals(WebPageValidation.Allowed, SchoolWebDomainPolicy.validate(request))
    }

    @Test
    fun schoolHostDetectionIsCaseAndPortInsensitive() {
        assertTrue(SchoolWebDomainPolicy.isSchoolHost("https://AA.BJTU.EDU.CN:443/notice/"))
        assertFalse(SchoolWebDomainPolicy.isSchoolHost("https://bjtu.edu.cn.evil.com/"))
        assertFalse(SchoolWebDomainPolicy.isSchoolHost("https://notbjtu.edu.cn/"))
        assertFalse(SchoolWebDomainPolicy.isSchoolHost("http://mis.bjtu.edu.cn/home/"))
        assertFalse(SchoolWebDomainPolicy.isSchoolHost("file://mis.bjtu.edu.cn/home/"))
        assertFalse(SchoolWebDomainPolicy.isSchoolHost("javascript:alert(1)"))
    }

    @Test
    fun requestRequiresHttps() {
        assertFailsWith<IllegalArgumentException> {
            WebPageRequest(url = "http://mis.bjtu.edu.cn/home/", title = "门户")
        }
    }

    @Test
    fun cookieToStringRedactsValue() {
        val text = cookie("mis.bjtu.edu.cn").toString()
        assertTrue("redacted" in text)
        assertFalse("secret" in text)
    }
}

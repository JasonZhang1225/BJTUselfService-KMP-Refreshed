package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PhyVlabCaptchaHtmlTest {
    private val captchaId = "0123456789abcdef0123456789abcdef"

    @Test
    fun mobileDeliveryRetainsTheProofForWebViewReads() {
        val html = phyVlabCaptchaHtml(captchaId, PhyVlabCaptchaDelivery.WindowVariable)
        assertContains(html, "window.$PHYVLAB_CAPTCHA_RESULT_VARIABLE = result;")
        assertFalse(html.contains("messageHandlers"))
        assertFalse(html.contains("fetch(location.pathname"))
    }

    @Test
    fun desktopDeliveryPostsBackToTheLoopbackEndpoint() {
        val html = phyVlabCaptchaHtml(captchaId, PhyVlabCaptchaDelivery.LoopbackFetch)
        assertContains(html, "fetch(location.pathname")
        assertFalse(html.contains("messageHandlers"))
        assertFalse(html.contains("phyVlabCaptchaProof"))
    }

    @Test
    fun everyDeliveryKeepsTheSameChallengeAndSecurityBoundary() {
        PhyVlabCaptchaDelivery.entries.forEach { delivery ->
            val html = phyVlabCaptchaHtml(captchaId, delivery)
            assertContains(html, captchaId)
            assertContains(html, "initAlicom4(")
            assertContains(html, "https://phyvlab.bjtu.edu.cn/login/ct4.js")
            assertContains(html, "default-src 'none'")
            assertContains(html, "armNoResponseHint();")
            assertContains(html, "已完成验证，但应用没有收到结果")
            // 结果字段必须与学校 captcha_verify.php 的 JSON 契约一致。
            listOf("lot_number", "captcha_output", "pass_token", "gen_time").forEach {
                assertContains(html, it)
            }
        }
    }

    @Test
    fun rejectsCaptchaIdsThatAreNotThirtyTwoHexCharacters() {
        assertFailsWith<IllegalArgumentException> { phyVlabCaptchaHtml("not-a-captcha-id") }
        assertFailsWith<IllegalArgumentException> { phyVlabCaptchaHtml("' onload='alert(1)") }
        assertEquals(32, captchaId.length)
    }
}

package team.bjtuss.bjtuselfservice.shared.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchoolHttpResponseSessionDetectionTest {
    @Test
    fun sameUrlLoginHtmlIsNotPassedToBusinessParsers() {
        val response = SchoolHttpResponse(
            statusCode = 200,
            finalUrl = "https://smart.bjtu.edu.cn/ve/back/coursePlatform/course.shtml",
            body = """
                <!doctype html>
                <form id="login"><input name="password" type="password"></form>
            """.trimIndent().encodeToByteArray(),
        )

        assertTrue(response.looksLikeSessionExpired())
    }

    @Test
    fun normalApiJsonIsNotClassifiedAsSessionExpiry() {
        val response = SchoolHttpResponse(
            statusCode = 200,
            finalUrl = "https://smart.bjtu.edu.cn/ve/back/coursePlatform/course.shtml",
            body = "{\"rows\":[]}".encodeToByteArray(),
        )

        assertFalse(response.looksLikeSessionExpired())
    }
}

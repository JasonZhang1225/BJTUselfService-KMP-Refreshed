package team.bjtuss.bjtuselfservice.shared.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 回调页解析测试。HTML 结构按学校真实页面（超星 CASAuth）构造，值全部是虚构的；
 * `data/time/enc` 是学校每次登录现场生成的一次性参数，解析失败必须返回 null，
 * 不能拿空值继续请求。
 */
class LibraryLoginProtocolTest {

    private fun callbackHtml(
        data: String = "0123456789abcdef0123456789abcdef",
        time: String = "1792567061413",
        enc: String = "48845894F128D4DB682A6BB82EE9E858",
        displayName: String = "\\u6D4B\\u8BD5",
        firstReferer: String = "https:\\/\\/opac.bjtu.edu.cn\\/find\\/sso\\/login\\/bjtu\\/0",
    ): String = """
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head><meta charset="utf-8"/><title>跳转中...</title></head>
        <body></body>
        <script>
            ${'$'}(function () {
                var url = "\/OAuth2\/bjtu\/login";
                var firstReferer = "$firstReferer";
                var res = 0;
                jQuery.ajax({
                    type: "get",
                    url: url,
                    async: false,
                    data: {
                        data: "$data",
                        time: $time,
                        enc: "$enc",
                        displayName: "$displayName",
                        userRole: 3
                    },
                    dataType: "json"
                });
            });
        </script>
        </html>
    """.trimIndent()

    @Test
    fun parsesOneTimeLoginParameters() {
        val parsed = parseLibraryCallback(callbackHtml())
            ?: error("expected callback params")
        assertEquals("0123456789abcdef0123456789abcdef", parsed.data)
        assertEquals("1792567061413", parsed.time)
        assertEquals("48845894F128D4DB682A6BB82EE9E858", parsed.enc)
    }

    @Test
    fun unescapesDisplayNameAndFirstReferer() {
        val parsed = parseLibraryCallback(callbackHtml()) ?: error("expected callback params")
        assertEquals("测试", parsed.displayName)
        assertEquals("https://opac.bjtu.edu.cn/find/sso/login/bjtu/0", parsed.firstReferer)
    }

    @Test
    fun missingDataReturnsNull() {
        assertNull(parseLibraryCallback("""<script>time: 1792567061413, enc: "48845894F128D4DB682A6BB82EE9E858"</script>"""))
    }

    @Test
    fun missingTimeReturnsNull() {
        assertNull(parseLibraryCallback("""<script>data: "0123456789abcdef", enc: "48845894F128D4DB682A6BB82EE9E858"</script>"""))
    }

    @Test
    fun missingEncReturnsNull() {
        assertNull(parseLibraryCallback("""<script>data: "0123456789abcdef", time: 1792567061413</script>"""))
    }

    @Test
    fun blankFirstRefererIsDropped() {
        val parsed = parseLibraryCallback(callbackHtml(firstReferer = "")) ?: error("expected callback params")
        assertNull(parsed.firstReferer)
    }

    @Test
    fun finishUrlKeepsLibraryHostReferer() {
        assertEquals(
            "https://opac.bjtu.edu.cn/find/sso/login/bjtu/0",
            libraryFinishUrl("https://opac.bjtu.edu.cn/find/sso/login/bjtu/0"),
        )
    }

    @Test
    fun finishUrlRejectsLookalikeAndForeignHosts() {
        listOf(
            "https://opac.bjtu.edu.cn.attacker.example/find/sso/login/bjtu/0",
            "https://opac.bjtu.edu.cn@attacker.example/find",
            "http://opac.bjtu.edu.cn/find/sso/login/bjtu/0",
            "https://opac.bjtu.edu.cn:8443/find/sso/login/bjtu/0",
            "https://attacker.example/?u=https://opac.bjtu.edu.cn",
            "/find/sso/login/bjtu/0",
            "",
            null,
        ).forEach { referer ->
            assertEquals(LibraryEndpoints.LOGIN_REFER, libraryFinishUrl(referer), "referer=$referer")
        }
    }
}

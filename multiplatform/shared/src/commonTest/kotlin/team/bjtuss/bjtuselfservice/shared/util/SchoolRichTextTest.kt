package team.bjtuss.bjtuselfservice.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchoolRichTextTest {
    @Test
    fun rendersFormattingAndRemovesActiveContent() {
        val html = "<p>要求<br><strong>重点</strong> 与 <em>斜体</em></p><script>bad()</script>"
        val safe = schoolRichTextSafeFragment(html)
        val result = schoolRichTextToAnnotated(safe)
        assertEquals("要求\n重点 与 斜体", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold && result.text.substring(it.start, it.end) == "重点" })
        assertTrue(result.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
        assertFalse(safe.contains("bad()"))
    }

    @Test
    fun preservesHtmlBreaks() {
        val text = schoolRichTextToPlainMultiline("第一行<br>第二行<br/>第三行")
        assertEquals(listOf("第一行", "第二行", "第三行"), text.lines())
    }

    @Test
    fun breaksNumberedPlainTextItems() {
        val flat =
            "1. 实验名称 课程助教。 2. 实验目的 本课程。 3. 实验环境 Windows。 4. 核心任务 每组。"
        val text = schoolRichTextToPlainMultiline(flat)
        assertTrue(text.lines().size >= 4)
        assertTrue(text.lines()[0].startsWith("1."))
        assertTrue(text.lines().any { it.startsWith("2.") })
        assertTrue(text.lines().any { it.startsWith("3.") })
        assertTrue(text.lines().any { it.startsWith("4.") })
    }

    @Test
    fun stripsTagsButKeepsParagraphs() {
        val text = schoolRichTextToPlainMultiline("<p>段一</p><p>段二</p>")
        assertEquals(listOf("段一", "段二"), text.lines())
    }

    @Test
    fun extractsTablesAndSkipsEmbeddedStyleRules() {
        val html = """
            <p>说明文字</p>
            <style>td { word-break: break-word; }</style>
            <table>
                <tr><th>序号</th><th>姓名</th></tr>
                <tr><td>1</td><td>测试同学</td></tr>
            </table>
        """.trimIndent()

        val blocks = schoolRichTextToBlocks(html)
        assertEquals(
            listOf(SchoolRichTextBlock.Paragraph("说明文字")),
            blocks.filterIsInstance<SchoolRichTextBlock.Paragraph>(),
        )
        val table = blocks.filterIsInstance<SchoolRichTextBlock.Table>().single()
        assertEquals(
            listOf(
                listOf("序号", "姓名"),
                listOf("1", "测试同学"),
            ),
            table.rows,
        )
        assertFalse(schoolRichTextToPlainMultiline(html).contains("word-break"))
    }

    @Test
    fun extractsMultipleWordTablesFromOneMail() {
        val html = """
            <div>
                <style>.default-font { font-size: 14px; }</style>
                <p>一等奖</p>
                <table><tr><td>序号</td><td>学生</td></tr><tr><td>1</td><td>甲</td></tr></table>
                <p>二等奖</p>
                <table><tr><td>序号</td><td>学生</td></tr><tr><td>2</td><td>乙</td></tr></table>
            </div>
        """.trimIndent()

        val tables = schoolRichTextToBlocks(html).filterIsInstance<SchoolRichTextBlock.Table>()
        assertEquals(2, tables.size)
        assertTrue(tables.all { it.rows.size == 2 })
    }
}

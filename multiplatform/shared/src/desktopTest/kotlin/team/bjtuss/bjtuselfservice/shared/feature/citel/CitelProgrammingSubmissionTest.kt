package team.bjtuss.bjtuselfservice.shared.feature.citel

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.network.*

class CitelProgrammingSubmissionTest {
    private val task = CitelTask(7, 3, "测试课程", "测试代码", "$CITEL_BASE/mod/programming/view.php?id=7", true)
    private val file = HomeworkFileContent("main.cpp", "text/plain", "int main(){}".encodeToByteArray())
    @Test fun sourceFileAndSelectedLanguageAreSentAndPendingJudgeIsNotCompletion() = runBlocking {
        val fixture = Fixture()
        assertEquals(mapOf("0" to "gcc", "1" to "g++"), fixture.client.options(task).languages)
        val result = fixture.client.submit(task, file, "1")
        assertEquals(1, result.submitCount)
        assertFalse(result.accepted)
        assertEquals("RJ: Running", result.status)
        val write = fixture.writes.single()
        assertEquals("5374", write.formFields["a"])
        assertEquals("1", write.formFields["language"])
        assertEquals("sourcefile", write.multipartFiles.single().fieldName)
    }
    @Test fun invalidLanguageAndOversizedFileFailBeforeSendingCode() = runBlocking {
        val fixture = Fixture()
        assertFailsWith<CitelFailure> { fixture.client.submit(task, file, "python") }
        assertFailsWith<CitelFailure> { fixture.client.submit(task, HomeworkFileContent("large.cpp", "text/plain", ByteArray(65537)), "1") }
        assertTrue(fixture.writes.isEmpty())
    }
    @Test fun lostPostResponseIsNotRetried() = runBlocking {
        val fixture = Fixture()
        fixture.disconnect = true
        assertFailsWith<IllegalStateException> { fixture.client.submit(task, file, "1") }
        assertEquals(1, fixture.writes.size)
    }
    private class Fixture {
        var count = 0
        var disconnect = false
        val writes = mutableListOf<SchoolHttpRequest>()
        val client = CitelProgrammingClient(read = { url ->
            val body = when {
                url.contains("/index.php") -> """<main id="region-main"><table><tbody><tr><td>章节</td><td><a href="view.php?id=7">测试</a></td><td>${if (count > 0) "RJ: Running" else ""}</td><td>g++</td><td>1</td><td>$count</td><td>26-09-15 18:08-26-10-18 23:59<br>26-10-18 23:59+</td></tr></tbody></table></main>"""
                url.contains("/submit.php") -> """<main id="region-main"><form action="$CITEL_BASE/mod/programming/submit.php"><input type="hidden" name="a" value="5374"><input type="hidden" name="MAX_FILE_SIZE" value="65536"><input type="file" name="sourcefile"><select name="language"><option value="0">gcc</option><option value="1">g++</option></select></form></main>"""
                else -> """<main id="region-main"><a href="$CITEL_BASE/mod/programming/submit.php?a=5374">Submit</a></main>"""
            }
            SchoolHttpResponse(200, url, body = body.encodeToByteArray())
        }, write = { request ->
            writes += request
            count++
            if (disconnect) error("fixture lost response")
            SchoolHttpResponse(200, "$CITEL_BASE/mod/programming/result.php?a=5374", body = "<main>Running</main>".encodeToByteArray())
        })
    }
}

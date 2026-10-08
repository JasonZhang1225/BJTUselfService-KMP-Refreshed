package team.bjtuss.bjtuselfservice.shared.feature.citel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault

class CitelProgrammingResultTest {
    private val task = CitelTask(7, 3, "数据结构 · 测试课程", "顺序表", "$CITEL_BASE/mod/programming/view.php?id=7", true)
    private val accepted = CitelProgrammingResult("AC: Accepted", true, emptyList(), false, 1,
        totalTests = 6, passedTests = 6, failedTests = 0,
        testCases = (1..6).map { CitelProgrammingTestCase(it, true, "AC: Accepted", "0", "3MB") })

    @Test fun onlyAcMeansCompletionAndPendingJudgesAreVisible() {
        listOf("AC", "AC: Accepted", " ac: accepted ").forEach { assertTrue(isCitelAccepted(it)) }
        listOf("WA: Wrong Answer", "RJ: Running", "已提交", "ACCEPTED", "not AC").forEach { assertFalse(isCitelAccepted(it)) }
        listOf("RJ: Running", "WJ: Waiting", "CJ: Compiling", "PD").forEach { assertTrue(isCitelTesting(it)) }
        assertFalse(isCitelTesting("WA: Wrong Answer"))
        assertTrue(CitelProgrammingResult("", false, emptyList(), false, submitCount = 1).testing)
        assertFalse(CitelProgrammingResult("", false, emptyList(), false, submitCount = 0).testing)
        assertEquals("已通过（AC）", task.copy(status = "AC: Accepted", submitted = true).submissionStatusText())
        val detail = "<main id='region-main'><h1 class='name'>题目</h1><table><tr><th>Submission status</th><td>已提交</td></tr></table></main>"
        assertFalse(parseCitelTaskDetail(detail, task).submitted)
    }

    @Test fun readsSixCasesAndCountsWithoutCountingNestedIoRows() {
        val html = """<main id="region-main">Current Status: The program is successfully processed.
            Test Result: There are 6 test cases. Your program has passed 6 of them and failed in 0 of them.
            <table id="test-result-detail-table"><tbody>${(1..6).joinToString("") { n ->
                "<tr><td>$n</td><td>1</td><td>1 seconds</td><td>128MB</td><td><table><tr><td>99</td></tr></table></td><td>output</td><td>output</td><td>N/A</td><td>0</td><td>3MB</td><td>Secure test</td><td>Yes</td><td>AC: Accepted</td></tr>"
            }}</tbody></table></main>"""
        val result = parseCitelProgrammingTestResult(html, accepted.copy(totalTests = null, passedTests = null, failedTests = null, testCases = emptyList()))
        assertEquals(6, result.totalTests)
        assertEquals(6, result.passedTests)
        assertEquals(0, result.failedTests)
        assertEquals((1..6).toList(), result.testCases.map { it.number })
        assertTrue(result.testCases.all { it.passed == true })
        val pending = parseCitelProgrammingTestResult("<main id='region-main'>Current Status: Running</main>",
            CitelProgrammingResult("RJ: Running", false, emptyList(), false))
        assertNull(pending.totalTests)
        assertTrue(pending.testing)
    }

    @Test fun pendingToAcUpdatesCachedTaskAndBlocksPostAndPollingAfterDismiss() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        var result = CitelProgrammingResult("RJ: Running", false, emptyList(), false, 1)
        var reads = 0
        var posts = 0
        var fail = false
        val vault = object : CredentialVault {
            var value: Credentials? = null
            override suspend fun load() = value
            override suspend fun save(credentials: Credentials) { value = credentials }
            override suspend fun clear() { value = null }
        }
        val remote = object : CitelDataSource {
            override suspend fun fetch(credentials: Credentials) = listOf(task)
            override suspend fun programmingStatus(credentials: Credentials, task: CitelTask): CitelProgrammingResult {
                reads++
                if (fail) error("offline")
                return result
            }
            override suspend fun programmingOptions(credentials: Credentials, task: CitelTask): CitelProgrammingOptions = error("AC/RJ must not request an upload form")
            override suspend fun submitProgramming(credentials: Credentials, task: CitelTask, file: HomeworkFileContent, language: String): CitelProgrammingResult {
                posts++
                return result
            }
        }
        try {
            val model = CitelModel("fixture", cache, vault, remote)
            model.saveAccount("fixture", "fixture", true)
            model.setEnabled(true)
            model.refresh()
            model.selectTask(task)
            assertTrue(model.state.value.programmingResult!!.testing)
            assertFalse(model.state.value.selectedTask!!.submitted)
            fail = true
            assertFalse(model.refreshProgrammingResult(task.id))
            assertTrue(model.state.value.programmingPollingPaused)
            assertTrue(model.state.value.submissionMessage!!.contains("已暂停"))
            fail = false
            result = accepted
            assertTrue(model.refreshProgrammingResult(task.id))
            assertFalse(model.state.value.programmingPollingPaused)
            assertTrue(model.state.value.selectedTask!!.submitted)
            assertTrue(model.state.value.tasks.single().submitted)
            assertTrue(cache.metadata("fixture", "citel.tasks")!!.contains("AC: Accepted"))
            assertEquals(6, model.state.value.programmingResult!!.passedTests)
            model.submitProgramming(listOf(HomeworkFileContent("main.cpp", "text/plain", byteArrayOf(1))), "1")
            assertEquals(0, posts)
            model.dismissTask()
            val before = reads
            assertFalse(model.refreshProgrammingResult(task.id))
            assertEquals(before, reads)
        } finally { cache.close() }
    }

    @Test fun renderProductionJudgeStates() = SwingUtilities.invokeAndWait {
        listOf(false, true).forEach { dark ->
            val scene = ImageComposeScene(1200, 920, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(useDarkTheme = dark, dynamicColorEnabled = false) {
                    Surface(Modifier.fillMaxSize()) {
                        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            listOf(accepted, CitelProgrammingResult("RJ: Running", false, emptyList(), false),
                                accepted.copy(status = "WA: Wrong Answer", accepted = false, passedTests = 3, failedTests = 3,
                                    testCases = accepted.testCases.map { if (it.number <= 3) it else it.copy(passed = false, result = "WA: Wrong Answer") })).forEach { result ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("编程作业 · ${result.displayStatus}", style = MaterialTheme.typography.titleLarge)
                                    ProgrammingJudgeResult(result, autoRefreshing = result.testing)
                                }
                            }
                        }
                    }
                }
            }
            try {
                repeat(5) { scene.render(1_000_000_000L + it * 100_000_000L).close() }
                val image = scene.render(2_000_000_000L)
                try {
                    val data = image.encodeToData(EncodedImageFormat.PNG)!!
                    try { File("build/reports/citel-ui/programming-judge-${if (dark) "dark" else "light"}.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) } }
                    finally { data.close() }
                } finally { image.close() }
            } finally { scene.close() }
        }
    }
}

package team.bjtuss.bjtuselfservice.shared.feature.citel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.AcademicWeekSlot
import team.bjtuss.bjtuselfservice.shared.domain.home.buildHomeAgenda
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeAgendaCalendarContent
import team.bjtuss.bjtuselfservice.shared.feature.settings.*
import team.bjtuss.bjtuselfservice.shared.feature.shell.*

class HomeAccountVisualTest {
    @Test fun renderHomeAndBothAccountFormsInOneImage() = SwingUtilities.invokeAndWait {
        val date = LocalDate(2026, 9, 28)
        val task = CitelTask(1, 1, "课程", "报告", "", false,
            openTime = LocalDateTime(2026, 9, 28, 0, 0).toInstant(citelTimeZone).epochSeconds,
            dueTime = LocalDateTime(2026, 10, 1, 23, 59).toInstant(citelTimeZone).epochSeconds)
        val events = citelAgendaEvents(listOf(task, task.copy(id = 2, submitted = true)))
        val agenda = buildHomeAgenda(emptyList(), emptyList(), date, LocalDateTime(2026, 9, 28, 10, 0), citelTimeZone, events)
        val scene = ImageComposeScene(width = 1200, height = 720, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(useDarkTheme = false, dynamicColorEnabled = false) {
                Surface(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            CompactAppTopBar("首页", false, titleAction = { TopBarCalendarAction("今", {}) }, idleStatusText = "已同步", onRefresh = {})
                            HomeAgendaCalendarContent(AcademicWeekSlot(null, date), agenda, date, emptyList(), emptyList(), events,
                                false, false, false, null, null, {}, date, {}, Modifier.fillMaxWidth().padding(16.dp))
                            Text("保存时的同步弹窗", Modifier.padding(top = 32.dp))
                            Card { AccountSyncDialogContent() }
                        }
                        AccountPanel("CITEL 账号设置", Modifier.weight(1f))
                        AccountPanel("物理实验账号设置", Modifier.weight(1f))
                    }
                }
            }
        }
        try {
            repeat(5) { scene.render(1_000_000_000L + it * 100_000_000L).close() }
            val image = scene.render(2_000_000_000L)
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)!!
                File("build/reports/citel-ui/home-accounts.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) }
                data.close()
            } finally { image.close() }
        } finally { scene.close() }
    }

    @Test fun reopeningCachedCitelPageNeverFetchesAutomatically() = SwingUtilities.invokeAndWait {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        var calls = 0
        try {
            cache.putMetadata("fixture", "citel.enabled", "true")
            cache.putMetadata("fixture", "citel.lastSync", "1")
            cache.putMetadata("fixture", "citel.tasks", Json.encodeToString(listOf(CitelTask(1, 1, "课程", "作业", "", false))))
            val model = CitelModel("fixture", cache, null, object : CitelDataSource {
                override suspend fun fetch(credentials: Credentials): List<CitelTask> { calls++; return emptyList() }
            })
            runBlocking { model.initialize() }
            repeat(2) {
                val scene = ImageComposeScene(390, 844, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    PlatformAppTheme(useDarkTheme = false, dynamicColorEnabled = false) { CitelWorkspace(model, false, {}) }
                }
                try { repeat(5) { scene.render(1_000_000_000L + it * 100_000_000L).close() } }
                finally { scene.close() }
            }
            assertEquals(0, calls)
            assertFalse(model.state.value.failed)
            assertNull(model.state.value.message)
            assertTrue(model.state.value.fromCache)
        } finally { cache.close() }
    }
}

@Composable
private fun AccountPanel(title: String, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        IndependentAccountSettings("测试账号", "", true, true, false, {}, {}, {}, null,
            websiteUrl = if (title.startsWith("CITEL")) "$CITEL_BASE/" else "http://wlsy.bjtu.edu.cn/")
    }
}

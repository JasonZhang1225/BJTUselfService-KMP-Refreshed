package team.bjtuss.bjtuselfservice.shared.feature.home

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.data.home.*
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeAgenda
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeStatus
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*

class HomeAssignmentRemindersUiTest {
    private val f = HomeReminderFixture()
    private val original = f.agenda()
    private val output = File(System.getenv("BJTU_HOME_REMINDER_TEST_OUTPUT")
        ?: "../../.artifacts/home-assignment-reminders/ui")

    @Test fun accountSwitchFirstFrameAndSubsequentEditsStayIsolatedWithRealSqlite() = SwingUtilities.invokeAndWait {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        val first = AssignmentCompletionStore(cache, "home-fixture-a")
        val second = AssignmentCompletionStore(cache, "home-fixture-b")
        runBlocking { f.keys.forEach { first.setCompleted(it, true) } }
        val current = mutableStateOf(first)
        val frames = mutableListOf<Pair<AssignmentCompletionStore, HomeAgenda>>()
        val scene = ImageComposeScene(300, 100, coroutineContext = Dispatchers.Unconfined) {
            val store = current.value
            CompositionLocalProvider(LocalAssignmentCompletionStore provides store) {
                val reminders = rememberHomeAssignmentReminders(original, f.assignments)
                SideEffect { frames += store to reminders }
            }
        }
        try {
            pump(scene)
            assertEquals(0, frames.last().second.reminderCount())
            current.value = second
            pump(scene)
            assertEquals(3, frames.first { it.first === second }.second.reminderCount(), "B 首帧不继承 A 的隐藏状态")
            runBlocking { second.setCompleted(f.citel.completionKey(), true) }
            pump(scene)
            assertEquals(2, frames.last().second.reminderCount())
            val returnIndex = frames.size
            current.value = first
            pump(scene)
            assertTrue(frames.drop(returnIndex).all { it.first === first && it.second.reminderCount() == 0 })
            runBlocking { first.setCompleted(f.physical.completionKey(), false) }
            pump(scene)
            assertEquals(listOf(f.physicalDeadline), frames.last().second.dueSoonEvents)
            assertEquals(setOf(f.citel.completionKey()), second.state.value.completedKeys)
            assertEquals(f.keys - f.physical.completionKey(), AssignmentCompletionStore(cache, "home-fixture-a").state.value.completedKeys)
            assertEquals(setOf(f.citel.completionKey()), AssignmentCompletionStore(cache, "home-fixture-b").state.value.completedKeys)
            val failure = AssignmentCompletionStore(object : AssignmentCompletionPersistence {
                override fun load(): Set<AssignmentCompletionKey> = error("fixture read failure")
                override fun save(keys: Set<AssignmentCompletionKey>) = error("unexpected write")
            })
            current.value = failure
            pump(scene)
            assertEquals(3, frames.first { it.first === failure }.second.reminderCount(), "读失败不能继承上个账号的标记")
            assertTrue(frames.all { it.second.days === original.days })
        } finally { scene.close(); cache.close() }
    }

    @Test fun realCardIgnoreMenuUpdatesReminderBindingAndUndoAcrossAllThreeSources() = SwingUtilities.invokeAndWait {
        for (item in f.assignments) {
            val store = AssignmentCompletionStore(MemoryPersistence())
            var displayed = original
            var opened = 0
            val scene = ImageComposeScene(480, 340, coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(false, false) {
                    CompositionLocalProvider(LocalAssignmentCompletionStore provides store) {
                        val reminders = rememberHomeAssignmentReminders(original, f.assignments)
                        SideEffect { displayed = reminders }
                        Surface(Modifier.fillMaxSize()) {
                            Column {
                                AggregatedAssignmentCard(item, f.seconds) { opened++ }
                                Text("首页提醒数量：${reminders.reminderCount()}")
                            }
                        }
                    }
                }
            }
            try {
                pump(scene)
                assertEquals(3, displayed.reminderCount())
                ignoreViaMenu(scene)
                await(scene) { displayed.reminderCount() == 2 }
                assertEquals(setOf(item.completionKey()), store.state.value.completedKeys)
                assertEquals(0, opened)
                assertSame(original.days, displayed.days)
                ignoreViaMenu(scene)
                await(scene) { displayed.reminderCount() == 3 }
                assertTrue(store.state.value.completedKeys.isEmpty())
                assertEquals(original, displayed)
            } finally { scene.close() }
        }
    }

    @Test fun actualHomeWorkspaceRendersPendingCompletedRestoredLoadingAndErrorStates() = SwingUtilities.invokeAndWait {
        for (dark in listOf(false, true)) {
            for (mode in listOf("pending", "completed", "restored", "large-text", "wide", "loading", "error")) {
                val store = AssignmentCompletionStore(MemoryPersistence())
                if (mode in listOf("completed", "restored")) runBlocking {
                    f.keys.forEach { store.setCompleted(it, true) }
                    if (mode == "restored") f.keys.forEach { store.setCompleted(it, false) }
                }
                val model = HomeScreenModel(object : HomeStatusRepository {
                    override fun load(): HomeStatus? = null
                    override suspend fun refresh(): HomeStatusRefreshResult =
                        if (mode == "error") HomeStatusRefreshResult.Failure(null, HomeStatusFailure.NETWORK)
                        else HomeStatusRefreshResult.Success(HomeStatus("0", "100", "100"))
                })
                runBlocking { model.refresh() }
                val density = Density(1f, if (mode == "large-text") 1.6f else 1f)
                val width = if (mode == "wide") 1000 else 440
                val scene = ImageComposeScene(width, if (mode == "large-text") 1600 else 1100,
                    density = density, coroutineContext = Dispatchers.Unconfined) {
                    PlatformAppTheme(dark, false) {
                        CompositionLocalProvider(LocalAssignmentCompletionStore provides store, LocalDensity provides density) {
                            Surface(Modifier.fillMaxSize()) {
                                HomeWorkspace(model, PlatformInfo(PlatformFamily.MacOS, "fixture", isWindows = true),
                                    expanded = mode == "wide", homework = listOf(f.homework), exams = emptyList(),
                                    phyVlabEvents = f.events, currentWeek = 1, now = f.now, timeZone = f.zone,
                                    isAgendaLoading = mode == "loading", onOpenMailbox = {}, onOpenHomework = {},
                                    onOpenExams = {}, changes = emptyList(), onClearAllChanges = {},
                                    onClearChangeDomain = {}, onOpenChangeDomain = {}, holdNetwork = true,
                                    reminderAssignments = f.assignments)
                            }
                        }
                    }
                }
                try {
                    pump(scene)
                    capture(scene, "$mode-${if (dark) "dark" else "light"}")
                } finally { scene.close() }
            }
        }
    }

    private fun HomeAgenda.reminderCount() = dueSoonHomework.size + dueSoonEvents.size
    private fun pump(scene: ImageComposeScene) { repeat(5) { scene.render(System.nanoTime()).close(); Thread.sleep(10) } }
    /** 右键卡片，再点菜单里的第一项（忽略这项作业 / 取消忽略）。 */
    private fun ignoreViaMenu(scene: ImageComposeScene) {
        // 先等上一个菜单的退出动画走完，否则右键只会被弹层当作“点外部关闭”；
        // 再等新菜单的进入动画走完才点菜单项。按真实时间出帧，慢机器上同样成立。
        settle(scene)
        val at = Offset(120f, 72f)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse,
            buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse,
            buttons = PointerButtons(), button = PointerButton.Secondary)
        settle(scene)
        click(scene, 170f, 104f)
    }

    private fun settle(scene: ImageComposeScene) { repeat(6) { Thread.sleep(100); pump(scene) } }

    private fun click(scene: ImageComposeScene, x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
    }
    private fun await(scene: ImageComposeScene, ready: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!ready() && System.nanoTime() < until) pump(scene)
        pump(scene)
        assertTrue(ready(), "等待首页提醒更新超时")
    }
    private fun capture(scene: ImageComposeScene, name: String) {
        val image = scene.render(System.nanoTime())
        try {
            val data = image.encodeToData(EncodedImageFormat.PNG)!!
            try { output.mkdirs(); File(output, "$name.png").writeBytes(data.bytes) }
            finally { data.close() }
        } finally { image.close() }
    }
    private class MemoryPersistence : AssignmentCompletionPersistence {
        private var keys = emptySet<AssignmentCompletionKey>()
        override fun load() = keys
        override fun save(keys: Set<AssignmentCompletionKey>) { this.keys = keys }
    }
}

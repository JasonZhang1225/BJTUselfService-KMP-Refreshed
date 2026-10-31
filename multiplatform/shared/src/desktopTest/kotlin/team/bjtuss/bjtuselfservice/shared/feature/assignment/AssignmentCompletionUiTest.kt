package team.bjtuss.bjtuselfservice.shared.feature.assignment

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
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
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.files.UnavailableHomeworkFileGateway

class AssignmentCompletionUiTest {
    private val items = aggregateAssignments(listOf(completionFixtureHomework()),
        listOf(completionFixturePhysical()), listOf(completionFixtureCitel()))
    private val output = File("../../.artifacts/assignment-ignore/ui")

    @Test fun firstCompositionAfterAccountSwitchNeverReusesPreviousMarks() = SwingUtilities.invokeAndWait {
        val key = items.first().completionKey()!!
        val first = AssignmentCompletionStore(MemoryPersistence(setOf(key)))
        val second = AssignmentCompletionStore(MemoryPersistence())
        val current = mutableStateOf(first)
        val observations = mutableListOf<Pair<AssignmentCompletionStore, Set<AssignmentCompletionKey>>>()
        val scene = ImageComposeScene(200, 100, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            val store = current.value
            CompositionLocalProvider(LocalAssignmentCompletionStore provides store) {
                val state = assignmentCompletionState()
                SideEffect { observations += store to state.completedKeys }
            }
        }
        try {
            val pump = frames(scene)
            pump()
            assertTrue(observations.any { it.first === first && key in it.second })
            current.value = second
            pump()
            val secondFrames = observations.filter { it.first === second }
            assertTrue(secondFrames.isNotEmpty())
            assertTrue(secondFrames.all { it.second.isEmpty() }, "切换账号的首帧也不能沿用上个账号的标记")
            current.value = first
            pump()
            assertEquals(setOf(key), observations.last().second)
        } finally { scene.close() }
    }

    @Test fun everyProductionCardIgnoresFromTheContextMenuWithoutOpeningDetails() = SwingUtilities.invokeAndWait {
        items.forEach { item ->
            val persistence = MemoryPersistence()
            val store = AssignmentCompletionStore(persistence)
            var opened = 0
            val scene = ImageComposeScene(480, 320, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(false, false) {
                    CompositionLocalProvider(LocalAssignmentCompletionStore provides store) {
                        Surface(Modifier.fillMaxSize()) {
                            Column { AggregatedAssignmentCard(item, 100) { opened++ } }
                        }
                    }
                }
            }
            try {
                val pump = frames(scene)
                pump()
                capture(scene, "card-${item.source.name.lowercase()}")
                rightClick(scene, 120f, 72f)
                settle(pump)
                capture(scene, "menu-${item.source.name.lowercase()}")
                click(scene, MENU_X, MENU_Y)
                awaitFrame(pump) { item.completionKey() in store.state.value.completedKeys }
                assertEquals(0, opened, "右键菜单不能打开详情：${item.source}")
                assertEquals(setOf(item.completionKey()), persistence.saved)
                assertEquals(item.completionKey(), store.undo.value?.key, "列表里忽略后提供撤销")
                capture(scene, "ignored-${item.source.name.lowercase()}")
                // 先等上一个菜单的退出动画结束，否则这次右键只会被弹层当作“点外部关闭”。
                settle(pump)
                rightClick(scene, 120f, 72f)
                settle(pump)
                click(scene, MENU_X, MENU_Y)
                awaitFrame(pump) { store.state.value.completedKeys.isEmpty() }
                assertEquals(0, opened)
                settle(pump)
                click(scene, 120f, 150f)
                pump()
                assertEquals(1, opened, "卡片正文仍打开详情：${item.source}")
            } finally { scene.close() }
        }
    }

    @Test fun renderNormalAllIgnoredReadFailureAndLargeTextInBothThemes() = SwingUtilities.invokeAndWait {
        listOf(false, true).forEach { dark ->
            listOf("normal", "all-ignored", "read-error", "large-text").forEach { mode ->
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                CacheDatabaseSql.Schema.create(driver)
                val cache = CacheStore(driver)
                val marks = items.mapNotNull { it.completionKey() }.let { if (mode == "all-ignored") it else it.take(1) }.toSet()
                val store = AssignmentCompletionStore(MemoryPersistence(marks, failRead = mode == "read-error"))
                val filterStore = AggregateAssignmentFilterStore(cache, "fixture")
                val density = Density(1f, if (mode == "large-text") 1.6f else 1f)
                val scene = ImageComposeScene(440, if (mode == "large-text") 1600 else 1100,
                    density = density, coroutineContext = Dispatchers.Unconfined) {
                    PlatformAppTheme(dark, false) {
                        CompositionLocalProvider(LocalAssignmentCompletionStore provides store, LocalDensity provides density) {
                            Surface(Modifier.fillMaxSize()) {
                                AggregatedAssignmentWorkspace(items, emptyList(), filterStore, {}, {}, {}, false, {},
                                    Modifier.fillMaxSize(), fileGateway = UnavailableHomeworkFileGateway,
                                    markdown = { "" }, detailContent = {})
                            }
                        }
                    }
                }
                try {
                    val pump = frames(scene)
                    pump()
                    val suffix = if (dark) "dark" else "light"
                    capture(scene, "$mode-$suffix")
                    if (mode == "normal") {
                        // 待处理 2 项之后是“已忽略 1 项”分组，点开后已忽略的卡片出现在它下方。
                        click(scene, 80f, IGNORED_HEADER_Y)
                        settle(pump)
                        capture(scene, "$mode-expanded-$suffix")
                    }
                } finally { scene.close(); cache.close() }
            }
        }
    }

    @Test fun serverSubmittedCardsCannotBeIgnoredEvenWithSavedMarks() = SwingUtilities.invokeAndWait {
        val submitted = aggregateAssignments(
            listOf(completionFixtureHomework().copy(idSnId = 99)),
            listOf(completionFixturePhysical().copy(completed = true)),
            listOf(completionFixtureCitel().copy(submitted = true),
                completionFixtureCitel().copy(id = 21, programming = true, submitted = true, status = "AC: Accepted")))
        submitted.forEachIndexed { index, item ->
            assertTrue(item.submitted)
            val saved = setOf(item.completionKey()!!)
            val persistence = MemoryPersistence(saved)
            val store = AssignmentCompletionStore(persistence)
            var opened = 0
            val scene = ImageComposeScene(480, 320, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(false, false) {
                    CompositionLocalProvider(LocalAssignmentCompletionStore provides store) {
                        Surface(Modifier.fillMaxSize()) {
                            Column { AggregatedAssignmentCard(item, 100) { opened++ } }
                        }
                    }
                }
            }
            try {
                val pump = frames(scene)
                pump()
                capture(scene, "submitted-$index-${item.source.name.lowercase()}")
                // 学校已提交的卡片没有菜单；原菜单项位置的点击不能改动已保存的标记。
                rightClick(scene, 120f, 72f)
                settle(pump)
                click(scene, MENU_X, MENU_Y)
                settle(pump)
                assertEquals(saved, store.state.value.completedKeys)
                assertEquals(saved, persistence.saved)
                assertNull(store.undo.value)
            } finally { scene.close() }
        }
    }

    @Test fun undoBarRestoresTheIgnoredItemAndHides() = SwingUtilities.invokeAndWait {
        val key = items.first().completionKey()!!
        val persistence = MemoryPersistence()
        val store = AssignmentCompletionStore(persistence)
        kotlinx.coroutines.runBlocking { store.requestSetCompleted(key, true, undoTitle = "课后阅读与思考")!!.join() }
        val scene = ImageComposeScene(480, 120, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(false, false) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        AssignmentIgnoreUndoBar(store, Modifier.align(androidx.compose.ui.Alignment.Center))
                    }
                }
            }
        }
        try {
            val pump = frames(scene)
            // 等进入动画走完再点，避免点在仍在上滑的提示边缘。
            settle(pump)
            capture(scene, "undo-bar")
            assertEquals(setOf(key), persistence.saved)
            click(scene, UNDO_X, 60f)
            awaitFrame(pump) { store.state.value.completedKeys.isEmpty() }
            assertTrue(persistence.saved.isEmpty(), "撤销后本机不再保存忽略标记")
            assertNull(store.undo.value)
        } finally { scene.close() }
    }

    private fun click(scene: ImageComposeScene, x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
    }

    private fun rightClick(scene: ImageComposeScene, x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Mouse,
            buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Mouse,
            buttons = PointerButtons(), button = PointerButton.Secondary)
    }

    /** 按真实时间连续出帧约 0.6 秒，让弹出菜单、撤销提示的进出动画走完；CI 机器慢时不靠单次出帧碰运气。 */
    private fun settle(pump: () -> Unit) {
        repeat(6) { Thread.sleep(100); pump() }
    }

    private fun frames(scene: ImageComposeScene): () -> Unit {
        return { repeat(5) { scene.render(System.nanoTime()).close(); Thread.sleep(10) } }
    }

    private fun awaitFrame(pump: () -> Unit, ready: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!ready() && System.nanoTime() < until) { pump(); Thread.sleep(5) }
        pump()
        assertTrue(ready(), "等待本地完成状态更新超时")
    }

    private fun capture(scene: ImageComposeScene, name: String) {
        val image = scene.render(System.nanoTime())
        try {
            val data = image.encodeToData(EncodedImageFormat.PNG)!!
            try { output.mkdirs(); File(output, "$name.png").writeBytes(data.bytes) }
            finally { data.close() }
        } finally { image.close() }
    }

    private companion object {
        // 菜单从右键位置 (120, 72) 向下展开，第一项在其下方约一行处。
        const val MENU_X = 170f
        const val MENU_Y = 104f
        const val IGNORED_HEADER_Y = 503f
        const val UNDO_X = 323f
    }

    private class MemoryPersistence(initial: Set<AssignmentCompletionKey> = emptySet(), val failRead: Boolean = false) : AssignmentCompletionPersistence {
        var saved = initial
        override fun load(): Set<AssignmentCompletionKey> { check(!failRead); return saved }
        override fun save(keys: Set<AssignmentCompletionKey>) { saved = keys }
    }
}

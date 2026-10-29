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
import androidx.compose.ui.input.pointer.PointerEventType
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
    private val output = File("../../.artifacts/assignment-completion-final/ui")

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

    @Test fun everyProductionCardCheckboxCanMarkAndUndoWithoutOpeningDetails() = SwingUtilities.invokeAndWait {
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
                click(scene, 440f, 72f)
                awaitFrame(pump) { item.completionKey() in store.state.value.completedKeys }
                assertEquals(0, opened, "复选框不能打开详情：${item.source}")
                assertEquals(setOf(item.completionKey()), persistence.saved)
                click(scene, 440f, 72f)
                awaitFrame(pump) { store.state.value.completedKeys.isEmpty() }
                assertEquals(0, opened)
                click(scene, 120f, 150f)
                pump()
                assertEquals(1, opened, "卡片正文仍打开详情：${item.source}")
            } finally { scene.close() }
        }
    }

    @Test fun renderNormalEmptyReadFailureAndLargeTextInBothThemes() = SwingUtilities.invokeAndWait {
        listOf(false, true).forEach { dark ->
            listOf("normal", "empty", "read-error", "large-text").forEach { mode ->
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                CacheDatabaseSql.Schema.create(driver)
                val cache = CacheStore(driver)
                val marks = items.mapNotNull { it.completionKey() }.toSet()
                val store = AssignmentCompletionStore(MemoryPersistence(marks, failRead = mode == "read-error"))
                val filterStore = AggregateAssignmentFilterStore(cache, "fixture")
                filterStore.save(AggregateAssignmentFilters(hideExpired = mode == "empty", hideSubmitted = true))
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
                    frames(scene)()
                    capture(scene, "$mode-${if (dark) "dark" else "light"}")
                } finally { scene.close(); cache.close() }
            }
        }
    }

    @Test fun serverSubmittedCardsHaveNoCompletionCheckboxEvenWithSavedManualMarks() = SwingUtilities.invokeAndWait {
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
                // 原复选框位置应交由卡片处理，不能再切换已保存的手动标记。
                click(scene, 440f, 72f)
                pump()
                assertEquals(1, opened, "学校已提交的作业不应出现复选框：${item.source}")
                assertEquals(saved, store.state.value.completedKeys)
                assertEquals(saved, persistence.saved)
            } finally { scene.close() }
        }
    }

    private fun click(scene: ImageComposeScene, x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
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

    private class MemoryPersistence(initial: Set<AssignmentCompletionKey> = emptySet(), val failRead: Boolean = false) : AssignmentCompletionPersistence {
        var saved = initial
        override fun load(): Set<AssignmentCompletionKey> { check(!failRead); return saved }
        override fun save(keys: Set<AssignmentCompletionKey>) { saved = keys }
    }
}

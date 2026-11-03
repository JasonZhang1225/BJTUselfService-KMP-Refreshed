package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.LocalReduceMotion
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.feature.shell.*
import team.bjtuss.bjtuselfservice.shared.files.UnavailableHomeworkFileGateway

class AssignmentScrollToTopTest {
    @Test fun thresholdTracksViewportAndNativeScrollingAndClickReachesTheFirstItem() = SwingUtilities.invokeAndWait {
        val height = mutableStateOf(300.dp)
        var state: LazyListState? = null
        var controller: NativeScrollController? = null
        val scene = ImageComposeScene(390, 800, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(false, false) {
                CompositionLocalProvider(
                    LocalReduceMotion provides true,
                    LocalNativeScrollControllerReporter provides { controller = it },
                ) {
                    val list = rememberLazyListState().also { state = it }
                    AssignmentListWithScrollToTop(list, Modifier.height(height.value)) { modifier, _ ->
                        TopScrollLazyColumn(list, modifier) { items(40) { Box(Modifier.height(100.dp)) } }
                    }
                }
            }
        }
        try {
            val frames = Frames(scene)
            frames.settle()
            assertNull(button(scene), "顶部不显示按钮")
            controller!!.consumeScroll(299f)
            frames.settle()
            assertNull(button(scene), "一屏以内不显示")
            controller!!.consumeScroll(2f)
            frames.settle()
            assertNotNull(button(scene), "原生滚动超过一屏后显示")
            controller!!.consumeScroll(-2f)
            frames.settle()
            assertNull(button(scene), "手动回到一屏内隐藏")
            runBlocking { state!!.scrollToItem(6) }
            frames.settle()
            assertNotNull(button(scene), "程序跳转也更新显示状态")
            height.value = 800.dp
            frames.settle()
            assertNull(button(scene), "窗口变高后按新的一屏高度计算")
            height.value = 300.dp
            frames.settle()
            val target = assertNotNull(button(scene))
            scene.sendPointerEvent(PointerEventType.Press, target.boundsInRoot.center)
            scene.sendPointerEvent(PointerEventType.Release, target.boundsInRoot.center)
            frames.settle()
            assertEquals(0, state!!.firstVisibleItemIndex)
            assertEquals(0, state!!.firstVisibleItemScrollOffset)
            assertNull(button(scene))
        } finally { scene.close() }
    }

    @Test fun nativeBottomBounceMovesListButKeepsScrollToTopButtonFixed() = SwingUtilities.invokeAndWait {
        for (scale in listOf(1f, 3f)) {
            var controller: NativeScrollController? = null
            var lastItemY = 0f
            val scene = ImageComposeScene(390, 800, density = Density(scale), coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(false, false) {
                    CompositionLocalProvider(LocalNativeScrollControllerReporter provides { controller = it }) {
                        val list = rememberLazyListState()
                        AssignmentListWithScrollToTop(list, Modifier.fillMaxSize()) { modifier, _ ->
                            TopScrollLazyColumn(list, modifier) {
                                items(40) { index ->
                                    Box(Modifier.height(100.dp).onGloballyPositioned {
                                        if (index == 39) lastItemY = it.positionInRoot().y
                                    })
                                }
                            }
                        }
                    }
                }
            }
            try {
                val frames = Frames(scene)
                frames.settle()
                controller!!.consumeScroll(100_000f)
                frames.settle()
                assertFalse(controller!!.canScrollForward)
                val fixedBounds = assertNotNull(button(scene)).boundsInRoot
                val restingY = lastItemY
                for (translation in listOf(-72f, -24f, 0f)) {
                    controller!!.setOverscrollTranslation(translation)
                    frames.settle()
                    assertEquals(fixedBounds, assertNotNull(button(scene)).boundsInRoot,
                        "UIKit bounce must not move the fixed overlay, density=$scale")
                    assertEquals(restingY + translation, lastItemY, 0.1f,
                        "Only the list follows the native physical-pixel displacement")
                }
            } finally { scene.close() }
        }
    }

    @Test fun productionAggregateKeepsButtonInsideListPaneAndAboveSafeArea() = SwingUtilities.invokeAndWait {
        val assignments = aggregateAssignments((1..60).map { index ->
            completionFixtureHomework().copy(upId = index, title = "练习 $index：阅读与思考", courseName = "示例课程")
        }, emptyList(), emptyList())
        for (expanded in listOf(false, true)) for (dark in listOf(false, true)) for (scale in listOf(1f, 1.6f)) {
            val width = if (expanded) 1000 else 390
            val density = Density(1f, scale)
            var controller: NativeScrollController? = null
            val reports = mutableListOf<Float>()
            val scene = ImageComposeScene(width, 844, density = density, coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(dark, false) {
                    CompositionLocalProvider(
                        LocalDensity provides density,
                        LocalTopBarClearance provides 112.dp,
                        LocalBottomBarClearance provides 84.dp,
                        LocalNativeScrollControllerReporter provides { controller = it },
                        LocalReportTopScroll provides { reports += it },
                    ) {
                        Surface(Modifier.fillMaxSize()) {
                            AggregatedAssignmentWorkspace(assignments, emptyList(), null, {}, {}, {}, false, {},
                                modifier = Modifier.fillMaxSize(), expanded = expanded,
                                fileGateway = UnavailableHomeworkFileGateway, markdown = { "" }, detailContent = {})
                        }
                    }
                }
            }
            try {
                val frames = Frames(scene)
                frames.settle()
                assertNull(button(scene))
                if (expanded) {
                    // Expanded panes intentionally do not register as the iOS primary scroller.
                    assertNull(controller)
                    val scroller = nodes(scene).first { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) != null }
                    scroller.config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy].action!!.invoke(0f, 1800f)
                } else {
                    controller!!.consumeScroll(1800f)
                }
                frames.settle(100)
                val target = assertNotNull(button(scene), "expanded=$expanded dark=$dark scale=$scale")
                val bounds = target.boundsInRoot
                assertTrue(bounds.width >= 48f && bounds.height >= 48f, "点击区域至少 48.dp")
                assertTrue(bounds.bottom <= 844f - 84f - 12f + 1f, "避开底部导航及安全区")
                assertTrue(bounds.right <= (if (expanded) width * 0.4f else width.toFloat()) - 8f,
                    "宽屏按钮留在左侧作业列表内")
                assertTrue(reports.any { it > 0f }, "保留统一容器的顶栏滚动上报")
                frames.capture("${if (expanded) "wide" else "phone"}-${if (dark) "dark" else "light"}-s$scale")
                // Default motion: clicking the real production button must finish at the filter banner.
                scene.sendPointerEvent(PointerEventType.Press, bounds.center)
                scene.sendPointerEvent(PointerEventType.Release, bounds.center)
                frames.settle(160)
                assertNull(button(scene))
                assertTrue(nodes(scene).any { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { "未来 48 小时" in it.text } == true
                }, "点击后回到顶部筛选摘要")
            } finally { scene.close() }
        }
    }

    @Test fun shortListNeverShowsButtonAndUndoMovesItClearOfTheCapsule() = SwingUtilities.invokeAndWait {
        val count = mutableStateOf(1)
        val store = AssignmentCompletionStore(object : AssignmentCompletionPersistence {
            override fun load() = emptySet<AssignmentCompletionKey>()
            override fun save(keys: Set<AssignmentCompletionKey>) = Unit
        })
        var state: LazyListState? = null
        val scene = ImageComposeScene(320, 568, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(false, false) {
                CompositionLocalProvider(LocalAssignmentCompletionStore provides store, LocalBottomBarClearance provides 84.dp) {
                    val list = rememberLazyListState().also { state = it }
                    AssignmentListWithScrollToTop(list, Modifier.fillMaxSize()) { modifier, _ ->
                        TopScrollLazyColumn(list, modifier) { items(count.value) { Box(Modifier.height(100.dp)) } }
                    }
                }
            }
        }
        try {
            val frames = Frames(scene)
            frames.settle()
            assertNull(button(scene))
            count.value = 40
            frames.settle()
            runBlocking { state!!.scrollToItem(10) }
            frames.settle()
            val normal = assertNotNull(button(scene)).boundsInRoot
            runBlocking { store.requestSetCompleted(completionFixtureHomework().completionKey(), true, "示例作业")!!.join() }
            frames.settle()
            assertTrue(assertNotNull(button(scene)).boundsInRoot.bottom <= normal.top - 16f,
                "撤销提示出现时，回顶按钮位于提示上方")
        } finally { scene.close() }
    }

    @Test fun undoLeavesLastProductionCardClearWithoutShrinkingTheViewport() = SwingUtilities.invokeAndWait {
        for (scale in listOf(1f, 1.6f)) {
            val store = AssignmentCompletionStore(object : AssignmentCompletionPersistence {
                override fun load() = emptySet<AssignmentCompletionKey>()
                override fun save(keys: Set<AssignmentCompletionKey>) = Unit
            })
            val assignments = aggregateAssignments((1..40).map {
                completionFixtureHomework().copy(upId = it, title = "尾项回归 $it")
            }, emptyList(), emptyList())
            var controller: NativeScrollController? = null
            val density = Density(1f, scale)
            val scene = ImageComposeScene(390, 568, density = density, coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(false, false) {
                    CompositionLocalProvider(LocalDensity provides density,
                        LocalAssignmentCompletionStore provides store, LocalBottomBarClearance provides 84.dp,
                        LocalNativeScrollControllerReporter provides { controller = it }) {
                        Surface(Modifier.fillMaxSize()) {
                            AggregatedAssignmentWorkspace(assignments, emptyList(), null, {}, {}, {}, false, {},
                                fileGateway = UnavailableHomeworkFileGateway, markdown = { "" }, detailContent = {})
                        }
                    }
                }
            }
            try {
                val frames = Frames(scene)
                frames.settle()
                fun viewport() = nodes(scene).first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }.boundsInRoot
                val originalViewport = viewport()
                runBlocking { store.requestSetCompleted(AssignmentCompletionKey(AssignmentSource.COURSE_PLATFORM, 10, 999),
                    true, "示例作业")!!.join() }
                frames.settle()
                assertEquals(originalViewport, viewport(), "撤销提示不能缩小或移动列表视口")
                controller!!.consumeScroll(100_000f)
                frames.settle(60)
                fun hasLastTitle(node: SemanticsNode): Boolean =
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "尾项回归 40" } == true ||
                        node.children.any(::hasLastTitle)
                val lastCard = nodes(scene).first {
                    it.config.getOrNull(SemanticsActions.OnClick) != null && hasLastTitle(it)
                }
                assertFalse(controller!!.canScrollForward)
                assertTrue(lastCard.boundsInRoot.bottom <= assertNotNull(button(scene)).boundsInRoot.top,
                    "滚动到底后，末项卡片必须完整位于上移的回顶按钮之上：scale=$scale")
                frames.capture("undo-last-card-s$scale")
            } finally { scene.close() }
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
        return scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
    }

    private fun button(scene: ImageComposeScene) = nodes(scene).firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("回到顶部") == true
    }

    private class Frames(val scene: ImageComposeScene) {
        private var frame = 0L
        fun settle(count: Int = 24) = repeat(count) { scene.render(++frame * 16_000_000L).close() }
        fun capture(name: String) {
            val directory = System.getProperty("assignmentScrollTop.artifactDir") ?: return
            val file = File(directory, "aggregate-$name.png")
            file.parentFile.mkdirs()
            scene.render(++frame * 16_000_000L).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) }
            }
        }
    }
}

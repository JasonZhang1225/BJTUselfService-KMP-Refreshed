package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopScrollContainersTest {
    @Test fun shortListAndColumnReportPlacedBounceWithoutLogicalScrolling() {
        SwingUtilities.invokeAndWait {
            for (lazy in listOf(true, false)) {
                val displacement = mutableIntStateOf(0)
                val reports = mutableListOf<Float>()
                val effect = object : OverscrollEffect {
                    override val isInProgress get() = displacement.intValue != 0
                    override val node = object : Modifier.Node(), LayoutModifierNode {
                        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
                            val placeable = measurable.measure(constraints)
                            return layout(placeable.width, placeable.height) {
                                placeable.placeRelative(0, displacement.intValue)
                            }
                        }
                    }
                    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset) = performScroll(delta)
                    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
                        performFling(velocity)
                    }
                }
                val factory = object : OverscrollFactory {
                    override fun createOverscrollEffect() = effect
                    override fun equals(other: Any?) = this === other
                    override fun hashCode() = System.identityHashCode(this)
                }
                var logicalOffset = -1
                val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    CompositionLocalProvider(
                        LocalOverscrollFactory provides factory,
                        LocalTopBarClearance provides 112.dp,
                        LocalReportTopScroll provides { reports += it },
                    ) {
                        if (lazy) {
                            val state = rememberLazyListState()
                            logicalOffset = state.firstVisibleItemScrollOffset
                            TopScrollLazyColumn(state, Modifier.fillMaxSize()) {
                                items(2) { Box(Modifier.height(100.dp)) }
                            }
                        } else {
                            val state = rememberScrollState()
                            logicalOffset = state.value
                            TopScrollColumn(state, Modifier.fillMaxSize()) {
                                Box(Modifier.height(200.dp))
                            }
                        }
                    }
                }
                try {
                    var frame = 0L
                    fun renderFrames() = repeat(6) { scene.render(++frame * 16_000_000L).close() }
                    renderFrames()
                    assertEquals(0, logicalOffset)
                    displacement.intValue = -120
                    renderFrames()
                    assertEquals(0, logicalOffset)
                    assertTrue(reports.any { it == 120f }, "Short ${if (lazy) "list" else "column"} must include placed bounce: $reports")
                    displacement.intValue = -30
                    renderFrames()
                    assertEquals(30f, reports.last())
                    displacement.intValue = 0
                    renderFrames()
                    assertEquals(0f, reports.last())
                } finally { scene.close() }
            }
        }
    }

    @Test fun nativeDelegateConsumesRealDistanceAndKeepsComposePhysicsOff() {
        SwingUtilities.invokeAndWait {
            for (lazy in listOf(true, false)) {
                var controller: NativeScrollController? = null
                var logicalOffset = 0
                val factory = object : OverscrollFactory {
                    override fun createOverscrollEffect(): OverscrollEffect = error("Native scrolling must not create Compose physics")
                    override fun equals(other: Any?) = this === other
                    override fun hashCode() = System.identityHashCode(this)
                }
                val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    CompositionLocalProvider(
                        LocalOverscrollFactory provides factory,
                        LocalNativeScrollControllerReporter provides { controller = it },
                    ) {
                        if (lazy) {
                            val state = rememberLazyListState()
                            logicalOffset = state.firstVisibleItemIndex * 100 + state.firstVisibleItemScrollOffset
                            TopScrollLazyColumn(state, Modifier.fillMaxSize()) {
                                items(40) { Box(Modifier.height(100.dp)) }
                            }
                        } else {
                            val state = rememberScrollState()
                            logicalOffset = state.value
                            TopScrollColumn(state, Modifier.fillMaxSize()) {
                                repeat(40) { Box(Modifier.height(100.dp)) }
                            }
                        }
                    }
                }
                try {
                    var frame = 0L
                    fun settle() = repeat(6) { scene.render(++frame * 16_000_000L).close() }
                    settle()
                    assertTrue(controller?.canScrollForward == true)
                    assertEquals(150f, controller!!.consumeScroll(150f))
                    settle()
                    assertEquals(150, logicalOffset, "Only the native consumed delta should move content")
                    val remaining = controller!!.consumeScroll(100_000f)
                    settle()
                    assertTrue(remaining in 0f..4000f, "The consumer must report the actual end, not the requested delta")
                    assertEquals(false, controller!!.canScrollForward)
                    assertEquals(-logicalOffset.toFloat(), controller!!.consumeScroll(-100_000f))
                    settle()
                    assertEquals(0, logicalOffset)
                    assertEquals(false, controller!!.canScrollBackward)
                } finally { scene.close() }
            }
        }
    }

}

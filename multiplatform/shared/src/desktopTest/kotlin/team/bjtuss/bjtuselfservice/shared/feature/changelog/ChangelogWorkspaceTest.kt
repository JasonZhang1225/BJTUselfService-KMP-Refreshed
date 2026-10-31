package team.bjtuss.bjtuselfservice.shared.feature.changelog

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.feature.shell.*
import team.bjtuss.bjtuselfservice.shared.update.BundledChangelog

class ChangelogWorkspaceTest {
    @Test fun nativeScrollingRespectsBarsAndReportsGlassProgress() = SwingUtilities.invokeAndWait {
        for ((width, dark, scale) in listOf(Triple(390, false, 1f), Triple(390, true, 1.6f), Triple(960, false, 1f))) {
            var controller: NativeScrollController? = null
            val reports = mutableListOf<Float>()
            val top = mutableStateOf(112.dp)
            val density = Density(1f, scale)
            val scene = ImageComposeScene(width, 844, density = density, coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(dark, false) {
                    CompositionLocalProvider(
                        LocalDensity provides density,
                        LocalTopBarClearance provides top.value,
                        LocalBottomBarClearance provides 84.dp,
                        LocalNativeScrollControllerReporter provides { controller = it },
                        LocalReportTopScroll provides { reports += it },
                    ) {
                        Surface(Modifier.fillMaxSize()) { ChangelogWorkspace(Modifier.fillMaxSize()) }
                    }
                }
            }
            try {
                var frame = 0L
                fun settle() = repeat(10) { scene.render(++frame * 16_000_000L).close() }
                fun texts(): List<SemanticsNode> {
                    fun collect(node: SemanticsNode): List<SemanticsNode> =
                        listOf(node) + node.children.flatMap(::collect)
                    return scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
                        .filter { it.config.getOrNull(SemanticsProperties.Text) != null }
                }
                fun node(text: String) = texts().first { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
                }
                settle()
                assertTrue(node("选择版本，查看本次更新").boundsInRoot.top >= 128f)
                assertNotNull(controller, "The whole page must participate in native scrolling")
                assertTrue(controller!!.canScrollForward)
                // A newly measured or rotated title bar must keep the picker clear.
                top.value = 140.dp
                settle()
                assertTrue(node("选择版本，查看本次更新").boundsInRoot.top >= 156f)
                assertTrue(controller!!.consumeScroll(100_000f) > 0f)
                settle()
                assertFalse(controller!!.canScrollForward)
                val lastNote = "• " + BundledChangelog.entries.first().sections.last().items.last()
                assertTrue(node(lastNote).boundsInRoot.bottom <= 844f - 84f - 16f + 1f,
                    "The final note must be fully above the bottom bar")
                assertTrue(reports.any { it > 0f }, "Scrolling must update glass-bar progress")
                controller!!.alignToStart()
                settle()
                assertTrue(node("选择版本，查看本次更新").boundsInRoot.top >= 156f)
                assertEquals(0f, reports.last())
            } finally { scene.close() }
        }
    }
}

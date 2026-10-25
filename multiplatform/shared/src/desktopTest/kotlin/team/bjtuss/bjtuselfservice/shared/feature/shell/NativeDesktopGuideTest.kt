package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeDesktopGuideTest {
    @Test
    fun timerPublishesEveryTickAndDisposalClearsNativePresentation() {
        SwingUtilities.invokeAndWait {
            val ticks = mutableListOf<Int>()
            var shown: NativeDesktopGuide? = null
            var confirmed = 0
            val presenter = object : NativeDesktopPresentation {
                override fun showLogin(value: NativeDesktopLogin?) = Unit
                override fun showGuide(value: NativeDesktopGuide?) {
                    shown = value
                    value?.let { ticks.add(it.remaining) }
                }
                override fun updateNavigation(title: String, canGoBack: Boolean, onBack: () -> Unit) = Unit
                override fun updateAction(value: NativeBarAction?) = Unit
            }
            val scene = ImageComposeScene(
                width = 640, height = 480,
                coroutineContext = Dispatchers.Unconfined,
            ) {
                CompositionLocalProvider(LocalNativeDesktopPresentation provides presenter) {
                    TimedGuideDialog("版本说明", "测试正文", saveFailed = false) { confirmed++ }
                }
            }
            try {
                repeat(3) { scene.render() }
                assertEquals(3, shown?.remaining)
                val deadline = System.nanoTime() + 6_000_000_000L
                while (shown?.remaining != 0 && System.nanoTime() < deadline) {
                    Thread.sleep(25)
                    repeat(2) { scene.render() }
                }
                assertEquals(0, shown?.remaining, "Native confirm must become enabled")
                assertEquals(listOf(3, 2, 1, 0), ticks.distinct(), "Every timer tick must reach the host")
                shown!!.onConfirm()
                assertEquals(1, confirmed)
            } finally {
                scene.close()
            }
            assertNull(shown, "Closing a composition must remove its native modal")
        }
    }
}

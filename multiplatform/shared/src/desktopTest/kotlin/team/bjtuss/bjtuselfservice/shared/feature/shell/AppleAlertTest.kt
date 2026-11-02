package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.Dispatchers
import team.bjtuss.bjtuselfservice.shared.feature.library.renewConfirmMessage
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppleAlertTest {
    @Test
    fun fallbackPutsCancelInDismissSlotAndStacksSeveralActions() {
        val keep = AppleAlertAction("保留已完成文件") {}
        val discard = AppleAlertAction("舍弃全部文件", AppleAlertRole.Destructive) {}
        val cancel = AppleAlertAction("继续下载", AppleAlertRole.Cancel) {}

        val three = appleAlertFallbackLayout(listOf(keep, discard, cancel))
        assertSame(cancel, three.cancel)
        assertEquals(listOf(keep, discard), three.primary)
        assertTrue(three.stacked)

        val two = appleAlertFallbackLayout(listOf(cancel, keep))
        assertEquals(listOf(keep), two.primary)
        assertFalse(two.stacked)

        val single = appleAlertFallbackLayout(listOf(keep))
        assertEquals(null, single.cancel)
        assertFalse(single.stacked)
    }

    @Test
    fun nativeAlertIsPresentedOnceUpdatedAndDismissedWithComposition() = onUiThread {
        val host = TestAlertHost()
        var visible by mutableStateOf(true)
        var confirmEnabled by mutableStateOf(false)
        withScene(host, content = {
            if (visible) {
                AppleAlert(
                    title = "确认续借",
                    message = "将续借 1 本图书",
                    actions = listOf(
                        AppleAlertAction("取消", AppleAlertRole.Cancel) { visible = false },
                        AppleAlertAction("续借", enabled = confirmEnabled) {},
                    ),
                    onDismissRequest = { visible = false },
                )
            }
        }) { render ->
            render()
            val alert = host.alerts.single()
            assertEquals(1, alert.presentCalls)
            assertEquals("确认续借", alert.title)
            assertFalse(alert.actions[1].enabled)

            confirmEnabled = true
            render()
            assertEquals(1, alert.presentCalls)
            assertTrue(alert.actions[1].enabled)

            // 系统点了取消：回调清掉显示状态，组合移除时只 dismiss 一次。
            alert.actions[0].onClick()
            render()
            assertEquals(1, alert.dismissCalls)
            assertEquals(1, host.alerts.size)
        }
    }

    @Test
    fun renewConfirmListsAtMostFiveTitles() {
        val message = renewConfirmMessage(List(7) { if (it == 0) "" else "书$it" })
        assertTrue(message.startsWith("将续借以下 7 本图书："))
        assertTrue("· 未命名图书" in message)
        assertTrue("· 书4" in message)
        assertFalse("· 书5" in message)
        assertTrue("等 7 本" in message)
    }

    private fun onUiThread(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun withScene(
        host: TestAlertHost,
        content: @Composable () -> Unit,
        assertions: (render: () -> Unit) -> Unit,
    ) {
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalNativeSheetPresenter provides host) { content() }
            }
        }
        var frame = 0L
        try {
            assertions { repeat(4) { scene.render(++frame * 16_000_000L).close() } }
        } finally {
            scene.close()
        }
    }

    private class TestAlertHost : NativeSheetPresenter {
        val alerts = mutableListOf<TestAlert>()
        override fun createAlert(): NativeAlertPresentation = TestAlert().also(alerts::add)

        override fun update(
            content: @Composable () -> Unit,
            onDismissRequest: () -> Unit,
            title: String?,
            confirmLabel: String?,
            confirmEnabled: Boolean,
            onConfirm: (() -> Unit)?,
            todayLabel: String?,
            todayEnabled: Boolean,
            onToday: (() -> Unit)?,
            dismissLabel: String?,
            dismissEnabled: Boolean,
            showDismissButton: Boolean,
        ) = Unit

        override fun present(needsFullHeight: Boolean) = Unit
        override fun dismiss() = Unit
    }

    private class TestAlert : NativeAlertPresentation {
        var title = ""
        var actions: List<AppleAlertAction> = emptyList()
        var presentCalls = 0
        var dismissCalls = 0

        override fun update(title: String, message: String?, actions: List<AppleAlertAction>) {
            this.title = title
            this.actions = actions
        }

        override fun present() {
            presentCalls++
        }

        override fun dismiss() {
            dismissCalls++
        }
    }
}

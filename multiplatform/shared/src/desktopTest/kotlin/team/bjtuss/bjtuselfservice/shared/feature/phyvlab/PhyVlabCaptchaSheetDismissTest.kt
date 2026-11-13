package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.Dispatchers
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalDismissNativeSheet
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeSheetPresenter
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeSheetPresenter
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhyVlabCaptchaSheetDismissTest {
    @Test
    fun switchingFromSyncSheetToCaptchaSheetQueuesBehindActiveDismiss() = onUiThread {
        val host = CoordinatedTestHost()
        var showSync by mutableStateOf(true)
        var showCaptcha by mutableStateOf(false)
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalNativeSheetPresenter provides host) {
                    if (showSync) {
                        AppleSheet(title = "同步失败", onDismissRequest = { showSync = false }) {
                            Button(onClick = {
                                showCaptcha = true
                                showSync = false
                            }) { Text("输入验证码") }
                        }
                    }
                    if (showCaptcha) {
                        AppleSheet(title = "物理在线验证码", onDismissRequest = { showCaptcha = false }) {
                            Text("验证码正文")
                        }
                    }
                }
            }
        }
        var frame = 0L
        fun render() {
            repeat(4) { scene.render(++frame * 16_000_000L).close() }
        }
        try {
            render()
            assertEquals("同步失败", host.activeHandle?.title)

            // 模拟切到验证码页：先关同步详情，再排进验证码页，等关闭完成后才真正弹出。
            showCaptcha = true
            showSync = false
            render()
            assertEquals(1, host.handles[0].dismissCalls)
            assertEquals(1, host.handles[1].presentCalls)
            assertNotNull(host.dismissingHandle)
            assertEquals(host.handles[1], host.queuedHandle)
            assertNull(host.activeHandle)

            host.finishDismissTransition()
            assertNull(host.dismissingHandle)
            assertNull(host.queuedHandle)
            assertEquals("物理在线验证码", host.activeHandle?.title)
        } finally {
            scene.close()
        }
    }

    @Test
    fun directNativeSheetDismissDoesNotRequireCoveredHostRecomposition() = onUiThread {
        val host = CoordinatedTestHost()
        var showCaptcha by mutableStateOf(true)
        var triggerDirectDismiss: (() -> Unit)? = null
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalNativeSheetPresenter provides host) {
                    if (showCaptcha) {
                        AppleSheet(title = "物理在线验证码", onDismissRequest = { showCaptcha = false }) {
                            triggerDirectDismiss = LocalDismissNativeSheet.current
                            Text("验证正文")
                        }
                    }
                }
            }
        }
        var frame = 0L
        fun render() {
            repeat(4) { scene.render(++frame * 16_000_000L).close() }
        }
        try {
            render()
            val sheetHandle = assertNotNull(host.activeHandle)
            val sheetScene = ImageComposeScene(width = 390, height = 844, coroutineContext = Dispatchers.Unconfined) {
                sheetHandle.content.invoke()
            }
            try {
                repeat(3) { sheetScene.render(++frame * 16_000_000L).close() }
                assertNotNull(triggerDirectDismiss)
                // 外层宿主状态未改变（未触发 onDispose）时，弹层内部也能直接请求关闭。
                triggerDirectDismiss!!.invoke()
                assertEquals(1, sheetHandle.dismissCalls)
                host.finishDismissTransition()
                assertNull(host.activeHandle)
            } finally {
                sheetScene.close()
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun queuedThirdSheetPreservesSecondSheetInFifoOrder() = onUiThread {
        val host = CoordinatedTestHost()
        var showFirst by mutableStateOf(true)
        var showSecond by mutableStateOf(false)
        var showThird by mutableStateOf(false)
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalNativeSheetPresenter provides host) {
                    if (showFirst) AppleSheet(title = "第一张", onDismissRequest = { showFirst = false }) { Text("1") }
                    if (showSecond) AppleSheet(title = "第二张", onDismissRequest = { showSecond = false }) { Text("2") }
                    if (showThird) AppleSheet(title = "第三张", onDismissRequest = { showThird = false }) { Text("3") }
                }
            }
        }
        var frame = 0L
        fun render() {
            repeat(4) { scene.render(++frame * 16_000_000L).close() }
        }
        try {
            render()
            assertEquals("第一张", host.activeHandle?.title)

            showSecond = true
            render()
            showThird = true
            render()
            showFirst = false
            render()

            assertEquals(listOf("第二张", "第三张"), host.queuedTitles())
            host.finishDismissTransition()
            assertEquals("第二张", host.activeHandle?.title)
            assertEquals(listOf("第三张"), host.queuedTitles())

            showSecond = false
            render()
            host.finishDismissTransition()
            assertEquals("第三张", host.activeHandle?.title)
            assertTrue(host.queuedTitles().isEmpty())
        } finally {
            scene.close()
        }
    }

    @Test
    fun nonIosFallbackDefaultDismissButtonRespectsDismissEnabled() = onUiThread {
        var dismissEnabled by mutableStateOf(false)
        var dismissCalls = 0
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                AppleSheet(
                    title = "物理在线验证码",
                    onDismissRequest = { dismissCalls++ },
                    dismissEnabled = dismissEnabled,
                    fitContentHeight = true,
                ) {
                    Text("正文")
                }
            }
        }
        var frame = 0L
        fun render(frames: Int = 24) {
            repeat(frames) { scene.render(++frame * 16_000_000L).close() }
        }
        fun hasCloseText(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "关闭" } == true ||
                node.children.any(::hasCloseText)
        fun closeButton(): SemanticsNode {
            fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
            return scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }.first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null && hasCloseText(node)
            }
        }
        try {
            render()
            val disabledButton = closeButton()
            assertTrue(SemanticsProperties.Disabled in disabledButton.config, "提交中默认关闭按钮也应禁用")
            val center = disabledButton.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, Offset(center.x, center.y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(center.x, center.y))
            render(4)
            assertEquals(0, dismissCalls)

            dismissEnabled = true
            render(8)
            val enabledButton = closeButton()
            assertFalse(SemanticsProperties.Disabled in enabledButton.config, "可关闭时默认关闭按钮应恢复可用")
            val enabledCenter = enabledButton.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, Offset(enabledCenter.x, enabledCenter.y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(enabledCenter.x, enabledCenter.y))
            render(4)
            assertEquals(1, dismissCalls)
        } finally {
            scene.close()
        }
    }

    private fun onUiThread(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private class CoordinatedTestHost : NativeSheetPresenter {
        val handles = mutableListOf<Handle>()
        var activeHandle: Handle? = null
        var dismissingHandle: Handle? = null
        private val queuedHandles = mutableListOf<Handle>()
        val queuedHandle: Handle? get() = queuedHandles.firstOrNull()

        fun queuedTitles(): List<String?> = queuedHandles.map { it.title }

        override fun createPresentation(): NativeSheetPresenter = Handle().also(handles::add)

        fun finishDismissTransition() {
            dismissingHandle = null
            val next = queuedHandles.removeFirstOrNull() ?: return
            next.present(next.lastNeedsFullHeight)
        }

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

        inner class Handle : NativeSheetPresenter {
            var title: String? = null
            var content: @Composable () -> Unit = {}
            var presentCalls = 0
            var dismissCalls = 0
            var lastNeedsFullHeight = false

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
            ) {
                this.content = content
                this.title = title
            }

            override fun present(needsFullHeight: Boolean) {
                presentCalls++
                lastNeedsFullHeight = needsFullHeight
                if (dismissingHandle != null || (activeHandle != null && activeHandle !== this)) {
                    if (this !in queuedHandles) queuedHandles += this
                    return
                }
                queuedHandles.remove(this)
                activeHandle = this
            }

            override fun dismiss() {
                dismissCalls++
                queuedHandles.remove(this)
                if (activeHandle === this) {
                    activeHandle = null
                    dismissingHandle = this
                }
            }
        }
    }
}

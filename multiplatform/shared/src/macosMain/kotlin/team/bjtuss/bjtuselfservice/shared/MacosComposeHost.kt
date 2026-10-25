package team.bjtuss.bjtuselfservice.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.SingleComposeSceneRenderingScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import platform.AppKit.NSCursor
import platform.AppKit.NSEvent
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSEventModifierFlagControl
import platform.AppKit.NSEventModifierFlagOption
import platform.AppKit.NSEventModifierFlagShift
import platform.AppKit.NSKeyDown
import platform.AppKit.NSKeyUp
import platform.AppKit.NSTrackingActiveAlways
import platform.AppKit.NSTrackingActiveInKeyWindow
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSTrackingAssumeInside
import platform.AppKit.NSTrackingInVisibleRect
import platform.AppKit.NSTrackingMouseEnteredAndExited
import platform.AppKit.NSTrackingMouseMoved
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.Foundation.NSMakeRect

/**
 * macOS 原生宿主视图：把共享 Compose 界面渲染进一个 [NSView]，交给 SwiftUI 的
 * `NavigationSplitView` 承载（侧栏、工具栏、材质全部由 SwiftUI 画）。
 *
 * Compose Multiplatform 1.12 的 macOS 目标没有 iOS 那样的 `ComposeUIViewController`，
 * 只有会自建 `NSWindow` 的 `ComposeWindow`。这里用 compose-ui 的公开 API 自建宿主：
 * `CanvasLayersComposeScene` 产出 [ComposeScene]，SkiaLayer 把它画进 NSView，
 * 鼠标、键盘、滚轮事件由 NSView 转成 Compose 事件。
 *
 * 生命周期由宿主负责：Swift 侧在 `NSViewRepresentable` 的 `dismantleNSView` 里调用 [dispose]。
 */
@OptIn(ExperimentalForeignApi::class, InternalComposeUiApi::class)
class MacosComposeHost(content: @Composable () -> Unit) {

    private var isDisposed = false

    // 单独命名，避免 PlatformContext 委托对象里的同属性 getter 解析到自身造成无限递归。
    private val macosWindowInfo = MacosWindowInfo()
    private val macosTextInputService = MacosTextInputService()

    private val platformContext: PlatformContext =
        object : PlatformContext by PlatformContext.Empty() {
            override val windowInfo: androidx.compose.ui.platform.WindowInfo get() = macosWindowInfo
            override val textInputService: androidx.compose.ui.text.input.PlatformTextInputService
                get() = macosTextInputService
            override fun setPointerIcon(pointerIcon: PointerIcon) {
                // macOS 端的指针图标类型在 compose-ui 内部，这里先统一用系统箭头，
                // 等上游把 MacosCursor 暴露出来后再按类型映射。
                NSCursor.arrowCursor.set()
            }
        }

    // 帧回调：Compose 失效时请求 SkiaLayer 重绘。
    private val frameRecomposer = FrameRecomposer(Dispatchers.Main) { skiaLayer.needRender() }

    private val skiaLayer = SkiaLayer()

    // 场景失效 → 请求新一帧；绘制必须走这个 scope 的 render(帧调度器, 画布)，
    // 直接调 ComposeScene.draw 不会驱动重组，界面会停在第一帧。
    private val sceneRenderingScope = SingleComposeSceneRenderingScope { skiaLayer.needRender() }

    private val scene: ComposeScene = CanvasLayersComposeScene(
        frameRecomposer = frameRecomposer,
        density = Density(2f),
        platformContext = platformContext,
        invalidateLayout = sceneRenderingScope::onSceneInvalidation,
        invalidateDraw = sceneRenderingScope::onSceneInvalidation,
    )

    private val renderDelegate = object : SkikoRenderDelegate {
        override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
            val sizeInPx = IntSize(width, height)
            macosWindowInfo.containerSize = sizeInPx
            macosWindowInfo.containerDpSize = androidx.compose.ui.unit.DpSize(
                width = (sizeInPx.width / scene.density.density).dp,
                height = (sizeInPx.height / scene.density.density).dp,
            )
            scene.size = sizeInPx
            with(sceneRenderingScope) {
                scene.render(frameRecomposer, canvas.asComposeCanvas(), nanoTime)
            }
        }
    }

    /** 承载 Compose 画面的原生视图；SwiftUI 用 `NSViewRepresentable` 包住它。 */
    val view: NSView = object : NSView(frame = NSMakeRect(0.0, 0.0, 0.0, 0.0)) {
        private var trackingArea: NSTrackingArea? = null

        override fun wantsUpdateLayer(): Boolean = true
        override fun acceptsFirstResponder(): Boolean = true

        override fun viewWillMoveToWindow(newWindow: NSWindow?) {
            super.viewWillMoveToWindow(newWindow)
            updateTrackingAreas()
        }

        override fun viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            // SkiaLayer 必须等视图进了窗口才能挂：macOS 的 Metal 重绘器要拿窗口的
            // Metal 设备，提前挂会空指针崩溃。
            onViewMovedToWindow()
        }

        override fun updateTrackingAreas() {
            trackingArea?.let { removeTrackingArea(it) }
            val area = NSTrackingArea(
                rect = bounds,
                options = NSTrackingActiveAlways or
                    NSTrackingMouseEnteredAndExited or
                    NSTrackingMouseMoved or
                    NSTrackingActiveInKeyWindow or
                    NSTrackingAssumeInside or
                    NSTrackingInVisibleRect,
                owner = this,
                userInfo = null,
            )
            addTrackingArea(area)
            trackingArea = area
        }

        override fun mouseDown(event: NSEvent) {
            onMouseEvent(event, PointerEventType.Press, PointerButton.Primary)
        }

        override fun mouseUp(event: NSEvent) {
            onMouseEvent(event, PointerEventType.Release, PointerButton.Primary)
        }

        override fun rightMouseDown(event: NSEvent) {
            onMouseEvent(event, PointerEventType.Press, PointerButton.Secondary)
        }

        override fun rightMouseUp(event: NSEvent) {
            onMouseEvent(event, PointerEventType.Release, PointerButton.Secondary)
        }

        override fun otherMouseDown(event: NSEvent) {
            onMouseEvent(event, PointerEventType.Press, PointerButton(event.buttonNumber.toInt()))
        }

        override fun otherMouseUp(event: NSEvent) {
            onMouseEvent(event, PointerEventType.Release, PointerButton(event.buttonNumber.toInt()))
        }

        override fun mouseMoved(event: NSEvent) {
            onMouseEvent(event, PointerEventType.Move)
        }

        override fun mouseDragged(event: NSEvent) {
            onMouseEvent(event, PointerEventType.Move)
        }

        override fun scrollWheel(event: NSEvent) {
            onMouseEvent(event, PointerEventType.Scroll)
        }

        override fun keyDown(event: NSEvent) {
            // 有输入会话时，字符直接转成编辑命令交给 Compose 的文本框；
            // 其余按键仍走 Compose 的按键事件（快捷键、Esc 等）。
            if (macosTextInputService.hasActiveSession && handleTextInput(event)) return
            val consumed = onKeyboardEvent(event.toComposeKeyEvent())
            if (!consumed) {
                // 未被 Compose 消费按键交回系统，否则 Cmd+Q、Esc 这类系统键会被吞掉。
                super.keyDown(event)
            }
        }

        override fun keyUp(event: NSEvent) {
            onKeyboardEvent(event.toComposeKeyEvent())
        }
    }

    private var layerAttached = false

    init {
        scene.density = Density(2f)
        scene.setContent { content() }
    }

    /** 视图进入窗口后挂上 SkiaLayer（macOS 的 Metal 重绘器此时才有窗口可用）。 */
    private fun onViewMovedToWindow() {
        if (isDisposed) return
        val window = view.window ?: return
        updateDensity(window.backingScaleFactor.toFloat())
        if (layerAttached) return
        layerAttached = true
        skiaLayer.renderDelegate = renderDelegate
        skiaLayer.attachTo(view)
        skiaLayer.needRender()
    }

    /** 跨屏拖动后 backingScaleFactor 会变，需要同步 Compose 的密度。 */
    fun updateDensity(scaleFactor: Float) {
        if (isDisposed) return
        val density = Density(scaleFactor)
        if (scene.density != density) {
            scene.density = density
            skiaLayer.needRender()
        }
    }

    /**
     * 把按键转成文本编辑动作。返回 true 表示这次按键已经被文本输入消费。
     * 只处理可打印字符与退格/回车；组合输入（中文候选）仍需上游实现。
     */
    private fun handleTextInput(event: NSEvent): Boolean {
        val characters = event.charactersIgnoringModifiers
        if (!characters.isNullOrEmpty()) {
            val text = characters.filter { it.code in 0x20..0x7E || it.code > 0x7E }
            if (text.isNotEmpty()) {
                macosTextInputService.insertText(text)
                return true
            }
        }
        when (event.keyCode.toInt()) {
            51 -> { // 退格
                macosTextInputService.deleteBackward()
                return true
            }
            36 -> { // 回车
                macosTextInputService.performImeAction()
                return true
            }
        }
        return false
    }

    private fun onKeyboardEvent(event: KeyEvent): Boolean {
        if (isDisposed) return false
        return scene.sendKeyEvent(event)
    }

    private fun onMouseEvent(
        event: NSEvent,
        eventType: PointerEventType,
        button: PointerButton? = null,
    ) {
        if (isDisposed) return
        scene.sendPointerEvent(
            eventType = eventType,
            position = event.composeOffset,
            scrollDelta = Offset(x = event.deltaX.toFloat(), y = event.deltaY.toFloat()),
            type = PointerType.Touch,
            nativeEvent = event,
            button = button,
        )
    }

    /** 事件坐标：AppKit 原点在左下角，Compose 原点在左上角，需要翻转 Y。 */
    private val NSEvent.composeOffset: Offset get() {
        val position = locationInWindow.useContents { x to y }
        val height = view.frame.useContents { size.height }
        return with(scene.density) {
            Offset(
                x = position.first.dp.toPx(),
                y = (height - position.second).dp.toPx(),
            )
        }
    }

    /** NSEvent → Compose 按键事件。compose-ui 内部的 toComposeEvent 是 internal，这里按公开工厂自己转。 */
    private fun NSEvent.toComposeKeyEvent(): KeyEvent = KeyEvent(
        key = Key(keyCode.toLong()),
        type = when (type) {
            NSKeyDown -> KeyEventType.KeyDown
            NSKeyUp -> KeyEventType.KeyUp
            else -> KeyEventType.Unknown
        },
        codePoint = characters?.firstOrNull()?.code ?: 0,
        isCtrlPressed = modifierFlags and NSEventModifierFlagControl != 0UL,
        isMetaPressed = modifierFlags and NSEventModifierFlagCommand != 0UL,
        isAltPressed = modifierFlags and NSEventModifierFlagOption != 0UL,
        isShiftPressed = modifierFlags and NSEventModifierFlagShift != 0UL,
        nativeEvent = this,
    )

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        if (layerAttached) skiaLayer.detach()
        scene.close()
        frameRecomposer.close()
    }
}

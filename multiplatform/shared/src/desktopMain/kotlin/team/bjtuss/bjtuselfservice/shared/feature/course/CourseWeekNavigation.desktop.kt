package team.bjtuss.bjtuselfservice.shared.feature.course

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.EventQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import team.bjtuss.bjtuselfservice.shared.desktopCredentialWindowHandle
import team.bjtuss.bjtuselfservice.shared.locateInputSourceHelper

/**
 * macOS 优先读取 AppKit 的手指 phase：一次物理横滑只翻一周，并忽略抬手后的惯性。
 * 原生 helper 不可用时才退回 Compose 的滚动距离分页。
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.courseWeekScrollNavigation(
    accumulator: CourseWeekScrollAccumulator,
    onDirection: (CourseWeekScrollDirection) -> Unit,
): Modifier = composed {
    val windowHandle = desktopCredentialWindowHandle.longValue
    val bridge = remember(windowHandle) { MacTrackpadPagerBridge.loadOrNull() }
    val latestDirection = rememberUpdatedState(onDirection)
    var nativeHost by remember(windowHandle, bridge) { mutableStateOf<NativeTrackpadPagerHost?>(null) }
    // The native constructor synchronously enters AppKit. Compose runs this modifier on
    // the AWT event thread, so creating the host here can stall the whole window while
    // AppKit is handling accessibility or mouse events. Keep that call off the event thread.
    LaunchedEffect(windowHandle, bridge) {
        if (windowHandle == 0L || bridge == null) return@LaunchedEffect
        val created = withContext(Dispatchers.Default + NonCancellable) {
            NativeTrackpadPagerHost.create(bridge, windowHandle) { direction ->
                EventQueue.invokeLater { latestDirection.value(direction) }
            }
        }
        if (currentCoroutineContext().isActive) nativeHost = created else created?.close()
    }

    DisposableEffect(nativeHost) {
        val host = nativeHost
        onDispose { host?.close() }
    }

    val activeHost = nativeHost
    if (activeHost != null) {
        val density = LocalDensity.current.density
        this.onGloballyPositioned { coordinates ->
            activeHost.updateFrame(coordinates.boundsInWindow(), density)
        }
    } else {
        this.onPointerEvent(PointerEventType.Scroll) { event ->
            val change = event.changes.firstOrNull() ?: return@onPointerEvent
            accumulator.add(
                deltaX = change.scrollDelta.x,
                deltaY = change.scrollDelta.y,
                // 部分 macOS/Compose 组合的 PointerInputChange.uptimeMillis 不会递增。
                eventTimeMillis = System.nanoTime() / 1_000_000L,
            )?.let(latestDirection.value)
            if (abs(change.scrollDelta.x) > abs(change.scrollDelta.y)) change.consume()
        }
    }
}

private interface MacTrackpadPagerCallback : Callback {
    fun invoke(direction: Int)
}

private interface MacTrackpadPagerBridge : Library {
    fun bjtuCreateTrackpadPager(
        windowHandle: Long,
        callback: MacTrackpadPagerCallback,
    ): Pointer?

    fun bjtuSetTrackpadPagerFrame(
        host: Pointer,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        density: Double,
    )

    fun bjtuDestroyTrackpadPager(host: Pointer)

    companion object {
        fun loadOrNull(): MacTrackpadPagerBridge? = runCatching {
            val library = locateInputSourceHelper() ?: return null
            Native.load(library.absolutePath, MacTrackpadPagerBridge::class.java)
        }.getOrNull()
    }
}

private class NativeTrackpadPagerHost private constructor(
    private val bridge: MacTrackpadPagerBridge,
    @Suppress("unused") private val callback: MacTrackpadPagerCallback,
    private val host: Pointer,
) {
    fun updateFrame(bounds: Rect, density: Float) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        bridge.bjtuSetTrackpadPagerFrame(
            host = host,
            x = bounds.left.toDouble(),
            y = bounds.top.toDouble(),
            width = bounds.width.toDouble(),
            height = bounds.height.toDouble(),
            density = density.toDouble(),
        )
    }

    fun close() {
        bridge.bjtuDestroyTrackpadPager(host)
    }

    companion object {
        fun create(
            bridge: MacTrackpadPagerBridge,
            windowHandle: Long,
            onDirection: (CourseWeekScrollDirection) -> Unit,
        ): NativeTrackpadPagerHost? {
            val callback = object : MacTrackpadPagerCallback {
                override fun invoke(direction: Int) {
                    onDirection(nativeTrackpadDirection(direction))
                }
            }
            val host = bridge.bjtuCreateTrackpadPager(windowHandle, callback) ?: return null
            return NativeTrackpadPagerHost(bridge, callback, host)
        }
    }
}

/** AppKit 的 scrollingDeltaX 表示内容滚动方向，与页面导航方向相反。 */
internal fun nativeTrackpadDirection(direction: Int): CourseWeekScrollDirection =
    if (direction > 0) CourseWeekScrollDirection.PREVIOUS else CourseWeekScrollDirection.NEXT

package team.bjtuss.bjtuselfservice.shared.feature.course

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * macOS 原生端的触摸板横滑翻周：直接读 Compose 的滚轮增量。
 * 旧版 JVM 桌面端靠 JNA 注入的 AppKit helper 取真实滚动相位，原生宿主下没有
 * AWT 事件线程可挂，这里退回按位移分页——一次物理横滑仍只翻一周（见 CourseWeekScrollAccumulator）。
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.courseWeekScrollNavigation(
    accumulator: CourseWeekScrollAccumulator,
    onDirection: (CourseWeekScrollDirection) -> Unit,
): Modifier = composed {
    val latestDirection = rememberUpdatedState(onDirection)
    val timeSource = remember { TimeSource.Monotonic }
    onPointerEvent(PointerEventType.Scroll) { event ->
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        accumulator.add(
            deltaX = change.scrollDelta.x,
            deltaY = change.scrollDelta.y,
            // 部分 macOS/Compose 组合的 PointerInputChange.uptimeMillis 不递增，用单调时钟兜底。
            eventTimeMillis = timeSource.markNow().elapsedNow().inWholeMilliseconds,
        )?.let(latestDirection.value)
        if (abs(change.scrollDelta.x) > abs(change.scrollDelta.y)) change.consume()
    }
}

package team.bjtuss.bjtuselfservice.shared.feature.course

import androidx.compose.ui.Modifier
import kotlin.math.abs
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo

internal enum class CourseWeekScrollDirection {
    PREVIOUS,
    NEXT,
}

/** 非教学周没有教学周编号；桌面动画按自然周日期排序，概览始终在最前。 */
internal fun courseWeekTransitionMovesForward(
    initial: CourseScheduleWeekPage,
    target: CourseScheduleWeekPage,
): Boolean = when {
    initial.isOverview -> !target.isOverview
    target.isOverview -> false
    initial.startDate != null && target.startDate != null -> target.startDate > initial.startDate
    else -> (target.teachingWeek ?: 0) > (initial.teachingWeek ?: 0)
}

/** 触摸板滚轮只给 Mac/Windows 宽屏课表。Android/iOS 宽屏改走 HorizontalPager，跟手连滑。 */
internal fun shouldUseFingerWeekPager(platform: PlatformInfo): Boolean =
    platform.family == PlatformFamily.Android || platform.family == PlatformFamily.IOS

/** 触摸板滚轮事件只由 desktop actual 接入；移动端宽屏改走 HorizontalPager。 */
internal expect fun Modifier.courseWeekScrollNavigation(
    accumulator: CourseWeekScrollAccumulator,
    onDirection: (CourseWeekScrollDirection) -> Unit,
): Modifier

/**
 * 把触摸板横向位移按距离分页。
 * 一次物理滑动只翻一周：翻页后丢掉同方向惯性，直到事件出现停顿
 * （手指抬起、惯性结束）才接受下一次滑动。反向滑动立即解锁。
 * Windows 精密触摸板的惯性尾流可长达数百毫秒，不能只靠短时间节流。
 */
internal class CourseWeekScrollAccumulator(
    private val threshold: Float = 36f,
    private val quietGapMillis: Long = 120L,
    private val minTurnIntervalMillis: Long = 180L,
) {
    private var accumulatedX = 0f
    private var lastEventMillis = Long.MIN_VALUE
    private var lastTurnMillis = Long.MIN_VALUE
    private var lastTurnPositive: Boolean? = null
    private var ignoringUntilQuiet = false

    fun resetGesture() {
        accumulatedX = 0f
        lastEventMillis = Long.MIN_VALUE
        lastTurnMillis = Long.MIN_VALUE
        lastTurnPositive = null
        ignoringUntilQuiet = false
    }

    fun add(
        deltaX: Float,
        deltaY: Float,
        eventTimeMillis: Long,
    ): CourseWeekScrollDirection? {
        val hasQuietGap = lastEventMillis != Long.MIN_VALUE &&
            eventTimeMillis - lastEventMillis > quietGapMillis
        if (hasQuietGap) {
            resetGesture()
        }
        lastEventMillis = eventTimeMillis

        if (abs(deltaX) <= abs(deltaY) || deltaX == 0f) {
            accumulatedX = 0f
            return null
        }
        val movingPositive = deltaX > 0f
        if (lastTurnPositive != null && lastTurnPositive != movingPositive) {
            ignoringUntilQuiet = false
            lastTurnMillis = Long.MIN_VALUE
            accumulatedX = 0f
        }
        if (ignoringUntilQuiet) {
            accumulatedX = 0f
            return null
        }
        if (accumulatedX != 0f && (accumulatedX > 0f) != movingPositive) {
            accumulatedX = 0f
            lastTurnMillis = Long.MIN_VALUE
        }
        accumulatedX += deltaX
        if (abs(accumulatedX) < threshold) return null

        if (lastTurnPositive != null && lastTurnPositive != (accumulatedX > 0f)) {
            lastTurnMillis = Long.MIN_VALUE
        }
        if (lastTurnMillis != Long.MIN_VALUE &&
            eventTimeMillis - lastTurnMillis < minTurnIntervalMillis
        ) {
            accumulatedX = 0f
            return null
        }
        val direction = if (accumulatedX > 0f) {
            CourseWeekScrollDirection.NEXT
        } else {
            CourseWeekScrollDirection.PREVIOUS
        }
        accumulatedX = 0f
        lastTurnMillis = eventTimeMillis
        lastTurnPositive = direction == CourseWeekScrollDirection.NEXT
        ignoringUntilQuiet = true
        return direction
    }
}

/**
 * 一次手指横滑结束后决定翻页方向。
 * 左滑（位移/速度为负）到下一周，右滑到上一周，与紧凑端 HorizontalPager 一致。
 * 位移够远就按位移；不够远但甩得够快则按速度。竖向主导的手势忽略。
 */
internal fun weekSwipeDirection(
    displacementX: Float,
    displacementY: Float,
    velocityX: Float,
    distanceThreshold: Float,
    velocityThreshold: Float,
): CourseWeekScrollDirection? {
    if (abs(displacementX) <= abs(displacementY)) return null
    if (abs(displacementX) >= distanceThreshold) {
        return if (displacementX < 0f) {
            CourseWeekScrollDirection.NEXT
        } else {
            CourseWeekScrollDirection.PREVIOUS
        }
    }
    if (abs(velocityX) >= velocityThreshold) {
        return if (velocityX < 0f) {
            CourseWeekScrollDirection.NEXT
        } else {
            CourseWeekScrollDirection.PREVIOUS
        }
    }
    return null
}

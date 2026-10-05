package team.bjtuss.bjtuselfservice.shared.feature.physicslab

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

// 校园网旧系统。仅允许登录、读取选课结果，不包含选课/退课操作。
internal const val PHYSICS_LAB_ORIGIN = "http://wlsy.bjtu.edu.cn"
internal const val PHYSICS_LAB_RESULTS = "$PHYSICS_LAB_ORIGIN/Student/Teach/Course/CourseResult.aspx"

/** 2026-10-05 校园网登录后 /Info/TimeInfo.aspx 的六个时段。 */
val physicsLabTimeRanges = listOf("13:20-15:50", "16:20-18:50", "19:10-21:40", "13:00-16:20", "17:40-21:00", "10:10-12:40")

@Serializable
data class PhysicsLab(
    val date: LocalDate,
    val period: Int,
    val name: String,
    val location: String,
    val teacher: String,
    val weekCount: Int,
) {
    init { require(period in 1..6); require(weekCount in 1..2) }

    val dates: List<LocalDate> get() = (0 until weekCount).map { date.plus(it * 7, DateTimeUnit.DAY) }
    val timeRange: String? get() = physicsLabTimeRanges.getOrNull(period - 1)
}

/** 与参考项目一致：专题/设计连续两周，其余一周；在设置页明确此规则。 */
fun physicsLabWeekCount(name: String): Int =
    if (listOf("专题", "软磁", "GPS模拟", "设计").any(name::contains)) 2 else 1

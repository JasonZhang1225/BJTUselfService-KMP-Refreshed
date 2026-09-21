package team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * 校历时间轴上的一个自然周。
 *
 * [teachingWeek] 为 null 时，表示这是一周校历明确留出的非教学周，而不是“没有课程”。
 * 这一区分很重要：作业/考试仍然可能在非教学周开始或截止。
 */
data class AcademicWeekSlot(
    val teachingWeek: Int?,
    val startDate: LocalDate,
) {
    val endDate: LocalDate
        get() = startDate.plus(6, DateTimeUnit.DAY)

    val isNonTeachingWeek: Boolean
        get() = teachingWeek == null
}

/**
 * 将校历中的教学周展开为按自然周排序的时间轴，并补出相邻教学周之间缺失的周。
 *
 * 例如第 3 周从 9 月 21 日开始、第 4 周从 10 月 12 日开始时，结果中会出现
 * 9 月 28 日和 10 月 5 日两个 [AcademicWeekSlot]（[teachingWeek] 为 null）。
 */
fun academicWeekSlots(
    weeks: List<OccupancyWeekDate>,
    maxTeachingWeek: Int = Int.MAX_VALUE,
): List<AcademicWeekSlot> {
    val dated = weeks
        .filter { it.week > 0 && it.week <= maxTeachingWeek && it.startDate != null }
        .map { week ->
            val start = week.startDate!!
            val monday = start.minus(start.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
            week to monday
        }
        .distinctBy { (_, monday) -> monday }
        .sortedBy { (_, monday) -> monday }
    if (dated.isEmpty()) return emptyList()

    val actualByMonday = dated.associate { (week, monday) -> monday to week.week }
    val first = dated.first().second
    val last = dated.last().second
    val result = mutableListOf<AcademicWeekSlot>()
    var monday = first
    while (monday <= last) {
        result += AcademicWeekSlot(
            teachingWeek = actualByMonday[monday],
            startDate = monday,
        )
        monday = monday.plus(7, DateTimeUnit.DAY)
    }
    return result
}

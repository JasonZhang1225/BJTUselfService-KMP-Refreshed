package team.bjtuss.bjtuselfservice.shared.feature.widget

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** 平台只负责排版；日期推进、正在上/下一节和周起点在这里统一计算。 */
data class CourseWidgetPresentation(
    val today: CourseWidgetDay,
    val nextDay: CourseWidgetDay?,
    val week: List<CourseWidgetDay>,
    val minute: Int,
) {
    val remainingToday: List<CourseWidgetSession> get() = today.sessions.filter { it.endMinute > minute }
    val isRestPeriod: Boolean get() = week.all { it.sessions.isEmpty() } &&
        (nextDay == null || LocalDate.parse(nextDay.date) > LocalDate.parse(today.date).plus(1, DateTimeUnit.DAY))
    val upcomingDay: CourseWidgetDay? get() = when {
        isRestPeriod -> null
        remainingToday.isNotEmpty() -> today.copy(sessions = remainingToday)
        else -> nextDay
    }
    val displayDay: CourseWidgetDay get() = if (remainingToday.isNotEmpty()) today else nextDay ?: today
}

/** null 表示快照已过期（不覆盖今天）；没有课程的一天仍是有效数据。 */
fun courseWidgetPresentation(
    snapshot: CourseWidgetSnapshot,
    date: LocalDate,
    minute: Int,
): CourseWidgetPresentation? {
    val days = snapshot.days.sortedBy { it.date }
    val today = days.firstOrNull { it.date == date.toString() } ?: return null
    val monday = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    return CourseWidgetPresentation(
        today = today.copy(sessions = today.sessions.sortedWith(compareBy({ it.startMinute }, { it.endMinute }, { it.name }))),
        nextDay = days.firstOrNull { it.date > today.date && it.sessions.isNotEmpty() }
            ?.let { it.copy(sessions = it.sessions.sortedBy(CourseWidgetSession::startMinute)) },
        week = (0..6).map { offset ->
            val key = monday.plus(offset, DateTimeUnit.DAY).toString()
            days.firstOrNull { it.date == key } ?: CourseWidgetDay(key, sessions = emptyList())
        },
        minute = minute,
    )
}

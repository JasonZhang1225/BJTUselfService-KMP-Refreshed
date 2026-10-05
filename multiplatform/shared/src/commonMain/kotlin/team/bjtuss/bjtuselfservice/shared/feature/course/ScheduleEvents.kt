package team.bjtuss.bjtuselfservice.shared.feature.course

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.SLOT_TIME_RANGES
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.domain.calendar.parseExamCalendarTime
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLab

/** 只在展示边界生成网格片段；原始课表与日历导出保持独立的数据模型。 */
fun scheduleEventCourses(exams: List<ExamSchedule>, labs: List<PhysicsLab>, weeks: List<OccupancyWeekDate>): List<Course> {
    val first = weeks.mapNotNull { it.startDate }.minOrNull() ?: return emptyList()
    val last = weeks.mapNotNull { it.startDate }.maxOrNull()?.plus(6, DateTimeUnit.DAY) ?: return emptyList()
    val result = mutableListOf<Course>()
    fun add(date: LocalDate, range: String, name: String, teacher: String, location: String, kind: String) {
        if (date < first || date > last) return
        val start = minuteOfDay(range.substringBefore('-')) ?: return
        val end = minuteOfDay(range.substringAfter('-')) ?: return
        if (end <= start) return
        val week = weeks.firstOrNull { it.startDate?.let { first -> date >= first && date <= first.plus(6, DateTimeUnit.DAY) } == true }?.week ?: 0
        val id = -("$kind|$date|$range|$name".hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        SLOT_TIME_RANGES.forEachIndexed { slot, time ->
            val slotStart = minuteOfDay(time.substringBefore('-')) ?: return@forEachIndexed
            val slotEnd = minuteOfDay(time.substringAfter('-')) ?: return@forEachIndexed
            if (start < slotEnd && end > slotStart) {
                result += Course(id, "$kind-$id", name, teacher, slot * 8 + date.dayOfWeek.isoDayNumber,
                    "第${week}周", location, false, kind, date.toString(), range,
                    ((maxOf(start, slotStart) - slotStart).toFloat() / (slotEnd - slotStart)),
                    ((minOf(end, slotEnd) - maxOf(start, slotStart)).toFloat() / (slotEnd - slotStart)))
            }
        }
    }
    exams.forEach { exam ->
        val parsed = parseExamCalendarTime(exam.examTimeAndPlace) ?: return@forEach
        fun time(h: Int, m: Int) = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
        add(parsed.date, "${time(parsed.startHour, parsed.startMinute)}-${time(parsed.endHour, parsed.endMinute)}", "${exam.courseName}（考试）", "", parsed.location, "exam")
    }
    labs.forEach { lab -> lab.dates.forEach { date -> lab.timeRange?.let { add(date, it, "${lab.name}（实验）", lab.teacher, lab.location, "physicslab") } } }
    return result
}

internal fun minuteOfDay(time: String): Int? {
    val parts = time.split(':')
    val hour = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return hour * 60 + minute
}

fun CourseScheduleUiState.coursesForPage(week: Int?, start: LocalDate?): List<Course> {
    if (week == 0 && start == null) return scheduleCourses
    val ordinary = if (week != null) team.bjtuss.bjtuselfservice.shared.domain.course.coursesForWeek(scheduleCourses.filter { it.scheduleEventKind == null }, week) else emptyList()
    val events = if (start != null) supplementalCourses.filter { it.scheduleEventDate?.let(LocalDate::parse)?.let { date -> date >= start && date <= start.plus(6, DateTimeUnit.DAY) } == true }
        else team.bjtuss.bjtuselfservice.shared.domain.course.coursesForWeek(supplementalCourses, week ?: 0)
    return ordinary + events
}

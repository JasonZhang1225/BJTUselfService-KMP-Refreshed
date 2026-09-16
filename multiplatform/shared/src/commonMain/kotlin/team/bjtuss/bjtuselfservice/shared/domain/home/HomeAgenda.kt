package team.bjtuss.bjtuselfservice.shared.domain.home

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.isHomeworkDueSoon
import team.bjtuss.bjtuselfservice.shared.domain.homework.parseSchoolLocalDateTime
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind

private val PHYVLAB_TIME_ZONE = TimeZone.of("Asia/Shanghai")
const val HOME_MAX_TEACHING_WEEK = 30

data class HomeAgendaDay(
    val date: LocalDate,
    val homeworkStarting: List<Homework>,
    val homeworkDue: List<Homework>,
    val exams: List<ExamSchedule>,
    val phyVlabEvents: List<PhyVlabEvent> = emptyList(),
) {
    val eventCount: Int
        get() = homeworkStarting.size + homeworkDue.size + exams.size + phyVlabEvents.size
}

data class HomeAgenda(
    val days: List<HomeAgendaDay>,
    val dueSoonHomework: List<Homework>,
) {
    init {
        require(days.size == 7)
    }
}

/**
 * Builds the Monday-to-Sunday agenda used by the v1.7.0 home screen.
 * A deadline at exactly 00:00 belongs to the previous calendar day, matching the Android baseline.
 * The same rule applies to physical-online deadlines; start events remain on their actual date.
 */
fun buildHomeAgenda(
    homework: List<Homework>,
    exams: List<ExamSchedule>,
    today: LocalDate,
    now: LocalDateTime,
    timeZone: TimeZone,
    phyVlabEvents: List<PhyVlabEvent> = emptyList(),
    weekStartDate: LocalDate? = null,
): HomeAgenda {
    val monday = weekStartDate ?: today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    val days = (0..6).map { offset ->
        val date = monday.plus(offset, DateTimeUnit.DAY)
        HomeAgendaDay(
            date = date,
            homeworkStarting = homework.filter { homeworkStartDate(it) == date }
                .sortedWith(compareBy(Homework::courseName, Homework::title)),
            homeworkDue = homework.filter { homeworkDueDate(it, timeZone) == date }
                .sortedBy(Homework::endTime),
            exams = exams.filter { examDate(it) == date }
                .sortedBy(ExamSchedule::examTimeAndPlace),
            phyVlabEvents = phyVlabEvents.filter { phyVlabEventDate(it, timeZone) == date }
                .sortedWith(compareBy<PhyVlabEvent> { it.dayTimestamp }.thenBy { it.title }),
        )
    }
    val dueSoon = homework.filter { isHomeworkDueSoon(it, now, timeZone) }
        .sortedWith(compareBy<Homework> { parseSchoolLocalDateTime(it.endTime) == null }
            .thenBy { parseSchoolLocalDateTime(it.endTime) })
    return HomeAgenda(days, dueSoon)
}

/**
 * Resolves the Monday shown by the home agenda for a selected teaching week.
 *
 * The school calendar is authoritative when it contains the requested week;
 * this matters across holidays, where teaching week 4 may start later than
 * seven days after teaching week 3. If the calendar is unavailable, keep a
 * predictable fallback relative to the current week's Monday.
 */
fun resolveHomeAgendaWeekStart(
    currentWeek: Int,
    selectedWeek: Int,
    today: LocalDate,
    weekDates: List<OccupancyWeekDate> = emptyList(),
): LocalDate {
    // 0 表示当前日期落在教学周之间的假期/非教学周，仍要展示自然周日程。
    if (selectedWeek == 0) {
        return today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    }
    require(selectedWeek in 1..HOME_MAX_TEACHING_WEEK)
    weekDates.firstOrNull { it.week == selectedWeek }?.startDate?.let { return it }

    val anchorWeek = currentWeek.takeIf { it in 1..HOME_MAX_TEACHING_WEEK } ?: selectedWeek
    val fallbackMonday = today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    val anchorStart = weekDates.firstOrNull { it.week == anchorWeek }?.startDate ?: fallbackMonday
    return anchorStart.plus(selectedWeek - anchorWeek, DateTimeUnit.DAY)
}

fun homeworkStartDate(homework: Homework): LocalDate? =
    parseSchoolLocalDateTime(homework.openDate)?.date

fun homeworkDueDate(homework: Homework, timeZone: TimeZone): LocalDate? =
    parseSchoolLocalDateTime(homework.endTime)
        ?.toInstant(timeZone)
        ?.minus(1.minutes)
        ?.toLocalDateTime(timeZone)
        ?.date

fun examDate(exam: ExamSchedule): LocalDate? = try {
    LocalDate.parse(exam.examTimeAndPlace.trim().substringBefore(' '))
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Moodle 日历以北京时间当天零点的 Unix 时间戳标记安排。
 *
 * [timeZone] 保留在函数签名中以兼容首页议程调用方，但物理在线事件不能按设备时区
 * 还原，否则海外设备会把北京时间零点的截止日显示成前一天。
 */
@Suppress("UNUSED_PARAMETER")
fun phyVlabEventDate(event: PhyVlabEvent, timeZone: TimeZone): LocalDate? =
    runCatching {
        val instant = Instant.fromEpochSeconds(event.dayTimestamp)
        val local = instant.toLocalDateTime(PHYVLAB_TIME_ZONE)
        val effectiveInstant = if (
            event.kind == PhyVlabEventKind.DEADLINE &&
            local.hour == 0 && local.minute == 0
        ) {
            instant.minus(1.minutes)
        } else {
            instant
        }
        effectiveInstant.toLocalDateTime(PHYVLAB_TIME_ZONE).date
    }.getOrNull()

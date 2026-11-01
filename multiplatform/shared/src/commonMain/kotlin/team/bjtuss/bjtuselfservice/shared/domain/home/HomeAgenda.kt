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
import team.bjtuss.bjtuselfservice.shared.domain.homework.isHomeworkSubmitted
import team.bjtuss.bjtuselfservice.shared.domain.homework.parseSchoolLocalDateTime
import team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone
import team.bjtuss.bjtuselfservice.shared.domain.exam.examDisplayDate
import team.bjtuss.bjtuselfservice.shared.domain.time.displayTimeZone
import team.bjtuss.bjtuselfservice.shared.domain.time.schoolToDisplay
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
    val courses: List<team.bjtuss.bjtuselfservice.shared.domain.course.Course> = emptyList(),
    val physicsLabCourses: List<team.bjtuss.bjtuselfservice.shared.domain.course.Course> = emptyList(),
) {
    val eventCount: Int
        get() = homeworkStarting.size + homeworkDue.size + exams.size + phyVlabEvents.size + courses.size + physicsLabCourses.size
}

data class HomeAgenda(
    val days: List<HomeAgendaDay>,
    val dueSoonHomework: List<Homework>,
    /** 48 小时内截止且未完成的物理在线 / CITEL 事项，与 [dueSoonHomework] 一起组成首页临近截止提醒。 */
    val dueSoonEvents: List<PhyVlabEvent> = emptyList(),
    /** 未来 48 小时内开始折扣且未完成的 CITEL 作业，与截止提醒分开保留。 */
    val dueSoonDiscountEvents: List<PhyVlabEvent> = emptyList(),
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
    nowInstant: Instant = now.toInstant(timeZone),
): HomeAgenda {
    val monday = weekStartDate ?: today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    val days = (0..6).map { offset ->
        val date = monday.plus(offset, DateTimeUnit.DAY)
        HomeAgendaDay(
            date = date,
            homeworkStarting = homework.filter { homeworkStartDate(it, timeZone) == date }
                .sortedWith(compareBy(Homework::courseName, Homework::title)),
            homeworkDue = homework.filter { homeworkDueDate(it, timeZone) == date }
                .sortedBy(Homework::endTime),
            exams = exams.filter { examDate(it, timeZone) == date }
                .sortedBy(ExamSchedule::examTimeAndPlace),
            phyVlabEvents = phyVlabEvents.filter { phyVlabEventDate(it, timeZone) == date }
                .sortedWith(compareBy<PhyVlabEvent> { it.dayTimestamp }.thenBy { it.title }),
        )
    }
    val dueSoon = homework.filter { isHomeworkDueSoon(it, now, timeZone, nowInstant) }
        .sortedWith(compareBy<Homework> { parseSchoolLocalDateTime(it.endTime) == null }
            .thenBy { parseSchoolLocalDateTime(it.endTime) })
    val nowEpochSeconds = nowInstant.epochSeconds
    val dueSoonEvents = phyVlabEvents
        .filter { isPhyVlabDeadlineDueSoon(it, nowEpochSeconds) }
        .sortedWith(compareBy<PhyVlabEvent> { it.dayTimestamp }.thenBy { it.title })
    val dueSoonDiscountEvents = phyVlabEvents
        .filter { isCitelDiscountDueSoon(it, nowEpochSeconds) }
        .sortedWith(compareBy<PhyVlabEvent> { it.dayTimestamp }.thenBy { it.title })
    return HomeAgenda(days, dueSoon, dueSoonEvents, dueSoonDiscountEvents)
}

/**
 * CITEL 折扣提醒窗口：未来 0–48 小时的秒级闭区间。
 * 与 [isPhyVlabDeadlineDueSoon] 的整小时截断不同——折扣卡要在开始折扣的精确时刻离开列表。
 */
private fun isCitelDiscountDueSoon(event: PhyVlabEvent, nowEpochSeconds: Long): Boolean {
    if (event.kind != PhyVlabEventKind.DISCOUNT || !event.id.startsWith("citel-discount-") || event.submitted) return false
    return event.dayTimestamp - nowEpochSeconds in 0L..48L * 3600
}

/** 与 [isHomeworkDueSoon] 同一窗口：未完成的截止事件，距今 0–48 小时（按整小时计）。 */
fun isPhyVlabDeadlineDueSoon(event: PhyVlabEvent, nowEpochSeconds: Long): Boolean {
    if (event.kind != PhyVlabEventKind.DEADLINE || event.submitted) return false
    val seconds = event.dayTimestamp - nowEpochSeconds
    return seconds >= 0 && seconds / 3600 <= 48
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
    return anchorStart.plus((selectedWeek - anchorWeek) * 7, DateTimeUnit.DAY)
}

/**
 * 当天截止项是否全部已做：有一项没做仍算未完成（红色），全部已提交才整天标绿；
 * 当天没有截止项时返回 false，不影响原有配色。
 *
 * 物理在线事件的已做信号由物理在线页在组装事件时写入
 * （课程页完成标记或详情提交信息）；未知时为 false。开始事件和考试不参与判定。
 */
fun isHomeAgendaDayFullySubmitted(day: HomeAgendaDay): Boolean {
    val hasDeadline = day.homeworkDue.isNotEmpty() ||
        day.phyVlabEvents.any { it.kind == PhyVlabEventKind.DEADLINE }
    if (!hasDeadline) return false
    return day.homeworkDue.all(::isHomeworkSubmitted) &&
        day.phyVlabEvents
            .filter { it.kind == PhyVlabEventKind.DEADLINE }
            .all(PhyVlabEvent::submitted)
}

/** 课程平台时间是北京时间文本；归到 [timeZone]（手机时区）的哪一天。 */
fun homeworkStartDate(homework: Homework, timeZone: TimeZone = displayTimeZone()): LocalDate? =
    parseSchoolLocalDateTime(homework.openDate)?.schoolToDisplay(timeZone)?.date

fun homeworkDueDate(homework: Homework, timeZone: TimeZone): LocalDate? =
    parseSchoolLocalDateTime(homework.endTime)
        ?.toInstant(SchoolTimeZone)
        ?.minus(1.minutes)
        ?.toLocalDateTime(timeZone)
        ?.date

/** 考试按开始时刻在 [timeZone]（手机时区）的日期归日；时刻解析不了时退回原文开头的日期。 */
fun examDate(exam: ExamSchedule, timeZone: TimeZone = displayTimeZone()): LocalDate? =
    examDisplayDate(exam.examTimeAndPlace, timeZone) ?: try {
        LocalDate.parse(exam.examTimeAndPlace.trim().substringBefore(' '))
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * 由作业换算出的事件（物理在线活动 `activity-`、CITEL `citel-`）带真实时刻，按手机时区归日；
 * Moodle 日历格子里的事件只用北京时间当天零点的时间戳标记“哪一天”，不是时刻，仍归到北京时间那天，
 * 否则海外设备会把它挪到前一天。
 */
fun phyVlabEventHasInstant(event: PhyVlabEvent): Boolean =
    event.id.startsWith("activity-") || event.id.startsWith("citel-")

fun phyVlabEventDate(event: PhyVlabEvent, timeZone: TimeZone): LocalDate? =
    runCatching {
        val zone = if (phyVlabEventHasInstant(event)) timeZone else PHYVLAB_TIME_ZONE
        val instant = Instant.fromEpochSeconds(event.dayTimestamp)
        val local = instant.toLocalDateTime(zone)
        val effectiveInstant = if (
            (event.kind == PhyVlabEventKind.DEADLINE || event.kind == PhyVlabEventKind.DISCOUNT) &&
            local.hour == 0 && local.minute == 0 && local.second == 0
        ) {
            instant.minus(1.minutes)
        } else {
            instant
        }
        effectiveInstant.toLocalDateTime(zone).date
    }.getOrNull()

/** Start and deadline counters include every homework source; discounts are distinct agenda events. */
data class AgendaWeekMarks(val courses: Int, val labs: Int, val exams: Int, val startingHomework: Int,
    val pendingHomework: Int, val submittedHomework: Int)

fun agendaWeekMarks(day: HomeAgendaDay): AgendaWeekMarks {
    val deadlines = day.phyVlabEvents.filter { it.kind == PhyVlabEventKind.DEADLINE }
    val submitted = day.homeworkDue.count(::isHomeworkSubmitted) + deadlines.count { it.submitted }
    return AgendaWeekMarks(day.courses.size, day.physicsLabCourses.size, day.exams.size,
        day.homeworkStarting.size + day.phyVlabEvents.count { it.kind == PhyVlabEventKind.START },
        day.homeworkDue.size + deadlines.size - submitted, submitted)
}

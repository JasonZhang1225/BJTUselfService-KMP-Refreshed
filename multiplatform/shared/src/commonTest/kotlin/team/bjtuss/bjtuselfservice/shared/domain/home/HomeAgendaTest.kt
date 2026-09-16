package team.bjtuss.bjtuselfservice.shared.domain.home

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind

class HomeAgendaTest {
    @Test
    fun nonTeachingWeekKeepsNaturalWeekAndShowsOctoberFirstDeadline() {
        val today = LocalDate(2026, 10, 1)
        val deadline = homework(
            title = "国庆期间截止作业",
            openDate = "2026-09-20 08:00",
            endTime = "2026-10-01 12:00",
        )
        val agenda = buildHomeAgenda(
            homework = listOf(deadline),
            exams = emptyList(),
            today = today,
            now = LocalDateTime(2026, 10, 1, 10, 0),
            timeZone = TimeZone.UTC,
            weekStartDate = resolveHomeAgendaWeekStart(
                currentWeek = 0,
                selectedWeek = 0,
                today = today,
            ),
        )

        assertEquals(LocalDate(2026, 9, 28), agenda.days.first().date)
        assertEquals(listOf(deadline), agenda.days.single { it.date == today }.homeworkDue)
    }

    @Test
    fun requestedTeachingWeekUsesCalendarDateAcrossHolidayGap() {
        val weekStart = resolveHomeAgendaWeekStart(
            currentWeek = 3,
            selectedWeek = 4,
            today = LocalDate(2026, 9, 23),
            weekDates = listOf(
                OccupancyWeekDate(3, "9/21", "9/27", LocalDate(2026, 9, 21)),
                OccupancyWeekDate(4, "10/5", "10/11", LocalDate(2026, 10, 5)),
            ),
        )

        assertEquals(LocalDate(2026, 10, 5), weekStart)
        val agenda = buildHomeAgenda(
            homework = emptyList(),
            exams = emptyList(),
            today = LocalDate(2026, 9, 23),
            now = LocalDateTime(2026, 9, 23, 10, 0),
            timeZone = TimeZone.UTC,
            weekStartDate = weekStart,
        )
        assertEquals(LocalDate(2026, 10, 5), agenda.days.first().date)
        assertEquals(LocalDate(2026, 10, 11), agenda.days.last().date)
    }

    @Test
    fun agendaAlwaysCoversTheCurrentMondayToSunday() {
        val agenda = buildHomeAgenda(
            homework = emptyList(),
            exams = emptyList(),
            today = LocalDate(2026, 7, 30),
            now = LocalDateTime(2026, 7, 30, 10, 0),
            timeZone = TimeZone.UTC,
        )

        assertEquals(LocalDate(2026, 7, 27), agenda.days.first().date)
        assertEquals(LocalDate(2026, 8, 2), agenda.days.last().date)
    }

    @Test
    fun homeworkStartAndMidnightDeadlineUseAndroidBaselineDates() {
        val homework = homework(
            openDate = "2026-07-29 08:00",
            endTime = "2026-07-31 00:00",
        )
        val agenda = buildHomeAgenda(
            homework = listOf(homework),
            exams = emptyList(),
            today = LocalDate(2026, 7, 30),
            now = LocalDateTime(2026, 7, 30, 10, 0),
            timeZone = TimeZone.UTC,
        )

        assertEquals(listOf(homework), agenda.days.single { it.date == LocalDate(2026, 7, 29) }.homeworkStarting)
        assertEquals(listOf(homework), agenda.days.single { it.date == LocalDate(2026, 7, 30) }.homeworkDue)
    }

    @Test
    fun examDateUsesTheLeadingSchoolDateAndRejectsMalformedRows() {
        val exam = exam("2026-08-01 09:00 思源楼")
        val malformed = exam("待通知")

        assertEquals(LocalDate(2026, 8, 1), examDate(exam))
        assertNull(examDate(malformed))
    }

    @Test
    fun dueSoonListExcludesSubmittedAndSortsByDeadline() {
        val later = homework(title = "later", endTime = "2026-07-31 09:00")
        val sooner = homework(title = "sooner", endTime = "2026-07-30 12:00")
        val submitted = homework(title = "done", endTime = "2026-07-30 11:00", subStatus = "已提交")

        val agenda = buildHomeAgenda(
            homework = listOf(later, submitted, sooner),
            exams = emptyList(),
            today = LocalDate(2026, 7, 30),
            now = LocalDateTime(2026, 7, 30, 10, 0),
            timeZone = TimeZone.UTC,
        )

        assertEquals(listOf("sooner", "later"), agenda.dueSoonHomework.map(Homework::title))
    }

    @Test
    fun phyVlabMidnightDeadlineMovesToThePreviousDateButStartDoesNot() {
        val deadline = PhyVlabEvent(
            id = "3115",
            title = "chap1 已到期",
            dateText = "03月19日",
            dayTimestamp = 1773849600L,
            eventUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689",
        )
        val start = deadline.copy(
            id = "3115-start",
            title = "chap1 开放",
            kind = PhyVlabEventKind.START,
        )
        val agenda = buildHomeAgenda(
            homework = emptyList(),
            exams = emptyList(),
            today = LocalDate(2026, 3, 19),
            now = LocalDateTime(2026, 3, 19, 10, 0),
            // 即使首页其它数据使用 UTC，物理在线事件仍按学校的北京时间归日。
            timeZone = TimeZone.UTC,
            phyVlabEvents = listOf(deadline, start),
        )

        val previousDay = agenda.days.single { it.date == LocalDate(2026, 3, 18) }
        val actualDay = agenda.days.single { it.date == LocalDate(2026, 3, 19) }
        assertEquals(listOf(deadline), previousDay.phyVlabEvents)
        assertEquals(listOf(start), actualDay.phyVlabEvents)
        assertEquals(1, previousDay.eventCount)
        assertEquals(1, actualDay.eventCount)
    }

    @Test
    fun phyVlabJanuaryFirstMidnightDeadlineMovesAcrossTheYearBoundary() {
        val midnight = LocalDateTime(2026, 1, 1, 0, 0)
            .toInstant(TimeZone.of("Asia/Shanghai"))
            .epochSeconds
        val deadline = PhyVlabEvent(
            id = "new-year-deadline",
            title = "元旦截止",
            dateText = "2026年01月01日 00:00",
            dayTimestamp = midnight,
        )

        assertEquals(LocalDate(2025, 12, 31), phyVlabEventDate(deadline, TimeZone.UTC))
        assertEquals(
            LocalDate(2026, 1, 1),
            phyVlabEventDate(deadline.copy(kind = PhyVlabEventKind.START), TimeZone.UTC),
        )
    }

    private fun homework(
        title: String = "作业",
        openDate: String = "2026-07-29 08:00",
        endTime: String = "2026-07-30 12:00",
        subStatus: String = "未提交",
    ) = Homework(
        upId = title.hashCode(),
        idSnId = null,
        score = "",
        userId = 1,
        courseId = 1,
        courseName = "程序设计",
        title = title,
        content = "",
        createDate = "",
        endTime = endTime,
        openDate = openDate,
        status = 0,
        submitCount = 0,
        allCount = 1,
        subStatus = subStatus,
        scoreId = 0,
        homeworkType = 0,
    )

    private fun exam(timeAndPlace: String) = ExamSchedule(
        examType = "期末考试",
        courseName = "高等数学",
        examTimeAndPlace = timeAndPlace,
        examStatus = "正常",
        detail = "",
    )
}

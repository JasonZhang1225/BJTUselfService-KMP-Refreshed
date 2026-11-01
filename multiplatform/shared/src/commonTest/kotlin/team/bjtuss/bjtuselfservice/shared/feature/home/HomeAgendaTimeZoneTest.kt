package team.bjtuss.bjtuselfservice.shared.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone

class HomeAgendaTimeZoneTest {
    private val monday = LocalDate(2026, 11, 2)
    private val sunday = LocalDate(2026, 11, 1)
    private val newYork = TimeZone.of("America/New_York")

    // 北京时间周一第 1 节（08:00）和第 6 节（19:00）。
    private val morning = Course(1, "c1", "早课", "教师", 0 * 8 + 1, "第1-16周", "思源楼101", false)
    private val evening = Course(2, "c2", "晚课", "教师", 5 * 8 + 1, "第1-16周", "思源楼102", false)
    private val schedule = HomeSchedulePresentation(courses = listOf(morning, evening), currentWeek = 7, today = monday)

    @Test fun beijingPhoneKeepsCoursesOnTheirBeijingDay() {
        assertEquals(listOf("早课", "晚课"),
            homeCourseOccurrencesOnLocalDate(schedule, monday, SchoolTimeZone).map { it.course.courseName })
        assertEquals(emptyList(), homeCourseOccurrencesOnLocalDate(schedule, sunday, SchoolTimeZone))
    }

    @Test fun otherTimeZonesPlaceEachClassOnTheLocalDayItStarts() {
        // 北京周一 08:00 = 纽约周日 19:00；北京周一 19:00 = 纽约周一 06:00。
        val sundayLocal = homeCourseOccurrencesOnLocalDate(schedule, sunday, newYork)
        assertEquals(listOf("早课"), sundayLocal.map { it.course.courseName })
        assertEquals(monday, sundayLocal.single().schoolDate)
        assertEquals(listOf("晚课"), homeCourseOccurrencesOnLocalDate(schedule, monday, newYork).map { it.course.courseName })
    }
    @Test fun fallbackWeekUsesBeijingAnchorWhileInitialPageContainsLocalToday() {
        val previousMonday = LocalDate(2026, 10, 26)
        val slots = homeAgendaWeekSlots(7, sunday, emptyList(), schoolToday = monday)
        assertEquals(monday, slots.single { it.teachingWeek == 7 }.startDate)
        assertEquals(previousMonday, homeAgendaTodaySlot(slots, sunday).startDate)
        assertEquals(6, homeAgendaTodaySlot(slots, sunday).teachingWeek)
        // 只在第 7 周上课，避免测试被 1..16 周范围掩盖错误。
        val seventhWeekOnly = schedule.copy(courses = listOf(morning.copy(courseTime = "第7周")))
        assertEquals(7, homeTeachingWeekOn(seventhWeekOnly, monday))
        assertEquals(listOf("早课"), homeCourseOccurrencesOnLocalDate(seventhWeekOnly, sunday, newYork).map { it.course.courseName })
    }

    @Test fun calendarWeekAlsoOpensOnLocalTodayAndKeepsNextSchoolWeekClass() {
        val weeks = listOf(
            team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate(6, "10/26", "11/1", LocalDate(2026, 10, 26)),
            team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate(7, "11/2", "11/8", monday),
        )
        val slot = homeAgendaTodaySlot(homeAgendaWeekSlots(7, sunday, weeks, monday), sunday)
        assertEquals(6, slot.teachingWeek)
        val agenda = team.bjtuss.bjtuselfservice.shared.domain.home.buildHomeAgenda(
            homework = emptyList(), exams = emptyList(), today = sunday,
            now = kotlinx.datetime.LocalDateTime(2026, 11, 1, 19, 0), timeZone = newYork,
            weekStartDate = slot.startDate,
        ).withCourses(schedule.copy(academicWeeks = weeks), newYork)
        assertEquals(listOf("早课"), agenda.days.single { it.date == sunday }.courses.map { it.courseName })
    }

    @Test fun deviceAheadOfBeijingUsesTheNextLocalWeek() {
        val localMonday = LocalDate(2026, 11, 2)
        val schoolSunday = LocalDate(2026, 11, 1)
        val slots = homeAgendaWeekSlots(6, localMonday, emptyList(), schoolSunday)
        assertEquals(7, homeAgendaTodaySlot(slots, localMonday).teachingWeek)
        assertEquals(localMonday, homeAgendaTodaySlot(slots, localMonday).startDate)
    }

}

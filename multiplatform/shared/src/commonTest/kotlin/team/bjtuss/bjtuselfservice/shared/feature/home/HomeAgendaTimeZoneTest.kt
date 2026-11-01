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
}

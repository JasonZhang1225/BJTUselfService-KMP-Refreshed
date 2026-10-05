package team.bjtuss.bjtuselfservice.shared.feature.home

import kotlinx.datetime.LocalDate
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate

class HomeScheduleTest {
    private fun course(name: String, index: Int, weeks: String = "第1-5周", selection: Boolean = false) =
        Course(courseId = name, courseName = name, courseTeacher = "教师", courseLocationIndex = index, courseTime = weeks, coursePlace = "教室", isCurrentSemester = selection)
    @Test fun officialTeachingWeeksExcludeHolidayAndSelectionScheduleAndSortPeriods() {
        val schedule = HomeSchedulePresentation(
            courses = listOf(course("下午", 25), course("上午", 1), course("下学期", 1, selection = true), course("别的星期", 2)),
            academicWeeks = listOf(OccupancyWeekDate(3, "9/21", "9/27", LocalDate(2026, 9, 21)), OccupancyWeekDate(4, "10/12", "10/18", LocalDate(2026, 10, 12))),
        )
        assertEquals(listOf("上午", "下午"), homeCoursesOnDate(schedule, LocalDate(2026, 10, 12)).map { it.courseName })
        assertTrue(homeCoursesOnDate(schedule, LocalDate(2026, 10, 5)).isEmpty())
    }
    @Test fun unsynchronizedWeekDoesNotShowAllCourses() {
        assertTrue(homeCoursesOnDate(HomeSchedulePresentation(courses = listOf(course("课程", 1))), LocalDate(2026, 10, 12)).isEmpty())
    }
}

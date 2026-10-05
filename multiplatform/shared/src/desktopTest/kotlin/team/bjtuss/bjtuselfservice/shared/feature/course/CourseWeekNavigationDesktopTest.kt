package team.bjtuss.bjtuselfservice.shared.feature.course

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class CourseWeekNavigationDesktopTest {
    @Test
    fun transitionsFollowCalendarAcrossNonTeachingWeeksInBothDirections() {
        val pages = listOf(
            CourseScheduleWeekPage(isOverview = true),
            CourseScheduleWeekPage(startDate = LocalDate(2026, 8, 31)),
            CourseScheduleWeekPage(teachingWeek = 3, startDate = LocalDate(2026, 9, 21)),
            CourseScheduleWeekPage(startDate = LocalDate(2026, 9, 28)),
            CourseScheduleWeekPage(startDate = LocalDate(2026, 10, 5)),
            CourseScheduleWeekPage(teachingWeek = 4, startDate = LocalDate(2026, 10, 12)),
            CourseScheduleWeekPage(startDate = LocalDate(2026, 10, 19)),
        )
        for (initial in pages.indices) {
            for (target in pages.indices) {
                assertEquals(
                    target > initial,
                    courseWeekTransitionMovesForward(pages[initial], pages[target]),
                    "Page $initial → $target",
                )
            }
        }
    }

    @Test
    fun transitionsUseTeachingWeekOrderWhenCalendarIsUnavailable() {
        val overview = CourseScheduleWeekPage(teachingWeek = 0, isOverview = true)
        val third = CourseScheduleWeekPage(teachingWeek = 3)
        val fourth = CourseScheduleWeekPage(teachingWeek = 4)
        assertTrue(courseWeekTransitionMovesForward(overview, third))
        assertFalse(courseWeekTransitionMovesForward(third, overview))
        assertTrue(courseWeekTransitionMovesForward(third, fourth))
        assertFalse(courseWeekTransitionMovesForward(fourth, third))
    }

    @Test
    fun appKitContentDeltaUsesOppositePageDirection() {
        assertEquals(CourseWeekScrollDirection.PREVIOUS, nativeTrackpadDirection(1))
        assertEquals(CourseWeekScrollDirection.NEXT, nativeTrackpadDirection(-1))
    }
}

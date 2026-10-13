package team.bjtuss.bjtuselfservice.shared.feature.widget

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseWidgetPresentationTest {
    private fun session(name: String, start: Int, end: Int) = CourseWidgetSession(name, "教室", "教师", start, end, color = 0xC9558A)
    private val date = LocalDate(2026, 10, 14)
    private val morning = session("第一节", 480, 590)
    private val later = session("第二节", 610, 720)
    private fun snapshot(vararg days: CourseWidgetDay) = CourseWidgetSnapshot(generatedAt = 0, days = days.toList())
    private fun day(key: String, vararg sessions: CourseWidgetSession) = CourseWidgetDay(key, 5, sessions.toList())

    @Test fun endsAreExclusiveAndStartsAreInclusive() {
        val snapshot = snapshot(day(date.toString(), later, morning))
        assertEquals(listOf(morning, later), courseWidgetPresentation(snapshot, date, 480)!!.remainingToday)
        assertEquals(listOf(later), courseWidgetPresentation(snapshot, date, 590)!!.remainingToday)
        assertTrue(courseWidgetPresentation(snapshot, date, 720)!!.remainingToday.isEmpty())
    }

    @Test fun finishedDayAdvancesAcrossEmptyDaysAndUsesNextDaysWeek() {
        val snapshot = snapshot(day("2026-10-19", later).copy(week = 6), day("2026-10-14", morning), day("2026-10-15"))
        val model = courseWidgetPresentation(snapshot, date, 600)!!
        assertEquals("2026-10-19", model.displayDay.date)
        assertEquals(6, model.displayDay.week)
        assertEquals(later, model.upcomingDay!!.sessions.single())
        assertFalse(model.isRestPeriod)
    }

    @Test fun emptyWeekDoesNotPresentFarFutureClassesAsUpcoming() {
        val model = courseWidgetPresentation(snapshot(day("2026-10-14"), day("2026-10-20", morning)), date, 0)!!
        assertTrue(model.isRestPeriod)
        assertNull(model.upcomingDay)
        assertEquals((12..18).map { "2026-10-$it" }, model.week.map { it.date })
    }

    @Test fun sundayStillBelongsToTheWeekStartingMonday() {
        val sunday = LocalDate(2026, 10, 18)
        val model = courseWidgetPresentation(snapshot(day(sunday.toString())), sunday, 0)!!
        assertEquals("2026-10-12", model.week.first().date)
        assertEquals(sunday.toString(), model.week.last().date)
    }

    @Test fun emptyTodayIsValidButMissingTodayIsStale() {
        val snapshot = snapshot(day(date.toString()))
        assertTrue(courseWidgetPresentation(snapshot, date, 0)!!.today.sessions.isEmpty())
        assertNull(courseWidgetPresentation(snapshot, LocalDate(2026, 11, 1), 0))
    }

    @Test fun decoderRejectsBrokenUnknownAndInvalidSnapshots() {
        val valid = snapshot(day(date.toString(), morning))
        assertEquals(valid, decodeCourseWidgetSnapshot(valid.encode()))
        assertNull(decodeCourseWidgetSnapshot("{"))
        assertNull(decodeCourseWidgetSnapshot(valid.copy(version = 99).encode()))
        assertNull(decodeCourseWidgetSnapshot(snapshot(day("2026-02-30", morning)).encode()))
        assertNull(decodeCourseWidgetSnapshot(snapshot(day(date.toString(), morning.copy(endMinute = 400))).encode()))
        assertNull(decodeCourseWidgetSnapshot(snapshot(day(date.toString()), day(date.toString())).encode()))
        assertEquals(valid, decodeCourseWidgetSnapshot(valid.encode().dropLast(1) + ",\"futureField\":true}"))
    }
}

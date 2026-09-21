package team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AcademicWeekSlotTest {
    @Test
    fun insertsEveryNaturalWeekBetweenTeachingWeeks() {
        val slots = academicWeekSlots(
            weeks = listOf(
                week(3, LocalDate(2026, 9, 21)),
                week(4, LocalDate(2026, 10, 12)),
            ),
            maxTeachingWeek = 30,
        )

        assertEquals(
            listOf(3, null, null, 4),
            slots.map(AcademicWeekSlot::teachingWeek),
        )
        assertEquals(
            listOf(
                LocalDate(2026, 9, 21),
                LocalDate(2026, 9, 28),
                LocalDate(2026, 10, 5),
                LocalDate(2026, 10, 12),
            ),
            slots.map(AcademicWeekSlot::startDate),
        )
    }

    private fun week(number: Int, start: LocalDate) = OccupancyWeekDate(
        week = number,
        startMonthDay = "${start.month.ordinal + 1}/${start.day}",
        endMonthDay = "",
        startDate = start,
    )
}

package team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.ClassroomOccupancy
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyKind
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.currentFreeRoomNames
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.currentFreeSummary
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.currentOccupancyPeriod
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.currentOrNextOccupancyPeriod

class ClassroomOccupancyNowTest {
    @Test
    fun periodFollowsTheOfficialSlotRangesAndStopsAtTheBoundary() {
        assertEquals(1, currentOccupancyPeriod(8 * 60))
        assertEquals(1, currentOccupancyPeriod(9 * 60 + 49))
        assertNull(currentOccupancyPeriod(9 * 60 + 50))
        assertEquals(2, currentOccupancyPeriod(10 * 60 + 10))
        assertNull(currentOccupancyPeriod(12 * 60))
        assertEquals(3, currentOccupancyPeriod(12 * 60 + 10))
        assertEquals(7, currentOccupancyPeriod(21 * 60))
        assertEquals(7, currentOccupancyPeriod(21 * 60 + 49))
        assertNull(currentOccupancyPeriod(21 * 60 + 50))
        assertNull(currentOccupancyPeriod(22 * 60 + 57))
    }

    @Test fun breaksHighlightTheNextPeriodIncludingDinner() {
        for ((minute, period) in listOf(0 to 1, 479 to 1, 480 to 1, 590 to 2,
            609 to 2, 610 to 2, 720 to 3, 840 to 4, 960 to 5, 1090 to 6,
            1139 to 6, 1140 to 6, 1250 to 7, 1260 to 7, 1309 to 7)) {
            assertEquals(period, currentOrNextOccupancyPeriod(minute), "minute=$minute")
        }
        for (minute in listOf(-1, 1310, 1439, 1440)) assertNull(currentOrNextOccupancyPeriod(minute))
        assertEquals("下一节第6节 · 空闲 SY101", currentFreeSummary(6, listOf("SY101"), upcoming = true))
    }

    @Test
    fun freeSummaryNamesAFewRoomsAndCountsTheRest() {
        val rooms = listOf(
            room("SY101", OccupancyKind.SCHEDULED),
            room("SY102", OccupancyKind.FREE),
            room("SY104", OccupancyKind.FREE),
            room("SY201", OccupancyKind.EXAM),
            room("SY301", OccupancyKind.FREE),
            room("SY401", OccupancyKind.FREE),
            room("SY501", OccupancyKind.FREE),
        )
        assertEquals(listOf("SY102", "SY104", "SY301", "SY401", "SY501"), currentFreeRoomNames(rooms, 3, 1))
        assertEquals("当前第1节 · 空闲 5 间", currentFreeSummary(1, currentFreeRoomNames(rooms, 3, 1)))
        assertEquals(
            "当前第1节 · 空闲 SY102、SY104",
            currentFreeSummary(1, listOf("SY102", "SY104")),
        )
        assertEquals("当前第4节 · 没有空闲教室", currentFreeSummary(4, emptyList()))
    }

    private fun room(name: String, kind: OccupancyKind) = ClassroomOccupancy(
        room = name,
        capacity = 32,
        cells = mapOf((3 to 1) to kind),
    )
}

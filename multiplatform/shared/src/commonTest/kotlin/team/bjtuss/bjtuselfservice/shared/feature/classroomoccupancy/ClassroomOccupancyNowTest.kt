package team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.ClassroomOccupancy
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyKind
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.currentFreeRoomNames
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.currentFreeSummary
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.currentOccupancyPeriod

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

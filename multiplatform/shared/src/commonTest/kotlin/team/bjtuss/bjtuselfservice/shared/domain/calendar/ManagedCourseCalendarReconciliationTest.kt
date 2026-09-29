package team.bjtuss.bjtuselfservice.shared.domain.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ManagedCourseCalendarReconciliationTest {
    @Test
    fun replacementTreatsTheNewSelectionAsTheCompleteManagedSnapshot() {
        val plan = planManagedCourseCalendarReconciliation(
            existingCourseMarkers = listOf(
                managedCourseEventMarker("course-math-1-0-0"),
                managedCourseEventMarker("course-cs-1-1-0"),
                "[BJTU-ID:exam-math-20261010-0]",
                "manual event without app marker",
            ),
            desiredCourseStableIds = listOf(
                "course-math-1-0-0",
                "course-new-1-2-0",
            ),
        )

        assertEquals(
            setOf(
                managedCourseEventMarker("course-math-1-0-0"),
                managedCourseEventMarker("course-cs-1-1-0"),
            ),
            plan.markersToRemove,
        )
        assertEquals(1, plan.insertedEventCount)
        assertEquals(1, plan.updatedEventCount)
        assertFalse(plan.markersToRemove.any { it.startsWith("[BJTU-ID:exam-") })
    }

    @Test
    fun replacementCountsARepeatedManagedCourseAsAnUpdate() {
        val marker = managedCourseEventMarker("course-math-1-0-0")
        val plan = planManagedCourseCalendarReconciliation(
            existingCourseMarkers = listOf(marker, marker),
            desiredCourseStableIds = listOf("course-math-1-0-0"),
        )

        assertEquals(setOf(marker), plan.markersToRemove)
        assertEquals(0, plan.insertedEventCount)
        assertEquals(1, plan.updatedEventCount)
    }

    @Test
    fun onlyCourseMarkersAreRecognized() {
        assertEquals("course-example", managedCourseStableIdFromMarker("[BJTU-ID:course-example]"))
        assertEquals(null, managedCourseStableIdFromMarker("[BJTU-ID:exam-example]"))
        assertEquals(null, managedCourseStableIdFromMarker("[BJTU-ID:course-]"))
    }
}

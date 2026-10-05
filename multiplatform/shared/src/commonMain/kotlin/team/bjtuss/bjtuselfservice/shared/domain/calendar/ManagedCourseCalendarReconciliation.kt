package team.bjtuss.bjtuselfservice.shared.domain.calendar

private const val EVENT_MARKER_PREFIX = "[BJTU-ID:"
private const val COURSE_STABLE_ID_PREFIX = "course-"
private const val EVENT_MARKER_SUFFIX = "]"

/**
 * Result of replacing the app-managed course events in a calendar snapshot.
 * Existing occurrence markers are supplied only for app-managed course events; gateways keep
 * unmarked events and other managed event kinds (such as exams) out of this plan.
 */
data class ManagedCourseCalendarReconciliation(
    val markersToRemove: Set<String>,
    val insertedEventCount: Int,
    val updatedEventCount: Int,
)

fun planManagedCourseCalendarReconciliation(
    existingCourseMarkers: Collection<String>,
    desiredCourseStableIds: Collection<String>,
): ManagedCourseCalendarReconciliation {
    val existingMarkers = existingCourseMarkers
        .filter { it.courseStableIdOrNull() != null }
        .toSet()
    val desiredStableIds = desiredCourseStableIds.toSet()
    val desiredMarkers = desiredStableIds.mapTo(mutableSetOf(), ::managedCourseEventMarker)

    return ManagedCourseCalendarReconciliation(
        markersToRemove = existingMarkers,
        insertedEventCount = (desiredMarkers - existingMarkers).size,
        updatedEventCount = (desiredMarkers intersect existingMarkers).size,
    )
}

fun managedCourseStableIdFromMarker(marker: String): String? = marker.courseStableIdOrNull()

fun managedCourseEventMarker(stableId: String): String = "$EVENT_MARKER_PREFIX$stableId$EVENT_MARKER_SUFFIX"

private fun String.courseStableIdOrNull(): String? {
    if (!startsWith(EVENT_MARKER_PREFIX) || !endsWith(EVENT_MARKER_SUFFIX)) return null
    return substring(EVENT_MARKER_PREFIX.length, length - EVENT_MARKER_SUFFIX.length)
        .takeIf { it.startsWith(COURSE_STABLE_ID_PREFIX) && it.length > COURSE_STABLE_ID_PREFIX.length }
}

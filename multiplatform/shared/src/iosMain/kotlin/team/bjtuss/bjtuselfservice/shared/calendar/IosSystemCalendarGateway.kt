package team.bjtuss.bjtuselfservice.shared.calendar

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.coroutines.resume
import platform.EventKit.EKCalendar
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKRecurrenceEnd
import platform.EventKit.EKRecurrenceFrequency
import platform.EventKit.EKRecurrenceRule
import platform.EventKit.EKSpan
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import team.bjtuss.bjtuselfservice.shared.domain.calendar.AcademicCalendarEvent
import team.bjtuss.bjtuselfservice.shared.domain.calendar.AcademicCalendarEventKind
import team.bjtuss.bjtuselfservice.shared.domain.calendar.managedCourseEventMarker
import team.bjtuss.bjtuselfservice.shared.domain.calendar.managedCourseStableIdFromMarker
import team.bjtuss.bjtuselfservice.shared.domain.calendar.planManagedCourseCalendarReconciliation

private const val EVENT_MARKER_PREFIX = "[BJTU-ID:"
private val BEIJING_TIME_ZONE = TimeZone.of("Asia/Shanghai")

/** iOS EventKit：仅在用户主动点“加入日历”时请求完整日历访问。 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSystemCalendarGateway : SystemCalendarGateway {
    override val isAvailable: Boolean = true

    override suspend fun install(calendars: List<SystemCalendarBatch>): SystemCalendarInstallResult {
        if (calendars.isEmpty() || calendars.all { it.events.isEmpty() && it.managedCourseRange == null }) {
            return SystemCalendarInstallResult.Failed(SystemCalendarFailure.UNAVAILABLE)
        }
        val store = EKEventStore()
        val granted = requestCalendarAccess(store)
        if (!granted) return SystemCalendarInstallResult.Failed(SystemCalendarFailure.PERMISSION_DENIED)
        return runCatching { installAuthorized(store, calendars) }
            .getOrElse { SystemCalendarInstallResult.Failed(SystemCalendarFailure.IO) }
    }

    private suspend fun requestCalendarAccess(store: EKEventStore): Boolean =
        suspendCancellableCoroutine { continuation ->
            val completion: (Boolean, platform.Foundation.NSError?) -> Unit = { granted, _ ->
                if (continuation.isActive) continuation.resume(granted)
            }
            val majorVersion = NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }
            if (majorVersion >= 17) {
                store.requestFullAccessToEventsWithCompletion(completion)
            } else {
                @Suppress("DEPRECATION")
                store.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent, completion)
            }
        }

    private fun installAuthorized(
        store: EKEventStore,
        batches: List<SystemCalendarBatch>,
    ): SystemCalendarInstallResult {
        var calendarCount = 0
        var inserted = 0
        var updated = 0
        batches.filter { it.events.isNotEmpty() || it.managedCourseRange != null }.forEach { batch ->
            val calendar = findOrCreateCalendar(store, batch.name) ?: return SystemCalendarInstallResult.Failed(
                SystemCalendarFailure.IO,
            )
            calendarCount += 1
            val existingOtherEvents = if (batch.managedCourseRange == null) {
                existingManagedEvents(store, calendar, batch.events)
            } else {
                emptyMap()
            }

            batch.managedCourseRange?.let { range ->
                val existingCourses = existingManagedCourseEvents(
                    store = store,
                    calendar = calendar,
                    start = range.startLocal.toNSDate(),
                    end = range.endLocal.toNSDate(),
                )
                val desiredCourses = batch.events.filter { it.kind == AcademicCalendarEventKind.COURSE }
                val reconciliation = planManagedCourseCalendarReconciliation(
                    existingCourseMarkers = existingCourses.map(ManagedCourseEvent::marker),
                    desiredCourseStableIds = desiredCourses.map(AcademicCalendarEvent::stableId),
                )
                // Replace every app-managed course series found in this academic term. User events
                // and exam markers are excluded by the marker check in existingManagedCourseEvents.
                val oldSeries = existingCourses
                    .filter { it.marker in reconciliation.markersToRemove }
                    .groupBy(ManagedCourseEvent::seriesKey)
                    .values
                    .mapNotNull { occurrences ->
                        occurrences.minByOrNull {
                            it.event.startDate?.timeIntervalSince1970 ?: Double.POSITIVE_INFINITY
                        }
                    }
                oldSeries.forEach { managed ->
                    if (!store.removeEvent(
                            managed.event,
                            EKSpan.EKSpanFutureEvents,
                            commit = false,
                            error = null,
                        )
                    ) {
                        return SystemCalendarInstallResult.Failed(SystemCalendarFailure.IO)
                    }
                }
                if (oldSeries.isNotEmpty() && !store.commit(null)) {
                    return SystemCalendarInstallResult.Failed(SystemCalendarFailure.IO)
                }
                inserted += reconciliation.insertedEventCount
                updated += reconciliation.updatedEventCount
            }

            batch.events.forEach { draft ->
                val marker = draft.marker()
                val replacingCourseSnapshot =
                    draft.kind == AcademicCalendarEventKind.COURSE && batch.managedCourseRange != null
                val existingEvent = if (replacingCourseSnapshot) null else existingOtherEvents[marker]
                val event = existingEvent ?: EKEvent.eventWithEventStore(store)
                if (!replacingCourseSnapshot) {
                    if (existingEvent != null) updated += 1 else inserted += 1
                }
                event.calendar = calendar
                event.title = draft.title
                event.startDate = draft.startLocal.toNSDate()
                event.endDate = draft.endLocal.toNSDate()
                event.location = draft.location.ifBlank { null }
                event.notes = buildString {
                    append(marker)
                    if (draft.notes.isNotBlank()) {
                        append('\n')
                        append(draft.notes)
                    }
                }
                event.allDay = false
                event.recurrenceRules
                    ?.filterIsInstance<EKRecurrenceRule>()
                    ?.forEach(event::removeRecurrenceRule)
                draft.recurrence?.takeIf { it.occurrenceCount > 1 }?.let { recurrence ->
                    event.addRecurrenceRule(
                        EKRecurrenceRule(
                            recurrenceWithFrequency = EKRecurrenceFrequency.EKRecurrenceFrequencyWeekly,
                            interval = 1L,
                            end = EKRecurrenceEnd.recurrenceEndWithOccurrenceCount(recurrence.occurrenceCount.toULong()),
                        ),
                    )
                }
                val span = if (existingEvent != null && draft.recurrence != null) {
                    EKSpan.EKSpanFutureEvents
                } else {
                    EKSpan.EKSpanThisEvent
                }
                if (!store.saveEvent(event, span, commit = false, error = null)) {
                    return SystemCalendarInstallResult.Failed(SystemCalendarFailure.IO)
                }
            }
            if (!store.commit(null)) return SystemCalendarInstallResult.Failed(SystemCalendarFailure.IO)

            batch.events.forEach { draft ->
                val recurrence = draft.recurrence ?: return@forEach
                val marker = draft.marker()
                val duration = draft.endLocal.toNSDate().timeIntervalSince1970 -
                    draft.startLocal.toNSDate().timeIntervalSince1970
                recurrence.excludedStartLocals.forEach { excludedText ->
                    val excludedStart = excludedText.toNSDate()
                    val queryStart = NSDate.create(
                        timeIntervalSince1970 = excludedStart.timeIntervalSince1970 - 1.0,
                    )
                    val queryEnd = NSDate.create(
                        timeIntervalSince1970 = excludedStart.timeIntervalSince1970 + duration + 1.0,
                    )
                    val predicate = store.predicateForEventsWithStartDate(queryStart, queryEnd, listOf(calendar))
                    val occurrence = store.eventsMatchingPredicate(predicate)
                        .filterIsInstance<EKEvent>()
                        .firstOrNull { event ->
                            val eventStart = event.startDate ?: return@firstOrNull false
                            event.notes?.lineSequence()?.firstOrNull() == marker &&
                                kotlin.math.abs(eventStart.timeIntervalSince1970 - excludedStart.timeIntervalSince1970) < 1.0
                        }
                    if (occurrence != null && !store.removeEvent(
                            occurrence,
                            EKSpan.EKSpanThisEvent,
                            commit = false,
                            error = null,
                        )
                    ) {
                        return SystemCalendarInstallResult.Failed(SystemCalendarFailure.IO)
                    }
                }
            }
        }
        if (!store.commit(null)) return SystemCalendarInstallResult.Failed(SystemCalendarFailure.IO)
        return SystemCalendarInstallResult.Installed(calendarCount, inserted, updated)
    }

    private fun findOrCreateCalendar(store: EKEventStore, name: String): EKCalendar? {
        val existing = store.calendarsForEntityType(EKEntityType.EKEntityTypeEvent)
            .filterIsInstance<EKCalendar>()
            .firstOrNull { it.title == name && it.allowsContentModifications }
        if (existing != null) return existing
        val source = store.defaultCalendarForNewEvents?.source ?: return null
        val calendar = EKCalendar.calendarForEntityType(EKEntityType.EKEntityTypeEvent, store)
        calendar.title = name
        calendar.source = source
        return calendar.takeIf { store.saveCalendar(calendar, commit = true, error = null) }
    }

    private fun existingManagedCourseEvents(
        store: EKEventStore,
        calendar: EKCalendar,
        start: NSDate,
        end: NSDate,
    ): List<ManagedCourseEvent> {
        val predicate = store.predicateForEventsWithStartDate(start, end, listOf(calendar))
        return store.eventsMatchingPredicate(predicate)
            .filterIsInstance<EKEvent>()
            .mapNotNull { event ->
                val marker = event.notes?.lineSequence()?.firstOrNull()
                    ?.takeIf { managedCourseStableIdFromMarker(it) != null }
                    ?: return@mapNotNull null
                ManagedCourseEvent(marker, event)
            }
    }

    private fun existingManagedEvents(
        store: EKEventStore,
        calendar: EKCalendar,
        drafts: List<AcademicCalendarEvent>,
    ): Map<String, EKEvent> {
        val start = drafts.minByOrNull { it.startLocal }?.startLocal?.toNSDate() ?: return emptyMap()
        val latestText = drafts.maxOfOrNull { it.recurrence?.lastEndLocal ?: it.endLocal } ?: return emptyMap()
        val end = NSDate.create(timeIntervalSince1970 = latestText.toNSDate().timeIntervalSince1970 + 1.0)
        val predicate = store.predicateForEventsWithStartDate(start, end, listOf(calendar))
        return store.eventsMatchingPredicate(predicate)
            .filterIsInstance<EKEvent>()
            .mapNotNull { event ->
                val marker = event.notes?.lineSequence()?.firstOrNull()
                    ?.takeIf { it.startsWith(EVENT_MARKER_PREFIX) }
                    ?: return@mapNotNull null
                marker to event
            }
            .groupBy(Pair<String, EKEvent>::first)
            .mapValues { (_, events) ->
                events.minByOrNull { it.second.startDate?.timeIntervalSince1970 ?: Double.POSITIVE_INFINITY }!!.second
            }
    }

    private fun AcademicCalendarEvent.marker(): String = if (kind == AcademicCalendarEventKind.COURSE) {
        managedCourseEventMarker(stableId)
    } else {
        "$EVENT_MARKER_PREFIX$stableId]"
    }

    private fun String.toNSDate(): NSDate {
        val instant = LocalDateTime.parse(this).toInstant(BEIJING_TIME_ZONE)
        return NSDate.create(
            timeIntervalSince1970 = instant.epochSeconds.toDouble() +
                instant.nanosecondsOfSecond / 1_000_000_000.0,
        )
    }
}

private data class ManagedCourseEvent(
    val marker: String,
    val event: EKEvent,
) {
    val seriesKey: String
        get() = event.calendarItemIdentifier
}

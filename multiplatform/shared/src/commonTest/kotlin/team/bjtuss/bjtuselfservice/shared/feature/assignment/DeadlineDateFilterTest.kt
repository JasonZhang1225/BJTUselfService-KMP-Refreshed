package team.bjtuss.bjtuselfservice.shared.feature.assignment

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import team.bjtuss.bjtuselfservice.shared.domain.home.phyVlabEventDate
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind

class DeadlineDateFilterTest {
    @Test fun midnightBelongsToPreviousDayAndUnknownDatesAreExcluded() {
        fun item(key: String, due: Long?) = AggregatedAssignment(
            AssignmentSource.CITEL, key, 1, "Course", due, false)
        val midnight = Instant.parse("2026-10-29T16:00:00Z").epochSeconds
        val items = listOf(item("midnight", midnight), item("later", midnight + 60), item("unknown", null))
        assertEquals(listOf("midnight"), filterAggregateAssignments(
            items, AggregateAssignmentFilters(beforeDate = "2026-10-30"), 0).map { it.key })
        assertEquals(3, filterAggregateAssignments(items, AggregateAssignmentFilters(), 0).size)
    }

    @Test fun filtersKeepExistingConditionsAndCacheCompatibility() {
        val old = Json.decodeFromString<AggregateAssignmentFilters>("""{"hideSubmitted":true}""")
        assertEquals(null, old.beforeDate)
        val selected = old.copy(beforeDate = "2027-01-01")
        assertEquals(selected, Json.decodeFromString<AggregateAssignmentFilters>(Json.encodeToString(selected)))
        val boundary = Instant.parse("2026-12-31T16:00:00Z").epochSeconds
        val item = AggregatedAssignment(AssignmentSource.CITEL, "x", 1, "Course", boundary, false)
        assertEquals(1, filterAggregateAssignments(listOf(item), selected, 0).size)
        assertEquals(0, filterAggregateAssignments(listOf(item.copy(submitted = true)), selected, 0).size)
        assertEquals(0, filterAggregateAssignments(listOf(item), selected.copy(hideExpired = true), boundary).size)
    }

    @Test fun discountMidnightMovesButStartDoesNot() {
        val event = PhyVlabEvent("discount", "Task", "", Instant.parse("2026-10-29T16:00:00Z").epochSeconds,
            "", kind = PhyVlabEventKind.DISCOUNT)
        assertEquals(LocalDate(2026, 10, 29), phyVlabEventDate(event, TimeZone.UTC))
        assertEquals(LocalDate(2026, 10, 30), phyVlabEventDate(event.copy(dayTimestamp = event.dayTimestamp + 1), TimeZone.UTC))
        assertEquals(LocalDate(2026, 10, 30), phyVlabEventDate(event.copy(kind = PhyVlabEventKind.START), TimeZone.UTC))
    }
}

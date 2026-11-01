package team.bjtuss.bjtuselfservice.shared.feature.assignment

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import team.bjtuss.bjtuselfservice.shared.domain.homework.DeadlineScope
import team.bjtuss.bjtuselfservice.shared.domain.homework.deadlineInScope
import team.bjtuss.bjtuselfservice.shared.domain.homework.deadlineScope
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import team.bjtuss.bjtuselfservice.shared.domain.home.phyVlabEventDate
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind

class DeadlineDateFilterTest {
    @Test fun untilDateIncludesThatDayAndMidnightButNotExpiredOrUnknown() {
        fun item(key: String, due: Long?) = AggregatedAssignment(
            AssignmentSource.CITEL, key, 1, "Course", due, false)
        val midnight = Instant.parse("2026-10-29T16:00:00Z").epochSeconds // 北京 10-30 00:00，显示为 10-29 24:00
        val items = listOf(item("expired", midnight - 7200), item("before", midnight - 1), item("midnight", midnight),
            item("after", midnight + 1), item("later", midnight + 60), item("unknown", null))
        val now = midnight - 3600
        val until = AggregateAssignmentFilters().withDeadlineScope(false, LocalDate(2026, 10, 29))
        assertEquals(listOf("before", "midnight"), filterAggregateAssignments(items, until, now).map { it.key })
        assertEquals(listOf("before", "midnight", "after", "later", "unknown"),
            filterAggregateAssignments(items, AggregateAssignmentFilters(hideExpired = true), now).map { it.key })
        assertEquals(6, filterAggregateAssignments(items, AggregateAssignmentFilters(), now).size)
        // 日期已过去时什么也不显示，由界面提示重新选择。
        assertEquals(0, filterAggregateAssignments(items, until, midnight + 3600).size)
    }

    @Test fun deadlineScopeIsExclusiveAndIndependentOfSubmission() {
        val base = AggregateAssignmentFilters(hideSubmitted = true)
        val until = base.withDeadlineScope(false, LocalDate(2026, 11, 8))
        assertEquals(DeadlineScope.UNTIL_DATE, deadlineScope(until.hideExpired, until.until))
        assertTrue(until.hideExpired, "旧版本只认识 hideExpired，读到新数据时退回“未过期”")
        assertTrue(until.hideSubmitted)
        val upcoming = until.withDeadlineScope(true, null)
        assertEquals(DeadlineScope.UPCOMING, deadlineScope(upcoming.hideExpired, upcoming.until))
        assertNull(upcoming.untilDate)
        val all = upcoming.withDeadlineScope(false, null)
        assertEquals(DeadlineScope.ALL, deadlineScope(all.hideExpired, all.until))
        assertTrue(all.hideSubmitted)
        assertTrue(until.active)
        assertFalse(AggregateAssignmentFilters().withDeadlineScope(false, null).active)
    }

    @Test fun dateBoundaryCombinesWithIgnoredGroupsForEverySource() {
        val midnight = Instant.parse("2026-10-29T16:00:00Z").epochSeconds
        val items = aggregateAssignments(
            listOf(completionFixtureHomework().copy(endTime = "2026-10-30 00:00")),
            listOf(completionFixturePhysical().copy(dueTimestamp = midnight)),
            listOf(completionFixtureCitel().copy(dueTime = midnight)), TimeZone.of("Asia/Shanghai"))
        val marks = items.mapNotNull { it.completionKey() }.toSet()
        val filters = AggregateAssignmentFilters(hideSubmitted = true).withDeadlineScope(false, LocalDate(2026, 10, 29))
        val visible = filterAggregateAssignments(items, filters, midnight - 60)
        assertEquals(3, visible.size)
        val (pending, ignored) = partitionIgnoredAssignments(visible, marks, { it.submitted }, { it.completionKey() })
        assertEquals(0, pending.size)
        assertEquals(3, ignored.size)
        assertEquals(0, filterAggregateAssignments(items.map { it.copy(dueTime = midnight + 1) }, filters, midnight - 60).size)
        assertEquals(0, filterAggregateAssignments(items.map { it.copy(submitted = true) }, filters, midnight - 60).size)
        assertEquals(0, filterAggregateAssignments(items, filters.withSource(AssignmentSource.COURSE_PLATFORM,
            AggregateCourseFilter(false)).withSource(AssignmentSource.PHYVLAB, AggregateCourseFilter(false))
            .withSource(AssignmentSource.CITEL, AggregateCourseFilter(false)), midnight - 60).size)
    }

    @Test fun legacySettingsMigrateWithoutLosingAnything() {
        fun load(raw: String) = Json { ignoreUnknownKeys = true }.decodeFromString<AggregateAssignmentFilters>(raw).migrated()
        // 1.8.x 之前：只有两个开关，原样保留。
        val old = load("""{"hideExpired":true,"hideSubmitted":true,"sortOrder":2}""")
        assertEquals(AggregateAssignmentFilters(hideExpired = true, hideSubmitted = true, sortOrder = 2), old)
        assertEquals(DeadlineScope.UPCOMING, deadlineScope(old.hideExpired, old.until))
        // PR #85 的“早于 D（不含当天）”转成“截至 D 前一天（含）”。
        val before = load("""{"hideSubmitted":true,"beforeDate":"2026-11-01"}""")
        assertEquals("2026-10-31", before.untilDate)
        assertNull(before.beforeDate)
        assertTrue(before.hideExpired && before.hideSubmitted)
        // 已有新字段时以新字段为准；坏日期直接丢弃。
        assertEquals("2026-12-01", load("""{"untilDate":"2026-12-01","beforeDate":"2026-11-01"}""").untilDate)
        assertEquals(AggregateAssignmentFilters(), load("""{"beforeDate":"not-a-date"}"""))
        val round = Json.decodeFromString<AggregateAssignmentFilters>(Json.encodeToString(before))
        assertEquals(before, round)
    }

    @Test fun phyVlabCitelAndCoursePlatformShareTheSameRule() {
        val now = Instant.parse("2026-10-29T08:00:00Z").epochSeconds
        val midnight = Instant.parse("2026-10-29T16:00:00Z").epochSeconds
        val filters = AssignmentFilters().withDeadlineScope(false, LocalDate(2026, 10, 29))
        val physical = completionFixturePhysical()
        assertEquals(listOf(1, 2), team.bjtuss.bjtuselfservice.shared.feature.phyvlab.filterPhyVlabActivities(
            listOf(physical.copy(id = 1, dueTimestamp = midnight), physical.copy(id = 2, dueTimestamp = now + 60),
                physical.copy(id = 3, dueTimestamp = midnight + 60), physical.copy(id = 4, dueTimestamp = null)),
            filters, now).map { it.id }.sorted())
        val citel = completionFixtureCitel()
        assertEquals(listOf(1), team.bjtuss.bjtuselfservice.shared.feature.citel.filteredCitelTasks(
            listOf(citel.copy(id = 1, dueTime = midnight), citel.copy(id = 2, dueTime = now - 60)),
            false, filters.hideExpired, null, 0, now, filters.until).map { it.id })
        val local = LocalDateTime(2026, 10, 29, 16, 0)
        assertTrue(deadlineInScope(LocalDateTime(2026, 10, 30, 0, 0), local, true, LocalDate(2026, 10, 29)))
        assertFalse(deadlineInScope(LocalDateTime(2026, 10, 30, 0, 1), local, true, LocalDate(2026, 10, 29)))
        assertFalse(deadlineInScope(null as LocalDateTime?, local, true, LocalDate(2026, 10, 29)))
        assertTrue(deadlineInScope(null as LocalDateTime?, local, true, null))
        // 旧版本只存了 JSON 两个开关时，AssignmentFilters 照样能读。
        assertNull(Json { ignoreUnknownKeys = true }.decodeFromString<AssignmentFilters>("""{"hideExpired":true}""").until)
    }

    @Test fun coursePlatformDeadlineUsesBeijingTimeRegardlessOfDeviceZone() {
        // 学校给的是北京时间文本；默认换算不看手机时区，和日期筛选的归日口径一致。
        val item = aggregateAssignments(listOf(completionFixtureHomework().copy(endTime = "2026-10-30 00:00")),
            emptyList(), emptyList()).single()
        assertEquals(Instant.parse("2026-10-29T16:00:00Z").epochSeconds, item.dueTime)
        val filters = AggregateAssignmentFilters().withDeadlineScope(true, LocalDate(2026, 10, 29))
        assertEquals(1, filterAggregateAssignments(listOf(item), filters, item.dueTime!! - 60).size)
    }

    @Test fun discountMidnightMovesButStartDoesNot() {
        val event = PhyVlabEvent("discount", "Task", "", Instant.parse("2026-10-29T16:00:00Z").epochSeconds,
            "", kind = PhyVlabEventKind.DISCOUNT)
        assertEquals(LocalDate(2026, 10, 29), phyVlabEventDate(event, TimeZone.UTC))
        assertEquals(LocalDate(2026, 10, 30), phyVlabEventDate(event.copy(dayTimestamp = event.dayTimestamp + 1), TimeZone.UTC))
        assertEquals(LocalDate(2026, 10, 30), phyVlabEventDate(event.copy(kind = PhyVlabEventKind.START), TimeZone.UTC))
    }
}

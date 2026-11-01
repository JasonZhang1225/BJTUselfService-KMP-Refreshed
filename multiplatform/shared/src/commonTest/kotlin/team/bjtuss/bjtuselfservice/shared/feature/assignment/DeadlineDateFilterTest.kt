package team.bjtuss.bjtuselfservice.shared.feature.assignment

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import team.bjtuss.bjtuselfservice.shared.domain.home.phyVlabEventDate
import team.bjtuss.bjtuselfservice.shared.domain.homework.DeadlineScope
import team.bjtuss.bjtuselfservice.shared.domain.homework.deadlineScope
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind
import team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone
import team.bjtuss.bjtuselfservice.shared.domain.time.deadlineDisplay
import team.bjtuss.bjtuselfservice.shared.domain.time.endOfDayEpochSeconds
import team.bjtuss.bjtuselfservice.shared.domain.time.epochToLocal

class DeadlineDateFilterTest {
    private val beijingEndOf1029 = endOfDayEpochSeconds(LocalDate(2026, 10, 29), SchoolTimeZone)

    @Test fun untilIncludesThatDayAndMidnightButNotExpiredOrUnknown() {
        fun item(key: String, due: Long?) = AggregatedAssignment(
            AssignmentSource.CITEL, key, 1, "Course", due, false)
        val midnight = Instant.parse("2026-10-29T16:00:00Z").epochSeconds // 北京 10-30 00:00，显示为 10-29 24:00
        assertEquals(midnight, beijingEndOf1029)
        val items = listOf(item("expired", midnight - 7200), item("before", midnight - 1), item("midnight", midnight),
            item("after", midnight + 1), item("later", midnight + 60), item("unknown", null))
        val now = midnight - 3600
        val until = AggregateAssignmentFilters().withDeadlineScope(false, beijingEndOf1029)
        assertEquals(listOf("before", "midnight"), filterAggregateAssignments(items, until, now).map { it.key })
        assertEquals(listOf("before", "midnight", "after", "later", "unknown"),
            filterAggregateAssignments(items, AggregateAssignmentFilters(hideExpired = true), now).map { it.key })
        assertEquals(6, filterAggregateAssignments(items, AggregateAssignmentFilters(), now).size)
        // 截止点已经过去时什么也不显示，由界面提示重新选择。
        assertEquals(0, filterAggregateAssignments(items, until, midnight + 3600).size)
    }

    @Test fun untilIsAMomentThatReadsDifferentlyInEachTimeZone() {
        // 在北京选“截至 11 月 8 日”，到纽约看是 11 月 8 日 11:00；在纽约选的 11 月 8 日，回北京是 11 月 9 日 13:00。
        val newYork = TimeZone.of("America/New_York")
        val fromBeijing = endOfDayEpochSeconds(LocalDate(2026, 11, 8), SchoolTimeZone)
        assertEquals(deadlineDisplay(epochToLocal(fromBeijing, SchoolTimeZone)).let { "${it.date} ${it.clock}" }, "2026-11-08 24:00")
        assertEquals(deadlineDisplay(epochToLocal(fromBeijing, newYork)).let { "${it.date} ${it.clock}" }, "2026-11-08 11:00")
        val fromNewYork = endOfDayEpochSeconds(LocalDate(2026, 11, 8), newYork)
        assertEquals(deadlineDisplay(epochToLocal(fromNewYork, SchoolTimeZone)).let { "${it.date} ${it.clock}" }, "2026-11-09 13:00")
        assertEquals(deadlineDisplay(epochToLocal(fromNewYork, newYork)).let { "${it.date} ${it.clock}" }, "2026-11-08 24:00")
    }

    @Test fun deadlineScopeIsExclusiveAndIndependentOfSubmission() {
        val base = AggregateAssignmentFilters(hideSubmitted = true)
        val until = base.withDeadlineScope(false, beijingEndOf1029)
        assertEquals(DeadlineScope.UNTIL_DATE, deadlineScope(until.hideExpired, until.untilEpoch))
        assertTrue(until.hideExpired, "旧版本只认识 hideExpired，读到新数据时退回“未过期”")
        assertTrue(until.hideSubmitted)
        val upcoming = until.withDeadlineScope(true, null)
        assertEquals(DeadlineScope.UPCOMING, deadlineScope(upcoming.hideExpired, upcoming.untilEpoch))
        assertNull(upcoming.untilEpoch)
        val all = upcoming.withDeadlineScope(false, null)
        assertEquals(DeadlineScope.ALL, deadlineScope(all.hideExpired, all.untilEpoch))
        assertTrue(all.hideSubmitted)
        assertTrue(until.active)
        assertFalse(AggregateAssignmentFilters().withDeadlineScope(false, null).active)
    }

    @Test fun dateBoundaryCombinesWithIgnoredGroupsForEverySource() {
        val midnight = Instant.parse("2026-10-29T16:00:00Z").epochSeconds
        val items = aggregateAssignments(
            listOf(completionFixtureHomework().copy(endTime = "2026-10-30 00:00")),
            listOf(completionFixturePhysical().copy(dueTimestamp = midnight)),
            listOf(completionFixtureCitel().copy(dueTime = midnight)))
        val marks = items.mapNotNull { it.completionKey() }.toSet()
        val filters = AggregateAssignmentFilters(hideSubmitted = true).withDeadlineScope(false, beijingEndOf1029)
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
        // 最早只有两个开关，原样保留。
        val old = load("""{"hideExpired":true,"hideSubmitted":true,"sortOrder":2}""")
        assertEquals(AggregateAssignmentFilters(hideExpired = true, hideSubmitted = true, sortOrder = 2), old)
        assertEquals(DeadlineScope.UPCOMING, deadlineScope(old.hideExpired, old.untilEpoch))
        // PR #85 的“早于 D（不含当天）”= D 的北京时间 0 点。
        val before = load("""{"hideSubmitted":true,"beforeDate":"2026-10-30"}""")
        assertEquals(beijingEndOf1029, before.untilEpoch)
        assertNull(before.beforeDate)
        assertTrue(before.hideExpired && before.hideSubmitted)
        // 上一版的“截至 D（含）”= D 的北京时间 24:00。
        val until = load("""{"untilDate":"2026-10-29"}""")
        assertEquals(beijingEndOf1029, until.untilEpoch)
        assertNull(until.untilDate)
        // 已有截止点时以它为准；坏日期直接丢弃。
        assertEquals(42L, load("""{"untilEpoch":42,"untilDate":"2026-12-01","beforeDate":"2026-11-01"}""").untilEpoch)
        assertEquals(AggregateAssignmentFilters(), load("""{"beforeDate":"not-a-date"}"""))
        assertEquals(before, Json.decodeFromString<AggregateAssignmentFilters>(Json.encodeToString(before)))
        // 物理在线 / CITEL 独立页同样迁移。
        val standalone = Json { ignoreUnknownKeys = true }.decodeFromString<AssignmentFilters>("""{"untilDate":"2026-10-29"}""").migrated()
        assertEquals(beijingEndOf1029, standalone.untilEpoch)
        assertNull(Json { ignoreUnknownKeys = true }.decodeFromString<AssignmentFilters>("""{"hideExpired":true}""").migrated().untilEpoch)
    }

    @Test fun phyVlabCitelAndCoursePlatformShareTheSameRule() {
        val now = Instant.parse("2026-10-29T08:00:00Z").epochSeconds
        val midnight = Instant.parse("2026-10-29T16:00:00Z").epochSeconds
        val filters = AssignmentFilters().withDeadlineScope(false, beijingEndOf1029)
        val physical = completionFixturePhysical()
        assertEquals(listOf(1, 2), team.bjtuss.bjtuselfservice.shared.feature.phyvlab.filterPhyVlabActivities(
            listOf(physical.copy(id = 1, dueTimestamp = midnight), physical.copy(id = 2, dueTimestamp = now + 60),
                physical.copy(id = 3, dueTimestamp = midnight + 60), physical.copy(id = 4, dueTimestamp = null)),
            filters, now).map { it.id }.sorted())
        val citel = completionFixtureCitel()
        assertEquals(listOf(1), team.bjtuss.bjtuselfservice.shared.feature.citel.filteredCitelTasks(
            listOf(citel.copy(id = 1, dueTime = midnight), citel.copy(id = 2, dueTime = now - 60)),
            false, filters.hideExpired, null, 0, now, filters.untilEpoch).map { it.id })
    }

    @Test fun coursePlatformDeadlineIsParsedAsBeijingTime() {
        val item = aggregateAssignments(listOf(completionFixtureHomework().copy(endTime = "2026-10-30 00:00")),
            emptyList(), emptyList()).single()
        assertEquals(Instant.parse("2026-10-29T16:00:00Z").epochSeconds, item.dueTime)
    }

    @Test fun calendarDayMarkersStayOnBeijingDayButRealDeadlinesFollowThePhone() {
        val beijingMidnight = Instant.parse("2026-10-29T16:00:00Z").epochSeconds
        // Moodle 日历格子：北京时间零点只标记“哪一天”，换时区也不挪。
        val marker = PhyVlabEvent("discount", "Task", "", beijingMidnight, "", kind = PhyVlabEventKind.DISCOUNT)
        assertEquals(LocalDate(2026, 10, 29), phyVlabEventDate(marker, TimeZone.UTC))
        assertEquals(LocalDate(2026, 10, 30), phyVlabEventDate(marker.copy(dayTimestamp = beijingMidnight + 1), TimeZone.UTC))
        assertEquals(LocalDate(2026, 10, 30), phyVlabEventDate(marker.copy(kind = PhyVlabEventKind.START), TimeZone.UTC))
        // 由 CITEL 截止换算的事件带真实时刻：北京零点 = UTC 10-29 16:00，归到 UTC 的 10-29。
        val citelDue = PhyVlabEvent("citel-due-1", "Task", "", beijingMidnight, "")
        assertEquals(LocalDate(2026, 10, 29), phyVlabEventDate(citelDue, SchoolTimeZone))
        assertEquals(LocalDate(2026, 10, 29), phyVlabEventDate(citelDue, TimeZone.UTC))
        assertEquals(LocalDate(2026, 10, 29), phyVlabEventDate(citelDue, TimeZone.of("America/New_York")))
    }
}

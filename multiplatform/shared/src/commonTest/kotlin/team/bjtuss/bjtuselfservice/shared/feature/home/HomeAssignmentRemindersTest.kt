package team.bjtuss.bjtuselfservice.shared.feature.home

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeAgenda
import team.bjtuss.bjtuselfservice.shared.domain.home.buildHomeAgenda
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.feature.citel.*

internal class HomeReminderFixture {
    val now = LocalDateTime(2026, 10, 30, 10, 0)
    val zone = TimeZone.UTC
    val seconds = now.toInstant(zone).epochSeconds
    val homework = completionFixtureHomework().copy(title = "示例阅读", endTime = "2026-10-31 10:00")
    val physical = completionFixturePhysical().copy(title = "示例实验准备",
        activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=20", dueTimestamp = seconds + 7200)
    val citel = completionFixtureCitel().copy(title = "示例报告", url = "$CITEL_BASE/lab/20",
        openTime = seconds - 3600, discountTime = seconds + 1800, dueTime = seconds + 3600)
    val physicalDeadline = PhyVlabEvent("activity-${physical.courseId}-${physical.id}-due",
        physical.title, "", physical.dueTimestamp!!, physical.activityUrl)
    val events = listOf(physicalDeadline,
        physicalDeadline.copy(id = "activity-10-20-start", kind = PhyVlabEventKind.START)) + citelAgendaEvents(listOf(citel))
    val assignments = aggregateAssignments(listOf(homework), listOf(physical), listOf(citel), zone)
    val keys = assignments.mapNotNull { it.completionKey() }.toSet()
    fun agenda(homework: List<Homework> = listOf(this.homework), events: List<PhyVlabEvent> = this.events): HomeAgenda =
        buildHomeAgenda(homework, emptyList(), now.date, now, zone, events)
}

class HomeAssignmentRemindersTest {
    private val f = HomeReminderFixture()
    private fun HomeAgenda.count() = dueSoonHomework.size + dueSoonEvents.size

    @Test fun threeSourcesHideAndRestoreIndependentlyWithoutChangingAgendaDays() {
        val original = f.agenda()
        assertEquals(3, original.count())
        for (key in f.keys) {
            val marked = filterHomeAssignmentReminders(original, f.assignments, setOf(key))
            assertEquals(2, marked.count(), key.source.name)
            assertSame(original.days, marked.days, "周日程和其中的开始、折扣、截止事项保持原样")
            assertSame(original, filterHomeAssignmentReminders(original, f.assignments, emptySet()))
        }
        val allDone = filterHomeAssignmentReminders(original, f.assignments, f.keys)
        assertEquals(0, allDone.count())
        assertSame(original.days, allDone.days)
        assertTrue(f.assignments.none { it.submitted }, "不改变学校提交状态")
    }

    @Test fun restoringMarksNeverReintroducesSubmittedExpiredFarOrUndatedHomework() {
        val homework = listOf(f.homework,
            f.homework.copy(upId = 21, subStatus = "已提交"),
            f.homework.copy(upId = 22, endTime = "2026-10-30 09:00"),
            f.homework.copy(upId = 23, endTime = "2026-11-03 10:00"),
            f.homework.copy(upId = 24, endTime = ""))
        val events = f.events + listOf(
            f.physicalDeadline.copy(id = "submitted", submitted = true),
            f.physicalDeadline.copy(id = "past", dayTimestamp = f.seconds - 1),
            f.physicalDeadline.copy(id = "far", dayTimestamp = f.seconds + 72 * 3600))
        val original = f.agenda(homework, events)
        assertEquals(3, original.count())
        assertEquals(0, filterHomeAssignmentReminders(original, f.assignments, f.keys).count())
        val restored = filterHomeAssignmentReminders(original, f.assignments, emptySet())
        assertEquals(listOf(f.homework), restored.dueSoonHomework)
        assertEquals(original, restored)
    }

    @Test fun existingTimeWindowAndOrderArePreservedAtItsBoundaries() {
        val events = listOf(-1L, 0L, 48 * 3600L, 49 * 3600L).mapIndexed { index, seconds ->
            PhyVlabEvent("boundary-$index", "示例边界", "", f.seconds + seconds)
        }
        val original = f.agenda(events = events)
        assertEquals(listOf("boundary-1", "boundary-2"), original.dueSoonEvents.map { it.id })
        val result = filterHomeAssignmentReminders(original, f.assignments, setOf(f.homework.completionKey()))
        assertEquals(original.dueSoonEvents, result.dueSoonEvents)
        assertSame(original.days, result.days)
    }

    @Test fun coursePlatformAndDerivedPhysicsEventsUseFullSourceAndCourseIdentity() {
        val otherCourse = f.homework.copy(courseId = 11)
        val otherPhysics = f.physicalDeadline.copy(id = "activity-11-20-due")
        val original = f.agenda(listOf(f.homework, otherCourse), listOf(f.physicalDeadline, otherPhysics))
        val result = filterHomeAssignmentReminders(original, f.assignments,
            setOf(f.homework.completionKey(), f.physical.completionKey()))
        assertEquals(listOf(otherCourse), result.dueSoonHomework)
        assertEquals(listOf(otherPhysics), result.dueSoonEvents)
        assertEquals(2, result.count())
        val onlyCitel = filterHomeAssignmentReminders(original, f.assignments, setOf(f.citel.completionKey()))
        assertSame(original, onlyCitel, "同 id 的其他来源不受 CITEL 标记影响")
    }

    @Test fun schoolCalendarPhysicsEventsUseAnUnambiguousActivityLink() {
        val linked = f.physicalDeadline.copy(id = "school-calendar-987")
        val sameTitle = linked.copy(id = "unrelated", eventUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=99")
        val original = f.agenda(events = listOf(linked, sameTitle))
        val result = filterHomeAssignmentReminders(original, f.assignments, setOf(f.physical.completionKey()))
        assertEquals(listOf(sameTitle), result.dueSoonEvents)
        val ambiguous = aggregateAssignments(emptyList(), listOf(f.physical, f.physical.copy(courseId = 11)), emptyList())
        assertEquals(original.dueSoonEvents,
            filterHomeAssignmentReminders(original, ambiguous, setOf(f.physical.completionKey())).dueSoonEvents)
    }

    @Test fun citelCourseAndProgrammingIdentitySurviveLegacyEventIdCollisions() {
        val programming = f.citel.copy(programming = true, url = "$CITEL_BASE/programming/20")
        val otherCourse = f.citel.copy(courseId = 11, url = "$CITEL_BASE/course/11/lab/20")
        val tasks = listOf(f.citel, programming, otherCourse)
        val assignments = aggregateAssignments(emptyList(), emptyList(), tasks)
        val events = citelAgendaEvents(tasks)
        val original = f.agenda(homework = emptyList(), events = events)
        assertEquals(3, original.dueSoonEvents.size)
        for (task in tasks) {
            val result = filterHomeAssignmentReminders(original, assignments, setOf(task.completionKey()))
            assertEquals(tasks.filter { it != task }.map { it.url }.toSet(), result.dueSoonEvents.map { it.eventUrl }.toSet())
            assertSame(original.days, result.days)
        }
    }

    @Test fun unknownAndAmbiguousEventsAreKeptInsteadOfMatchingByTitleOrTaskIdAlone() {
        val ambiguousTasks = listOf(f.citel.copy(url = ""), f.citel.copy(programming = true, url = ""))
        val ambiguous = citelAgendaEvents(ambiguousTasks).filter { it.kind == PhyVlabEventKind.DEADLINE }
        val unknown = f.physicalDeadline.copy(id = "unknown", eventUrl = null)
        val original = f.agenda(homework = emptyList(), events = ambiguous + unknown)
        val assignments = aggregateAssignments(emptyList(), listOf(f.physical), ambiguousTasks)
        assertSame(original, filterHomeAssignmentReminders(original, assignments, f.keys))
    }

    @Test fun pageFiltersDoNotAlterHomeRemindersOrTheExistingAssignmentRules() {
        val original = f.agenda()
        val marked = filterHomeAssignmentReminders(original, f.assignments, f.keys)
        for (hideExpired in listOf(false, true)) for (hideSubmitted in listOf(false, true)) {
            val filters = AggregateAssignmentFilters(hideExpired = hideExpired, hideSubmitted = hideSubmitted)
            val (pending, ignored) = partitionIgnoredAssignments(filterAggregateAssignments(f.assignments, filters, f.seconds),
                f.keys, { it.submitted }, { it.completionKey() })
            assertEquals(0, pending.size)
            assertEquals(3, ignored.size)
            assertEquals(0, marked.count(), "首页不依赖作业页的显示全部日期或隐藏选项")
        }
        assertEquals(3, original.count())
    }

    @Test fun citelAcAndSubmittedReportsStayExcludedAfterUndo() {
        val wa = f.citel.copy(programming = true, submitted = true, status = "WA: Wrong Answer")
        val ac = wa.copy(id = 21, status = "AC: Accepted")
        val report = f.citel.copy(id = 22, submitted = true)
        val tasks = listOf(wa, ac, report)
        val assignments = aggregateAssignments(emptyList(), emptyList(), tasks)
        val original = f.agenda(homework = emptyList(), events = citelAgendaEvents(tasks))
        assertEquals(1, original.dueSoonEvents.size)
        assertEquals(0, filterHomeAssignmentReminders(original, assignments, setOf(wa.completionKey())).count())
        assertEquals(original, filterHomeAssignmentReminders(original, assignments, emptySet()))
    }

    @Test fun citelDiscountRemindersHideWithManualCompletionAndRestoreOnUndo() {
        val original = f.agenda()
        assertEquals(listOf("citel-discount-${f.citel.id}"), original.dueSoonDiscountEvents.map { it.id })
        assertTrue(original.dueSoonEvents.any { it.id == "citel-due-${f.citel.id}" })

        val marked = filterHomeAssignmentReminders(original, f.assignments, setOf(f.citel.completionKey()))
        assertTrue(marked.dueSoonDiscountEvents.isEmpty(), "手动完成后折扣提醒应消失")
        assertTrue(marked.dueSoonEvents.none { it.id.startsWith("citel-") }, "截止提醒一并消失")
        assertEquals(listOf(f.homework), marked.dueSoonHomework)
        assertEquals(listOf(f.physicalDeadline), marked.dueSoonEvents)
        assertSame(original.days, marked.days)

        val restored = filterHomeAssignmentReminders(original, f.assignments, emptySet())
        assertEquals(original.dueSoonDiscountEvents, restored.dueSoonDiscountEvents)
        assertEquals(original, restored)

        val onlyOtherSources = filterHomeAssignmentReminders(
            original,
            f.assignments,
            setOf(f.homework.completionKey(), f.physical.completionKey()),
        )
        assertEquals(original.dueSoonDiscountEvents, onlyOtherSources.dueSoonDiscountEvents,
            "其他来源标记不得影响 CITEL 折扣提醒")
        assertEquals(listOf("citel-due-${f.citel.id}"), onlyOtherSources.dueSoonEvents.map { it.id })
    }

    @Test fun discountOnlyWindowStillHidesAndRestoresWithoutADueSoonDeadline() {
        val discountOnly = f.citel.copy(
            discountTime = f.seconds + 3600,
            dueTime = f.seconds + 7 * 86400,
        )
        val assignments = aggregateAssignments(emptyList(), emptyList(), listOf(discountOnly))
        val original = f.agenda(homework = emptyList(), events = citelAgendaEvents(listOf(discountOnly)))
        assertEquals(listOf("citel-discount-${discountOnly.id}"), original.dueSoonDiscountEvents.map { it.id })
        assertTrue(original.dueSoonEvents.isEmpty(), "截止在 48 小时外时折扣列表应独立存在")

        val marked = filterHomeAssignmentReminders(original, assignments, setOf(discountOnly.completionKey()))
        assertTrue(marked.dueSoonDiscountEvents.isEmpty())
        assertTrue(marked.dueSoonEvents.isEmpty())
        assertSame(original.days, marked.days)
        assertEquals(original, filterHomeAssignmentReminders(original, assignments, emptySet()))
    }
}

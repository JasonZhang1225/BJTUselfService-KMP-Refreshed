package team.bjtuss.bjtuselfservice.shared.feature.assignment

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.domain.homework.*
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.feature.citel.*
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkUiState
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.filterPhyVlabActivities

internal fun completionFixtureHomework() = Homework(upId = 20, idSnId = null, score = "", userId = 0,
    courseId = 10, courseName = "示例课程", title = "课后阅读与思考", content = "", createDate = "",
    endTime = "2026-10-30 23:59", openDate = "2026-10-01 00:00", status = 0, submitCount = 0,
    allCount = 30, subStatus = "未提交", scoreId = 0, homeworkType = 0)

internal fun completionFixturePhysical() = PhyVlabActivity(20, 10, "示例物理课程", "线下实验准备", "作业", "",
    dueTimestamp = 200, dueText = "2026-10-30 23:59")

internal fun completionFixtureCitel() = CitelTask(20, 10, "示例程序设计课程", "课后练习", "", programming = false, dueTime = 200)

class AssignmentCompletionFilterTest {
    private val homework = completionFixtureHomework()
    private val physical = completionFixturePhysical()
    private val citel = completionFixtureCitel()
    private val now = LocalDateTime(2026, 10, 29, 12, 0)

    private fun split(items: List<AggregatedAssignment>, marks: Set<AssignmentCompletionKey>) =
        partitionIgnoredAssignments(items, marks, { it.submitted }, { it.completionKey() })

    @Test fun ignoredItemsMoveToTheirOwnSegmentInEveryFilterCombination() {
        for (hideExpired in listOf(false, true)) for (hideSubmitted in listOf(false, true)) {
            for (ignored in listOf(false, true)) for (submitted in listOf(false, true)) {
                val h = homework.copy(subStatus = if (submitted) "已提交" else "未提交")
                val p = physical.copy(completed = submitted)
                val c = citel.copy(submitted = submitted)
                val items = aggregateAssignments(listOf(h), listOf(p), listOf(c), TimeZone.UTC)
                val marks = if (ignored) items.mapNotNull { it.completionKey() }.toSet() else emptySet()
                val context = "expired=$hideExpired submittedFilter=$hideSubmitted ignored=$ignored server=$submitted"
                val filters = AggregateAssignmentFilters(hideExpired = hideExpired, hideSubmitted = hideSubmitted)
                val shown = filterAggregateAssignments(items, filters, 100)
                // 忽略标记不再参与筛选：筛选结果只由学校状态、日期和课程决定。
                assertEquals(if (hideSubmitted && submitted) 0 else 3, shown.size, context)
                val (pending, ignoredItems) = split(shown, marks)
                val expectIgnored = ignored && !submitted
                assertEquals(if (expectIgnored) emptyList() else shown, pending, context)
                assertEquals(if (expectIgnored) shown else emptyList(), ignoredItems, context)

                val homeworkList = HomeworkUiState(homework = listOf(h), now = now,
                    hideExpired = hideExpired, hideSubmitted = hideSubmitted).visibleHomework
                assertEquals(expectIgnored && homeworkList.isNotEmpty(),
                    partitionIgnoredAssignments(homeworkList, marks, ::isHomeworkSubmitted) { it.completionKey() }.second.isNotEmpty(), context)
                val phyList = filterPhyVlabActivities(listOf(p), AssignmentFilters(hideExpired = hideExpired, hideSubmitted = hideSubmitted), 100)
                assertEquals(expectIgnored && phyList.isNotEmpty(),
                    partitionIgnoredAssignments(phyList, marks, { it.completed }) { it.completionKey() }.second.isNotEmpty(), context)
                val citelList = filteredCitelTasks(listOf(c), hideSubmitted, hideExpired, null, 0, 100)
                assertEquals(expectIgnored && citelList.isNotEmpty(),
                    partitionIgnoredAssignments(citelList, marks, { it.completed }) { it.completionKey() }.second.isNotEmpty(), context)
            }
        }
    }

    @Test fun bothSegmentsKeepTheFilteredOrder() {
        val items = aggregateAssignments(listOf(homework, homework.copy(upId = 21, endTime = "2026-10-31 23:59")),
            listOf(physical.copy(dueTimestamp = 150)), listOf(citel.copy(dueTime = 250)), TimeZone.UTC)
        val sorted = filterAggregateAssignments(items, AggregateAssignmentFilters(sortOrder = 1), 100)
        val marks = setOf(sorted[0].completionKey()!!, sorted[2].completionKey()!!)
        val (pending, ignored) = split(sorted, marks)
        assertEquals(listOf(sorted[1], sorted[3]), pending)
        assertEquals(listOf(sorted[0], sorted[2]), ignored)
    }

    @Test fun courseAndDateFiltersStillApplyToIgnoredItems() {
        val items = aggregateAssignments(listOf(homework), listOf(physical), listOf(citel))
        val marks = items.mapNotNull { it.completionKey() }.toSet()
        val filters = AssignmentSource.entries.fold(AggregateAssignmentFilters()) { f, source ->
            f.withSource(source, AggregateCourseFilter(false, setOf(999)))
        }
        assertEquals(emptyList<AggregatedAssignment>() to emptyList(), split(filterAggregateAssignments(items, filters, 100), marks))
        val expired = aggregateAssignments(listOf(homework.copy(endTime = "2026-10-01 00:00")),
            listOf(physical.copy(dueTimestamp = 50)), listOf(citel.copy(dueTime = null)))
        val (_, ignored) = split(filterAggregateAssignments(expired, AggregateAssignmentFilters(hideExpired = true), 2_000_000_000L),
            expired.mapNotNull { it.completionKey() }.toSet())
        // 过期筛选照常排除已忽略的过期项；无截止日期的仍显示在已忽略分组。
        assertEquals(listOf(AssignmentSource.CITEL), ignored.map { it.source })
    }

    @Test fun ignoringNeverChangesServerStatusOrSearchResults() {
        val items = aggregateAssignments(listOf(homework), listOf(physical), listOf(citel))
        assertEquals(items, searchAggregateAssignments(items, "示例", 0))
        assertEquals(listOf(homework), searchHomework(listOf(homework), "课后", HomeworkSortOrder.ORIGINAL))
        assertTrue(items.none { it.submitted })
    }

    @Test fun citelProgrammingCanOnlyBeIgnoredUntilAccepted() {
        val wa = citel.copy(programming = true, submitted = true, status = "WA: Wrong Answer")
        val ac = wa.copy(id = 21, status = "AC: Accepted")
        val marks = setOf(wa.completionKey(), ac.completionKey())
        val (pending, ignored) = partitionIgnoredAssignments(listOf(wa, ac), marks, { it.completed }) { it.completionKey() }
        assertEquals(listOf(ac), pending)
        assertEquals(listOf(wa), ignored)
    }

    @Test fun emptyTitleSeparatesNothingAllIgnoredAndFiltered() {
        assertEquals("暂无作业", assignmentEmptyTitle(hasAny = false, hasIgnored = false, filtered = true))
        assertEquals("没有待处理的作业", assignmentEmptyTitle(hasAny = true, hasIgnored = true, filtered = false))
        assertEquals("当前筛选下没有作业", assignmentEmptyTitle(hasAny = true, hasIgnored = true, filtered = true))
        assertEquals("当前筛选下没有作业", assignmentEmptyTitle(hasAny = true, hasIgnored = false, filtered = false))
    }
}

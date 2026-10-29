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

    @Test fun allFilterCombinationsAgreeAcrossAggregateAndThreeIndependentLists() {
        for (hideExpired in listOf(false, true)) for (hideSubmitted in listOf(false, true)) {
            for (manual in listOf(false, true)) for (submitted in listOf(false, true)) {
                val h = homework.copy(subStatus = if (submitted) "已提交" else "未提交")
                val p = physical.copy(completed = submitted)
                val c = citel.copy(submitted = submitted)
                val items = aggregateAssignments(listOf(h), listOf(p), listOf(c), TimeZone.UTC)
                val marks = if (manual) items.mapNotNull { it.completionKey() }.toSet() else emptySet()
                val expectedVisible = when {
                    !hideSubmitted -> true
                    submitted -> false
                    !hideExpired -> true
                    manual -> false
                    else -> true
                }
                val context = "expired=$hideExpired submittedFilter=$hideSubmitted manual=$manual server=$submitted"
                assertEquals(if (expectedVisible) 3 else 0, filterAggregateAssignments(items,
                    AggregateAssignmentFilters(hideExpired = hideExpired, hideSubmitted = hideSubmitted), 100, marks).size, context)
                assertEquals(expectedVisible, HomeworkUiState(homework = listOf(h), now = now,
                    hideExpired = hideExpired, hideSubmitted = hideSubmitted).visibleHomework(marks).isNotEmpty(), context)
                assertEquals(expectedVisible, filterPhyVlabActivities(listOf(p), AssignmentFilters(
                    hideExpired = hideExpired, hideSubmitted = hideSubmitted), 100, marks).isNotEmpty(), context)
                assertEquals(expectedVisible, filteredCitelTasks(listOf(c), hideSubmitted, hideExpired,
                    null, 0, 100, marks).isNotEmpty(), context)
            }
        }
    }

    @Test fun expiredAndMissingDatesStillFollowTheirExistingRules() {
        val items = aggregateAssignments(listOf(homework.copy(endTime = "2026-10-01 00:00")),
            listOf(physical.copy(dueTimestamp = 50)), listOf(citel.copy(dueTime = null)))
        val marks = items.mapNotNull { it.completionKey() }.toSet()
        assertEquals(3, filterAggregateAssignments(items, AggregateAssignmentFilters(hideSubmitted = true), 100, marks).size)
        val filters = AggregateAssignmentFilters(hideExpired = true)
        assertEquals(2, filterAggregateAssignments(items, filters, 100, marks).size)
        // 使用真实学校时间戳时，课程平台旧日期被日期筛选排除；无日期仍可显示。
        assertTrue(HomeworkUiState(homework = listOf(homework.copy(endTime = "2026-10-01 00:00")),
            now = now, hideExpired = true).visibleHomework(marks).isEmpty())
        assertEquals(listOf(items.last()), filterAggregateAssignments(items,
            filters.copy(hideSubmitted = true), 2_000_000_000L, emptySet()))
    }

    @Test fun courseFiltersAreNeverBypassedByShowAllDates() {
        val items = aggregateAssignments(listOf(homework), listOf(physical), listOf(citel))
        val marks = items.mapNotNull { it.completionKey() }.toSet()
        val filters = AssignmentSource.entries.fold(AggregateAssignmentFilters(hideSubmitted = true)) { f, source ->
            f.withSource(source, AggregateCourseFilter(false, setOf(999)))
        }
        assertTrue(filterAggregateAssignments(items, filters, 100, marks).isEmpty())
        assertTrue(HomeworkUiState(homework = listOf(homework), selectedCourses = setOf("其他课程"), now = now)
            .visibleHomework(marks).isEmpty())
        assertTrue(filterPhyVlabActivities(listOf(physical), AssignmentFilters(courses = setOf(999)), 100, marks).isEmpty())
        assertTrue(filteredCitelTasks(listOf(citel), true, false, 999, 0, 100, marks).isEmpty())
    }

    @Test fun markingDoesNotChangeServerSubmissionOrSearchResultsOrOrdering() {
        val items = aggregateAssignments(listOf(homework), listOf(physical), listOf(citel))
        val marks = items.mapNotNull { it.completionKey() }.toSet()
        val hidden = filterAggregateAssignments(items, AggregateAssignmentFilters(hideExpired = true, hideSubmitted = true), 100, marks)
        assertTrue(hidden.isEmpty())
        assertEquals(items, searchAggregateAssignments(items, "示例", 0))
        assertEquals(listOf(homework), searchHomework(listOf(homework), "课后", HomeworkSortOrder.ORIGINAL))
        assertTrue(items.none { it.submitted })
        val restored = filterAggregateAssignments(items, AggregateAssignmentFilters(hideSubmitted = true), 100, marks)
        assertEquals(filterAggregateAssignments(items, AggregateAssignmentFilters(hideSubmitted = true), 100), restored)
    }

    @Test fun citelProgrammingStillRequiresAcEvenWhenManualMarkIsUndone() {
        val wa = citel.copy(programming = true, submitted = true, status = "WA: Wrong Answer")
        val ac = wa.copy(id = 21, status = "AC: Accepted")
        val marks = setOf(wa.completionKey(), ac.completionKey())
        assertEquals(listOf(wa), filteredCitelTasks(listOf(wa, ac), true, false, null, 0, 100, marks))
        assertTrue(filteredCitelTasks(listOf(wa, ac), true, true, null, 0, 100, marks).isEmpty())
        assertEquals(listOf(wa), filteredCitelTasks(listOf(wa, ac), true, true, null, 0, 100, emptySet()))
    }
}

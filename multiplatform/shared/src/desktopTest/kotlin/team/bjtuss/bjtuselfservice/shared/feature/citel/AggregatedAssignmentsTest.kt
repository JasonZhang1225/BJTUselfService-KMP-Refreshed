package team.bjtuss.bjtuselfservice.shared.feature.citel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.cache.*
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.feature.shell.*
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity

class AggregatedAssignmentsTest {
    @Test fun aggregationReplacesThreeEntrancesAndPreservesTheirSavedBottomBarChoices() {
        val prefs = AppPreferences(citelEnabled = true, bottomNavigationItems = listOf("HOMEWORK", "PHYVLAB", "CITEL"))
        val merged = prefs.copy(aggregateAssignments = true)
        assertEquals(listOf(AppSection.HOME, AppSection.ASSIGNMENTS, AppSection.MORE), bottomNavSections(merged))
        assertTrue(applicationSections(merged).none { it in setOf(AppSection.HOMEWORK, AppSection.PHYVLAB, AppSection.CITEL) })
        assertEquals(listOf(AppSection.HOME, AppSection.HOMEWORK, AppSection.PHYVLAB, AppSection.CITEL, AppSection.MORE), bottomNavSections(merged.copy(aggregateAssignments = false)))
        assertEquals("课程平台作业", AppSection.HOMEWORK.title)
        assertEquals("作业", AppSection.ASSIGNMENTS.title)
        assertTrue(team.bjtuss.bjtuselfservice.shared.isNativeDetailRoute("ASSIGNMENTS"))
    }

    @Test fun allChipCanDeselectAndReselectWhileCourseIdsStayScopedToEachPlatform() {
        val options = setOf(1, 2)
        val none = AggregateCourseFilter().toggleAll(options)
        assertFalse(none.matches(1))
        assertFalse(none.matches(3))
        val all = none.toggleAll(options)
        assertTrue(all.matches(3))
        assertEquals(setOf(2), all.toggleCourse(1, options).courses)
        val items = listOf(
            AggregatedAssignment(AssignmentSource.COURSE_PLATFORM, "course:1", 1, "A", 150, false),
            AggregatedAssignment(AssignmentSource.PHYVLAB, "phy:1", 1, "A", 160, false),
            AggregatedAssignment(AssignmentSource.CITEL, "citel:1", 1, "A", 170, true),
        )
        val filters = AggregateAssignmentFilters().withSource(AssignmentSource.PHYVLAB, none)
        assertEquals(listOf("course:1", "citel:1"), filterAggregateAssignments(items, filters, 100).map { it.key })
        assertEquals(listOf("course:1"), filterAggregateAssignments(items, filters.copy(hideSubmitted = true), 100).map { it.key })
        assertTrue(filterAggregateAssignments(items, filters.copy(hideExpired = true), 200).isEmpty())
    }

    @Test fun filtersAndAggregationSwitchPersistWithoutSavingVisibleResults() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        try {
            cache.savePreferences(AppPreferences(aggregateAssignments = true))
            assertTrue(cache.preferences().aggregateAssignments)
            val filter = AggregateAssignmentFilters().withSource(AssignmentSource.CITEL, AggregateCourseFilter(false, setOf(5)))
            AggregateAssignmentFilterStore(cache, "fixture").save(filter)
            assertEquals(filter, AggregateAssignmentFilterStore(cache, "fixture").load())
            assertEquals(AggregateAssignmentFilters(), AggregateAssignmentFilterStore(cache, "other-account").load())
            val saved = cache.metadata("fixture", "assignment.aggregate.filters").orEmpty()
            assertFalse(saved.contains("dueTime"))
            assertFalse(saved.contains("submitted"))
        } finally { cache.close() }
    }

    @Test fun syncStatesRemainIndependentAndWaitingNeverBecomesSuccess() {
        val complete = AssignmentSourceSync(AssignmentSource.COURSE_PLATFORM, false, false, true)
        val failure = AssignmentSourceSync(AssignmentSource.PHYVLAB, false, true, false, true, "需要校园网")
        val waiting = AssignmentSourceSync(AssignmentSource.CITEL, false, false, false, true)
        assertEquals("已同步", complete.status)
        assertEquals("同步失败·正显示缓存", failure.status)
        assertEquals("等待同步·正显示缓存", waiting.status)
        assertEquals("部分同步失败", aggregateAssignmentSyncStatus(listOf(complete, failure, waiting)))
        assertEquals("等待同步", aggregateAssignmentSyncStatus(listOf(complete, waiting)))
        assertEquals("同步中", aggregateAssignmentSyncStatus(listOf(complete, failure.copy(busy = true))))
    }

    @Test fun sameIdsOnDifferentPlatformsDoNotCollide() {
        val physical = PhyVlabActivity(1, 1, "课程", "作业", "作业", "", dueTimestamp = 150)
        val citel = CitelTask(1, 1, "课程", "作业", "", false, dueTime = 150)
        val merged = aggregateAssignments(emptyList(), listOf(physical), listOf(citel))
        assertEquals(2, merged.map { it.key }.distinct().size)
        assertEquals(listOf(AssignmentSource.PHYVLAB, AssignmentSource.CITEL), merged.map { it.source })
    }
}

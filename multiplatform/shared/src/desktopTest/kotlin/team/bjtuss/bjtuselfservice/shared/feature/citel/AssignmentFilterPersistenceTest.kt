package team.bjtuss.bjtuselfservice.shared.feature.citel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.filterPhyVlabActivities
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity

class AssignmentFilterPersistenceTest {
    @Test fun preferencesSurviveDatabaseReopenAndStayIsolatedByAccountAndSource() {
        val file = Files.createTempFile("assignment-filters", ".db")
        val url = "jdbc:sqlite:$file"
        val filters = AssignmentFilters(setOf(10, 20), hideExpired = true, hideSubmitted = true, sortOrder = 2)
        try {
            val driver = JdbcSqliteDriver(url)
            CacheDatabaseSql.Schema.create(driver)
            CacheStore(driver).let { cache ->
                AssignmentFilterStore(cache, "student-a", "phyvlab").save(filters)
                AssignmentFilterStore(cache, "student-a", "citel").save(filters.copy(sortOrder = 0))
                cache.close()
            }
            CacheStore(JdbcSqliteDriver(url)).let { cache ->
                try {
                    assertEquals(filters, AssignmentFilterStore(cache, "student-a", "phyvlab").load())
                    assertEquals(filters.copy(sortOrder = 0), AssignmentFilterStore(cache, "student-a", "citel").load())
                    assertEquals(AssignmentFilters(), AssignmentFilterStore(cache, "student-b", "phyvlab").load())
                    val stored = cache.metadata("student-a", "phyvlab.filters").orEmpty()
                    assertFalse(stored.contains("activities"))
                    assertFalse(stored.contains("tasks"))
                } finally { cache.close() }
            }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun savedPreferencesAreAppliedToCurrentTasksAndCurrentTime() {
        val filters = AssignmentFilters(setOf(1), hideExpired = true, hideSubmitted = true)
        val old = PhyVlabActivity(1, 1, "Course", "Task", "", "", dueTimestamp = 150)
        assertEquals(listOf(old), filterPhyVlabActivities(listOf(old), filters, 100))
        assertTrue(filterPhyVlabActivities(listOf(old), filters, 200).isEmpty())
        assertTrue(filterPhyVlabActivities(listOf(old.copy(completed = true)), filters, 100).isEmpty())
        val new = old.copy(id = 2, dueTimestamp = 250)
        assertEquals(listOf(new), filterPhyVlabActivities(listOf(old, new), filters, 200))
    }

    @Test fun coursePlatformLegacyUntilDateLoadsAsBeijingEndOfDay() {
        val file = Files.createTempFile("homework-filter", ".db")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
            CacheDatabaseSql.Schema.create(driver)
            CacheStore(driver).let { cache ->
                try {
                    // 上一版按北京时间日期存“截至 10 月 29 日”。
                    cache.putMetadata("student-a", "homework_filter_until_date", "2026-10-29")
                    val loaded = cache.homeworkFilter("student-a")
                    assertEquals(kotlin.time.Instant.parse("2026-10-29T16:00:00Z").epochSeconds, loaded.untilEpoch)
                    // 新版只写截止点，并清掉旧键，下次读到的就是新值。
                    cache.saveHomeworkFilter("student-a", loaded.copy(untilEpoch = 42))
                    assertEquals(42L, cache.homeworkFilter("student-a").untilEpoch)
                    cache.saveHomeworkFilter("student-a", loaded.copy(untilEpoch = null))
                    assertNull(cache.homeworkFilter("student-a").untilEpoch)
                } finally { cache.close() }
            }
        } finally { Files.deleteIfExists(file) }
    }
}

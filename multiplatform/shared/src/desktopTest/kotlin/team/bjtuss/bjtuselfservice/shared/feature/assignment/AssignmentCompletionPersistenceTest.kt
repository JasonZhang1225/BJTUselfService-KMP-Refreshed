package team.bjtuss.bjtuselfservice.shared.feature.assignment

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql

class AssignmentCompletionPersistenceTest {
    private val key = AssignmentCompletionKey(AssignmentSource.COURSE_PLATFORM, 10, 20)

    @Test fun allSourcesSurviveDatabaseReopenAndUndoStaysSaved() = runBlocking {
        val file = Files.createTempFile("assignment-completion", ".db")
        val keys = setOf(key, key.copy(source = AssignmentSource.PHYVLAB),
            key.copy(source = AssignmentSource.CITEL), key.copy(source = AssignmentSource.CITEL, programming = true))
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
            CacheDatabaseSql.Schema.create(driver)
            CacheStore(driver).useCache { cache ->
                val store = AssignmentCompletionStore(cache, "fixture-a")
                assertTrue(store.state.value.loaded)
                keys.forEach { assertTrue(store.setCompleted(it, true)) }
            }
            CacheStore(JdbcSqliteDriver("jdbc:sqlite:$file")).useCache { cache ->
                val store = AssignmentCompletionStore(cache, "fixture-a")
                assertEquals(keys, store.state.value.completedKeys)
                assertTrue(AssignmentCompletionStore(cache, "fixture-b").state.value.completedKeys.isEmpty())
                assertTrue(store.setCompleted(key, false))
            }
            CacheStore(JdbcSqliteDriver("jdbc:sqlite:$file")).useCache { cache ->
                assertEquals(keys - key, AssignmentCompletionStore(cache, "fixture-a").state.value.completedKeys)
            }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun clearingOneAccountLeavesOtherAccountsAndFiltersUntouched() = runBlocking {
        withCache { cache ->
            val a = AssignmentCompletionStore(cache, "fixture-a")
            val b = AssignmentCompletionStore(cache, "fixture-b")
            assertTrue(a.setCompleted(key, true))
            assertTrue(b.setCompleted(key, true))
            val filters = AggregateAssignmentFilters(hideExpired = true, hideSubmitted = true)
            AggregateAssignmentFilterStore(cache, "fixture-b").save(filters)
            cache.clearAccount("fixture-a")
            assertTrue(a.reload())
            assertTrue(a.state.value.completedKeys.isEmpty())
            assertEquals(setOf(key), AssignmentCompletionStore(cache, "fixture-b").state.value.completedKeys)
            assertEquals(filters, AggregateAssignmentFilterStore(cache, "fixture-b").load())
            cache.clearAll()
            assertTrue(b.reload())
            assertTrue(b.state.value.completedKeys.isEmpty())
        }
    }

    @Test fun malformedSavedDataIsReportedAndNeverOverwrittenByMarking() = runBlocking {
        withCache { cache ->
            cache.putMetadata("fixture", "assignment.manualCompletion.v1", "broken fixture")
            val store = AssignmentCompletionStore(cache, "fixture")
            assertFalse(store.state.value.loaded)
            assertEquals(AssignmentCompletionFailure.LOAD, store.state.value.failure)
            assertFalse(store.setCompleted(key, true))
            assertEquals("broken fixture", cache.metadata("fixture", "assignment.manualCompletion.v1"))
        }
    }

    @Test fun closedDatabaseDoesNotPublishUnsavedChanges() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        val store = AssignmentCompletionStore(cache, "fixture")
        assertTrue(store.setCompleted(key, true))
        cache.close()
        assertFalse(store.setCompleted(key, false))
        assertEquals(setOf(key), store.state.value.completedKeys)
        assertEquals(AssignmentCompletionFailure.SAVE, store.state.value.failure)
    }

    @Test fun blankAccountIsRejected() {
        withCache { cache -> assertFailsWith<IllegalArgumentException> { AssignmentCompletionStore(cache, "  ") } }
    }

    @Test fun clearingLiveAccountImmediatelyPublishesEmptyAndDoesNotResurrectOldMarks() = runBlocking {
        withCache { cache ->
            val store = AssignmentCompletionStore(cache, "fixture")
            assertTrue(store.setCompleted(key, true))
            assertTrue(store.clearWith { cache.clearAccount("fixture") })
            assertTrue(store.state.value.completedKeys.isEmpty())
            val another = key.copy(assignmentId = 21)
            assertTrue(store.setCompleted(another, true))
            assertEquals(setOf(another), AssignmentCompletionStore(cache, "fixture").state.value.completedKeys)
        }
    }

    @Test fun failedClearKeepsSavedMarksAndReportsFailure() = runBlocking {
        withCache { cache ->
            val store = AssignmentCompletionStore(cache, "fixture")
            assertTrue(store.setCompleted(key, true))
            assertFalse(store.clearWith { error("fixture") })
            assertEquals(setOf(key), store.state.value.completedKeys)
        }
    }

    private inline fun CacheStore.useCache(block: (CacheStore) -> Unit) {
        try { block(this) } finally { close() }
    }

    private inline fun withCache(block: (CacheStore) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        try { block(cache) } finally { cache.close() }
    }
}

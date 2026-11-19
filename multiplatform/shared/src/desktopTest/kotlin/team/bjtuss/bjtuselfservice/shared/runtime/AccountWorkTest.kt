package team.bjtuss.bjtuselfservice.shared.runtime

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql

class AccountWorkTest {
    @Test
    fun lateResponseCannotWriteAfterSameAccountReloginAndJoinWaitsForItsFinally() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver).value
        val cache = CacheStore(driver)
        val old = AccountWork()
        val fresh = AccountWork()
        val oldCache = cache.guardedBy(old)
        val freshCache = cache.guardedBy(fresh)
        val oldState = old.state("initial")
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val finalizing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val task = async(Dispatchers.Default) {
            runCatching { old.run {
                try {
                    withContext(NonCancellable) { entered.complete(Unit); response.await() }
                    oldState.update { "late" }
                    oldCache.putMetadata("fixture-a", "result", "old")
                } finally {
                    withContext(NonCancellable) { finalizing.complete(Unit); finish.await() }
                }
            } }.exceptionOrNull()
        }
        try {
            entered.await()
            old.stop()
            oldState.update { "stale-callback" }
            assertEquals("initial", oldState.value)
            val stopped = async { old.join() }
            cache.clearAccount("fixture-a")
            freshCache.putMetadata("fixture-a", "result", "new")
            response.complete(Unit)
            finalizing.await()
            assertFalse(stopped.isCompleted)
            assertEquals("initial", oldState.value)
            finish.complete(Unit)
            stopped.await()
            assertIs<CancellationException>(task.await())
            assertEquals("new", cache.metadata("fixture-a", "result"))
            assertFailsWith<CancellationException> { oldCache.putSetting("late-global", "old") }
            assertFailsWith<CancellationException> { oldCache.clearAll() }
            assertNull(cache.setting("late-global"))
            freshCache.putMetadata("fixture-a", "result", "still-new")
            assertEquals("still-new", cache.metadata("fixture-a", "result"))
        } finally {
            response.complete(Unit); finish.complete(Unit)
            old.stop(); fresh.stop(); old.join(); fresh.join(); task.join(); cache.close()
        }
    }

    @Test
    fun callerCancellationWaitsForFinalizerWithoutCancellingOtherAccountWork() = runBlocking<Unit> {
        val work = AccountWork()
        val entered = CompletableDeferred<Unit>()
        val finalizing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val task = launch(Dispatchers.Default) { work.run {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { finalizing.complete(Unit); finish.await() } }
        } }
        try {
            entered.await(); task.cancel(); finalizing.await()
            assertFalse(task.isCompleted)
            assertEquals(7, work.run { 7 })
            finish.complete(Unit); task.join()
            work.stop(); work.join()
            assertFailsWith<CancellationException> { work.run { error("Must not enter stopped work") } }
        } finally { finish.complete(Unit); task.cancelAndJoin(); work.stop(); work.join() }
    }

    @Test
    fun revocationCannotSplitAnAlreadyAdmittedSynchronousCommit() = runBlocking<Unit> {
        val work = AccountWork()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revoking = CompletableDeferred<Unit>()
        val values = mutableListOf<Int>()
        val writing = async(Dispatchers.Default) { work.mutate {
            values += 1
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            values += 2
        } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stopped = async(Dispatchers.Default) { revoking.complete(Unit); work.stop() }
            revoking.await()
            assertFalse(stopped.isCompleted)
            release.countDown(); writing.await(); stopped.await(); work.join()
            assertEquals(listOf(1, 2), values)
            assertFailsWith<CancellationException> { work.mutate { values += 3 } }
        } finally { release.countDown(); writing.join(); work.stop(); work.join() }
    }

    @Test
    fun nestedModelOperationsReuseTheirOwnerAndBorrowedCacheDoesNotCloseDatabase() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver).value
        val cache = CacheStore(driver)
        val work = AccountWork()
        try {
            val view = cache.guardedBy(work)
            work.run { work.run { view.putMetadata("fixture-a", "nested", "ok") } }
            view.close()
            assertEquals("ok", cache.metadata("fixture-a", "nested"))
        } finally { work.stop(); work.join(); cache.close() }
    }
}

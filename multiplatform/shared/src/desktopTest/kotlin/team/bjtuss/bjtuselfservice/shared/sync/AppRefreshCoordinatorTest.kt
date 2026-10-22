package team.bjtuss.bjtuselfservice.shared.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class AppRefreshCoordinatorTest {
    private class FakeAdapter(
        override val source: RefreshSource,
        override val usesSchoolSession: Boolean = true,
        var availabilityValue: RefreshAvailability = RefreshAvailability.ENABLED,
        var gate: CompletableDeferred<Unit>? = null,
        var fail: Boolean = false,
        var expireUntilRecovered: Boolean = false,
        var throwOnRefresh: Boolean = false,
        var fetchedAt: Long? = 100L,
        val onSuccessFetchedAt: Long = 200L,
    ) : RefreshSourceAdapter {
        val calls = AtomicInteger()
        var recovered = false
        var expired = false

        override suspend fun availability() = availabilityValue

        override suspend fun refresh() {
            calls.incrementAndGet()
            gate?.await()
            if (throwOnRefresh) error("boom")
            expired = expireUntilRecovered && !recovered
            if (!fail && !expired) fetchedAt = onSuccessFetchedAt
        }

        override fun sessionExpired() = expired

        override fun outcome() = if (fail) RefreshSourceResult(source, RefreshStatus.FAILED, "network")
        else RefreshSourceResult(source, RefreshStatus.SUCCESS)

        override fun fetchedAtEpochSeconds() = fetchedAt
    }

    private fun coordinator(
        adapters: List<RefreshSourceAdapter>,
        reauthenticate: (suspend () -> Boolean)? = null,
        timeoutMillis: Long = 5_000,
    ) = AppRefreshCoordinator(
        accountScope = "test-account",
        adapters = adapters,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        reauthenticate = reauthenticate,
        nowEpochSeconds = { 1_000L },
        perSourceTimeoutMillis = timeoutMillis,
    )

    private fun request(vararg sources: RefreshSource, origin: RefreshOrigin = RefreshOrigin.APP) =
        RefreshRequest(sources = sources.toSet(), origin = origin)

    @Test
    fun concurrentIdenticalRequestsShareOneExecution() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val grades = FakeAdapter(RefreshSource.GRADES, gate = gate)
        val coordinator = coordinator(listOf(grades))
        val first = async(Dispatchers.Default) { coordinator.refresh(request(RefreshSource.GRADES)) }
        val second = async(Dispatchers.Default) { coordinator.refresh(request(RefreshSource.GRADES, origin = RefreshOrigin.CLI)) }
        waitUntil { grades.calls.get() == 1 && coordinator.running.value == setOf(RefreshSource.GRADES) }
        gate.complete(Unit)
        val reports = listOf(first, second).awaitAll()
        assertEquals(1, grades.calls.get())
        reports.forEach { assertEquals(RefreshStatus.SUCCESS, it.result(RefreshSource.GRADES)?.status) }
        assertEquals(RefreshOrigin.CLI, reports[1].origin)
    }

    @Test
    fun overlappingRequestReusesInFlightWorkAndFillsMissingSources() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val grades = FakeAdapter(RefreshSource.GRADES, gate = gate)
        val exams = FakeAdapter(RefreshSource.EXAMS)
        val coordinator = coordinator(listOf(grades, exams))
        val first = async(Dispatchers.Default) { coordinator.refresh(request(RefreshSource.GRADES)) }
        waitUntil { grades.calls.get() == 1 }
        val second = async(Dispatchers.Default) { coordinator.refresh(request(RefreshSource.GRADES, RefreshSource.EXAMS)) }
        waitUntil { exams.calls.get() == 1 }
        gate.complete(Unit)
        first.await()
        val report = second.await()
        assertEquals(1, grades.calls.get())
        assertEquals(1, exams.calls.get())
        assertEquals(listOf(RefreshSource.EXAMS, RefreshSource.GRADES), report.results.map { it.source })
    }

    @Test
    fun laterRequestAfterCompletionRunsAgain() = runBlocking {
        val grades = FakeAdapter(RefreshSource.GRADES)
        val coordinator = coordinator(listOf(grades))
        coordinator.refresh(request(RefreshSource.GRADES))
        coordinator.refresh(request(RefreshSource.GRADES))
        assertEquals(2, grades.calls.get())
    }

    @Test
    fun independentFailureDoesNotBlockOtherSourcesAndKeepsOldSyncTime() = runBlocking {
        val grades = FakeAdapter(RefreshSource.GRADES, fail = true, fetchedAt = 50L)
        val exams = FakeAdapter(RefreshSource.EXAMS)
        val crashing = FakeAdapter(RefreshSource.COURSES, throwOnRefresh = true, fetchedAt = 70L)
        val report = coordinator(listOf(grades, exams, crashing))
            .refresh(request(RefreshSource.GRADES, RefreshSource.EXAMS, RefreshSource.COURSES))
        val failed = report.result(RefreshSource.GRADES)!!
        assertEquals(RefreshStatus.FAILED, failed.status)
        assertEquals("network", failed.reason)
        // 失败来源保留上次同步时间，不能被本次刷新时间冒充。
        assertEquals(50L, failed.fetchedAtEpochSeconds)
        assertEquals(RefreshStatus.SUCCESS, report.result(RefreshSource.EXAMS)?.status)
        assertEquals(200L, report.result(RefreshSource.EXAMS)?.fetchedAtEpochSeconds)
        assertEquals(RefreshReason.ERROR, report.result(RefreshSource.COURSES)?.reason)
        assertEquals(70L, report.result(RefreshSource.COURSES)?.fetchedAtEpochSeconds)
        assertFalse(report.allSucceeded)
    }

    @Test
    fun disabledAndUnconfiguredSourcesAreSkippedWithoutNetwork() = runBlocking {
        val citel = FakeAdapter(RefreshSource.CITEL, usesSchoolSession = false, availabilityValue = RefreshAvailability.NOT_CONFIGURED)
        val lab = FakeAdapter(RefreshSource.PHYSICS_LAB, usesSchoolSession = false, availabilityValue = RefreshAvailability.DISABLED)
        val report = coordinator(listOf(citel, lab)).refresh(request(RefreshSource.CITEL, RefreshSource.PHYSICS_LAB))
        assertEquals(0, citel.calls.get() + lab.calls.get())
        assertEquals(RefreshReason.NOT_CONFIGURED, report.result(RefreshSource.CITEL)?.reason)
        assertEquals(RefreshReason.DISABLED, report.result(RefreshSource.PHYSICS_LAB)?.reason)
        assertEquals(RefreshStatus.SKIPPED, report.result(RefreshSource.CITEL)?.status)
        assertTrue(report.allSucceeded)
    }

    @Test
    fun unsupportedSourceIsReportedAsSkipped() = runBlocking {
        val report = coordinator(emptyList()).refresh(request(RefreshSource.MAIL_UNREAD))
        assertEquals(RefreshStatus.SKIPPED, report.result(RefreshSource.MAIL_UNREAD)?.status)
    }

    @Test
    fun expiredSessionRecoversOnceForTheWholeBatch() = runBlocking {
        val recoveries = AtomicInteger()
        val grades = FakeAdapter(RefreshSource.GRADES, expireUntilRecovered = true)
        val exams = FakeAdapter(RefreshSource.EXAMS, expireUntilRecovered = true)
        val report = coordinator(listOf(grades, exams), reauthenticate = {
            recoveries.incrementAndGet()
            grades.recovered = true
            exams.recovered = true
            true
        }).refresh(request(RefreshSource.GRADES, RefreshSource.EXAMS))
        assertEquals(1, recoveries.get())
        assertTrue(report.allSucceeded)
    }

    @Test
    fun failedRecoveryNeedsUserAction() = runBlocking {
        val grades = FakeAdapter(RefreshSource.GRADES, expireUntilRecovered = true)
        val report = coordinator(listOf(grades), reauthenticate = { false }).refresh(request(RefreshSource.GRADES))
        assertEquals(RefreshStatus.NEEDS_USER_ACTION, report.result(RefreshSource.GRADES)?.status)
        assertEquals(RefreshReason.SESSION_EXPIRED, report.result(RefreshSource.GRADES)?.reason)
        assertTrue(report.needsUserAction)
    }

    @Test
    fun forcedReauthenticationFailureSkipsTheWholeBatch() = runBlocking {
        val grades = FakeAdapter(RefreshSource.GRADES)
        val citel = FakeAdapter(RefreshSource.CITEL, usesSchoolSession = false)
        val report = coordinator(listOf(grades, citel), reauthenticate = { false })
            .refresh(RefreshRequest(setOf(RefreshSource.GRADES, RefreshSource.CITEL), RefreshOrigin.APP, forceReauthenticate = true))
        assertEquals(0, grades.calls.get() + citel.calls.get())
        report.results.forEach { assertEquals(RefreshReason.RECOVERY_FAILED, it.reason) }
    }

    @Test
    fun timeoutIsReportedAndReleasesTheSource() = runBlocking {
        val grades = FakeAdapter(RefreshSource.GRADES, gate = CompletableDeferred())
        val coordinator = coordinator(listOf(grades), timeoutMillis = 50)
        val report = coordinator.refresh(request(RefreshSource.GRADES))
        assertEquals(RefreshReason.TIMEOUT, report.result(RefreshSource.GRADES)?.reason)
        assertEquals(100L, report.result(RefreshSource.GRADES)?.fetchedAtEpochSeconds)
        assertTrue(coordinator.running.value.isEmpty())
    }

    @Test
    fun waiterCancellationDoesNotCancelSharedWork() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val grades = FakeAdapter(RefreshSource.GRADES, gate = gate)
        val coordinator = coordinator(listOf(grades))
        val impatient = async(Dispatchers.Default) { coordinator.refresh(request(RefreshSource.GRADES)) }
        val patient = async(Dispatchers.Default) { coordinator.refresh(request(RefreshSource.GRADES)) }
        waitUntil { grades.calls.get() == 1 }
        impatient.cancel()
        gate.complete(Unit)
        assertEquals(RefreshStatus.SUCCESS, patient.await().result(RefreshSource.GRADES)?.status)
        assertEquals(1, grades.calls.get())
    }

    @Test
    fun closeCancelsInFlightWorkAndRejectsLaterRequests() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val grades = FakeAdapter(RefreshSource.GRADES, gate = gate)
        val coordinator = coordinator(listOf(grades))
        val waiting = async(Dispatchers.Default) { coordinator.refresh(request(RefreshSource.GRADES)) }
        waitUntil { grades.calls.get() == 1 }
        coordinator.close()
        val cancelled = withTimeout(2_000) { waiting.await() }
        assertEquals(RefreshReason.CANCELLED, cancelled.result(RefreshSource.GRADES)?.reason)
        // 切换账号后旧协调器不再访问学校。
        val later = coordinator.refresh(request(RefreshSource.GRADES))
        assertEquals(1, grades.calls.get())
        assertEquals(RefreshReason.CANCELLED, later.result(RefreshSource.GRADES)?.reason)
        assertTrue(coordinator.isClosed)
    }

    @Test
    fun reportListsSourcesInStableOrderWithTimes() = runBlocking {
        val adapters = listOf(FakeAdapter(RefreshSource.GRADES), FakeAdapter(RefreshSource.HOME))
        val report = coordinator(adapters).refresh(request(RefreshSource.GRADES, RefreshSource.HOME))
        assertEquals(listOf(RefreshSource.HOME, RefreshSource.GRADES), report.results.map { it.source })
        assertEquals("test-account", report.accountScope)
        assertEquals(1_000L, report.startedAtEpochSeconds)
        assertNull(report.result(RefreshSource.CITEL))
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) {
                yield()
                delay(5)
            }
        }
    }
}

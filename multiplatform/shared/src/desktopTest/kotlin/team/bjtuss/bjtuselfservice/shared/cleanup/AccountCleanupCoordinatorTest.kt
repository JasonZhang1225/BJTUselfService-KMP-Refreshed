package team.bjtuss.bjtuselfservice.shared.cleanup

import kotlinx.coroutines.*
import kotlin.test.*

class AccountCleanupCoordinatorTest {
    private fun actions(visited: MutableList<CleanupStep>, fail: Set<CleanupStep> = emptySet()) =
        CleanupStep.entries.associateWith { step -> suspend { visited += step; step !in fail } }

    @Test fun cacheAndUnpairHaveSeparateLimitedPlans() = runBlocking {
        val coordinator = AccountCleanupCoordinator()
        val visited = mutableListOf<CleanupStep>()
        assertTrue(coordinator.execute(CleanupRequest(CleanupOperation.ACCOUNT_CACHE, "fixture-a"), actions(visited)).succeeded)
        assertEquals(listOf(CleanupStep.DATABASE, CleanupStep.STALE_TEMP), visited)
        visited.clear()
        assertTrue(coordinator.execute(CleanupRequest(CleanupOperation.REMOTE_UNPAIR, "fixture-a"), actions(visited)).succeeded)
        assertEquals(listOf(CleanupStep.STOP_WORK, CleanupStep.REMOTE_TOKENS, CleanupStep.REMOTE_RECORDS), visited)
    }

    @Test fun stopFailureBlocksEveryLocalDeletion() = runBlocking {
        val coordinator = AccountCleanupCoordinator()
        val visited = mutableListOf<CleanupStep>()
        val report = coordinator.execute(CleanupRequest(CleanupOperation.SIGN_OUT, "fixture-a"), actions(visited, setOf(CleanupStep.STOP_WORK)))
        assertEquals(listOf(CleanupStep.STOP_WORK, CleanupStep.SCHOOL_SESSION), visited)
        assertFalse(report.localDataCleared)
        assertEquals(CleanupStatus.BLOCKED, report.items.single { it.step == CleanupStep.DATABASE }.status)
        assertSame(report, coordinator.lastReport.value)
    }

    @Test fun discoveryFailureCannotEraseTheOwnerIndex() = runBlocking {
        val visited = mutableListOf<CleanupStep>()
        val report = AccountCleanupCoordinator().execute(CleanupRequest(CleanupOperation.ALL_LOCAL_DATA, "fixture-a"), actions(visited, setOf(CleanupStep.DISCOVER_OWNERS)))
        assertEquals(listOf(CleanupStep.STOP_WORK, CleanupStep.DISCOVER_OWNERS, CleanupStep.SCHOOL_SESSION), visited)
        assertFalse(report.succeeded)
    }

    @Test fun independentFailuresAreSummarizedAndDoNotStopRemainingStores() = runBlocking {
        val visited = mutableListOf<CleanupStep>()
        val report = AccountCleanupCoordinator().execute(CleanupRequest(CleanupOperation.SIGN_OUT, "fixture-a"), actions(visited, setOf(CleanupStep.PHYSICS_LAB, CleanupStep.PHY_VLAB)))
        assertEquals(AccountCleanupCoordinator.steps(CleanupOperation.SIGN_OUT), visited)
        assertEquals(2, report.items.count { it.status == CleanupStatus.FAILED })
        assertEquals(listOf("系统安全存储"), report.failureLabels)
        assertEquals(CleanupStatus.SUCCEEDED, report.items.single { it.step == CleanupStep.DATABASE }.status)
    }

    @Test fun schoolLogoutFailureDoesNotMisreportLocalDeletion() = runBlocking {
        val report = AccountCleanupCoordinator().execute(CleanupRequest(CleanupOperation.SIGN_OUT, "fixture-a"), actions(mutableListOf(), setOf(CleanupStep.SCHOOL_SESSION)))
        assertTrue(report.localDataCleared)
        assertFalse(report.succeeded)
        assertEquals(listOf("学校服务端会话"), report.failureLabels)
    }

    @Test fun admittedCleanupSurvivesPageCancellationAndPublishesItsReport() = runBlocking {
        val coordinator = AccountCleanupCoordinator()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val visited = mutableListOf<CleanupStep>()
        val actions = actions(visited).toMutableMap()
        actions[CleanupStep.STOP_WORK] = { entered.complete(Unit); finish.await(); visited += CleanupStep.STOP_WORK; true }
        val job = launch { coordinator.execute(CleanupRequest(CleanupOperation.SIGN_OUT, "fixture-a"), actions) }
        try {
            entered.await(); job.cancel()
            assertFalse(job.isCompleted)
            finish.complete(Unit); job.join()
            assertTrue(coordinator.lastReport.value!!.succeeded)
            assertEquals(AccountCleanupCoordinator.steps(CleanupOperation.SIGN_OUT), visited)
        } finally { finish.complete(Unit); job.cancelAndJoin() }
    }

    @Test fun cancelledWorkWaitingForCleanupMutexCannotDeadlockStopAndJoin() = runBlocking {
        val coordinator = AccountCleanupCoordinator()
        val entered = CompletableDeferred<Unit>()
        val joinWaiter = CompletableDeferred<Job>()
        val signout = launch {
            val actions = actions(mutableListOf()).toMutableMap()
            actions[CleanupStep.STOP_WORK] = { entered.complete(Unit); joinWaiter.await().cancelAndJoin(); true }
            coordinator.execute(CleanupRequest(CleanupOperation.SIGN_OUT, "fixture-a"), actions)
        }
        entered.await()
        val waitingCache = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.execute(CleanupRequest(CleanupOperation.ACCOUNT_CACHE, "fixture-a"), actions(mutableListOf()))
        }
        joinWaiter.complete(waitingCache)
        withTimeout(5000) { signout.join() }
        assertTrue(waitingCache.isCancelled)
        assertEquals(CleanupOperation.SIGN_OUT, coordinator.lastReport.value!!.request.operation)
    }

    @Test fun missingAdapterIsAnExplicitFailureAndRequestsRedactOwners() = runBlocking {
        val request = CleanupRequest(CleanupOperation.ACCOUNT_CACHE, "fixture-private-owner")
        val report = AccountCleanupCoordinator().execute(request, emptyMap())
        assertEquals(2, report.items.count { it.status == CleanupStatus.FAILED })
        assertFalse(request.toString().contains("fixture-private-owner"))
    }
}

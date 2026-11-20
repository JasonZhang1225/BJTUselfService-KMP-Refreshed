package team.bjtuss.bjtuselfservice.shared.cleanup

import kotlinx.coroutines.*
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault

class AccountCleanupRecoveryTest {
    private class Slot : CredentialVault {
        var payload: Credentials? = null
        var failLoad = false
        var failSave = false
        var failClear = false
        override suspend fun load(): Credentials? { check(!failLoad); return payload }
        override suspend fun save(credentials: Credentials) { check(!failSave); payload = credentials }
        override suspend fun clear() { check(!failClear); payload = null }
    }
    private class ProcessDeath : Error()
    private val request get() = CleanupRequest(CleanupOperation.SIGN_OUT, "fixture-owner")
    private fun actions(visited: MutableList<CleanupStep>, fail: Set<CleanupStep> = emptySet()) =
        CleanupStep.entries.associateWith { step -> suspend { visited += step; step !in fail } }

    @Test fun restartRetriesOnlyFailedStepsAndReestablishesQuiescence() = runBlocking {
        val slot = Slot()
        val first = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        val original = mutableListOf<CleanupStep>()
        assertFalse(first.execute(request, actions(original, setOf(CleanupStep.PHYSICS_LAB))).succeeded)
        assertNotNull(slot.payload)
        val restarted = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        val visited = mutableListOf<CleanupStep>()
        val record = restarted.pending().single()
        assertTrue(restarted.execute(record.request, actions(visited), CleanupContext(), record.id).succeeded)
        assertEquals(listOf(CleanupStep.STOP_WORK, CleanupStep.PHYSICS_LAB), visited)
        assertNull(slot.payload)
        assertTrue(restarted.pending().isEmpty())
    }

    @Test fun processDeathLeavesTheAdmittedIntentAndLastDurableCheckpoint() = runBlocking {
        val slot = Slot()
        val context = CleanupContext(CleanupInventory(accounts = setOf("fixture-a", "fixture-b"), ownsRemote = true))
        val actions = actions(mutableListOf()).toMutableMap()
        actions[CleanupStep.PHYSICS_DEMO] = { throw ProcessDeath() }
        assertFailsWith<ProcessDeath> { AccountCleanupCoordinator(VaultCleanupJournal(slot)).execute(request, actions, context) }
        val restarted = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        val record = restarted.pending().single()
        assertEquals(setOf("fixture-a", "fixture-b"), record.inventory.accounts)
        assertTrue(record.inventory.ownsRemote)
        assertEquals(CleanupStatus.SUCCEEDED, record.items.single { it.step == CleanupStep.PHYSICS_LAB }.status)
        assertTrue(record.items.none { it.step == CleanupStep.PHYSICS_DEMO })
        val visited = mutableListOf<CleanupStep>()
        assertTrue(restarted.execute(record.request, actions(visited), CleanupContext(), record.id).succeeded)
        assertTrue(CleanupStep.PHYSICS_DEMO in visited)
        assertFalse(CleanupStep.PHYSICS_LAB in visited)
        assertEquals(CleanupStep.STOP_WORK, visited.first())
    }

    @Test fun initialIntentWriteFailurePerformsNoDeletion() = runBlocking {
        val slot = Slot().apply { failSave = true }
        val visited = mutableListOf<CleanupStep>()
        val report = AccountCleanupCoordinator(VaultCleanupJournal(slot)).execute(request, actions(visited))
        assertTrue(visited.isEmpty())
        assertEquals(CleanupStatus.FAILED, report.items.single { it.step == CleanupStep.JOURNAL }.status)
        assertFalse(report.accountWorkStopped)
    }

    @Test fun checkpointFailureStopsLaterDeletionAndRepeatsOnlyTheUncertainStep() = runBlocking {
        val slot = Slot()
        val visited = mutableListOf<CleanupStep>()
        val firstActions = actions(visited).toMutableMap()
        firstActions[CleanupStep.PHYSICS_LAB] = { visited += CleanupStep.PHYSICS_LAB; slot.failSave = true; true }
        val first = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        assertFalse(first.execute(request, firstActions).succeeded)
        assertFalse(CleanupStep.DATABASE in visited)
        slot.failSave = false
        val restarted = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        val record = restarted.pending().single()
        visited.clear()
        assertTrue(restarted.execute(record.request, actions(visited), CleanupContext(), record.id).succeeded)
        assertEquals(listOf(CleanupStep.STOP_WORK, CleanupStep.PHYSICS_LAB), visited.take(2))
        assertFalse(CleanupStep.PLAYBACK in visited)
    }

    @Test fun journalRemovalFailureDoesNotRepeatAlreadyCompletedDeletions() = runBlocking {
        val slot = Slot().apply { failClear = true }
        val first = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        assertFalse(first.execute(request, actions(mutableListOf())).succeeded)
        slot.failClear = false
        val restarted = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        val record = restarted.pending().single()
        val visited = mutableListOf<CleanupStep>()
        assertTrue(restarted.execute(record.request, actions(visited), CleanupContext(), record.id).succeeded)
        assertEquals(listOf(CleanupStep.STOP_WORK), visited)
        assertNull(slot.payload)
    }

    @Test fun serverFailureAloneCannotCreateAReplayAgainstTheNextLogin() = runBlocking {
        val slot = Slot()
        val report = AccountCleanupCoordinator(VaultCleanupJournal(slot)).execute(request, actions(mutableListOf(), setOf(CleanupStep.SCHOOL_SESSION)))
        assertFalse(report.succeeded)
        assertTrue(report.localDataCleared)
        assertNull(slot.payload)
        assertTrue(AccountCleanupCoordinator(VaultCleanupJournal(slot)).pending().isEmpty())
    }

    @Test fun corruptOrUnreadableJournalIsPreservedAndBlocksAllActions() = runBlocking {
        for (unreadable in listOf(false, true)) {
            val slot = Slot().apply { payload = Credentials("fixture", "not-a-cleanup-record"); failLoad = unreadable }
            val before = slot.payload
            val visited = mutableListOf<CleanupStep>()
            val report = AccountCleanupCoordinator(VaultCleanupJournal(slot)).execute(request, actions(visited))
            assertFalse(report.succeeded)
            assertTrue(visited.isEmpty())
            assertEquals(before, slot.payload)
        }
    }

    @Test fun distinctOwnersAndOperationsAreRetainedAsSeparatePendingRecords() = runBlocking {
        val slot = Slot()
        val coordinator = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        coordinator.execute(request, actions(mutableListOf(), setOf(CleanupStep.CITEL)))
        coordinator.execute(CleanupRequest(CleanupOperation.ACCOUNT_CACHE, "fixture-b"), actions(mutableListOf(), setOf(CleanupStep.STALE_TEMP)))
        val records = AccountCleanupCoordinator(VaultCleanupJournal(slot)).pending()
        assertEquals(2, records.size)
        assertEquals(setOf("fixture-owner", "fixture-b"), records.map { it.request.accountScope }.toSet())
        assertFalse(records.joinToString().contains("fixture-owner"))
    }

    @Test fun anotherFailedStopCannotErasePreviouslySuccessfulCheckpoints() = runBlocking {
        val slot = Slot()
        AccountCleanupCoordinator(VaultCleanupJournal(slot)).execute(request, actions(mutableListOf(), setOf(CleanupStep.PHYSICS_LAB)))
        val second = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        assertFalse(second.execute(request, actions(mutableListOf(), setOf(CleanupStep.STOP_WORK))).succeeded)
        val third = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        val visited = mutableListOf<CleanupStep>()
        assertTrue(third.execute(request, actions(visited)).succeeded)
        assertEquals(listOf(CleanupStep.STOP_WORK, CleanupStep.PHYSICS_LAB), visited)
    }

    @Test fun aQueuedIntentCanBeSavedWhileAnotherOperationWaitsAndItsCheckpointsCannotOverwriteIt() = runBlocking {
        val slot = Slot()
        val coordinator = AccountCleanupCoordinator(VaultCleanupJournal(slot))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val firstActions = actions(mutableListOf()).toMutableMap()
        firstActions[CleanupStep.STOP_WORK] = { entered.complete(Unit); finish.await(); true }
        val running = launch { coordinator.execute(request, firstActions) }
        try {
            entered.await()
            val queued = CleanupRequest(CleanupOperation.SIGN_OUT, "fixture-next")
            withTimeout(5000) { assertTrue(coordinator.admit(queued, CleanupInventory(accounts = setOf("fixture-next")))) }
            assertEquals(2, coordinator.pending().size)
            finish.complete(Unit)
            running.join()
            val pending = AccountCleanupCoordinator(VaultCleanupJournal(slot)).pending().single()
            assertEquals("fixture-next", pending.request.accountScope)
            assertTrue(pending.items.isEmpty())
        } finally { finish.complete(Unit); running.cancelAndJoin() }
    }
}

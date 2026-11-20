package team.bjtuss.bjtuselfservice.shared.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.feature.shell.SessionRefreshCoordinator
import team.bjtuss.bjtuselfservice.shared.sync.AppRefreshCoordinator
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshRequest
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSourceAdapter
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSourceResult
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

class RefreshSessionProbeTest {
    @Test
    fun overlappingRefreshesReuseSuccessAndLaterRefreshChecksAgain() = runBlocking {
        val enteredProbe = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var calls = 0
        val probe = RefreshSessionProbe {
            calls++
            enteredProbe.complete(Unit)
            releaseProbe.await()
            true
        }

        val first = launch {
            probe.use { check ->
                assertTrue(check!!())
                releaseRefresh.await()
            }
        }
        withTimeout(5_000) { enteredProbe.await() }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            probe.use { check -> assertTrue(check!!()) }
        }
        assertFalse(second.isCompleted)
        releaseProbe.complete(Unit)
        second.join()
        probe.use { check -> assertTrue(check!!()) }
        assertEquals(1, calls)

        releaseRefresh.complete(Unit)
        first.join()
        probe.use { check -> assertTrue(check!!()) }
        assertEquals(2, calls)
    }

    @Test
    fun expiredProbeStillRecoversBeforeReadingAndDoesNotLeaveAStaleFailure() = runBlocking {
        val events = mutableListOf<String>()
        var valid = false
        val shared = RefreshSessionProbe { events += "probe"; valid }
        shared.use { firstCheck ->
            SessionRefreshCoordinator(
                reauthenticate = { events += "recover"; valid = true; true },
                probeSession = firstCheck,
            ).run({ events += "read" }, { false })
            shared.use { secondCheck -> assertTrue(secondCheck!!()) }
        }
        assertEquals(listOf("probe", "recover", "read", "probe"), events)
    }

    @Test
    fun businessExpiryStillRecoversAndRetriesAfterReusingSuccessfulProbe() = runBlocking {
        val events = mutableListOf<String>()
        val shared = RefreshSessionProbe { events += "probe"; true }
        shared.use { firstCheck ->
            assertTrue(firstCheck!!())
            shared.use { check ->
                var expired = true
                SessionRefreshCoordinator(
                    reauthenticate = { events += "recover"; expired = false; true },
                    probeSession = check,
                ).run({ events += "read" }, { expired })
            }
        }
        assertEquals(listOf("probe", "read", "recover", "read"), events)
    }

    @Test
    fun batchHoldsProbeLeaseUntilFinishedEvenAfterCallerCancels() = runBlocking {
        var probes = 0
        val shared = RefreshSessionProbe { probes++; true }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val account = AccountWork()
        val coordinator = AppRefreshCoordinator(
            accountScope = "fixture",
            adapters = listOf(
                object : RefreshSourceAdapter {
                    override val source = RefreshSource.GRADES
                    override val usesSchoolSession = true
                    override suspend fun refresh() {
                        entered.complete(Unit)
                        release.await()
                    }
                    override fun sessionExpired() = false
                    override fun outcome() = RefreshSourceResult(source, RefreshStatus.SUCCESS)
                    override fun fetchedAtEpochSeconds(): Long? = null
                },
            ),
            scope = account.scope(Dispatchers.Default),
            reauthenticate = { true },
            probeSession = { true },
            nowEpochSeconds = { 100L },
        )
        try {
            val caller = launch {
                coordinator.refresh(RefreshRequest(setOf(RefreshSource.GRADES), RefreshOrigin.APP), shared)
            }
            withTimeout(5_000) { entered.await() }
            caller.cancelAndJoin()
            assertEquals(1, probes)

            // 批次仍在后台运行，重用同一共享探测结果而不重查 MIS。
            shared.use { check -> assertTrue(check!!()) }
            assertEquals(1, probes)

            release.complete(Unit)
            withTimeout(5_000) {
                while (coordinator.running.value.isNotEmpty()) {
                    yield()
                    delay(5)
                }
            }
            // 批次完成后，新发起的独立刷新必须重新探测。
            shared.use { check -> assertTrue(check!!()) }
            assertEquals(2, probes)
        } finally {
            release.complete(Unit)
            coordinator.stopAndJoin()
            account.stop()
            account.join()
        }
    }
}

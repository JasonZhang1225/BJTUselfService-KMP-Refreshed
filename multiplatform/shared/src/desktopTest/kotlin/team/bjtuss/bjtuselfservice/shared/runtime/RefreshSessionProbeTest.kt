package team.bjtuss.bjtuselfservice.shared.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.feature.shell.SessionRefreshCoordinator

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
}

package team.bjtuss.bjtuselfservice.shared.feature.shell

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRefreshCoordinatorTest {
    @Test
    fun reauthenticatesAndRetriesOnlyAfterSessionFailure() = runSuspend {
        var operationCount = 0
        var authenticationCount = 0
        var recoveryInProgressCount = 0
        var sessionExpired = true
        val coordinator = SessionRefreshCoordinator(
            reauthenticate = {
                authenticationCount += 1
                true
            },
            onRecoveryStateChanged = { inProgress ->
                recoveryInProgressCount += if (inProgress) 1 else -1
            },
        )

        coordinator.run(
            operation = {
                operationCount += 1
                if (operationCount == 2) sessionExpired = false
            },
            sessionExpired = { sessionExpired },
        )

        assertEquals(2, operationCount)
        assertEquals(1, authenticationCount)
        assertEquals(0, recoveryInProgressCount)
    }

    @Test
    fun failedReauthenticationStaysOnTheCurrentPageAndStopsAfterTwoAttempts() = runSuspend {
        var operationCount = 0
        var authenticationCount = 0
        val coordinator = SessionRefreshCoordinator(
            reauthenticate = {
                authenticationCount += 1
                false
            },
        )

        coordinator.run(
            operation = { operationCount += 1 },
            sessionExpired = { true },
        )

        assertEquals(1, operationCount)
        assertEquals(2, authenticationCount)
    }
}

private fun runSuspend(block: suspend () -> Unit) {
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                failure = result.exceptionOrNull()
            }
        },
    )
    failure?.let { throw it }
}

package team.bjtuss.bjtuselfservice.shared.security

import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacOsKeychainCredentialVaultTest {
    @Test
    fun syntheticCredentialRoundTripUsesSystemKeychain() {
        // desktopTest also runs on Windows CI; Security.framework only exists on macOS.
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return
        runSuspend {
            val vault = MacOsKeychainCredentialVault(
                service = "team.bjtuss.bjtuselfservice.kmp.credentials.desktop-smoke",
                account = "synthetic-fixture",
            )
            val fixture = Credentials("fixture-student", "fixture-password-安全")

            try {
                vault.clear()
                vault.save(fixture)
                assertEquals(fixture, vault.load())
            } finally {
                vault.clear()
            }
            assertNull(vault.load())
        }
    }
}

private fun runSuspend(block: suspend () -> Unit) {
    var failure: Throwable? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Unit> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                failure = result.exceptionOrNull()
            }
        },
    )
    failure?.let { throw it }
}

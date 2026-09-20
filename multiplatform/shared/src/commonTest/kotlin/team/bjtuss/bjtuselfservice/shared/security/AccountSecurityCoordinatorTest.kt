package team.bjtuss.bjtuselfservice.shared.security

import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountSecurityCoordinatorTest {
    @Test
    fun savesRestoresAndClearsCredentialsInOrder() = runSuspend {
        val vault = FakeCredentialVault()
        val preferences = FakeAccountPreferences()
        val coordinator = AccountSecurityCoordinator(AccountSecurityStore(vault, preferences))
        val credentials = Credentials("student", "secret")

        assertTrue(coordinator.persistAfterLogin(credentials, rememberCredentials = true))
        assertEquals(credentials, vault.saved)
        assertTrue(preferences.enabled)
        assertEquals(
            credentials,
            assertIs<CredentialRestoreResult.Restored>(coordinator.restore()).credentials,
        )

        assertTrue(coordinator.clear())
        assertEquals(null, vault.saved)
        assertFalse(preferences.enabled)
    }

    @Test
    fun missingOrUnreadableSavedCredentialsRecoverToSignedOut() = runSuspend {
        val missingPreferences = FakeAccountPreferences(enabled = true)
        val missing = AccountSecurityCoordinator(
            AccountSecurityStore(FakeCredentialVault(), missingPreferences),
        )
        assertIs<CredentialRestoreResult.Empty>(missing.restore())
        assertFalse(missingPreferences.enabled)

        val failingPreferences = FakeAccountPreferences(enabled = true)
        val failingVault = FakeCredentialVault(failOnLoad = true)
        val failing = AccountSecurityCoordinator(AccountSecurityStore(failingVault, failingPreferences))
        assertIs<CredentialRestoreResult.Failed>(failing.restore())
        assertTrue(failingVault.clearCount > 0)
        assertFalse(failingPreferences.enabled)
    }

    @Test
    fun unrememberedStatePurgesResidualVaultContent() = runSuspend {
        // 模拟 iOS 卸载重装：记忆标记（NSUserDefaults）被清除，Keychain 密文残留。
        val vault = FakeCredentialVault().apply { saved = Credentials("student", "secret") }
        val preferences = FakeAccountPreferences(enabled = false)
        val coordinator = AccountSecurityCoordinator(AccountSecurityStore(vault, preferences))

        assertIs<CredentialRestoreResult.Empty>(coordinator.restore())
        assertTrue(vault.clearCount > 0)
        assertEquals(null, vault.saved)
    }

    @Test
    fun missingRememberMarkerMigratesExistingSecureCredentials() = runSuspend {
        val credentials = Credentials("student", "secret")
        val vault = FakeCredentialVault().apply { saved = credentials }
        val preferences = FakeAccountPreferences(enabled = false, settingExists = false)
        val coordinator = AccountSecurityCoordinator(AccountSecurityStore(vault, preferences))

        assertEquals(
            credentials,
            assertIs<CredentialRestoreResult.Restored>(coordinator.restore()).credentials,
        )
        assertTrue(preferences.enabled)
        assertEquals(0, vault.clearCount)
    }

    @Test
    fun unavailableVaultNeverClaimsCredentialsWereRemembered() = runSuspend {
        val preferences = FakeAccountPreferences(enabled = true)
        val coordinator = AccountSecurityCoordinator(AccountSecurityStore(null, preferences))

        assertFalse(coordinator.canStoreCredentials)
        assertIs<CredentialRestoreResult.Unavailable>(coordinator.restore())
        assertTrue(coordinator.persistAfterLogin(Credentials("student", "secret"), true))
        assertFalse(preferences.enabled)
    }

    @Test
    fun rememberPreferenceIsAppliedImmediatelyAndOptOutClearsVault() = runSuspend {
        val vault = FakeCredentialVault().apply {
            saved = Credentials("student", "secret")
        }
        val preferences = FakeAccountPreferences()
        val coordinator = AccountSecurityCoordinator(AccountSecurityStore(vault, preferences))

        assertTrue(coordinator.setRememberCredentials(enabled = true))
        assertTrue(preferences.enabled)
        assertTrue(coordinator.setRememberCredentials(enabled = false))
        assertFalse(preferences.enabled)
        assertEquals(null, vault.saved)
        assertTrue(vault.clearCount > 0)
    }
}

private class FakeCredentialVault(
    private val failOnLoad: Boolean = false,
) : CredentialVault {
    var saved: Credentials? = null
    var clearCount: Int = 0

    override suspend fun save(credentials: Credentials) {
        saved = credentials
    }

    override suspend fun load(): Credentials? {
        if (failOnLoad) error("fixture failure")
        return saved
    }

    override suspend fun clear() {
        clearCount++
        saved = null
    }
}

private class FakeAccountPreferences(
    var enabled: Boolean = false,
    private val settingExists: Boolean = true,
) : AccountPreferences {
    override suspend fun shouldRememberCredentials(): Boolean = enabled

    override suspend fun hasRememberCredentialsSetting(): Boolean = settingExists

    override suspend fun setShouldRememberCredentials(enabled: Boolean) {
        this.enabled = enabled
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

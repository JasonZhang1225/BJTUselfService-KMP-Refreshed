package team.bjtuss.bjtuselfservice.shared.security

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUUID
import platform.Security.errSecNotAvailable
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class IosKeychainReinstallTest {
    @Test
    fun missingInstallPreferenceClearsCredentialWithNativePreferences() = runBlocking {
        val suffix = NSUUID().UUIDString
        val store = createAppleAccountSecurityStore(
            accessibleAfterFirstUnlock = true,
            credentialService = "team.bjtuss.bjtuselfservice.kmp.credentials.ios-test.$suffix",
            credentialAccount = "synthetic-fixture",
            rememberCredentialsKey = "remember_credentials_ios_test_$suffix",
        )
        val fixtureVault = InMemoryCredentialVault()
        try {
            fixtureVault.save(Credentials("synthetic-student", "synthetic-password"))
            // NSUserDefaults is the real iOS implementation; uninstall removes this marker.
            store.preferences.clearRememberCredentialsSetting()

            assertIs<CredentialRestoreResult.Empty>(
                AccountSecurityCoordinator(store.copy(credentialVault = fixtureVault)).restore(),
            )
            assertNull(fixtureVault.load())
        } finally {
            store.preferences.clearRememberCredentialsSetting()
        }
    }

    @Test
    fun nativeKeychainRoundTripWhenAvailable() = runBlocking {
        val suffix = NSUUID().UUIDString
        val store = createAppleAccountSecurityStore(
            accessibleAfterFirstUnlock = true,
            credentialService = "team.bjtuss.bjtuselfservice.kmp.credentials.ios-test.$suffix",
            credentialAccount = "synthetic-fixture",
            rememberCredentialsKey = "remember_credentials_ios_test_$suffix",
        )
        val vault = requireNotNull(store.credentialVault)
        try {
            vault.save(Credentials("synthetic-student", "synthetic-password"))
        } catch (error: CredentialVaultException) {
            if (error.platformStatus == errSecNotAvailable) {
                // The unsigned Kotlin/Native test executable has no Keychain in CI.
                // The NSUserDefaults/coordinator behavior remains covered above;
                // a signed app on a physical device must validate this native path.
                println("Native Keychain unavailable (OSStatus ${error.platformStatus}); device test required")
                return@runBlocking
            }
            throw error
        }
        try {
            store.preferences.clearRememberCredentialsSetting()
            assertIs<CredentialRestoreResult.Empty>(AccountSecurityCoordinator(store).restore())
            assertNull(vault.load())
        } finally {
            vault.clear()
            store.preferences.clearRememberCredentialsSetting()
        }
    }
}

private class InMemoryCredentialVault : CredentialVault {
    private var value: Credentials? = null

    override suspend fun save(credentials: Credentials) {
        value = credentials
    }

    override suspend fun load(): Credentials? = value

    override suspend fun clear() {
        value = null
    }
}

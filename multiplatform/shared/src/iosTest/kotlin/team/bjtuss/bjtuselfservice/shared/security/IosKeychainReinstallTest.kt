package team.bjtuss.bjtuselfservice.shared.security

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUUID
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class IosKeychainReinstallTest {
    @Test
    fun missingInstallPreferenceDeletesResidualKeychainCredential() = runBlocking {
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
            // iOS uninstall removes UserDefaults while the Keychain item survives.
            store.preferences.clearRememberCredentialsSetting()

            assertIs<CredentialRestoreResult.Empty>(AccountSecurityCoordinator(store).restore())
            assertNull(vault.load())
        } finally {
            vault.clear()
            store.preferences.clearRememberCredentialsSetting()
        }
    }
}

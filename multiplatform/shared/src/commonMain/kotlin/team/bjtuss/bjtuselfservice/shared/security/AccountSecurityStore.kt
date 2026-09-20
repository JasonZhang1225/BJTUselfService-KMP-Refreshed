package team.bjtuss.bjtuselfservice.shared.security

import team.bjtuss.bjtuselfservice.shared.auth.Credentials

data class AccountSecurityStore(
    val credentialVault: CredentialVault?,
    val preferences: AccountPreferences,
)

interface AccountPreferences {
    suspend fun shouldRememberCredentials(): Boolean
    suspend fun setShouldRememberCredentials(enabled: Boolean)

    /**
     * Distinguish an explicit opt-out from a missing setting left by an older
     * build. The default keeps existing non-Apple implementations/tests
     * backward-compatible; platform stores with a real preference API override it.
     */
    suspend fun hasRememberCredentialsSetting(): Boolean = true
}

sealed interface CredentialRestoreResult {
    data object Empty : CredentialRestoreResult
    data object Unavailable : CredentialRestoreResult
    data class Restored(val credentials: Credentials) : CredentialRestoreResult
    data object Failed : CredentialRestoreResult
}

class AccountSecurityCoordinator(
    private val store: AccountSecurityStore,
) {
    val canStoreCredentials: Boolean get() = store.credentialVault != null

    /**
     * Keep the preference in sync with the checkbox immediately.  The credential
     * itself is still written only after a successful login, but opting out must
     * remove an old Keychain item right away rather than waiting for logout.
     */
    suspend fun setRememberCredentials(enabled: Boolean): Boolean {
        return if (enabled) {
            runCatching {
                store.preferences.setShouldRememberCredentials(true)
            }.isSuccess
        } else {
            clear()
        }
    }

    suspend fun restore(): CredentialRestoreResult {
        val vault = store.credentialVault ?: return CredentialRestoreResult.Unavailable
        val rememberCredentials = store.preferences.shouldRememberCredentials()
        val rememberSettingExists = store.preferences.hasRememberCredentialsSetting()
        if (!rememberCredentials && rememberSettingExists) {
            // A real, explicit opt-out is authoritative. Do not keep a residual
            // Keychain/keystore entry after the user disabled password saving.
            runCatching { vault.clear() }
            return CredentialRestoreResult.Empty
        }

        return try {
            val credentials = vault.load()
            if (credentials == null) {
                if (rememberSettingExists) {
                    runCatching { store.preferences.setShouldRememberCredentials(false) }
                }
                CredentialRestoreResult.Empty
            } else {
                // Older builds could save the credential before the preference
                // marker existed. Migrate that valid secure item instead of
                // treating a missing marker as an opt-out.
                if (!rememberCredentials && !rememberSettingExists) {
                    runCatching { store.preferences.setShouldRememberCredentials(true) }
                }
                CredentialRestoreResult.Restored(credentials)
            }
        } catch (_: Exception) {
            runCatching { vault.clear() }
            runCatching { store.preferences.setShouldRememberCredentials(false) }
            CredentialRestoreResult.Failed
        }
    }

    suspend fun persistAfterLogin(
        credentials: Credentials,
        rememberCredentials: Boolean,
    ): Boolean {
        val vault = store.credentialVault
        if (!rememberCredentials || vault == null) {
            return clear()
        }

        return try {
            vault.save(credentials)
            store.preferences.setShouldRememberCredentials(true)
            true
        } catch (_: Exception) {
            runCatching { vault.clear() }
            runCatching { store.preferences.setShouldRememberCredentials(false) }
            false
        }
    }

    suspend fun clear(): Boolean {
        val vaultResult = runCatching { store.credentialVault?.clear() }
        val preferenceResult = runCatching {
            store.preferences.setShouldRememberCredentials(false)
        }
        return vaultResult.isSuccess && preferenceResult.isSuccess
    }
}

package team.bjtuss.bjtuselfservice.shared.security

import team.bjtuss.bjtuselfservice.shared.auth.Credentials

data class AccountSecurityStore(
    val credentialVault: CredentialVault?,
    val preferences: AccountPreferences,
)

interface AccountPreferences {
    suspend fun shouldRememberCredentials(): Boolean
    suspend fun setShouldRememberCredentials(enabled: Boolean)
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

    suspend fun restore(): CredentialRestoreResult {
        val vault = store.credentialVault ?: return CredentialRestoreResult.Unavailable
        if (!store.preferences.shouldRememberCredentials()) {
            // iOS 卸载会清掉 NSUserDefaults 但保留 Keychain 条目：记忆标记为否却仍有
            // 密文即为重装残留，直接清除。升级用户标记仍在，凭据不受影响。
            // Android/Windows 此时保险库本就为空，clear 只是无害兜底。
            runCatching { vault.clear() }
            return CredentialRestoreResult.Empty
        }

        return try {
            val credentials = vault.load()
            if (credentials == null) {
                store.preferences.setShouldRememberCredentials(false)
                CredentialRestoreResult.Empty
            } else {
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

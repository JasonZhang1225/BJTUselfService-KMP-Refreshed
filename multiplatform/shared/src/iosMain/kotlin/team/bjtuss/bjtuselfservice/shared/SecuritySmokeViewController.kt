package team.bjtuss.bjtuselfservice.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.security.AppleKeychainCredentialVault
import team.bjtuss.bjtuselfservice.shared.security.CredentialRestoreResult
import team.bjtuss.bjtuselfservice.shared.security.CredentialVaultException
import team.bjtuss.bjtuselfservice.shared.security.createAppleAccountSecurityStore

fun SecuritySmokeViewController() = ComposeUIViewController {
    var result by remember { mutableStateOf("SECURITY_SMOKE_RUNNING") }
    LaunchedEffect(Unit) {
        result = runCatching {
            val vault = AppleKeychainCredentialVault(
                service = "team.bjtuss.bjtuselfservice.kmp.credentials.smoke",
                account = "synthetic-fixture",
                accessibleAfterFirstUnlock = true,
            )
            val fixture = Credentials("fixture-student", "fixture-password-安全")
            vault.clear()
            vault.save(fixture)
            check(vault.load() == fixture)
            vault.clear()
            check(vault.load() == null)

            // Exercise the same Apple store/coordinator path as production, but
            // use an isolated service/account/preferences key. A smoke screen
            // must never clear a user's real saved credentials when it finishes.
            val coordinator = AccountSecurityCoordinator(
                createAppleAccountSecurityStore(
                    accessibleAfterFirstUnlock = true,
                    credentialService = "team.bjtuss.bjtuselfservice.kmp.credentials.smoke.coordinator",
                    credentialAccount = "synthetic-fixture",
                    rememberCredentialsKey = "remember_credentials_smoke_coordinator",
                ),
            )
            check(coordinator.clear())
            check(coordinator.persistAfterLogin(fixture, rememberCredentials = true))
            // Recreate both the coordinator and the platform store to model a
            // cold launch rather than only an in-process readback.
            val reopenedCoordinator = AccountSecurityCoordinator(
                createAppleAccountSecurityStore(
                    accessibleAfterFirstUnlock = true,
                    credentialService = "team.bjtuss.bjtuselfservice.kmp.credentials.smoke.coordinator",
                    credentialAccount = "synthetic-fixture",
                    rememberCredentialsKey = "remember_credentials_smoke_coordinator",
                ),
            )
            check(
                (reopenedCoordinator.restore() as? CredentialRestoreResult.Restored)?.credentials == fixture,
            )
            check(reopenedCoordinator.clear())
            "SECURITY_SMOKE_PASS"
        }.getOrElse { error ->
            val vaultError = error as? CredentialVaultException
            if (vaultError == null) {
                "SECURITY_SMOKE_FAIL_UNKNOWN"
            } else {
                "SECURITY_SMOKE_FAIL_${vaultError.operation}_${vaultError.platformStatus ?: "NONE"}"
            }
        }
    }
    MaterialTheme { Text(result) }
}

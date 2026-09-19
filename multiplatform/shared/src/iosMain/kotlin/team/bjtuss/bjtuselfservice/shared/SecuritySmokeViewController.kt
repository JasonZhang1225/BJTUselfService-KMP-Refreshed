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
import team.bjtuss.bjtuselfservice.shared.security.AppleKeychainCredentialVault
import team.bjtuss.bjtuselfservice.shared.security.CredentialVaultException

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

package team.bjtuss.bjtuselfservice.shared.security

import platform.Foundation.NSUserDefaults

/**
 * iOS 与 macOS 原生共用同一套「Keychain 存密文 + NSUserDefaults 存记忆标记」。
 *
 * [accessibleAfterFirstUnlock] 必须由调用方按平台给：iOS 传 `true`（既定安全决定），
 * macOS 传 `false`（`kSecAttrAccessible` 在 Mac 上不适用）。
 */
fun createAppleAccountSecurityStore(
    accessibleAfterFirstUnlock: Boolean,
): AccountSecurityStore = AccountSecurityStore(
    credentialVault = AppleKeychainCredentialVault(accessibleAfterFirstUnlock = accessibleAfterFirstUnlock),
    preferences = AppleAccountPreferences(),
)

private class AppleAccountPreferences(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : AccountPreferences {
    override suspend fun shouldRememberCredentials(): Boolean =
        defaults.boolForKey(REMEMBER_CREDENTIALS_KEY)

    override suspend fun setShouldRememberCredentials(enabled: Boolean) {
        defaults.setBool(enabled, forKey = REMEMBER_CREDENTIALS_KEY)
    }

    private companion object {
        const val REMEMBER_CREDENTIALS_KEY = "remember_credentials"
    }
}

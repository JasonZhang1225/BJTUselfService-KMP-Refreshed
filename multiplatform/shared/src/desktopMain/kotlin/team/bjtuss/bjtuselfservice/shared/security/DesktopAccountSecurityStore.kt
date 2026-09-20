package team.bjtuss.bjtuselfservice.shared.security

import java.util.prefs.Preferences

fun createDesktopAccountSecurityStore(): AccountSecurityStore = AccountSecurityStore(
    credentialVault = MacOsKeychainCredentialVault(),
    preferences = DesktopAccountPreferences(),
)

private class DesktopAccountPreferences : AccountPreferences {
    private val preferences = Preferences.userRoot().node(
        "/team/bjtuss/bjtuselfservice/kmp/account",
    )

    override suspend fun shouldRememberCredentials(): Boolean =
        preferences.getBoolean(REMEMBER_CREDENTIALS_KEY, false)

    override suspend fun hasRememberCredentialsSetting(): Boolean =
        preferences.get(REMEMBER_CREDENTIALS_KEY, null) != null

    override suspend fun setShouldRememberCredentials(enabled: Boolean) {
        preferences.putBoolean(REMEMBER_CREDENTIALS_KEY, enabled)
        preferences.flush()
    }

    private companion object {
        const val REMEMBER_CREDENTIALS_KEY = "remember_credentials"
    }
}

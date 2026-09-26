package team.bjtuss.bjtuselfservice.shared.security

import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openDesktopCacheStore

class MacOsFullWipeIntegrationTest {
    @Test
    fun syntheticAccountCannotBeRestoredAfterFullWipeAndReopen() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return@runBlocking

        val fixtureId = UUID.randomUUID().toString()
        val directory = Files.createTempDirectory("bjtu-mac-wipe-").toFile()
        val preferenceNode = Preferences.userRoot().node("/team/bjtuss/security-test/$fixtureId")
        val vault = MacOsKeychainCredentialVault(
            service = "team.bjtuss.bjtuselfservice.kmp.credentials.wipe-test.$fixtureId",
            account = "synthetic-fixture",
        )
        val preferences = object : AccountPreferences {
            override suspend fun shouldRememberCredentials(): Boolean =
                preferenceNode.get("remember_credentials", null)?.toBoolean() == true

            override suspend fun setShouldRememberCredentials(enabled: Boolean) {
                preferenceNode.putBoolean("remember_credentials", enabled)
                preferenceNode.flush()
            }

            override suspend fun clearRememberCredentialsSetting() {
                preferenceNode.remove("remember_credentials")
                preferenceNode.flush()
            }
        }
        val coordinator = AccountSecurityCoordinator(AccountSecurityStore(vault, preferences))
        val protector = JvmAesCacheValueProtector(ByteArray(32) { (it + 1).toByte() })

        try {
            assertTrue(coordinator.persistAfterLogin(Credentials("fixture-student", "fixture-password"), true))
            assertTrue(coordinator.restore() is CredentialRestoreResult.Restored)

            val first = openDesktopCacheStore(directory, protector, cacheKeyCreated = true)
            try {
                first.store.putMetadata("fixture-student", "profile_name", "synthetic-name")
                assertEquals("synthetic-name", first.store.metadata("fixture-student", "profile_name"))
                first.store.clearAll()
                assertTrue(coordinator.purge())
            } finally {
                first.store.close()
            }

            assertNull(preferenceNode.get("remember_credentials", null))
            assertNull(vault.load())
            assertEquals(CredentialRestoreResult.Empty, coordinator.restore())

            val reopened = openDesktopCacheStore(directory, protector)
            try {
                assertEquals(0L, reopened.store.rowCount())
                assertNull(reopened.store.metadata("fixture-student", "profile_name"))
            } finally {
                reopened.store.close()
            }
        } finally {
            vault.clear()
            preferenceNode.removeNode()
            Preferences.userRoot().flush()
            assertTrue(directory.deleteRecursively())
        }
    }
}

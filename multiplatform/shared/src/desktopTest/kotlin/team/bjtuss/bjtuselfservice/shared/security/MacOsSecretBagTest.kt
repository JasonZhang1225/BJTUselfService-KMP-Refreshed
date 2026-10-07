package team.bjtuss.bjtuselfservice.shared.security

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.auth.Credentials

class MacOsSecretBagTest {
    @Test
    fun payloadRoundTripKeepsIndependentKeys() {
        val credentials = encodeCredentialPayload(Credentials("fixture-student", "fixture-password-安全"))
        val cacheKey = ByteArray(32) { it.toByte() }
        val payload = encodeSecretBagPayload(
            mapOf(
                MacOsSecretKeys.CREDENTIALS to credentials,
                MacOsSecretKeys.CACHE_KEY to cacheKey,
                MacOsSecretKeys.physicsLab("20210000") to encodeCredentialPayload(
                    Credentials("lab-user", "lab-password"),
                ),
            ),
        )
        val decoded = decodeSecretBagPayload(payload)
        assertEquals(3, decoded.size)
        assertContentEquals(credentials, decoded.getValue(MacOsSecretKeys.CREDENTIALS))
        assertContentEquals(cacheKey, decoded.getValue(MacOsSecretKeys.CACHE_KEY))
        assertEquals(
            Credentials("lab-user", "lab-password"),
            decodeCredentialPayload(decoded.getValue(MacOsSecretKeys.physicsLab("20210000"))),
        )
    }

    @Test
    fun payloadRejectsUnknownMagic() {
        assertFailsWith<CredentialVaultException> {
            decodeSecretBagPayload("not-a-bag".encodeToByteArray())
        }
    }

    @Test
    fun keychainBagKeepsSiblingKeysAfterOneRemoval() {
        if (!runningOnMac()) return
        val bag = MacOsSecretBag(
            service = "team.bjtuss.bjtuselfservice.kmp.secrets.desktop-smoke.${UUID.randomUUID()}",
            account = "synthetic-fixture",
        )
        try {
            bag.put(MacOsSecretKeys.CACHE_KEY, ByteArray(32) { 7 })
            bag.put(MacOsSecretKeys.CREDENTIALS, encodeCredentialPayload(Credentials("a", "b")))
            bag.remove(MacOsSecretKeys.CREDENTIALS)
            assertNull(bag.get(MacOsSecretKeys.CREDENTIALS))
            assertContentEquals(ByteArray(32) { 7 }, bag.get(MacOsSecretKeys.CACHE_KEY))
        } finally {
            bag.clearAll()
        }
        assertNull(bag.get(MacOsSecretKeys.CACHE_KEY))
    }

    @Test
    fun keychainBagMigratesLegacyItemAndDeletesIt() {
        if (!runningOnMac()) return
        val suffix = UUID.randomUUID().toString()
        val legacy = MacOsKeychainItem(
            service = "team.bjtuss.bjtuselfservice.kmp.credentials.desktop-smoke.$suffix",
            account = "synthetic-legacy",
        )
        val bag = MacOsSecretBag(
            service = "team.bjtuss.bjtuselfservice.kmp.secrets.desktop-smoke.$suffix",
            account = "synthetic-bag",
        )
        val payload = encodeCredentialPayload(Credentials("legacy-student", "legacy-password"))
        try {
            legacy.save(payload, CredentialVaultOperation.SAVE)
            assertContentEquals(payload, bag.get(MacOsSecretKeys.CREDENTIALS, legacy))
            assertNull(legacy.load(CredentialVaultOperation.LOAD))
            assertContentEquals(payload, bag.get(MacOsSecretKeys.CREDENTIALS))
        } finally {
            bag.clearAll()
            legacy.clear(CredentialVaultOperation.CLEAR)
        }
    }

    @Test
    fun bagBackedVaultClearLeavesCacheKey() = runBlocking {
        if (!runningOnMac()) return@runBlocking
        val suffix = UUID.randomUUID().toString()
        val bag = MacOsSecretBag(
            service = "team.bjtuss.bjtuselfservice.kmp.secrets.desktop-smoke.$suffix",
            account = "synthetic-bag",
        )
        val vault = MacOsBagBackedCredentialVault(
            bag = bag,
            key = MacOsSecretKeys.CREDENTIALS,
            legacyService = "team.bjtuss.bjtuselfservice.kmp.credentials.desktop-smoke.$suffix",
            legacyAccount = "synthetic-legacy",
        )
        val labVault = MacOsBagBackedCredentialVault(
            bag = bag,
            key = MacOsSecretKeys.physicsLab("fixture-student"),
            legacyService = "team.bjtuss.bjtuselfservice.kmp.physicslab.desktop-smoke.$suffix",
            legacyAccount = "fixture-student",
        )
        try {
            bag.put(MacOsSecretKeys.CACHE_KEY, ByteArray(32) { 3 })
            val login = Credentials("fixture-student", "fixture-password")
            val lab = Credentials("lab-user", "lab-password")
            vault.save(login)
            labVault.save(lab)
            assertEquals(login, vault.load())
            assertEquals(lab, labVault.load())
            vault.clear()
            assertNull(vault.load())
            assertEquals(lab, labVault.load())
            assertContentEquals(ByteArray(32) { 3 }, bag.get(MacOsSecretKeys.CACHE_KEY))
            labVault.clear()
            assertNull(labVault.load())
            assertContentEquals(ByteArray(32) { 3 }, bag.get(MacOsSecretKeys.CACHE_KEY))
        } finally {
            bag.clearAll()
        }
    }

    @Test
    fun coordinatorLogoutDoesNotDropSiblingSecrets() = runBlocking {
        if (!runningOnMac()) return@runBlocking
        val suffix = UUID.randomUUID().toString()
        val bag = MacOsSecretBag(
            service = "team.bjtuss.bjtuselfservice.kmp.secrets.desktop-smoke.$suffix",
            account = "synthetic-bag",
        )
        val preferences = object : AccountPreferences {
            private var remember = false
            override suspend fun shouldRememberCredentials(): Boolean = remember
            override suspend fun setShouldRememberCredentials(enabled: Boolean) {
                remember = enabled
            }
            override suspend fun clearRememberCredentialsSetting() {
                remember = false
            }
        }
        val coordinator = AccountSecurityCoordinator(
            AccountSecurityStore(
                credentialVault = MacOsBagBackedCredentialVault(
                    bag = bag,
                    key = MacOsSecretKeys.CREDENTIALS,
                    legacyService = "team.bjtuss.bjtuselfservice.kmp.credentials.desktop-smoke.$suffix",
                    legacyAccount = "synthetic-legacy",
                ),
                preferences = preferences,
                physicsLabVault = {
                    MacOsBagBackedCredentialVault(
                        bag = bag,
                        key = MacOsSecretKeys.physicsLab(it),
                        legacyService = "team.bjtuss.bjtuselfservice.kmp.physicslab.desktop-smoke.$suffix",
                        legacyAccount = it,
                    )
                },
            ),
        )
        try {
            bag.put(MacOsSecretKeys.CACHE_KEY, ByteArray(32) { 9 })
            assertTrue(coordinator.persistAfterLogin(Credentials("fixture-student", "fixture-password"), true))
            coordinator.physicsLabVault("fixture-student")
                ?.save(Credentials("lab-user", "lab-password"))
            assertTrue(coordinator.clear())
            assertEquals(CredentialRestoreResult.Empty, coordinator.restore())
            assertEquals(
                Credentials("lab-user", "lab-password"),
                coordinator.physicsLabVault("fixture-student")?.load(),
            )
            assertContentEquals(ByteArray(32) { 9 }, bag.get(MacOsSecretKeys.CACHE_KEY))
        } finally {
            bag.clearAll()
        }
    }
}

private fun runningOnMac(): Boolean =
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

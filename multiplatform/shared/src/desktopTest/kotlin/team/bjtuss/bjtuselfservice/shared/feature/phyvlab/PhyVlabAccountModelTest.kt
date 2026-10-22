package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSessionResult
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault

class PhyVlabAccountModelTest {
    @Test fun missingMetadataRestoresVaultButExplicitOptOutRemainsRespected() = runBlocking<Unit> {
        for (marker in listOf<String?>(null, "false")) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            CacheDatabaseSql.Schema.create(driver)
            val cache = CacheStore(driver)
            val vault = MemoryVault()
            val saved = Credentials("fixture-account", "fixture-password")
            vault.save(saved)
            if (marker != null) cache.putMetadata("scope", "phyvlab.configured", marker)
            try {
                val model = PhyVlabAccountModel("scope", cache, vault) { error("must not log in during restore") }
                model.initialize()
                assertEquals(saved, vault.load(), "初始化绝不能删除已保存的密码")
                assertEquals(marker == null, model.state.value.configured)
                if (marker == null) assertEquals("fixture-account", model.state.value.username)
            } finally { cache.close() }
        }
    }

    private fun store(): CacheStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        return CacheStore(driver)
    }

    @Test
    fun verifiedAccountIsSavedAndProvidedToSessionProtocol() = runBlocking<Unit> {
        val cache = store()
        try {
            val vault = MemoryVault()
            val signedIn = mutableListOf<String>()
            val model = PhyVlabAccountModel("scope-a", cache, vault) { creds ->
                signedIn += creds.username
                PhyVlabSessionResult.Ready("https://phyvlab.bjtu.edu.cn/")
            }
            assertNull(model.credentials())

            assertTrue(model.configure("lab-a", "fixture-password"))

            assertEquals(listOf("lab-a"), signedIn)
            assertEquals("lab-a", vault.credentials?.username)
            assertEquals("lab-a", model.credentials()?.username)
            val restored = PhyVlabAccountModel("scope-a", cache, vault) { error("no sign-in on restore") }
            restored.initialize()
            assertTrue(restored.state.value.configured)
            assertEquals("lab-a", restored.state.value.username)
        } finally {
            cache.close()
        }
    }

    @Test
    fun rejectedAccountNeverReplacesSavedOneAndClearRemovesPassword() = runBlocking<Unit> {
        val cache = store()
        try {
            val vault = MemoryVault()
            var result: PhyVlabSessionResult = PhyVlabSessionResult.Ready("https://phyvlab.bjtu.edu.cn/")
            val model = PhyVlabAccountModel("scope-a", cache, vault) { result }
            assertTrue(model.configure("lab-a", "fixture-password"))

            result = PhyVlabSessionResult.CredentialsRejected
            assertFalse(model.configure("lab-b", "wrong-password"))

            assertEquals("lab-a", vault.credentials?.username)
            assertTrue(model.state.value.failed)
            assertEquals("物理在线账号或密码错误。", model.state.value.message)

            model.clearConfiguration()
            assertNull(vault.credentials)
            assertNull(model.credentials())
            assertFalse(model.state.value.configured)
        } finally {
            cache.close()
        }
    }
}

private class MemoryVault : CredentialVault {
    var credentials: Credentials? = null
    override suspend fun save(credentials: Credentials) { this.credentials = credentials }
    override suspend fun load(): Credentials? = credentials
    override suspend fun clear() { credentials = null }
}

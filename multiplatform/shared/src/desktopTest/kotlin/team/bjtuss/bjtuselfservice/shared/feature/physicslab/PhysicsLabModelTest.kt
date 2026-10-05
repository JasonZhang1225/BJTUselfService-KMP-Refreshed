package team.bjtuss.bjtuselfservice.shared.feature.physicslab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.network.*
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault

class PhysicsLabModelTest {
    @Test fun failureKeepsCacheAndEmptySuccessReplacesIt() = runBlocking {
        val cache = store()
        try {
            val vault = MemoryVault()
            val transport = TestTransport()
            val model = PhysicsLabModel("fixture-account", cache, vault, PhysicsLabRemote(transport))
            model.configure("fixture-lab", "fixture-password", true)
            assertEquals(1, model.state.value.labs.size)
            transport.fail = true
            model.refresh()
            assertEquals(1, model.state.value.labs.size)
            assertTrue(model.state.value.fromCache)
            assertTrue(model.state.value.failed)
            val restored = PhysicsLabModel("fixture-account", cache, vault, PhysicsLabRemote(transport))
            restored.initialize()
            assertEquals(model.state.value.labs, restored.state.value.labs)
            transport.fail = false
            transport.empty = true
            restored.refresh()
            assertTrue(restored.state.value.labs.isEmpty())
            assertTrue(restored.state.value.synced)
            assertFalse(restored.state.value.failed)
        } finally { cache.close() }
    }
    @Test fun disabledFeatureDoesNotRequestAndDifferentAccountCannotSeeOldLabs() = runBlocking {
        val cache = store()
        try {
            val vault = MemoryVault()
            val transport = TestTransport()
            val model = PhysicsLabModel("fixture-a", cache, vault, PhysicsLabRemote(transport))
            model.configure("fixture-lab-a", "fixture", true)
            assertEquals(1, model.state.value.labs.size)
            model.configure("fixture-lab-a", "", false)
            val calls = transport.calls
            model.refresh()
            assertEquals(calls, transport.calls)
            assertFalse(model.state.value.enabled)
            val other = PhysicsLabModel("fixture-b", cache, MemoryVault(), PhysicsLabRemote(transport))
            other.initialize()
            assertTrue(other.state.value.labs.isEmpty())
            transport.fail = true
            model.configure("fixture-lab-b", "fixture", true)
            assertTrue(model.state.value.labs.isEmpty())
        } finally { cache.close() }
    }
    @Test fun previousDisabledCoreSyncPreferencesAreMigratedToAlwaysOn() {
        val cache = store()
        try {
            cache.savePreferences(AppPreferences(autoSyncGrades = false, autoSyncHomework = false, autoSyncSchedule = false, autoSyncExams = false, autoSyncPhyVlab = false))
            val prefs = cache.preferences()
            assertTrue(prefs.autoSyncGrades)
            assertTrue(prefs.autoSyncHomework)
            assertTrue(prefs.autoSyncSchedule)
            assertTrue(prefs.autoSyncExams)
            assertFalse(prefs.isPhyVlabEnabled)
        } finally { cache.close() }
    }
    @Test fun clearingOfflineDataPreservesSyncConfigurationAndFullWipeCanEnumerateAllVaultOwners() {
        val cache = store()
        try {
            listOf("fixture-a", "fixture-b").forEach { scope ->
                cache.putMetadata(scope, "physicslab.configured", "true")
                cache.putMetadata(scope, "physicslab.enabled", "true")
                cache.putMetadata(scope, "physicslab.results", "[]")
            }
            cache.clearAccount("fixture-a")
            assertEquals("true", cache.metadata("fixture-a", "physicslab.enabled"))
            assertNull(cache.metadata("fixture-a", "physicslab.results"))
            assertEquals(setOf("fixture-a", "fixture-b"), cache.metadataAccountScopes("physicslab.configured").toSet())
            cache.clearAll()
            assertTrue(cache.metadataAccountScopes("physicslab.configured").isEmpty())
        } finally { cache.close() }
    }
    private fun store(): CacheStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        return CacheStore(driver)
    }
}
private class MemoryVault : CredentialVault {
    var credentials: Credentials? = null
    override suspend fun load() = credentials
    override suspend fun save(credentials: Credentials) { this.credentials = credentials }
    override suspend fun clear() { credentials = null }
}
private class TestTransport : SchoolHttpTransport {
    var calls = 0
    var fail = false
    var empty = false
    override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
        calls++
        if (fail) throw SchoolNetworkException("fixture unavailable")
        val body = when {
            request.url == PHYSICS_LAB_RESULTS -> if (empty) "<table id='a_ASPxGridViewCourseList_DXMainTable'></table>" else results("超声专题")
            else -> loginForm
        }
        return SchoolHttpResponse(200, request.url, body = body.encodeToByteArray())
    }
    override fun clearSession() {}
}

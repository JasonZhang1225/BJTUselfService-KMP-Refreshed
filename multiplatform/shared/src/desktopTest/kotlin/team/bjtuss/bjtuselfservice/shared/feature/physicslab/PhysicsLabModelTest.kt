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
    @Test fun masterSwitchCanEnableBeforeCredentialsAndKeepsSavedAccountWhenDisabled() = runBlocking {
        val cache = store()
        try {
            val vault = MemoryVault()
            val transport = TestTransport()
            val model = PhysicsLabModel("fixture-toggle", cache, vault, PhysicsLabRemote(transport))
            model.setEnabled(true)
            assertTrue(model.state.value.enabled)
            assertEquals(0, transport.calls)
            model.configure("fixture-lab", "fixture-password", true)
            val calls = transport.calls
            val labs = model.state.value.labs
            model.setEnabled(false)
            model.refresh()
            assertEquals(calls, transport.calls)
            assertEquals(labs, model.state.value.labs)
            assertEquals("fixture-password", model.savedPassword())
            model.setEnabled(true)
            model.refresh()
            assertTrue(transport.calls > calls)
        } finally { cache.close() }
    }

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
    @Test fun durationChoiceUpdatesImmediatelySurvivesRefreshRestartAndCacheClearAndIsAccountScoped() = runBlocking {
        val cache = store()
        try {
            val vault = MemoryVault()
            val transport = TestTransport()
            val model = PhysicsLabModel("duration", cache, vault, PhysicsLabRemote(transport))
            model.configure("lab-a", "fixture", true)
            val lab = model.state.value.labs.single()
            assertEquals(2, lab.weekCount)
            val calls = transport.calls
            model.setTwoWeeks(lab, false)
            assertEquals(1, model.state.value.labs.single().dates.size)
            assertEquals(calls, transport.calls)
            model.refresh()
            assertEquals(1, model.state.value.labs.single().weekCount)
            val restored = PhysicsLabModel("duration", cache, vault, PhysicsLabRemote(transport))
            restored.initialize()
            assertEquals(1, restored.state.value.labs.single().weekCount)
            cache.clearAccount("duration")
            restored.refresh()
            assertEquals(1, restored.state.value.labs.single().weekCount)
            val afterClear = PhysicsLabModel("duration", cache, vault, PhysicsLabRemote(transport))
            afterClear.initialize(); afterClear.refresh()
            assertEquals(1, afterClear.state.value.labs.single().weekCount)
            afterClear.setTwoWeeks(afterClear.state.value.labs.single(), true)
            assertEquals(2, afterClear.state.value.labs.single().dates.size)
            afterClear.configure("lab-b", "fixture", true)
            assertEquals(2, afterClear.state.value.labs.single().weekCount)
            assertEquals("{}", cache.metadata("duration", "physicslab.twoWeekOverrides"))
        } finally { cache.close() }
    }

    @Test fun ordinaryLabCanBeExtendedToTwoWeeksAndOverrideSurvivesSync() = runBlocking {
        val cache = store()
        try {
            val transport = TestTransport().also { it.experimentName = "综合实验" }
            val model = PhysicsLabModel("ordinary-duration", cache, MemoryVault(), PhysicsLabRemote(transport))
            model.configure("lab", "fixture", true)
            val lab = model.state.value.labs.single()
            assertEquals(1, lab.weekCount)
            model.setTwoWeeks(lab, true)
            assertEquals(2, model.state.value.labs.single().dates.size)
            model.refresh()
            assertEquals(2, model.state.value.labs.single().weekCount)
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
    var experimentName = "超声专题"
    override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
        calls++
        if (fail) throw SchoolNetworkException("fixture unavailable")
        val body = when {
            request.url == PHYSICS_LAB_RESULTS -> if (empty) "<table id='a_ASPxGridViewCourseList_DXMainTable'></table>" else results(experimentName)
            else -> loginForm
        }
        return SchoolHttpResponse(200, request.url, body = body.encodeToByteArray())
    }
    override fun clearSession() {}
}

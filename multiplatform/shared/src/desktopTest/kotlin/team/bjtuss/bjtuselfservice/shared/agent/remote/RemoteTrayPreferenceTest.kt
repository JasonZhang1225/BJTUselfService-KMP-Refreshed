package team.bjtuss.bjtuselfservice.shared.agent.remote

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql

class RemoteTrayPreferenceTest {
    @Test fun oldConfigAndUnpairPreserveTheWindowPreferenceWithoutEnablingSync() {
        val legacy = remoteJson.decodeFromString(RemoteAgentConfig.serializer(), """{"enabled":false}""")
        assertTrue(legacy.minimizeToTray)
        assertFalse(legacy.runnable)
        assertFalse(legacy.copy(minimizeToTray = false).unpaired().minimizeToTray)
    }

    @Test fun preferencePersistsIndependentlyOfPairingAndDoesNotContactSite() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val secrets = object : RemoteSecretStore {
            override suspend fun loadDeviceToken(): String? = error("No credential access for a window preference")
            override suspend fun saveDeviceToken(token: String) = error("No credential write")
            override suspend fun loadPlatformAccess(): String? = error("No credential access")
            override suspend fun savePlatformAccess(value: String?) = error("No credential write")
            override suspend fun clear() = error("No credential deletion")
        }
        val controller = RemoteAgentController(RemoteAgentStore(cache), secrets, FakeExecutor(), scope,
            clientFactory = { error("Window preferences must not create a Site client") })
        try {
            controller.setMinimizeToTray(false)
            assertFalse(RemoteAgentStore(cache).loadConfig().minimizeToTray)
            controller.setMinimizeToTray(true)
            assertTrue(RemoteAgentStore(cache).loadConfig().minimizeToTray)
            assertFalse(controller.config.value.paired)
            assertFalse(controller.config.value.enabled)
            assertEquals(RemoteScope.DEFAULT, controller.config.value.scopeSet)
        } finally {
            controller.close(); scope.cancel(); cache.close()
        }
    }
}

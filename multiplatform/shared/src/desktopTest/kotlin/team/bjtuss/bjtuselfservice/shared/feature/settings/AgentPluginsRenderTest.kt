package team.bjtuss.bjtuselfservice.shared.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.agent.remote.*
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.feature.shell.MoreWorkspace

/** Only fictional data; no OS startup writes, account cache, school or Site requests. */
class AgentPluginsRenderTest {
    @Test fun renderApplicationEntryAndRemoteSetupInLightDarkAndLargeType() {
        SwingUtilities.invokeAndWait {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            CacheDatabaseSql.Schema.create(driver)
            val cache = CacheStore(driver)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = RemoteAgentController(RemoteAgentStore(cache), object : RemoteSecretStore {
                override suspend fun loadDeviceToken(): String? = null
                override suspend fun saveDeviceToken(token: String) {}
                override suspend fun loadPlatformAccess(): String? = null
                override suspend fun savePlatformAccess(value: String?) {}
                override suspend fun clear() {}
            }, object : RemoteRefreshExecutor {
                override fun currentAccountScope(): String? = null
                override suspend fun refreshAll(remote: Boolean): team.bjtuss.bjtuselfservice.shared.sync.RefreshReport? = null
                override suspend fun snapshotContent(scopes: Set<RemoteScope>, lastStates: Map<String, RemoteSourceState>): RemoteSnapshotContent? = null
            }, scope, host = object : RemoteHostPlatform {
                override val deviceName = "测试电脑"
                override val autostartSupported = true
                override val traySupported = true
                override fun isAutostartEnabled() = false
                override fun setAutostart(enabled: Boolean) = true
            })
            try {
                for (name in listOf("apps-light", "apps-dark", "setup-light", "setup-dark", "setup-large")) {
                    val scene = ImageComposeScene(width = if (name.startsWith("apps")) 1000 else 520,
                        height = if (name.startsWith("apps")) 850 else 3200,
                        density = Density(1f, if (name.endsWith("large")) 1.5f else 1f), coroutineContext = Dispatchers.Unconfined) {
                        PlatformAppTheme(useDarkTheme = name.endsWith("dark"), dynamicColorEnabled = false) {
                            CompositionLocalProvider(LocalRemoteAgentController provides controller) {
                                Surface(Modifier.fillMaxSize()) {
                                    if (name.startsWith("apps")) MoreWorkspace(AppPreferences(), {}, Modifier.fillMaxSize(), onOpenAgentPlugins = {})
                                    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { RemoteAgentCard(controller) }
                                }
                            }
                        }
                    }
                    try {
                        repeat(3) { scene.render() }
                        val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
                        val output = File("../../.artifacts/agent-plugins-ui/$name.png")
                        output.parentFile.mkdirs(); output.writeBytes(bytes)
                        assertTrue(bytes.size > 1000)
                    } finally { scene.close() }
                }
            } finally { controller.close(); scope.cancel(); cache.close() }
        }
    }
}

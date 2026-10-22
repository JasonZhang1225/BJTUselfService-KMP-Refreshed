package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import team.bjtuss.bjtuselfservice.shared.agent.remote.*
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.feature.settings.RemoteAgentCard
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport

/** 仅内存配置和虚构数据，不访问学校、真实凭据或私人 Site。 */
@Composable
fun DesktopRemoteAgentSmoke() {
    val scope = rememberCoroutineScope()
    val cache = remember {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        CacheStore(driver)
    }
    val controller = remember {
        RemoteAgentController(RemoteAgentStore(cache), object : RemoteSecretStore {
            override suspend fun loadDeviceToken(): String? = null
            override suspend fun saveDeviceToken(token: String) {}
            override suspend fun loadPlatformAccess(): String? = null
            override suspend fun savePlatformAccess(value: String?) {}
            override suspend fun clear() {}
        }, object : RemoteRefreshExecutor {
            override fun currentAccountScope(): String? = null
            override suspend fun refreshAll(remote: Boolean): RefreshReport? = null
            override suspend fun snapshotContent(scopes: Set<RemoteScope>, lastStates: Map<String, RemoteSourceState>) =
                RemoteSnapshotContent(emptyMap(), JsonObject(emptyMap()))
        }, scope, host = object : RemoteHostPlatform {
            override val deviceName = "测试 Mac（macOS）"
            override val usesDockForBackground = true
        })
    }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main) { check(javax.swing.SwingUtilities.isEventDispatchThread()) }
        ready = true
    }
    DisposableEffect(controller) { onDispose { controller.close(); cache.close() } }
    PlatformAppTheme(useDarkTheme = false, dynamicColorEnabled = false) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (ready) "主线程调度器正常 · Mac 插件服务测试" else "检查主线程调度器…")
                RemoteAgentCard(controller)
            }
        }
    }
}

package team.bjtuss.bjtuselfservice.shared.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentController

/**
 * 桌面端开机启动。只用系统认可、用户能在系统里看见并关闭的方式：
 * Windows 写当前用户的启动项（任务管理器“启动应用”可禁用），macOS 写登录项（系统设置“登录项”可关闭）。
 * 用户在系统里禁用后，这里如实显示为关闭，不会偷偷改回。
 */
interface AppStartupHost {
    val autostartSupported: Boolean
    /** 不支持时给用户看的原因（如“源码运行”）。 */
    val unsupportedReason: String
    /** 在系统里管理开机启动的位置，例如“任务管理器 → 启动应用”。 */
    val systemManageHint: String
    /** 静默启动的效果说明（各平台不同）。 */
    val silentStartDetail: String
    fun isAutostartEnabled(): Boolean
    fun setAutostart(enabled: Boolean): Boolean
    fun isSilentStart(): Boolean
    fun setSilentStart(enabled: Boolean)
    /** 打开系统的启动项设置；做不到返回 false。 */
    fun openSystemSettings(): Boolean = false
}

/** 只有桌面宿主（Windows、macOS 安装版）提供。 */
val LocalAppStartupHost = staticCompositionLocalOf<AppStartupHost?> { null }

@Composable
internal fun StartupSection(host: AppStartupHost, remote: RemoteAgentController?) {
    val scope = rememberCoroutineScope()
    var autostart by remember(host) { mutableStateOf(false) }
    var silent by remember(host) { mutableStateOf(host.isSilentStart()) }
    var loaded by remember(host) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(host) {
        // 每次进入页面都重新读取：用户可能刚在系统里关掉了。
        autostart = withContext(Dispatchers.Default) { host.isAutostartEnabled() }
        loaded = true
    }
    SettingsSection("启动与后台运行", "远程查询需要 App 在电脑上保持运行。") {
        SwitchRow("开机启动", if (host.autostartSupported) "登录电脑后自动启动（默认关闭）。也可以在${host.systemManageHint}里随时关闭。"
            else host.unsupportedReason, autostart,
            enabled = !busy && loaded && host.autostartSupported) { enabled ->
            busy = true
            error = null
            scope.launch {
                val success = withContext(Dispatchers.Default) { host.setAutostart(enabled) }
                autostart = withContext(Dispatchers.Default) { host.isAutostartEnabled() }
                if (!success || autostart != enabled) error = "开机启动设置未生效，请在${host.systemManageHint}里检查。"
                busy = false
            }
        }
        SwitchRow("静默启动", host.silentStartDetail, silent, enabled = host.autostartSupported) { enabled ->
            host.setSilentStart(enabled)
            silent = host.isSilentStart()
        }
        remote?.host?.let { remoteHost ->
            val config by remote.config.collectAsState()
            SwitchRow("最小化到托盘", if (remoteHost.traySupported) "最小化或关闭窗口时留在托盘中继续运行；从托盘菜单选择“退出”才会结束 App。"
                else "当前系统不支持托盘，关闭窗口会退出应用。", config.minimizeToTray,
                enabled = remoteHost.traySupported, onChange = remote::setMinimizeToTray)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var opened by remember { mutableStateOf<Boolean?>(null) }
            if (host.autostartSupported) {
                OutlinedButton(onClick = { opened = host.openSystemSettings() }) { Text("在系统设置中管理") }
            }
            if (opened == false) {
                Text("无法自动打开，请手动前往${host.systemManageHint}。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 功能名：电脑端有本机 Agent 和 ChatGPT 插件两部分；手机端只有 ChatGPT 插件。 */
fun agentPluginTitle(platform: team.bjtuss.bjtuselfservice.shared.PlatformInfo): String =
    if (platform.isMobile) "ChatGPT 插件" else "Agent 和 ChatGPT 插件"

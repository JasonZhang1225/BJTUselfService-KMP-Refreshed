package team.bjtuss.bjtuselfservice.shared.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.agent.*
import team.bjtuss.bjtuselfservice.shared.agent.remote.*
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalBottomBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalTopBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.TopScrollColumn

/** 设置与状态分别滚动；进入只读取本机状态，不安装、不配对、不触发学校刷新。 */
@Composable
internal fun AiAssistantSettingsScreen(model: SettingsScreenModel, platform: PlatformInfo, modifier: Modifier = Modifier) {
    val preferences by model.state.collectAsState()
    val integration = remember(platform.family) {
        if (platform.family == PlatformFamily.MacOS) platformAgentIntegration() else null
    }
    val remote = LocalRemoteAgentController.current
    val startup = LocalAppStartupHost.current
    var localStatus by remember(integration) { mutableStateOf<AgentIntegrationStatus?>(null) }
    var localLoading by remember(integration) { mutableStateOf(integration != null) }
    var statusRefreshKey by remember { mutableStateOf(0) }

    val settings = remember(integration, remote, startup, model) { movableContentOf {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        integration?.let {
            AgentAssistantCard(it, preferences.preferences.agentGradesAllowed, model::setAgentGradesAllowed,
                showStatus = false, statusRefreshKey = statusRefreshKey,
                onStatusChange = { status -> localStatus = status; localLoading = false })
        }
        remote?.let { RemoteAgentCard(it, showStatus = false) }
        startup?.let { StartupSection(it, remote) }
        if (integration == null && remote == null) {
            Text("当前平台暂不提供本机 AI 接入或远程同步设置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
    } }
    val dashboard = remember(integration, remote) { movableContentOf {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("运行状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp))
        if (integration != null || !platform.isMobile) LocalAssistantStatus(localStatus, localLoading, integration != null) {
            localLoading = true
            statusRefreshKey += 1
        }
        remote?.let { RemoteAssistantStatus(it) }
        }
    } }
    BoxWithConstraints(modifier.fillMaxSize().padding(top = LocalTopBarClearance.current, bottom = LocalBottomBarClearance.current)) {
        if (maxWidth >= 760.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AssistantPane(Modifier.weight(0.58f).fillMaxHeight()) { settings() }
                AssistantPane(Modifier.weight(0.42f).fillMaxHeight()) { dashboard() }
            }
        } else {
            AssistantPane(Modifier.fillMaxSize()) {
                dashboard()
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                settings()
            }
        }
    }
}

@Composable
private fun AssistantPane(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    TopScrollColumn(state = scroll, modifier = modifier.desktopTouchScroll(scroll),
        contentModifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
private fun StatusPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatusField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LocalAssistantStatus(status: AgentIntegrationStatus?, loading: Boolean, supported: Boolean, onRefresh: () -> Unit) {
    StatusPanel("本机 AI 助手") {
        val label = when {
            !supported -> "当前平台不支持"
            loading -> "正在检测…"
            status == null -> "暂时无法读取状态"
            !status.installable -> "当前运行环境不可安装"
            !status.installed -> "尚未安装"
            status.stale -> "需要重新安装"
            else -> "已安装，可读取本机缓存"
        }
        Text(label, style = MaterialTheme.typography.titleSmall,
            color = if (status?.stale == true || (!loading && supported && status == null)) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary)
        status?.let {
            it.unavailableReason?.let { reason -> Text(reason, style = MaterialTheme.typography.bodySmall) }
            if (it.installed) {
                StatusField("命令行", it.cliPath)
                StatusField("Skill", it.skillPath)
                it.installedVersion?.let { version -> StatusField("安装版本", version) }
                StatusField("Claude Code", when (it.claude) {
                    ClaudeSkillLink.SHARED, ClaudeSkillLink.LINKED -> "已接入"
                    ClaudeSkillLink.NOT_LINKED -> "已有同名目录，尚未链接"
                    ClaudeSkillLink.ABSENT -> "使用读取本机 Skill 的 AI 工具"
                })
            }
        }
        if (supported) OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("重新检测") }
    }
}

@Composable
private fun RemoteAssistantStatus(controller: RemoteAgentController) {
    val config by controller.config.collectAsState()
    val status by controller.status.collectAsState()
    val uri = LocalUriHandler.current
    StatusPanel(if (controller.mobile) "ChatGPT 插件" else "远程查询") {
        Text(if (!config.paired) "尚未配对" else if (controller.mobile && status.phase == RemotePhase.RUNNING) "已开启：每次刷新后上传。"
            else remotePhaseText(status.phase), style = MaterialTheme.typography.titleSmall,
            color = if (status.phase == RemotePhase.RUNNING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (config.paired) {
            StatusField("Site 地址", config.siteUrl.orEmpty())
            StatusField("最近连接", formatRemoteTime(status.lastSeenAt))
            StatusField("最近刷新学校数据", formatRemoteTime(status.lastRefreshAt))
            StatusField("最近上传快照", formatRemoteTime(status.lastUploadAt))
            if (!controller.mobile) {
                StatusField("下次计划同步", formatRemoteTime(status.nextUploadAt))
                StatusField("正在执行的请求", status.activeTasks.toString())
            }
            StatusField("允许上云", RemoteScope.entries.filter { it in config.scopeSet }.joinToString("、") { it.label })
            OutlinedButton(onClick = { config.siteUrl?.let(uri::openUri) }) { Text("打开我的 Site") }
        } else {
            Text("按“设置方法”搭建私人 Site 后，粘贴完整配对长码完成配对，无需填写地址。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

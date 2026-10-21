package team.bjtuss.bjtuselfservice.shared.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toLocalDateTime
import team.bjtuss.bjtuselfservice.shared.agent.AGENT_TIME_ZONE
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentController
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteIntervals
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemotePhase
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteScope
import team.bjtuss.bjtuselfservice.shared.agent.remote.REMOTE_SETUP_DOCUMENT_URL
import team.bjtuss.bjtuselfservice.shared.agent.remote.REMOTE_SETUP_PROMPT

/** 只有提供了远程控制器的宿主（首版仅 Windows）才显示“远程查询”设置。 */
val LocalRemoteAgentController = staticCompositionLocalOf<RemoteAgentController?> { null }

/**
 * 应用页“Agent 和 ChatGPT 插件”：配对私人 Site、选择上云范围与周期、查看最近同步与错误、暂停或解除绑定。
 * 默认关闭；开启前必须明确同意上云内容可被 Site 与 ChatGPT 读取。
 */
@Composable
internal fun RemoteAgentCard(controller: RemoteAgentController, showStatus: Boolean = true) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val config by controller.config.collectAsState()
    val status by controller.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun launchAction(action: suspend () -> Pair<String, Boolean>?) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            message = action()
            busy = false
        }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("远程查询", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "通过 ChatGPT Sites 架构插件实现远程查询。电脑轮询 Site 并定时上传缓存数据快照。" +
                    "你可在手机等其他设备的 ChatGPT（Chat Work 和 Codex 模式均可）调用插件，获取你的课表、作业等数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "该功能不会上传你的学校系统密码，需要你的电脑一直开机并运行交大自由行 App 作为服务端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("设置方法", style = MaterialTheme.typography.labelLarge)
            Text("1. 在 ChatGPT Work 中，发送以下内容：", style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(REMOTE_SETUP_PROMPT, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(REMOTE_SETUP_PROMPT))
                    message = "设置提示词已复制。" to false
                }) { Text("复制提示词") }
                OutlinedButton(onClick = { uriHandler.openUri(REMOTE_SETUP_DOCUMENT_URL) }) { Text("查看文档") }
            }
            Text("2. 拿到 Site 地址和配对码后，在本页下方填写，选择允许上云的数据，确认同意后点击“配对并开启”。" +
                "若配对码过期，可在你的 Site 页面重新生成。", style = MaterialTheme.typography.bodySmall)
            Text("远程同步默认关闭，配对前请由连接助手确认私人 Site 可供电脑访问。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!config.paired) {
                PairingForm(busy) { url, code, scopes, consent ->
                    launchAction {
                        controller.pair(url, code, null, scopes, consent)?.let { it to true } ?: ("配对成功，已开始同步。" to false)
                    }
                }
            } else {
                if (showStatus) {
                    Text(remotePhaseText(status.phase), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append("Site：${config.siteUrl}\n")
                            append("最近上传：${formatRemoteTime(status.lastUploadAt)}　最近连接：${formatRemoteTime(status.lastSeenAt)}")
                            if (status.activeTasks > 0) append("\n正在执行 ${status.activeTasks} 个手机请求")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status.lastError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                SwitchRow("开启远程同步", "关闭后不再上传与轮询，本机正常使用不受影响。", config.enabled) { enabled ->
                    controller.setEnabled(enabled)
                }
                Text("允许上云的数据", style = MaterialTheme.typography.labelLarge)
                RemoteScope.entries.forEach { item ->
                    val checked = item in config.scopeSet
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, enabled = !busy, onCheckedChange = { on ->
                            val next = if (on) config.scopeSet + item else config.scopeSet - item
                            launchAction { controller.setScopes(next)?.let { it to true } }
                        })
                        Text(item.label + if (item in RemoteScope.DEFAULT) "" else "（默认关闭）")
                    }
                }
                IntervalFields(config.uploadIntervalSeconds, config.pollIntervalSeconds) { upload, poll ->
                    val effective = controller.setIntervals(upload, poll)
                    message = "实际生效：每 ${effective.uploadIntervalSeconds / 60} 分 ${effective.uploadIntervalSeconds % 60} 秒刷新上报，" +
                        "每 ${effective.pollIntervalSeconds} 秒检查手机请求。" to false
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { launchAction { controller.uploadNow() to false } }, enabled = !busy && config.enabled,
                        modifier = Modifier.weight(1f)) {
                        Text(if (busy) "处理中…" else "立即同步")
                    }
                    OutlinedButton(onClick = {
                        launchAction {
                            if (controller.unpair()) "已解除本机绑定。云端数据请在你的 Site 上撤销设备或清除。" to false
                            else "已解除绑定，但系统安全存储清除失败。" to true
                        }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("解除绑定") }
                }
            }
            controller.host?.let { host ->
                Text("后台运行", style = MaterialTheme.typography.labelLarge)
                var autostart by remember(host) { mutableStateOf(false) }
                var startupLoaded by remember(host) { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(host) {
                    autostart = withContext(Dispatchers.IO) { host.isAutostartEnabled() }
                    startupLoaded = true
                }
                SwitchRow("开机启动", if (host.autostartSupported) "默认关闭。登录 Windows 后自动启动；远程同步已开启时在后台运行。"
                    else "安装版支持开机启动，当前运行环境无法设置。", autostart,
                    enabled = !busy && startupLoaded && host.autostartSupported) { enabled ->
                    launchAction {
                        val success = withContext(Dispatchers.IO) { host.setAutostart(enabled) }
                        autostart = withContext(Dispatchers.IO) { host.isAutostartEnabled() }
                        if (success && autostart == enabled) null else "开机启动设置未生效，请稍后重试。" to true
                    }
                }
                SwitchRow("最小化到托盘", if (host.traySupported) "开启后，最小化或点击关闭会留在托盘中后台运行。"
                    else "当前系统不支持托盘，关闭窗口会退出应用。", config.minimizeToTray,
                    enabled = host.traySupported && !busy, onChange = controller::setMinimizeToTray)
                Text("可从托盘重新打开窗口；在托盘菜单选择“退出”会结束 App 并停止远程查询。" +
                    "关闭此开关后，点击窗口关闭按钮会退出 App。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message?.let { (text, error) ->
                Text(text, style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PairingForm(
    busy: Boolean,
    onPair: (String, String, Set<RemoteScope>, Boolean) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var scopes by remember { mutableStateOf(RemoteScope.DEFAULT) }
    var consent by remember { mutableStateOf(false) }
    OutlinedTextField(url, { url = it.trim() }, label = { Text("Site 地址（https://…）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(code, { code = it.uppercase().take(12) }, label = { Text("配对码（在 Site 页面生成）") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Text("允许上云的数据", style = MaterialTheme.typography.labelLarge)
    RemoteScope.entries.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item in scopes, onCheckedChange = { on -> scopes = if (on) scopes + item else scopes - item })
            Text(item.label + if (item in RemoteScope.DEFAULT) "" else "（默认关闭）")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = consent, onCheckedChange = { consent = it })
        Text("我同意所选数据上传到我自己的 Site，并可被 Site 与 ChatGPT 读取。", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = { onPair(url, code, scopes, consent) }, enabled = !busy && consent && url.isNotBlank() && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "正在配对…" else "配对并开启")
    }
}

@Composable
private fun IntervalFields(uploadSeconds: Int, pollSeconds: Int, onApply: (Int, Int) -> Unit) {
    var upload by remember(uploadSeconds) { mutableStateOf((uploadSeconds / 60).toString()) }
    var poll by remember(pollSeconds) { mutableStateOf(pollSeconds.toString()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(upload, { upload = it.filter(Char::isDigit).take(4) }, label = { Text("刷新上报（分钟）") }, singleLine = true,
            modifier = Modifier.weight(1f))
        OutlinedTextField(poll, { poll = it.filter(Char::isDigit).take(3) }, label = { Text("检查请求（秒）") }, singleLine = true,
            modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            onApply((upload.toIntOrNull() ?: RemoteIntervals.DEFAULT_UPLOAD_SECONDS / 60) * 60,
                poll.toIntOrNull() ?: RemoteIntervals.DEFAULT_POLL_SECONDS)
        }) { Text("应用") }
    }
    Text("范围：刷新上报 1 分钟–24 小时（默认 30 分钟）；检查请求 1–300 秒（默认 5 秒）。没有请求时不访问学校。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

internal fun remotePhaseText(phase: RemotePhase): String = when (phase) {
    RemotePhase.DISABLED -> "已暂停：远程同步未开启。"
    RemotePhase.NOT_PAIRED -> "尚未配对。"
    RemotePhase.WAITING_FOR_LOGIN -> "等待 App 登录完成后开始同步。"
    RemotePhase.RUNNING -> "运行中。"
    RemotePhase.ACCOUNT_MISMATCH -> "已暂停：当前登录的学校账号与配对时不同，请解除绑定后重新配对。"
    RemotePhase.NEEDS_PAIRING -> "已停止：Site 不再接受本机（设备被撤销或令牌失效），请重新配对。"
    RemotePhase.PROTOCOL_MISMATCH -> "已停止：电脑端与 Site 模板版本不一致，请更新。"
}

internal fun formatRemoteTime(epochSeconds: Long?): String = epochSeconds?.let {
    val time = Instant.fromEpochSeconds(it).toLocalDateTime(AGENT_TIME_ZONE)
    "${time.month.ordinal + 1}月${time.day}日 ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
} ?: "—"

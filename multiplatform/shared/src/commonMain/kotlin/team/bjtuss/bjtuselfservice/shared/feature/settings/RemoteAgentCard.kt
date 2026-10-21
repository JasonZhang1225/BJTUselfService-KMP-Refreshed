package team.bjtuss.bjtuselfservice.shared.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import team.bjtuss.bjtuselfservice.shared.agent.AGENT_TIME_ZONE
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentController
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteIntervals
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemotePhase
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteScope

/** 只有提供了远程控制器的宿主（首版仅 Windows）才显示“远程查询”设置。 */
val LocalRemoteAgentController = staticCompositionLocalOf<RemoteAgentController?> { null }

/**
 * 设置页“远程查询（手机）”：配对私人 Site、选择上云范围与周期、查看最近同步与错误、暂停或解除绑定。
 * 默认关闭；开启前必须明确同意上云内容可被 Site 与 ChatGPT 读取。
 */
@Composable
internal fun RemoteAgentCard(controller: RemoteAgentController) {
    val scope = rememberCoroutineScope()
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
            Text("远程查询（手机）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "让手机通过你自己的私人 Site 查询这台电脑同步的数据。电脑主动连接 Site，不需要公网 IP。" +
                    "所选数据会上传到你的 Site，可被 Site 与 ChatGPT 读取；学校密码不会上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "测试功能：私人 Site 的平台访问方式尚未实测，请先用测试 Site 验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (!config.paired) {
                PairingForm(busy) { url, code, access, scopes, consent ->
                    launchAction {
                        controller.pair(url, code, access, scopes, consent)?.let { it to true } ?: ("配对成功，已开始同步。" to false)
                    }
                }
            } else {
                Text(phaseText(status.phase), style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append("Site：${config.siteUrl}\n")
                        append("最近上传：${formatTime(status.lastUploadAt)}　最近连接：${formatTime(status.lastSeenAt)}")
                        if (status.activeTasks > 0) append("\n正在执行 ${status.activeTasks} 个手机请求")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status.lastError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                controller.host?.takeIf { it.autostartSupported }?.let { host ->
                    var autostart by remember(host) { mutableStateOf(host.isAutostartEnabled()) }
                    SwitchRow("开机后在后台启动", "默认关闭。登录 Windows 后自动在后台运行，不弹出窗口。", autostart) { enabled ->
                        autostart = if (host.setAutostart(enabled)) enabled else host.isAutostartEnabled()
                    }
                }
                Text(
                    "开启后关闭主窗口仍在后台运行（任务栏通知区域可重新打开）；在通知区域选择“退出”才会停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    onPair: (String, String, String?, Set<RemoteScope>, Boolean) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var access by remember { mutableStateOf("") }
    var scopes by remember { mutableStateOf(RemoteScope.DEFAULT) }
    var consent by remember { mutableStateOf(false) }
    OutlinedTextField(url, { url = it.trim() }, label = { Text("Site 地址（https://…）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(code, { code = it.uppercase().take(12) }, label = { Text("配对码（在 Site 页面生成）") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    OutlinedTextField(access, { access = it }, label = { Text("平台访问凭据（如 Site 要求，可留空）") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
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
    Button(onClick = { onPair(url, code, access, scopes, consent) }, enabled = !busy && consent && url.isNotBlank() && code.isNotBlank(),
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
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun phaseText(phase: RemotePhase): String = when (phase) {
    RemotePhase.DISABLED -> "已暂停：远程同步未开启。"
    RemotePhase.NOT_PAIRED -> "尚未配对。"
    RemotePhase.WAITING_FOR_LOGIN -> "等待 App 登录完成后开始同步。"
    RemotePhase.RUNNING -> "运行中。"
    RemotePhase.ACCOUNT_MISMATCH -> "已暂停：当前登录的学校账号与配对时不同，请解除绑定后重新配对。"
    RemotePhase.NEEDS_PAIRING -> "已停止：Site 不再接受本机（设备被撤销或令牌失效），请重新配对。"
    RemotePhase.PROTOCOL_MISMATCH -> "已停止：电脑端与 Site 模板版本不一致，请更新。"
}

private fun formatTime(epochSeconds: Long?): String = epochSeconds?.let {
    val time = Instant.fromEpochSeconds(it).toLocalDateTime(AGENT_TIME_ZONE)
    "${time.month.ordinal + 1}月${time.day}日 ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
} ?: "—"

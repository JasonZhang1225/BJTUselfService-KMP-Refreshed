package team.bjtuss.bjtuselfservice.shared.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.toLocalDateTime
import team.bjtuss.bjtuselfservice.shared.agent.AGENT_TIME_ZONE
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentController
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteIntervals
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemotePhase
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteScope
import team.bjtuss.bjtuselfservice.shared.agent.remote.REMOTE_SETUP_PROMPT
import team.bjtuss.bjtuselfservice.shared.agent.remote.REMOTE_TEMPLATE_RELEASE

/** 只有提供了远程控制器的宿主才显示“远程查询”设置。 */
val LocalRemoteAgentController = staticCompositionLocalOf<RemoteAgentController?> { null }

/**
 * 应用页“Agent 和 ChatGPT 插件”的远程部分：两张卡片。
 * - 远程查询：说明 → 设置方法（框起来的步骤，只留“复制提示词”）→ 配对表单或同步设置。
 * 开机启动、静默启动与托盘在 [StartupSection]。
 * 默认关闭；开启前必须明确同意上云内容可被 Site 与 ChatGPT 读取。
 */
@Composable
internal fun RemoteAgentCard(controller: RemoteAgentController, showStatus: Boolean = true) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val config by controller.config.collectAsState()
    val status by controller.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var promptCopied by remember { mutableStateOf(false) }

    fun launchAction(action: suspend () -> Pair<String, Boolean>?) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            message = action()
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val mobile = controller.mobile
        SettingsSection(
            title = if (mobile) "ChatGPT 插件" else "远程查询（ChatGPT 插件）",
            subtitle = if (mobile) {
                "在 ChatGPT 里查询课表、作业、考试和邮箱。每次在 App 里刷新后，把这台设备的缓存上传到你自己的私人 Site，" +
                    "不会上传学校系统密码。手机不在后台常驻，也不执行“要最新数据”这类即时查询（那需要电脑端在线）。"
            } else {
                "在手机等设备的 ChatGPT 里查询课表、作业、考试和邮箱。电脑定时把本机缓存上传到你自己的私人 Site，" +
                    "不会上传学校系统密码；电脑需要保持开机并运行交大自由行。"
            },
        ) {
            SetupSteps(paired = config.paired, copied = promptCopied, mobile = mobile) {
                clipboard.setText(AnnotatedString(REMOTE_SETUP_PROMPT))
                promptCopied = true
            }
            if (!config.paired) {
                GroupLabel("配对")
                PairingForm(busy) { info, scopes, consent ->
                    launchAction {
                        controller.pair(info, scopes, consent)?.let { it to true }
                            ?: ((if (mobile) "配对成功。之后每次刷新都会上传到 Site。" else "配对成功，已开始同步。") to false)
                    }
                }
            } else {
                if (showStatus) {
                    Text(remotePhaseText(status.phase), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "最近上传 ${formatRemoteTime(status.lastUploadAt)} · 最近连接 ${formatRemoteTime(status.lastSeenAt)}",
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
                GroupLabel("允许上云的数据")
                ScopeChecklist(config.scopeSet, enabled = !busy) { next ->
                    launchAction { controller.setScopes(next)?.let { it to true } }
                }
                GroupLabel("同步频率")
                if (!mobile) IntervalFields(config.uploadIntervalSeconds, config.pollIntervalSeconds) { upload, poll ->
                    val effective = controller.setIntervals(upload, poll)
                    message = "实际生效：每 ${effective.uploadIntervalSeconds / 60} 分 ${effective.uploadIntervalSeconds % 60} 秒刷新上报，" +
                        "每 ${effective.pollIntervalSeconds} 秒检查手机请求。" to false
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { launchAction { controller.uploadNow() to false } }, enabled = !busy && config.enabled,
                        modifier = Modifier.weight(1f)) {
                        Text(if (busy) "处理中…" else if (mobile) "刷新并上传" else "立即同步")
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

/** 一张设置卡片：标题、一句说明、内容，统一间距与字号。 */
@Composable
internal fun SettingsSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp))
}

/** 框起来的设置步骤：提示词不展开，只提供复制按钮。 */
@Composable
private fun SetupSteps(paired: Boolean, copied: Boolean, mobile: Boolean = false, onCopy: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设置方法", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SetupStep(1, if (mobile) "已经在电脑上搭好 Site：登录 Site 为这台设备生成配对信息（Site 建议与当前 App 一致，使用模板 $REMOTE_TEMPLATE_RELEASE）。" +
                "还没有 Site：复制搭建提示词发给 ChatGPT（Work 模式），由它搭建只有你能访问的私人 Site 和插件。"
            else "复制搭建提示词，发给 ChatGPT（Work 模式）。它会用 Sites 为你搭建只有你能访问的私人 Site 和插件。") {
                FilledTonalButton(onClick = onCopy) { Text(if (copied) "已复制" else "复制提示词") }
            }
            SetupStep(2, "打开助手提供的 Site 链接并登录，点击“生成配对信息”，把完整配对长码粘贴到下方，无需填写地址。过期可在 Site 重新生成，无需回到对话取码。")
            SetupStep(3, "选择允许上云的数据，确认同意后点击“配对并开启”。")
            if (paired) {
                Text("本机已配对。更新 Site 模板或换新 Site 时，可再次复制提示词。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SetupStep(number: Int, text: String, action: (@Composable () -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

@Composable
private fun ScopeChecklist(selected: Set<RemoteScope>, enabled: Boolean = true, onChange: (Set<RemoteScope>) -> Unit) {
    Column {
        RemoteScope.entries.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = item in selected, enabled = enabled, onCheckedChange = { on ->
                    onChange(if (on) selected + item else selected - item)
                })
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.bodyLarge)
                    Text(remoteScopeDetail(item), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

internal fun remoteScopeDetail(scope: RemoteScope): String = when (scope) {
    RemoteScope.SCHEDULE -> "课表、物理实验与演示实验（前 1 周到后 8 周）"
    RemoteScope.EXAMS -> "考试时间地点"
    RemoteScope.ASSIGNMENTS -> "课程平台、物理在线、CITEL 的作业与截止时间"
    RemoteScope.GRADES -> "全部成绩（默认关闭）"
    RemoteScope.MAIL -> "收件箱最近邮件的发件人、主题与摘要；可按需读取正文（默认关闭）"
}

@Composable
private fun PairingForm(
    busy: Boolean,
    onPair: (String, Set<RemoteScope>, Boolean) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var scopes by remember { mutableStateOf(RemoteScope.DEFAULT) }
    var consent by remember { mutableStateOf(false) }
    OutlinedTextField(code, { code = it.take(16_384) }, label = { Text("完整配对信息（bjtuss-pair-v1:…）") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth())
    GroupLabel("允许上云的数据")
    ScopeChecklist(scopes) { scopes = it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = consent, onCheckedChange = { consent = it })
        Text("我同意所选数据上传到我自己的 Site，并可被 Site 与 ChatGPT 读取。", style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = { onPair(code, scopes, consent) }, enabled = !busy && consent && code.isNotBlank(),
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
internal fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
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

package team.bjtuss.bjtuselfservice.shared.feature.physicslab

import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalBottomBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalTopBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.ReportTopScrollState

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun PhysicsLabSettings(model: PhysicsLabModel, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(model) {
        model.initialize()
        username = model.state.value.username
        password = model.savedPassword()
        ready = true
    }
    PhysicsLabSettingsForm(
        state, username, password, ready,
        onUsername = { username = it }, onPassword = { password = it },
        onSave = { scope.launch { model.configure(username, password, state.enabled) } },
        onTwoWeeksChanged = { lab, twoWeeks -> scope.launch { model.setTwoWeeks(lab, twoWeeks) } },
        modifier = modifier, showTitle = showTitle,
    )
}

@Composable
internal fun PhysicsLabSettingsForm(
    state: PhysicsLabState,
    username: String,
    password: String,
    ready: Boolean,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    onTwoWeeksChanged: (PhysicsLab, Boolean) -> Unit = { _, _ -> },
) {
    val scrollState = rememberScrollState()
    ReportTopScrollState(scrollState)
    Column(
        modifier.fillMaxSize()
            .verticalScroll(scrollState)
            .desktopTouchScroll(scrollState)
            // 顶栏留白必须在滚动内容里：外层 padding 会让视口止于栏底，栏后只剩纯色，
            // 原生玻璃采不到内容。与设置/成绩页同一套：首项让开栏高，滚起来穿进栏后。
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = 20.dp + LocalTopBarClearance.current,
                bottom = 20.dp + LocalBottomBarClearance.current,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showTitle) Text("物理实验同步", style = MaterialTheme.typography.headlineSmall)
        Text("请填写物理实验系统网站 wlsy.bjtu.edu.cn 的账号和密码。", style = MaterialTheme.typography.bodyMedium)
        Text("连接校园网后，每次同步读取已选实验；校外使用上次成功的缓存。实验系统使用校园网 HTTP 接口。", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(username, onUsername, label = { Text("实验系统账号") }, singleLine = true, enabled = ready && !state.refreshing, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, onPassword, label = { Text("实验系统密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = ready && !state.refreshing, modifier = Modifier.fillMaxWidth())
        Button(onClick = onSave, enabled = ready && !state.refreshing) {
            Text(if (state.refreshing) "正在同步…" else "保存账号")
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text("专题、设计实验默认做两周，可按本学期实际安排修改；修改后会自动保存。", style = MaterialTheme.typography.bodySmall)
        if (state.fromCache) Text("当前显示缓存", style = MaterialTheme.typography.labelMedium)
        state.labs.forEach { lab ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(lab.name, style = MaterialTheme.typography.titleSmall)
                    Text(lab.type.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${lab.dates.joinToString("、")} · ${lab.timeRange ?: "第${lab.period}时段"}")
                    Text("${lab.location} · ${lab.teacher}")
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = lab.weekCount == 2,
                            enabled = ready && !state.refreshing,
                            role = Role.Checkbox,
                            onValueChange = { onTwoWeeksChanged(lab, it) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = lab.weekCount == 2, onCheckedChange = null, enabled = ready && !state.refreshing)
                        Text("做两周", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

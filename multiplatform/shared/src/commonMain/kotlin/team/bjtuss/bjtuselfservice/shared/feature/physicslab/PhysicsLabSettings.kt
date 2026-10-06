package team.bjtuss.bjtuselfservice.shared.feature.physicslab

import androidx.compose.foundation.layout.*
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
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp + bottomInset),
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
        Text("专题、设计类按连续两周显示，其余实验按一周显示（沿用参考项目规则）。", style = MaterialTheme.typography.bodySmall)
        if (state.fromCache) Text("当前显示缓存", style = MaterialTheme.typography.labelMedium)
        state.labs.forEach { lab ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(lab.name, style = MaterialTheme.typography.titleSmall)
                    Text("${lab.dates.joinToString("、")} · ${lab.timeRange ?: "第${lab.period}时段"}")
                    Text("${lab.location} · ${lab.teacher}")
                }
            }
        }
    }
}

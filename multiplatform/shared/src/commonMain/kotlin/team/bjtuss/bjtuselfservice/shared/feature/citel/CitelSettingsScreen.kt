package team.bjtuss.bjtuselfservice.shared.feature.citel

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.feature.settings.IndependentAccountSettings

@Composable
fun CitelSettingsWorkspace(model: CitelModel, modifier: Modifier = Modifier, holdNetwork: Boolean = false,
    onSaved: () -> Unit = {}) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var username by remember(model) { mutableStateOf(state.username) }
    var password by remember(model) { mutableStateOf("") }
    var ready by remember(model) { mutableStateOf(false) }
    var saving by remember(model) { mutableStateOf(false) }
    var saved by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model) {
        model.initialize()
        username = model.state.value.username
        ready = true
    }
    IndependentAccountSettings(username, password, state.configured && username.trim() == state.username,
        ready && !holdNetwork, saving, { username = it }, { password = it }, onSave = {
            saving = true
            scope.launch {
                var success = false
                try { success = model.saveAccountAndSync(username, password); saved = success }
                finally { saving = false }
                if (success) onSaved()
            }
        }, message = if (state.failed) state.message else if (saved) "账号已保存" else null,
        failed = state.failed, modifier = modifier, websiteUrl = "$CITEL_BASE/")
}

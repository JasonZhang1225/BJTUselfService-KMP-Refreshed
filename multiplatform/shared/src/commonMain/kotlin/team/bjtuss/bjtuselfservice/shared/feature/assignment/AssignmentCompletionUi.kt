package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppErrorBanner

internal val LocalAssignmentCompletionStore = staticCompositionLocalOf<AssignmentCompletionStore?> { null }

@Composable
internal fun assignmentCompletionState(): AssignmentCompletionState {
    val store = LocalAssignmentCompletionStore.current
    return key(store) {
        val flow = remember { store?.state ?: MutableStateFlow(AssignmentCompletionState()) }
        flow.collectAsState().value
    }
}

@Composable
internal fun AssignmentCompletionCheckbox(key: AssignmentCompletionKey, title: String) {
    val store = LocalAssignmentCompletionStore.current ?: return
    val state = assignmentCompletionState()
    val scope = rememberCoroutineScope()
    var saving by remember(key, store) { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Checkbox(
            checked = key in state.completedKeys,
            enabled = state.loaded && !saving,
            onCheckedChange = { checked ->
                saving = true
                scope.launch {
                    try { withContext(Dispatchers.Default) { store.setCompleted(key, checked) } }
                    finally { saving = false }
                }
            },
            modifier = Modifier.size(48.dp).semantics { contentDescription = "标记${title}为已完成" },
        )
        Text("已完成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AssignmentCompletionFeedback() {
    val store = LocalAssignmentCompletionStore.current ?: return
    val failure = assignmentCompletionState().failure ?: return
    val scope = rememberCoroutineScope()
    val reload: () -> Unit = { scope.launch { withContext(Dispatchers.Default) { store.reload() } } }
    if (failure == AssignmentCompletionFailure.LOAD) {
        AppErrorBanner(title = "完成标记读取失败", message = "暂时无法读取本机完成标记，请重试。", onRetry = reload)
    } else {
        AppErrorBanner(title = "完成标记未保存", message = "原有标记已保留，请重新勾选重试。",
            onDismiss = reload, dismissLabel = "知道了")
    }
}

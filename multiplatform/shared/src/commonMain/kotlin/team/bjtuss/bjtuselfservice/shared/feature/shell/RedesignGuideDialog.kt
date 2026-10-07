package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun RedesignGuideDialog(saveFailed: Boolean, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("新版使用提醒") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("本次改版已将底栏调整为“首页”和“应用”。其他功能可以从“应用”进入。")
                Text("你可以在“应用 → 设置 → 底栏显示”中添加常用页面，并拖动调整顺序。之后更新版本会保留你的底栏设置。")
                Text("新增物理实验功能。请在“应用 → 设置 → 功能开关”中启用“物理实验”，再从“应用”进入，也可以将它添加到底栏。")
                if (saveFailed) {
                    Text("确认保存失败，请重试。", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认") } },
    )
}

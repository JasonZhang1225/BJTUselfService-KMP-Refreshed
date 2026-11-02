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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** 警告框按钮的角色，对应 `UIAlertAction.Style`。 */
enum class AppleAlertRole {
    /** 普通动作。 */
    Default,

    /** 取消/关闭；最多一个。iOS 放在固定位置，Android/桌面点外部或返回键也触发它。 */
    Cancel,

    /** 破坏性动作（清除、删除、舍弃），iOS 与回退对话框都显示为红色。 */
    Destructive,
}

class AppleAlertAction(
    val label: String,
    val role: AppleAlertRole = AppleAlertRole.Default,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 系统警告框：一句话的确认、警告或结果，配 1–3 个按钮。
 *
 * iOS 由宿主弹出真正的 `UIAlertController`（iOS 26 起为液态玻璃外观），不能下滑或点外部关闭，
 * 必须选一个按钮；Android/桌面回退为 Material [AlertDialog]。有正文内容（列表、勾选、二维码、输入）
 * 的用 [AppleSheet] / [AppleSheetOrAlert]，不要塞进 [message]。
 *
 * 任何按钮被点后系统都会自己关掉警告框，调用方在 [AppleAlertAction.onClick] 里把显示状态清掉即可。
 * [onDismissRequest] 只在没有取消按钮时，供回退对话框的点外部/返回键使用。
 */
@Composable
fun AppleAlert(
    title: String,
    message: String?,
    actions: List<AppleAlertAction>,
    onDismissRequest: () -> Unit,
) {
    require(actions.isNotEmpty()) { "AppleAlert needs at least one action" }
    val host = LocalNativeSheetPresenter.current
    val presentation = remember(host) { host?.createAlert() }
    if (presentation != null) {
        SideEffect { presentation.update(title, message, actions) }
        LaunchedEffect(presentation) { presentation.present() }
        DisposableEffect(presentation) { onDispose { presentation.dismiss() } }
        return
    }

    val layout = appleAlertFallbackLayout(actions)
    AlertDialog(
        onDismissRequest = layout.cancel?.onClick ?: onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = layout.cancel != null,
            dismissOnClickOutside = layout.cancel != null,
        ),
        title = { Text(title) },
        text = message?.let { { Text(it, Modifier.verticalScroll(rememberScrollState())) } },
        confirmButton = {
            if (layout.stacked) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    layout.primary.forEach { AlertFallbackButton(it) }
                }
            } else {
                layout.primary.forEach { AlertFallbackButton(it) }
            }
        },
        dismissButton = layout.cancel?.let { { AlertFallbackButton(it) } },
    )
}

@Composable
private fun AlertFallbackButton(action: AppleAlertAction) {
    TextButton(onClick = action.onClick, enabled = action.enabled) {
        Text(
            action.label,
            color = if (action.role == AppleAlertRole.Destructive && action.enabled) {
                MaterialTheme.colorScheme.error
            } else {
                Color.Unspecified
            },
        )
    }
}

/** Material 对话框的按钮布局：取消放 dismiss 槽，其余放 confirm 槽；超过两个非取消动作时竖排。 */
internal class AppleAlertFallbackLayout(
    val primary: List<AppleAlertAction>,
    val cancel: AppleAlertAction?,
    val stacked: Boolean,
)

internal fun appleAlertFallbackLayout(actions: List<AppleAlertAction>): AppleAlertFallbackLayout {
    val cancel = actions.firstOrNull { it.role == AppleAlertRole.Cancel }
    val primary = actions.filter { it !== cancel }
    return AppleAlertFallbackLayout(primary, cancel, stacked = primary.size > 1)
}

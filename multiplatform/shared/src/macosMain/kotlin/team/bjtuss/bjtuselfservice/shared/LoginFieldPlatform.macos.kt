package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

actual fun usernameKeyboardOptions(): KeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Number,
)

actual fun passwordKeyboardOptions(): KeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
)

/**
 * macOS 原生端由 Compose 自己的文本输入会话提供系统级编辑菜单，
 * 不再像旧版 JVM 桌面端那样用 AWT 弹出菜单覆盖。
 */
@Composable
actual fun ProvideNativeTextContextMenu(content: @Composable () -> Unit) {
    content()
}

/**
 * macOS 原生端用 Compose 输入框：Skiko 的 macOS 文本输入服务已接上 AppKit
 * 文本输入上下文，输入法、选区与系统菜单都是原生的；不再走 JVM 端那套
 * JNA 注入的 NSTextField 桥（原生应用里没有 AWT 事件线程可挂）。
 */
@Composable
actual fun PlatformCredentialFields(
    username: String,
    password: String,
    enabled: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordImeAction: () -> Unit,
    modifier: Modifier,
) {
    ComposeCredentialFields(
        username = username,
        password = password,
        enabled = enabled,
        onUsernameChange = onUsernameChange,
        onPasswordChange = onPasswordChange,
        onPasswordImeAction = onPasswordImeAction,
        modifier = modifier.focusGroup(),
    )
}

/** macOS 没有软键盘，输入法由系统顶栏管理，这里无需收起动作。 */
actual fun dismissPlatformKeyboard() = Unit

actual fun Modifier.platformLoginKeyboardAvoidance(enabled: Boolean): Modifier = this

actual val showsPasswordVisibilityToggle: Boolean
    get() = true

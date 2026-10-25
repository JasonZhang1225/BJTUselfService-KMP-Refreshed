package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.compositionLocalOf

/** Host-owned macOS controls. Credentials stay in memory and use the existing login workflow. */
class NativeDesktopLogin(
    val username: String,
    val password: String,
    val rememberCredentials: Boolean,
    val canRememberCredentials: Boolean,
    val busy: Boolean,
    val message: String,
    val onUsernameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onRememberChange: (Boolean) -> Unit,
    val onLogin: () -> Unit,
)

class NativeDesktopGuide(
    val title: String,
    val body: String,
    val remaining: Int,
    val saveFailed: Boolean,
    val onConfirm: () -> Unit,
)

interface NativeDesktopPresentation {
    fun showHome(value: team.bjtuss.bjtuselfservice.shared.feature.home.NativeDesktopHome?) {}
    fun showLogin(value: NativeDesktopLogin?)
    fun showGuide(value: NativeDesktopGuide?)
    fun updateNavigation(title: String, canGoBack: Boolean, onBack: () -> Unit)
    fun updateAction(value: NativeBarAction?)
}

val LocalNativeDesktopPresentation = compositionLocalOf<NativeDesktopPresentation?> { null }

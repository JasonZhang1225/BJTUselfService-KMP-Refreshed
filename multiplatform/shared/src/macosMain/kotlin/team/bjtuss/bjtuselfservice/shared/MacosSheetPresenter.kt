package team.bjtuss.bjtuselfservice.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSViewController
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeSheetPresenter

/** Each presentation owns a Compose body; SwiftUI owns its sheet, title, and buttons. */
@OptIn(ExperimentalForeignApi::class)
class MacosSheetState(
    val id: String,
    val viewController: NSViewController,
    val title: String?,
    val confirmLabel: String?,
    val confirmEnabled: Boolean,
    val onConfirm: (() -> Unit)?,
    val todayLabel: String?,
    val todayEnabled: Boolean,
    val onToday: (() -> Unit)?,
    val dismissLabel: String?,
    val dismissEnabled: Boolean,
    val showDismissButton: Boolean,
    val onDismiss: () -> Unit,
)

@OptIn(ExperimentalForeignApi::class)
internal class MacosSheetPresenter(
    private val onChanged: (String, MacosSheetState?) -> Unit,
) : NativeSheetPresenter {
    private val id = platform.Foundation.NSUUID().UUIDString
    private val body = mutableStateOf<(@Composable () -> Unit)?>(null)
    private var host: MacosComposeHost? = null
    private var state: MacosSheetState? = null
    private var presented = false

    override fun createPresentation(): NativeSheetPresenter = MacosSheetPresenter(onChanged)

    override fun update(
        content: @Composable () -> Unit,
        onDismissRequest: () -> Unit,
        title: String?, confirmLabel: String?, confirmEnabled: Boolean, onConfirm: (() -> Unit)?,
        todayLabel: String?, todayEnabled: Boolean, onToday: (() -> Unit)?,
        dismissLabel: String?, dismissEnabled: Boolean, showDismissButton: Boolean,
    ) {
        body.value = content
        val current = host ?: MacosComposeHost { body.value?.invoke() }.also { host = it }
        val controller = state?.viewController ?: NSViewController().apply { view = current.view }
        state = MacosSheetState(
            id, controller, title, confirmLabel, confirmEnabled, onConfirm,
            todayLabel, todayEnabled, onToday, dismissLabel, dismissEnabled, showDismissButton,
            onDismissRequest,
        )
        if (presented) onChanged(id, state)
    }

    override fun present(needsFullHeight: Boolean) {
        presented = true
        onChanged(id, state)
    }

    override fun dismiss() {
        if (presented) onChanged(id, null)
        presented = false
        host?.dispose()
        host = null
        state = null
        body.value = null
    }
}

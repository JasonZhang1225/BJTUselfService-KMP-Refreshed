package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Host-owned sheet presentation.
 *
 * The shared UI supplies the sheet content, while the platform host owns the
 * presentation controller. This keeps UIKit responsible for the actual iOS
 * sheet chrome, detents, grabber, blur and dismissal gesture.
 */
interface NativeSheetPresenter {
    fun update(
        content: @Composable () -> Unit,
        onDismissRequest: () -> Unit,
        title: String? = null,
        confirmLabel: String? = null,
        confirmEnabled: Boolean = true,
        onConfirm: (() -> Unit)? = null,
        dismissLabel: String? = null,
        dismissEnabled: Boolean = true,
        showDismissButton: Boolean = true,
    )

    fun present(needsFullHeight: Boolean)

    fun dismiss()
}

val LocalNativeSheetPresenter = compositionLocalOf<NativeSheetPresenter?> { null }

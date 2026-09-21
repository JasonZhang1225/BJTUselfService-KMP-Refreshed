package team.bjtuss.bjtuselfservice.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIPresentationController
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeSheetPresenter

/**
 * Presents shared Compose content through UIKit's real sheet presentation
 * controller. The presenter is intentionally host-owned: the shared screen
 * only describes content and dismissal, while UIKit owns the sheet chrome and
 * interactive transition.
 */
@OptIn(
    ExperimentalForeignApi::class,
    ExperimentalComposeUiApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)
class IosNativeSheetPresenter(
    private val owner: () -> UIViewController,
) : NativeSheetPresenter {
    private var currentContent by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var dismissRequest: () -> Unit = {}
    private var title: String? = null
    private var confirmLabel: String? = null
    private var confirmEnabled: Boolean = true
    private var todayLabel: String? = null
    private var todayEnabled: Boolean = true
    private var dismissLabel: String? = null
    private var dismissEnabled: Boolean = true
    private var showDismissButton: Boolean = true
    private var confirmTarget: SheetActionTarget? = null
    private var todayTarget: SheetActionTarget? = null
    private var dismissTarget: SheetActionTarget? = null
    private var sheetController: UIViewController? = null
    private var sheetDelegate: SheetDelegate? = null

    override fun update(
        content: @Composable () -> Unit,
        onDismissRequest: () -> Unit,
        title: String?,
        confirmLabel: String?,
        confirmEnabled: Boolean,
        onConfirm: (() -> Unit)?,
        todayLabel: String?,
        todayEnabled: Boolean,
        onToday: (() -> Unit)?,
        dismissLabel: String?,
        dismissEnabled: Boolean,
        showDismissButton: Boolean,
    ) {
        currentContent = content
        dismissRequest = onDismissRequest
        this.title = title
        this.confirmLabel = confirmLabel
        this.confirmEnabled = confirmEnabled
        this.todayLabel = todayLabel
        this.todayEnabled = todayEnabled
        this.dismissLabel = dismissLabel
        this.dismissEnabled = dismissEnabled
        this.showDismissButton = showDismissButton
        confirmTarget = onConfirm?.let(::SheetActionTarget)
        todayTarget = onToday?.let(::SheetActionTarget)
        dismissTarget = if (showDismissButton) SheetActionTarget(onDismissRequest) else null
        sheetController?.let(::updateNativeHeader)
    }

    override fun present(needsFullHeight: Boolean) {
        val existing = sheetController
        if (existing != null) {
            BJTUConfigureSheetPresentation(existing, needsFullHeight)
            updateNativeHeader(existing)
            return
        }

        val contentController = ComposeUIViewController(
            configure = { opaque = false },
        ) {
            currentContent?.invoke()
        }
        val sheet = BJTUCreateNativeSheetController(
            content = contentController,
            title = title,
            confirmLabel = confirmLabel,
            confirmEnabled = confirmEnabled,
            confirmTarget = confirmTarget,
            todayLabel = todayLabel,
            todayEnabled = todayEnabled,
            todayTarget = todayTarget,
            dismissLabel = dismissLabel,
            dismissEnabled = dismissEnabled,
            dismissTarget = dismissTarget,
        ) ?: error("Unable to create native sheet controller")
        BJTUInstallNativeSheetMaterial(sheet)
        BJTUConfigureSheetPresentation(sheet, needsFullHeight)

        val delegate = SheetDelegate { handleDismissed() }
        sheetDelegate = delegate
        sheetController = sheet
        owner().presentViewController(sheet, animated = true) {
            BJTUInstallNativeSheetMaterial(sheet)
        }
        BJTUAttachSheetPresentationDelegate(sheet, delegate)
    }

    private fun updateNativeHeader(sheet: UIViewController) {
        BJTUConfigureNativeSheetHeader(
            sheet = sheet,
            title = title,
            confirmLabel = confirmLabel,
            confirmEnabled = confirmEnabled,
            confirmTarget = confirmTarget,
            todayLabel = todayLabel,
            todayEnabled = todayEnabled,
            todayTarget = todayTarget,
            dismissLabel = dismissLabel,
            dismissEnabled = dismissEnabled,
            dismissTarget = dismissTarget,
        )
    }

    override fun dismiss() {
        val sheet = sheetController ?: return
        sheetController = null
        sheetDelegate = null
        confirmTarget = null
        todayTarget = null
        dismissTarget = null
        sheet.dismissViewControllerAnimated(flag = true, completion = null)
    }

    private fun handleDismissed() {
        sheetController = null
        sheetDelegate = null
        confirmTarget = null
        todayTarget = null
        dismissTarget = null
        dismissRequest()
    }

    private class SheetActionTarget(
        private val action: () -> Unit,
    ) : NSObject() {
        @ObjCAction
        fun invoke(sender: NSObject?) {
            action()
        }
    }

    private class SheetDelegate(
        private val onDidDismiss: () -> Unit,
    ) : NSObject(), UIAdaptivePresentationControllerDelegateProtocol {
        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
            onDidDismiss()
        }
    }
}

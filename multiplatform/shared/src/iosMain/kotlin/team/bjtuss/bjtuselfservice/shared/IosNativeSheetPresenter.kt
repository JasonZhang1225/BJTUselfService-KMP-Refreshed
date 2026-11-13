package team.bjtuss.bjtuselfservice.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.UINavigationController
import platform.UIKit.UIRectEdgeBottom
import platform.UIKit.UIRectEdgeNone
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController
import platform.UIKit.modalInPresentation
import platform.darwin.NSObject
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_time
import platform.darwin.dispatch_get_main_queue
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeAlertPresentation
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeSheetPresenter
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeSheetContentBoundsHandled
import team.bjtuss.bjtuselfservice.shared.logging.AppLog

/**
 * Presents shared Compose content through UIKit's real sheet presentation
 * controller. The host supplies an owner; each AppleSheet lifetime gets an
 * independent presenter so content, dismissal and pending transitions cannot
 * be overwritten by another sheet. UIKit owns the chrome and transition.
 */
@OptIn(
    ExperimentalForeignApi::class,
    ExperimentalComposeUiApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)
class IosNativeSheetPresenter private constructor(
    private val host: SheetHostCoordinator,
) : NativeSheetPresenter {
    constructor(owner: () -> UIViewController) : this(SheetHostCoordinator(owner))

    override fun createPresentation(): NativeSheetPresenter = IosNativeSheetPresenter(host)

    override fun createAlert(): NativeAlertPresentation = IosNativeAlertPresentation(host.owner)

    private var interactiveDismissDisabled = false

    override fun setInteractiveDismissDisabled(disabled: Boolean) {
        if (interactiveDismissDisabled == disabled) return
        interactiveDismissDisabled = disabled
        sheetController?.modalInPresentation = disabled
    }

    private var scrollableBody = false

    override fun setScrollableBody(enabled: Boolean) {
        if (scrollableBody == enabled) return
        scrollableBody = enabled
        sheetController?.let(::applyContentLayout)
    }

    private fun applyContentLayout(sheet: UIViewController) {
        val content = (sheet as? UINavigationController)?.topViewController ?: return
        // Keep the native header above the body, while allowing scrollable content
        // to draw under the home indicator. Compose puts that inset in its content.
        content.edgesForExtendedLayout = if (scrollableBody) UIRectEdgeBottom else UIRectEdgeNone
    }

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
    private var pendingDismiss = false
    private var awaitingRetry = false
    private var retryCount = 0

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

    private var fittedContentHeight: Float? = null

    override fun setFittedContentHeight(heightDp: Float) {
        if (fittedContentHeight == heightDp) return
        fittedContentHeight = heightDp
        val targetSheet = sheetController ?: return
        if (pendingDismiss || host.dismissingSheet === targetSheet || targetSheet.isBeingDismissed()) return
        // 高度来自 Compose 的 onSizeChanged（布局进行中）；同步改 detent 会让宿主在测量中途
        // 改尺寸并崩溃（updateRootConstraints called while measuring），推迟到下一轮主线程。
        dispatch_async(dispatch_get_main_queue()) {
            val current = sheetController ?: return@dispatch_async
            if (current !== targetSheet || pendingDismiss || host.dismissingSheet === current || current.isBeingDismissed()) {
                return@dispatch_async
            }
            val height = fittedContentHeight ?: return@dispatch_async
            BJTUSetSheetFittedHeight(current, height.toDouble())
        }
    }

    override fun present(needsFullHeight: Boolean) {
        val existing = sheetController
        if (existing != null) {
            // 打开动画还没结束就被关、又马上被重新打开：取消待执行的关闭，继续用这张 sheet。
            pendingDismiss = false
            awaitingRetry = false
            BJTUConfigureSheetPresentation(existing, needsFullHeight)
            fittedContentHeight?.let { BJTUSetSheetFittedHeight(existing, it.toDouble()) }
            updateNativeHeader(existing)
            return
        }
        // 同一宿主同一时刻只允许一张 UIKit sheet：前一张还没关完或仍在展示时，排队等关闭回调。
        val activeOther = host.activeSheet
        if (host.dismissingSheet != null || (activeOther != null && activeOther !== sheetController)) {
            host.queuePresentation(this, needsFullHeight)
            AppLog.d(
                "native-sheet",
                "present queued dismissing=${host.dismissingSheet != null} activeOther=${activeOther != null}",
            )
            return
        }
        host.clearQueuedPresentation(this)
        awaitingRetry = false

        val contentController = ComposeUIViewController(
            configure = { opaque = false },
        ) {
            CompositionLocalProvider(LocalNativeSheetContentBoundsHandled provides true) {
                currentContent?.invoke()
            }
        }
        val sheet = BJTUCreateNativeSheetController(
            content = NativeSheetContentHost(contentController),
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
        applyContentLayout(sheet)
        sheet.modalInPresentation = interactiveDismissDisabled
        BJTUInstallNativeSheetMaterial(sheet)
        BJTUConfigureSheetPresentation(sheet, needsFullHeight)
        fittedContentHeight?.let { BJTUSetSheetFittedHeight(sheet, it.toDouble()) }

        val delegate = SheetDelegate { handleDismissed(sheet) }
        sheetDelegate = delegate
        sheetController = sheet
        host.activeSheet = sheet
        host.presentingSheet = sheet
        BJTUAttachSheetPresentationDelegate(sheet, delegate)
        AppLog.d("native-sheet", "present call full=$needsFullHeight")
        host.owner().presentViewController(sheet, animated = true) {
            if (host.presentingSheet === sheet) host.presentingSheet = null
            BJTUInstallNativeSheetMaterial(sheet)
            AppLog.d(
                "native-sheet",
                "present completion pendingDismiss=$pendingDismiss hasPresenting=${sheet.presentingViewController != null}",
            )
            // 打开动画期间收到的关闭请求（例如刚弹出就点了选项）在这里补做。
            if (pendingDismiss && sheetController === sheet) {
                performDismiss(sheet)
            }
        }
        // 适配呈现可能选择另一 presentation controller，再对实际控制器确认绑定。
        BJTUAttachSheetPresentationDelegate(sheet, delegate)
        if (sheet.presentingViewController == null) {
            // 宿主正被别的转场占用（例如其他弹窗正在关闭），present 被拒绝：稍后重试，不让入口失效。
            AppLog.d("native-sheet", "present rejected immediately retry=$retryCount")
            clearSheetReferences(sheet)
            scheduleRetry(needsFullHeight)
        } else {
            retryCount = 0
        }
    }

    private fun scheduleRetry(needsFullHeight: Boolean) {
        if (retryCount >= MAX_PRESENT_RETRIES) {
            retryCount = 0
            awaitingRetry = false
            AppLog.d("native-sheet", "present retry exhausted")
            // 实在弹不出来：把 Compose 状态复位，下次点击可以重新打开。
            dismissRequest()
            return
        }
        retryCount += 1
        awaitingRetry = true
        host.queuePresentation(this, needsFullHeight)
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, PRESENT_RETRY_DELAY_NS),
            dispatch_get_main_queue(),
        ) {
            if (!awaitingRetry || sheetController != null) return@dispatch_after
            if (host.dismissingSheet != null || host.activeSheet != null) {
                // 前一张 sheet 仍在关闭，保持排队等关闭完成回调，不再盲撞 UIKit。
                return@dispatch_after
            }
            if (host.queuedPresenter !== this) return@dispatch_after
            host.clearQueuedPresentation(this)
            awaitingRetry = false
            present(needsFullHeight)
        }
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
        applyContentLayout(sheet)
    }

    override fun dismiss() {
        awaitingRetry = false
        host.clearQueuedPresentation(this)
        val sheet = sheetController ?: run {
            AppLog.d("native-sheet", "dismiss skipped: no active sheet")
            return
        }
        if (pendingDismiss) {
            AppLog.d("native-sheet", "dismiss skipped: already pending")
            return
        }
        if (host.dismissingSheet === sheet || sheet.isBeingDismissed()) {
            AppLog.d("native-sheet", "dismiss skipped: already dismissing")
            clearSheetReferences(sheet)
            host.dismissingSheet = sheet
            return
        }
        if (host.presentingSheet === sheet || sheet.isBeingPresented()) {
            pendingDismiss = true
            AppLog.d("native-sheet", "dismiss deferred while presenting")
            dispatch_after(
                dispatch_time(DISPATCH_TIME_NOW, PRESENT_RETRY_DELAY_NS),
                dispatch_get_main_queue(),
            ) {
                if (pendingDismiss && sheetController === sheet && !sheet.isBeingPresented()) {
                    performDismiss(sheet)
                }
            }
            return
        }
        performDismiss(sheet)
    }

    private fun clearSheetReferences(sheet: UIViewController) {
        pendingDismiss = false
        awaitingRetry = false
        fittedContentHeight = null
        if (sheetController === sheet) sheetController = null
        sheetDelegate = null
        confirmTarget = null
        todayTarget = null
        dismissTarget = null
        if (host.activeSheet === sheet) {
            host.activeSheet = null
        }
        if (host.presentingSheet === sheet) {
            host.presentingSheet = null
        }
    }

    private fun performDismiss(sheet: UIViewController) {
        val presentingController = sheet.presentingViewController
        val presentedOnPresenting = presentingController?.presentedViewController
        clearSheetReferences(sheet)
        if (presentingController == null) {
            AppLog.d("native-sheet", "dismiss abort: no presentingViewController")
            flushQueuedPresentation()
            return
        }
        // 用 NSObject.isEqual（==）比对原生 VC 指针语义，不用易受 Kotlin/Native 包装影响的不等。
        if (presentedOnPresenting != null && presentedOnPresenting != sheet) {
            AppLog.d(
                "native-sheet",
                "dismiss abort: host presents different VC sameRef=${presentedOnPresenting === sheet}",
            )
            flushQueuedPresentation()
            return
        }
        host.dismissingSheet = sheet
        AppLog.d(
            "native-sheet",
            "dismiss started sameRef=${presentedOnPresenting === sheet} sheetHasChild=${sheet.presentedViewController != null}",
        )
        presentingController.dismissViewControllerAnimated(flag = true) {
            AppLog.d("native-sheet", "dismiss completed")
            if (host.dismissingSheet === sheet) {
                host.dismissingSheet = null
            }
            flushQueuedPresentation()
        }
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, DISMISS_WATCHDOG_DELAY_NS),
            dispatch_get_main_queue(),
        ) {
            if (host.dismissingSheet !== sheet) return@dispatch_after
            val stillPresenting = sheet.presentingViewController
            if (stillPresenting == null) {
                AppLog.d("native-sheet", "dismiss watchdog: already detached")
                host.dismissingSheet = null
                flushQueuedPresentation()
            } else if (!sheet.isBeingDismissed()) {
                AppLog.d("native-sheet", "dismiss watchdog: retry without animation")
                stillPresenting.dismissViewControllerAnimated(flag = false) {
                    AppLog.d("native-sheet", "dismiss watchdog completed")
                    if (host.dismissingSheet === sheet) {
                        host.dismissingSheet = null
                    }
                    flushQueuedPresentation()
                }
            }
        }
    }

    private fun flushQueuedPresentation() {
        if (host.dismissingSheet != null || host.activeSheet != null) return
        val next = host.queuedPresenter ?: return
        val needsFullHeight = host.queuedNeedsFullHeight
        host.queuedPresenter = null
        AppLog.d("native-sheet", "flushing queued present")
        next.present(needsFullHeight)
    }

    private fun handleDismissed(sheet: UIViewController) {
        // UIKit 的旧 delegate 可能在程序关闭/后续呈现之后才回调。
        // 只处理它所属的控制器，不能清空新控制器或调用新页面的 dismissRequest。
        val ownedByHandle = sheetController === sheet
        val wasDismissing = host.dismissingSheet === sheet
        if (!ownedByHandle && !wasDismissing) return
        AppLog.d("native-sheet", "didDismiss callback owned=$ownedByHandle")
        if (wasDismissing) {
            host.dismissingSheet = null
        }
        if (ownedByHandle) {
            clearSheetReferences(sheet)
            dismissRequest()
        }
        flushQueuedPresentation()
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

    /** 协调同一 UIKit 宿主控制器上串行切换的原生弹层转场，避免多张 AppleSheet 抢占同一呈现槽位。 */
    private class SheetHostCoordinator(
        val owner: () -> UIViewController,
    ) {
        var activeSheet: UIViewController? = null
        var presentingSheet: UIViewController? = null
        var dismissingSheet: UIViewController? = null
        var queuedPresenter: IosNativeSheetPresenter? = null
        var queuedNeedsFullHeight: Boolean = false

        fun queuePresentation(presenter: IosNativeSheetPresenter, needsFullHeight: Boolean) {
            queuedPresenter = presenter
            queuedNeedsFullHeight = needsFullHeight
        }

        fun clearQueuedPresentation(presenter: IosNativeSheetPresenter) {
            if (queuedPresenter === presenter) {
                queuedPresenter = null
            }
        }
    }
}

private const val MAX_PRESENT_RETRIES = 10
private const val PRESENT_RETRY_DELAY_NS = 120_000_000L
private const val DISMISS_WATCHDOG_DELAY_NS = 1_200_000_000L

/** Uses this presentation's insets, never the underlying tab/navigation bars. */
@OptIn(ExperimentalForeignApi::class)
private class NativeSheetContentHost(private val content: UIViewController) : UIViewController(null, null) {
    override fun viewDidLoad() {
        super.viewDidLoad()
        addChildViewController(content)
        view.addSubview(content.view)
        content.didMoveToParentViewController(this)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val width = view.bounds.useContents { size.width }
        val height = view.bounds.useContents { size.height }
        val left = view.safeAreaInsets.useContents { left }
        val right = view.safeAreaInsets.useContents { right }
        content.view.setFrame(CGRectMake(left, 0.0, (width - left - right).coerceAtLeast(0.0), height))
    }
}

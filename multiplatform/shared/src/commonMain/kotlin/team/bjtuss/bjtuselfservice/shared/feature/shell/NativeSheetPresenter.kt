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
    /** 每个 AppleSheet 组合独占自己的内容、回调与关闭过程，宿主仅提供创建入口。 */
    fun createPresentation(): NativeSheetPresenter = this

    /** Scrollable sheets own their bottom inset inside the scroll content. */
    fun setScrollableBody(enabled: Boolean) {}

    fun update(
        content: @Composable () -> Unit,
        onDismissRequest: () -> Unit,
        title: String? = null,
        confirmLabel: String? = null,
        confirmEnabled: Boolean = true,
        onConfirm: (() -> Unit)? = null,
        todayLabel: String? = null,
        todayEnabled: Boolean = true,
        onToday: (() -> Unit)? = null,
        dismissLabel: String? = null,
        dismissEnabled: Boolean = true,
        showDismissButton: Boolean = true,
    )

    fun present(needsFullHeight: Boolean)

    /** 按内容定高的 sheet 回报正文高度（dp，iOS 上 1pt == 1dp）；宿主据此调整 detent。 */
    fun setFittedContentHeight(heightDp: Float) {}

    fun dismiss()

    /** 宿主能弹系统警告框时返回一个独立的呈现；null 表示走 Compose 对话框回退。 */
    fun createAlert(): NativeAlertPresentation? = null

    /** 弹层不允许下滑/点外部关闭（如必须读完的版本说明）。 */
    fun setInteractiveDismissDisabled(disabled: Boolean) {}
}

/** 一个 [AppleAlert] 的宿主呈现：iOS 上是 `UIAlertController`，按钮回调随重组更新。 */
interface NativeAlertPresentation {
    fun update(title: String, message: String?, actions: List<AppleAlertAction>)

    fun present()

    fun dismiss()
}

internal val LocalDismissNativeSheet = compositionLocalOf<(() -> Unit)?> { null }

val LocalNativeSheetPresenter = compositionLocalOf<NativeSheetPresenter?> { null }
/** The modal host bounds its body using the sheet's own UIKit safe area. */
val LocalNativeSheetContentBoundsHandled = compositionLocalOf { false }

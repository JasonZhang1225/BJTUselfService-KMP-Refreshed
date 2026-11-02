package team.bjtuss.bjtuselfservice.shared

import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertActionStyleDestructive
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIViewController
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleAlertAction
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleAlertRole
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeAlertPresentation

/**
 * [team.bjtuss.bjtuselfservice.shared.feature.shell.AppleAlert] 的 iOS 实现：系统 `UIAlertController`，
 * 外观（iOS 26 起的液态玻璃）、按钮排布和关闭动画都由 UIKit 负责。
 *
 * 按钮回调按下标取最新一次 [update] 的动作，重组换了闭包也不会调到旧状态。
 * 从当前最上层的呈现控制器弹出，这样已经开着的 sheet 上也能弹；宿主正在转场时稍后重试。
 */
internal class IosNativeAlertPresentation(
    private val owner: () -> UIViewController,
) : NativeAlertPresentation {
    private var title: String = ""
    private var message: String? = null
    private var actions: List<AppleAlertAction> = emptyList()
    private var alert: UIAlertController? = null
    private var nativeActions: List<UIAlertAction> = emptyList()
    private var wantsPresent = false
    private var retryCount = 0

    override fun update(title: String, message: String?, actions: List<AppleAlertAction>) {
        this.title = title
        this.message = message
        this.actions = actions
        val current = alert ?: return
        if (current.title != title) current.title = title
        if (current.message != message) current.message = message
        nativeActions.forEachIndexed { index, nativeAction ->
            val enabled = actions.getOrNull(index)?.enabled ?: false
            if (nativeAction.enabled != enabled) nativeAction.enabled = enabled
        }
    }

    override fun present() {
        wantsPresent = true
        if (alert != null) return
        val controller = UIAlertController.alertControllerWithTitle(
            title = title,
            message = message,
            preferredStyle = UIAlertControllerStyleAlert,
        )
        val created = actions.mapIndexed { index, action ->
            UIAlertAction.actionWithTitle(
                title = action.label,
                style = when (action.role) {
                    AppleAlertRole.Default -> UIAlertActionStyleDefault
                    AppleAlertRole.Cancel -> UIAlertActionStyleCancel
                    AppleAlertRole.Destructive -> UIAlertActionStyleDestructive
                },
            ) { _ ->
                // UIKit 已在关闭警告框；先断开引用，Compose 随后移除组合时不会再去 dismiss。
                if (alert === controller) {
                    alert = null
                    nativeActions = emptyList()
                    wantsPresent = false
                }
                actions.getOrNull(index)?.onClick?.invoke()
            }.also {
                it.enabled = action.enabled
                controller.addAction(it)
            }
        }
        // 只有一个普通动作配一个取消时，把普通动作设为首选（加粗），与系统确认框一致。
        actions.withIndex()
            .filter { it.value.role == AppleAlertRole.Default }
            .singleOrNull()
            ?.takeIf { actions.any { action -> action.role == AppleAlertRole.Cancel } }
            ?.let { controller.preferredAction = created[it.index] }

        topPresenter().presentViewController(controller, animated = true, completion = null)
        if (controller.presentingViewController == null) {
            // 别的弹层正在开/关时 UIKit 会拒绝这次呈现；稍后重试，不让弹窗丢失。
            scheduleRetry()
            return
        }
        retryCount = 0
        alert = controller
        nativeActions = created
    }

    override fun dismiss() {
        wantsPresent = false
        val current = alert ?: return
        alert = null
        nativeActions = emptyList()
        if (current.presentingViewController != null && !current.isBeingDismissed()) {
            current.dismissViewControllerAnimated(flag = true, completion = null)
        }
    }

    private fun topPresenter(): UIViewController {
        var top = owner()
        while (true) {
            val next = top.presentedViewController ?: break
            if (next.isBeingDismissed()) break
            top = next
        }
        return top
    }

    private fun scheduleRetry() {
        if (retryCount >= MAX_ALERT_PRESENT_RETRIES) {
            // 实在弹不出来：按取消处理，让 Compose 复位显示状态，下次还能再弹。
            retryCount = 0
            wantsPresent = false
            actions.firstOrNull { it.role == AppleAlertRole.Cancel }?.onClick?.invoke()
            return
        }
        retryCount += 1
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, ALERT_PRESENT_RETRY_DELAY_NS),
            dispatch_get_main_queue(),
        ) {
            if (wantsPresent && alert == null) present()
        }
    }
}

private const val MAX_ALERT_PRESENT_RETRIES = 10
private const val ALERT_PRESENT_RETRY_DELAY_NS = 120_000_000L

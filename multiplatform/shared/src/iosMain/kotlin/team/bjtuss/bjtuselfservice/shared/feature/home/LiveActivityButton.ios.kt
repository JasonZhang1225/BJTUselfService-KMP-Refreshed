package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIAction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIButton
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIMenu
import platform.UIKit.UIMenuElementAttributesDestructive
import platform.UIKit.UIMenuOptionsDisplayInline

// 与 iosApp/iosApp/CourseLiveActivityCoordinator.swift 的 CourseLiveActivityBridge 一一对应。
private const val COMMAND_NOTIFICATION = "BJTULiveActivityCommand"
private const val STATUS_NOTIFICATION = "BJTULiveActivityStatusDidChange"
private const val SUPPORTED_KEY = "BJTULiveActivity.supported"
private const val ENABLED_KEY = "BJTULiveActivity.enabled"
private const val ACTIVE_ID_KEY = "BJTULiveActivity.activeId"
private const val LEAD_MINUTES_KEY = "BJTULiveActivity.leadMinutes"

/** 课前多久自动出现，与 Swift `courseLiveActivityLeadMinuteOptions` / `courseLiveActivityDefaultLeadMinutes` 一致。 */
private val LEAD_MINUTE_OPTIONS = listOf(10, 20)
private const val DEFAULT_LEAD_MINUTES = 10

private data class LiveActivityStatus(
    /** 系统设置里允许实时活动。 */
    val enabled: Boolean,
    /** 正在显示的那一项（[LiveActivityTarget.id]）。 */
    val activeId: String?,
    /** 课前多少分钟自动出现。 */
    val leadMinutes: Int,
)

/** 宿主协调器还没写过状态时返回 null，不显示按钮。 */
private fun readLiveActivityStatus(): LiveActivityStatus? {
    val defaults = NSUserDefaults.standardUserDefaults
    if (!defaults.boolForKey(SUPPORTED_KEY)) return null
    val lead = defaults.integerForKey(LEAD_MINUTES_KEY).toInt().takeIf { it in LEAD_MINUTE_OPTIONS } ?: DEFAULT_LEAD_MINUTES
    return LiveActivityStatus(defaults.boolForKey(ENABLED_KEY), defaults.stringForKey(ACTIVE_ID_KEY), lead)
}

@Composable
private fun rememberLiveActivityStatus(): LiveActivityStatus? {
    var status by remember { mutableStateOf(readLiveActivityStatus()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val token = center.addObserverForName(
            name = STATUS_NOTIFICATION,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> status = readLiveActivityStatus() }
        status = readLiveActivityStatus()
        onDispose { center.removeObserver(token) }
    }
    return status
}

/**
 * - start：立即显示这一项（可早于自动出现的时间），也用于误关后重新开启；
 * - arm：恢复“课前 N 分钟自动显示”，不立即出现；
 * - lead：只换了提前时间（已写入 UserDefaults），让宿主重新对齐；
 * - stop：关闭，这一项不再自动出现。
 */
private fun sendLiveActivityCommand(action: String, target: LiveActivityTarget) {
    val info = mapOf<Any?, Any?>(
        "action" to action,
        "id" to target.id,
        "day" to target.date.toString(),
        "name" to target.name,
        "place" to target.place,
        "teacher" to target.teacher,
        "startMinute" to target.startMinute.toString(),
        "endMinute" to target.endMinute.toString(),
        "color" to target.color.toString(),
        "kind" to target.kind,
    )
    NSNotificationCenter.defaultCenter.postNotificationName(COMMAND_NOTIFICATION, `object` = null, userInfo = info)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal actual fun LiveActivityButton(target: LiveActivityTarget, modifier: Modifier) {
    val status = rememberLiveActivityStatus() ?: return
    val active = status.activeId == target.id
    var pressed by remember { mutableStateOf(false) }
    val description = if (active) "实时活动，已开启" else "实时活动，未开启"
    Box(
        // 卡片整体可点并合并无障碍信息；这里单独成一个按钮节点，读屏双击直接开启或关闭。
        modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = description
            onClick {
                sendLiveActivityCommand(if (active) "stop" else "start", target)
                true
            }
        },
    ) {
        LiveActivityChip(active, Color(0xFF000000 or target.color), pressed, Modifier.fillMaxWidth())
        UIKitView(
            factory = { LiveActivityMenuButton(onPressedChange = { pressed = it }) },
            modifier = Modifier.matchParentSize(),
            update = { view -> view.menu = buildMenu(target, active, status) },
            onRelease = { view -> view.onPressedChange = {} },
            properties = UIKitInteropProperties(
                // 默认的协同模式会先扣住触摸约 150ms 判断是否滚动：快速点按弹不出菜单，
                // 外层卡片还会同时出现按压态。按钮很小，触摸直接交给 UIKit。
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
                // 胶囊由 Compose 画（与状态胶囊同主题、同字号缩放），原生按钮透明叠在上面只负责弹菜单。
                placedAsOverlay = true,
                isNativeAccessibilityEnabled = false,
            ),
        )
    }
}

/** 透明的原生按钮：原生 UIMenu 必须挂在真正的 UIButton 上；按下状态回传给 Compose 胶囊。 */
@OptIn(ExperimentalForeignApi::class)
private class LiveActivityMenuButton(
    var onPressedChange: (Boolean) -> Unit,
) : UIButton(frame = CGRectMake(0.0, 0.0, 44.0, 22.0)) {
    init {
        showsMenuAsPrimaryAction = true
        backgroundColor = UIColor.clearColor
    }

    override fun setHighlighted(highlighted: Boolean) {
        super.setHighlighted(highlighted)
        onPressedChange(highlighted)
    }
}

private fun buildMenu(target: LiveActivityTarget, active: Boolean, status: LiveActivityStatus): UIMenu {
    if (!status.enabled) {
        val settings = UIAction.actionWithTitle(
            title = "前往设置开启",
            image = UIImage.systemImageNamed("gear"),
            identifier = null,
        ) { _ ->
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any?>(), completionHandler = null)
            }
        }
        return UIMenu.menuWithTitle(title = "实时活动已在系统设置中关闭", children = listOf(settings))
    }
    // 选中的提前时间打勾，并记作以后所有课程的默认值；没在显示时顺便恢复这一项的自动出现。
    val leads = LEAD_MINUTE_OPTIONS.map { minutes ->
        val leadMinute = (target.startMinute - minutes).coerceAtLeast(0)
        UIAction.actionWithTitle(
            title = "课前 $minutes 分钟显示 (${hm(leadMinute)})",
            image = if (minutes == status.leadMinutes) UIImage.systemImageNamed("checkmark") else null,
            identifier = null,
        ) { _ ->
            NSUserDefaults.standardUserDefaults.setInteger(minutes.toLong(), LEAD_MINUTES_KEY)
            sendLiveActivityCommand(if (active) "lead" else "arm", target)
        }
    }
    val control = if (active) {
        UIAction.actionWithTitle(
            title = "关闭",
            image = UIImage.systemImageNamed("xmark.circle"),
            identifier = null,
        ) { _ -> sendLiveActivityCommand("stop", target) }.also {
            it.attributes = UIMenuElementAttributesDestructive
        }
    } else {
        UIAction.actionWithTitle(
            title = if (isWithinLeadWindow(target, status.leadMinutes)) "重新开启" else "立即开启",
            image = UIImage.systemImageNamed("dot.radiowaves.left.and.right"),
            identifier = null,
        ) { _ -> sendLiveActivityCommand("start", target) }
    }
    return UIMenu.menuWithTitle(
        title = "",
        children = listOf(
            UIMenu.menuWithTitle(
                title = "",
                image = null,
                identifier = null,
                options = UIMenuOptionsDisplayInline,
                children = leads,
            ),
            control,
        ),
    )
}

/** 已到课前 N 分钟：此时没在显示说明被关掉了，按钮叫“重新开启”。 */
private fun isWithinLeadWindow(target: LiveActivityTarget, leadMinutes: Int): Boolean {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    if (now.date != target.date) return now.date > target.date
    val minute = now.hour * 60 + now.minute
    return minute >= target.startMinute - leadMinutes
}

private fun hm(minuteOfDay: Int): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

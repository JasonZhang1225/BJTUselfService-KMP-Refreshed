package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import team.bjtuss.bjtuselfservice.shared.feature.widget.widgetClock

/** 宿主（androidApp 的 CourseLiveUpdateCoordinator）发布的状态。 */
data class AndroidLiveActivityStatus(
    /** 通知权限与“实时动态”（推广通知）都允许。 */
    val enabled: Boolean,
    /** 正在显示的那一项（[LiveActivityTarget.id]）。 */
    val activeId: String?,
    /** 课前多少分钟自动出现。 */
    val leadMinutes: Int,
)

/**
 * 实时动态（Android 16 Live Updates）只有通知接口，由 androidApp 宿主代办；卡片发命令、读状态。
 * 命令与 iOS 相同：start 立即显示、arm 恢复课前自动显示、lead 只换了提前时间、stop 关闭。
 */
interface AndroidLiveActivityHost {
    /** 系统不支持实时动态（Android 16 以下）时为 null，不显示按钮。 */
    val status: StateFlow<AndroidLiveActivityStatus?>
    fun command(action: String, target: LiveActivityTarget)
    fun setLeadMinutes(minutes: Int)
    /** 通知或实时动态被关时：申请通知权限，或前往系统设置；允许后开启这一项。 */
    fun requestEnable(target: LiveActivityTarget)
}

private var liveActivityHost: AndroidLiveActivityHost? = null

/** 由 androidApp 在 Application/Activity 创建时调用，重复调用无副作用。 */
fun installAndroidLiveActivityHost(host: AndroidLiveActivityHost) {
    liveActivityHost = host
}

@Composable
internal actual fun LiveActivityButton(target: LiveActivityTarget, modifier: Modifier) {
    val host = liveActivityHost ?: return
    val status = host.status.collectAsState().value ?: return
    val active = status.activeId == target.id
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val description = when {
        !status.enabled -> "实时动态，通知未允许"
        active -> "实时动态，已开启"
        else -> "实时动态，未开启"
    }
    Box(modifier) {
        LiveActivityChip(
            active = active,
            accent = Color(0xFF000000 or target.color),
            pressed = pressed || expanded,
            modifier = Modifier
                .fillMaxWidth()
                // 卡片整体可点并合并无障碍信息；这里单独成一个按钮节点，点开菜单（读屏同样可达）。
                .semantics { contentDescription = description }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "打开实时动态菜单",
                ) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun close(action: () -> Unit) {
                expanded = false
                action()
            }
            if (!status.enabled) {
                DropdownMenuItem(
                    text = { Text("允许通知并开启") },
                    onClick = { close { host.requestEnable(target) } },
                )
                return@DropdownMenu
            }
            // 选中的提前时间打勾，并记作以后所有课程的默认值；没在显示时顺便恢复这一项的自动出现。
            LIVE_ACTIVITY_LEAD_MINUTE_OPTIONS.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(leadTitle(target, minutes)) },
                    trailingIcon = if (minutes == status.leadMinutes) {
                        { Text("✓", color = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    onClick = {
                        close {
                            host.setLeadMinutes(minutes)
                            host.command(if (active) "lead" else "arm", target)
                        }
                    },
                )
            }
            HorizontalDivider()
            if (active) {
                DropdownMenuItem(
                    text = { Text("关闭", color = MaterialTheme.colorScheme.error) },
                    onClick = { close { host.command("stop", target) } },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(if (isWithinLeadWindow(target, status.leadMinutes)) "重新开启" else "立即开启") },
                    onClick = { close { host.command("start", target) } },
                )
            }
        }
    }
}

private fun leadTitle(target: LiveActivityTarget, minutes: Int): String =
    "课前 $minutes 分钟显示 (${widgetClock((target.startMinute - minutes).coerceAtLeast(0))})"

/** 已到课前 N 分钟：此时没在显示说明被关掉了，按钮叫“重新开启”。 */
private fun isWithinLeadWindow(target: LiveActivityTarget, leadMinutes: Int): Boolean {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    if (now.date != target.date) return now.date > target.date
    val minute = now.hour * 60 + now.minute
    return minute >= target.startMinute - leadMinutes
}

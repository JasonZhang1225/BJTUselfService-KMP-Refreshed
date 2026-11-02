package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.bjtuss.bjtuselfservice.shared.accessibleAlpha

/** 下拉选单里的一项。[key] 用于回调与标记选中；[subtitle] 在 iOS 显示为选项下方的灰色小字。 */
internal data class AppleMenuOption(
    val key: String,
    val title: String,
    val subtitle: String? = null,
)

/**
 * 点按弹出的单选下拉选单，挂在 [AppleMenuPickerCard] 上，铺满触发卡片。
 *
 * - iOS：透明的原生 `UIButton` 叠在卡片上，点按即弹系统 `UIMenu`（液态玻璃、从卡片处展开、
 *   选项多时菜单内上下滑动、当前项打勾），与 Safari 网页 `<select>` 的样子一致。卡片本身收不到点按，
 *   按下态由 [onPressedChange] 回传。
 * - Android / 桌面：Material `DropdownMenu`，由 [expanded] 控制，锚在卡片下方。
 */
@Composable
internal expect fun AppleMenuAnchor(
    options: List<AppleMenuOption>,
    selectedKey: String?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier,
)

/** iOS 的原生按钮直接吃掉点按；为 true 时卡片改用无障碍自定义操作逐项提供选择。 */
internal expect val appleMenuAnchorHandlesTap: Boolean

/**
 * 页面内的“当前选择 + 切换”卡片：标签一行（如“课程 · 共 15 门”），下面是当前值，右侧“切换”和下拉箭头。
 * 放在页面主滚动列表的首项，随内容滚动。更新日志的版本、课件下载与课程回放的课程都用它。
 *
 * [onOpen] 在用户点按打开菜单时调用（iOS 为按下时），可用来补拉选项的附加信息。
 */
@Composable
internal fun AppleMenuPickerCard(
    label: String,
    value: String,
    options: List<AppleMenuOption>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    accessibilityName: String,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val open = {
        onOpen()
        expanded = true
    }
    Box(modifier) {
        Surface(
            onClick = open,
            enabled = options.isNotEmpty(),
            shape = RoundedCornerShape(16.dp),
            color = colors.primaryContainer.accessibleAlpha(if (pressed) 0.85f else 0.55f),
            border = BorderStroke(1.dp, colors.primary.accessibleAlpha(0.35f)),
            // 子文字合成一句朗读；清除子语义后补回点击动作，读屏和键盘才能打开菜单。
            // iOS 的原生菜单读屏够不到，改用自定义操作逐项选择。
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                role = Role.Button
                contentDescription = "当前$accessibilityName $value，点按切换$accessibilityName"
                if (appleMenuAnchorHandlesTap) {
                    customActions = options.map { option ->
                        val suffix = if (option.key == selectedKey) "，已选中" else ""
                        CustomAccessibilityAction(option.title + suffix) {
                            onSelect(option.key)
                            true
                        }
                    }
                } else {
                    onClick(label = "切换$accessibilityName") {
                        open()
                        true
                    }
                }
            },
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(
                        value,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("切换", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.primary)
                Spacer(Modifier.width(6.dp))
                ChevronDown(colors.primary)
            }
        }
        AppleMenuAnchor(
            options = options,
            selectedKey = selectedKey,
            expanded = expanded,
            onDismiss = { expanded = false },
            onSelect = { key ->
                expanded = false
                onSelect(key)
            },
            onPressedChange = { down ->
                if (down && !pressed) onOpen()
                pressed = down
            },
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** Android / 桌面的回退实现：Material 下拉菜单，选中项加粗并用主色。 */
@Composable
internal fun MaterialMenuAnchor(
    options: List<AppleMenuOption>,
    selectedKey: String?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            options.forEach { option ->
                val current = option.key == selectedKey
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                option.title,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (current) colors.primary else colors.onSurface,
                            )
                            option.subtitle?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = { onSelect(option.key) },
                )
            }
        }
    }
}

@Composable
private fun ChevronDown(tint: Color) {
    Canvas(Modifier.size(14.dp)) {
        val stroke = 2.dp.toPx()
        val y0 = size.height * 0.35f
        val y1 = size.height * 0.68f
        drawLine(tint, Offset(size.width * 0.15f, y0), Offset(size.width / 2, y1), stroke, StrokeCap.Round)
        drawLine(tint, Offset(size.width / 2, y1), Offset(size.width * 0.85f, y0), stroke, StrokeCap.Round)
    }
}

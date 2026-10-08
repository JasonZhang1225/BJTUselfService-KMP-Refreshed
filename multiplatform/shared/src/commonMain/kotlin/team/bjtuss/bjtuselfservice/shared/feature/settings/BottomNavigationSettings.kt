package team.bjtuss.bjtuselfservice.shared.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.feature.shell.*

@Composable
internal fun BottomNavigationSettings(preferences: AppPreferences, model: SettingsScreenModel) {
    val candidates = bottomNavigationCandidates(preferences.isPhyVlabEnabled, preferences.isPhysicsLabEnabled, preferences.isCitelEnabled, preferences.aggregateAssignments)
    val selected = bottomNavSections(preferences).filter { it in candidates }
    // Preview locally while dragging; save the final order on release.
    var order by remember(selected) { mutableStateOf(selected) }
    var dragged by remember(selected) { mutableStateOf<AppSection?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var limitAttempted by remember(selected) { mutableStateOf(false) }
    val stride = 64.dp
    val stridePx = with(LocalDensity.current) { stride.toPx() }
    val density = LocalDensity.current
    val finishDrag by rememberUpdatedState {
        if (order != selected) model.setBottomNavigationOrder(order.map { it.name })
        order = bottomNavSections(model.state.value.preferences).filter { it in candidates }
        dragged = null
    }
    val cancelDrag by rememberUpdatedState {
        order = selected
        dragged = null
    }
    val startDrag by rememberUpdatedState { section: AppSection ->
        dragY = order.indexOf(section) * stridePx
        dragged = section
    }
    val dragBy by rememberUpdatedState { section: AppSection, delta: Float ->
        dragY = (dragY + delta).coerceIn(0f, (order.size - 1).coerceAtLeast(0) * stridePx)
        val target = (dragY / stridePx).roundToInt().coerceIn(order.indices)
        val index = order.indexOf(section)
        if (index >= 0 && index != target) {
            order = order.toMutableList().apply { add(target, removeAt(index)) }
        }
    }

    Text("当前底栏", style = MaterialTheme.typography.titleSmall)
    Text("首页和应用固定在前，最多再选四项；长按功能排序，点减号移出。", style = MaterialTheme.typography.bodySmall)
    listOf(AppSection.HOME, AppSection.MORE).forEach { section ->
        BottomNavigationSettingRow(section) {
            Text("固定", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(stride * order.size)) {
        // Stable composition order keeps the gesture alive while rows change slots.
        selected.forEach { section ->
            key(section.name) {
                val active = dragged == section
                val y by animateFloatAsState(
                    targetValue = if (active) dragY else order.indexOf(section) * stridePx,
                    animationSpec = if (active) snap() else spring(stiffness = 700f),
                    label = "bottomNavigationPlacement",
                )
                val lift by animateFloatAsState(if (active) 1f else 0f, label = "bottomNavigationLift")
                BottomNavigationSettingRow(
                    section,
                    modifier = Modifier.offset { IntOffset(0, y.roundToInt()) }
                        .zIndex(if (active || lift > 0f) 1f else 0f)
                        .graphicsLayer {
                            scaleX = 1f + .02f * lift
                            scaleY = 1f + .02f * lift
                            shadowElevation = with(density) { 8.dp.toPx() } * lift
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        }
                        .semantics {
                            contentDescription = "${section.title}，长按拖动排序"
                            customActions = listOf(
                                CustomAccessibilityAction("上移") { model.moveBottomNavigationItem(section.name, -1) },
                                CustomAccessibilityAction("下移") { model.moveBottomNavigationItem(section.name, 1) },
                                CustomAccessibilityAction("移出底栏") { model.setBottomNavigationItem(section.name, false); true },
                            )
                        }
                        .pointerInput(section, stridePx) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startDrag(section) },
                                onDragEnd = { finishDrag() },
                                onDragCancel = { cancelDrag() },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragBy(section, amount.y)
                                },
                            )
                        },
                    highlighted = active,
                ) {
                    IconButton(
                        enabled = dragged == null,
                        onClick = { model.setBottomNavigationItem(section.name, false) },
                        modifier = Modifier.semantics { contentDescription = "移出底栏：${section.title}" },
                    ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }

    HorizontalDivider()
    Text("可添加", style = MaterialTheme.typography.titleSmall)
    Text("点一下功能即可加入当前底栏，最多添加四项。", style = MaterialTheme.typography.bodySmall)
    if (selected.size >= 4) {
        Text(
            if (limitAttempted) "已达上限，请先移出一项再添加。" else "已达上限（4/4）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    candidates.filter { it !in selected }.forEach { section ->
        BottomNavigationSettingRow(
            section,
            modifier = Modifier.clickable(enabled = dragged == null) {
                if (selected.size >= 4) limitAttempted = true
                else model.setBottomNavigationItem(section.name, true)
            }.semantics { contentDescription = "添加到底栏：${section.title}" },
        ) {
            Text(
                "+", style = MaterialTheme.typography.titleLarge,
                color = if (selected.size >= 4) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(48.dp).wrapContentWidth(),
            )
        }
    }
    val state by model.state.collectAsState()
    if (state.saveFailed) {
        Text("底栏设置保存失败，请重试。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BottomNavigationSettingRow(
    section: AppSection,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    trailing: @Composable RowScope.() -> Unit,
) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactTabIcon(section)
            Text(section.title, modifier = Modifier.weight(1f).padding(start = 12.dp))
            trailing()
        }
    }
}

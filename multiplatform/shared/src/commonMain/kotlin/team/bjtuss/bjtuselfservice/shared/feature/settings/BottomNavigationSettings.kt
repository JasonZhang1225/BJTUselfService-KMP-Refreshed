package team.bjtuss.bjtuselfservice.shared.feature.settings

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.feature.shell.*

@Composable
internal fun BottomNavigationSettings(preferences: AppPreferences, model: SettingsScreenModel) {
    val candidates = bottomNavigationCandidates(preferences.isPhyVlabEnabled)
    val selected = bottomNavSections(preferences).filter { it in candidates }
    val currentSelected by rememberUpdatedState(selected)
    val ordered = selected + candidates.filter { it !in selected }
    val gapPx = with(LocalDensity.current) { 8.dp.toPx() }
    ordered.forEach { section ->
        key(section.name) {
            var dragging by remember { mutableStateOf(false) }
            var offset by remember { mutableFloatStateOf(0f) }
            var rowHeight by remember { mutableIntStateOf(0) }
            val checked = section in selected
            Surface(
                color = if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = offset }
                    .onSizeChanged { rowHeight = it.height },
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactTabIcon(section)
                    Text(section.title, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Checkbox(
                        checked = checked,
                        enabled = checked || selected.size < 4,
                        onCheckedChange = { model.setBottomNavigationItem(section.name, it) },
                        modifier = Modifier.semantics { contentDescription = "在底栏显示${section.title}" },
                    )
                    if (checked) {
                        Box(
                            Modifier.size(48.dp).semantics {
                                contentDescription = "拖动排序${section.title}"
                                customActions = listOf(
                                    CustomAccessibilityAction("上移") { model.moveBottomNavigationItem(section.name, -1) },
                                    CustomAccessibilityAction("下移") { model.moveBottomNavigationItem(section.name, 1) },
                                )
                            }.draggable(
                                orientation = Orientation.Vertical,
                                startDragImmediately = true,
                                onDragStarted = { dragging = true },
                                onDragStopped = { dragging = false; offset = 0f },
                                state = rememberDraggableState { delta ->
                                    offset += delta
                                    val stride = rowHeight + gapPx
                                    if (stride > 0f) {
                                        val direction = when {
                                            offset > stride / 2 -> 1
                                            offset < -stride / 2 -> -1
                                            else -> 0
                                        }
                                        val index = currentSelected.indexOf(section)
                                        if (direction != 0 && index + direction in currentSelected.indices &&
                                            model.moveBottomNavigationItem(section.name, direction)) {
                                            offset -= direction * stride
                                        }
                                        if (index == 0 && offset < 0 || index == currentSelected.lastIndex && offset > 0) {
                                            offset = offset.coerceIn(-stride / 2, stride / 2)
                                        }
                                    }
                                },
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Text("≡", style = MaterialTheme.typography.titleLarge) }
                    } else Spacer(Modifier.width(48.dp))
                }
            }
        }
    }
}

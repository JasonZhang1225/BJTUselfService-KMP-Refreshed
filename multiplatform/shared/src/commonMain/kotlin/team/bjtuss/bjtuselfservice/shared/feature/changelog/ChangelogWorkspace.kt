package team.bjtuss.bjtuselfservice.shared.feature.changelog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import team.bjtuss.bjtuselfservice.shared.update.BundledChangelog
import team.bjtuss.bjtuselfservice.shared.update.ChangelogItem
import team.bjtuss.bjtuselfservice.shared.update.ChangelogRelease
import team.bjtuss.bjtuselfservice.shared.update.ChangelogSection
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalTopBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalBottomBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.TopScrollLazyColumn
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll

@Composable
internal fun ChangelogWorkspace(modifier: Modifier = Modifier) {
    var selectedTag by rememberSaveable { mutableStateOf(BundledChangelog.entries.first().tag) }
    val release = BundledChangelog.entries.first { it.tag == selectedTag }
    val topClearance = LocalTopBarClearance.current
    val bottomClearance = LocalBottomBarClearance.current
    // Keep the picker inside the same viewport as the notes so native scrolling,
    // safe-area padding and glass-bar progress all describe the whole page.
    key(selectedTag) {
        val listState = rememberLazyListState()
        TopScrollLazyColumn(
            state = listState,
            modifier = modifier.desktopTouchScroll(listState),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 16.dp + topClearance, bottom = 16.dp + bottomClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "picker") {
                ChangelogVersionPicker(release, BundledChangelog.entries) { selectedTag = it }
            }
            release.sections.forEach { section ->
                item(key = section.title) { ChangelogSectionCard(section) }
            }
        }
    }
}

/** 版本选择：整块可点的卡片，右侧“切换”和下拉箭头表明这里能选。 */
@Composable
private fun ChangelogVersionPicker(release: ChangelogRelease, entries: List<ChangelogRelease>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(16.dp),
            color = colors.primaryContainer.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                role = Role.Button
                contentDescription = "当前版本 ${release.label}，点按切换版本"
            },
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("版本 · 共 ${entries.size} 个", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(release.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.onSurface)
                }
                Text("切换", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.primary)
                Spacer(Modifier.width(6.dp))
                ChevronDown(colors.primary)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                val current = entry.tag == release.tag
                DropdownMenuItem(
                    text = {
                        Text(entry.label, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (current) colors.primary else colors.onSurface)
                    },
                    onClick = {
                        onSelect(entry.tag)
                        expanded = false
                    },
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

/** 一个板块一张卡片：板块标题 > 条目小标题 > 正文，条目整体缩进在圆点之后。 */
@Composable
private fun ChangelogSectionCard(section: ChangelogSection) {
    val colors = MaterialTheme.colorScheme
    val accent = when (section.title) {
        "新增" -> colors.primary
        "优化" -> colors.tertiary
        else -> colors.secondary
    }
    Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 4.dp, height = 18.dp).background(accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(10.dp))
                Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.width(8.dp))
                Text("${section.items.size} 项", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            section.items.forEach { ChangelogItemRow(it, accent) }
        }
    }
}

@Composable
private fun ChangelogItemRow(item: ChangelogItem, accent: Color) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        // 圆点对齐首行文字中线；正文换行后仍缩进在圆点右侧。
        Box(Modifier.padding(top = 8.dp).size(6.dp).background(accent, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (item.title != null) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Text(item.body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            } else {
                Text(item.body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            }
        }
    }
}

package team.bjtuss.bjtuselfservice.shared.feature.changelog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import team.bjtuss.bjtuselfservice.shared.update.BundledChangelog
import team.bjtuss.bjtuselfservice.shared.update.ChangelogItem
import team.bjtuss.bjtuselfservice.shared.update.ChangelogRelease
import team.bjtuss.bjtuselfservice.shared.update.ChangelogSection
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleMenuOption
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleMenuPickerCard
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
                AppleMenuPickerCard(
                    label = "版本 · 共 ${BundledChangelog.entries.size} 个",
                    value = release.label,
                    options = BundledChangelog.entries.map { AppleMenuOption(it.tag, it.label) },
                    selectedKey = release.tag,
                    onSelect = { selectedTag = it },
                    accessibilityName = "版本",
                )
            }
            release.sections.forEach { section ->
                item(key = section.title) { ChangelogSectionCard(section) }
            }
        }
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

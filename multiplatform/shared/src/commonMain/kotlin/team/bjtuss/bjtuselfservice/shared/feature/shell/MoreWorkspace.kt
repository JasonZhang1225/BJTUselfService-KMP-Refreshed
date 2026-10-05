package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabModel
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll

@Composable
internal fun MoreWorkspace(
    preferences: AppPreferences,
    onOpenSection: (AppSection) -> Unit,
    modifier: Modifier,
    physicsLabModel: PhysicsLabModel? = null,
    onOpenPhysicsLab: () -> Unit = {},
) {
    val scroll = rememberScrollState()
    ReportTopScrollState(scroll)
    val sections = applicationSections(preferences)
    val entries = sections.map { section ->
        ApplicationTile(section.title, section) { onOpenSection(section) }
    }.toMutableList()
    if (physicsLabModel != null) {
        entries.add(
            physicsLabApplicationIndex(sections),
            ApplicationTile("物理实验同步", AppSection.PHYVLAB, onOpenPhysicsLab),
        )
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = when { maxWidth >= 800.dp -> 4; maxWidth >= 480.dp -> 3; else -> 2 }
        val barClearance = LocalBottomBarClearance.current
        // 玻璃底栏的净空只含栏高。滑到最底时再叠系统安全区，方块才停在栏上沿。
        // 自绘底栏没有这份净空，页面布局已经让出栏位，这里不再加。
        val bottomStop = if (barClearance > 0.dp) {
            stackedFloatingBottomInset(barClearance, LocalSystemBottomInset.current)
        } else {
            0.dp
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).desktopTouchScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp + LocalTopBarClearance.current,
                    bottom = 16.dp + bottomStop),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            entries.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { entry ->
                        Surface(
                            onClick = entry.onClick,
                            modifier = Modifier.weight(1f).heightIn(min = 108.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
                                CompactTabIcon(entry.section)
                                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private data class ApplicationTile(val title: String, val section: AppSection, val onClick: () -> Unit)

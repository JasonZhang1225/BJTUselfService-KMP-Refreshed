package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.withoutVisualEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
) {
    val scroll = rememberLazyListState()
    val topClearance = LocalTopBarClearance.current
    val tracksElasticPosition = topClearance > 0.dp
    val overscroll = rememberOverscrollEffect()
    val viewportTop = remember { mutableStateOf<Float?>(null) }
    val contentTop = remember { mutableStateOf<Float?>(null) }
    val report = LocalReportTopScroll.current
    if (tracksElasticPosition) {
        LaunchedEffect(scroll, report) {
            snapshotFlow {
                val logicalOffset = if (scroll.firstVisibleItemIndex > 0) Float.MAX_VALUE
                    else scroll.firstVisibleItemScrollOffset.toFloat()
                resolveVisualTopScrollOffset(logicalOffset, viewportTop.value, contentTop.value)
            }.collect { report(it) }
        }
    } else {
        ReportTopScrollListState(scroll)
    }
    val sections = applicationSections(preferences).filter {
        it != AppSection.PHYSICS_LAB || physicsLabModel != null
    }
    val entries = sections.map { section ->
        ApplicationTile(if (section == AppSection.PHYSICS_LAB) "物理实验" else section.title, section) { onOpenSection(section) }
    }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { viewportTop.value = it.positionInRoot().y }) {
        val columns = when { maxWidth >= 800.dp -> 4; maxWidth >= 480.dp -> 3; else -> 2 }
        val barClearance = LocalBottomBarClearance.current
        // 玻璃底栏的净空只含栏高。滑到最底时再叠系统安全区，方块才停在栏上沿。
        // 自绘底栏没有这份净空，页面布局已经让出栏位，这里不再加。
        val bottomStop = if (barClearance > 0.dp) {
            stackedFloatingBottomInset(barClearance, LocalSystemBottomInset.current)
        } else {
            0.dp
        }
        val rows = entries.chunked(columns)
        LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize().desktopTouchScroll(scroll)
                .then(if (tracksElasticPosition) Modifier.overscroll(overscroll) else Modifier)
                .onGloballyPositioned { contentTop.value = it.positionInRoot().y },
            overscrollEffect = if (tracksElasticPosition) overscroll?.withoutVisualEffect() else overscroll,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp + topClearance,
                bottom = 16.dp + bottomStop),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows.size) { rowIndex ->
                val row = rows[rowIndex]
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { entry ->
                        ApplicationTileCard(
                            entry,
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private data class ApplicationTile(val title: String, val section: AppSection, val onClick: () -> Unit)

@Composable
private fun ApplicationTileCard(entry: ApplicationTile, modifier: Modifier) {
    val tint = applicationIconColor(entry.section)
    Surface(
        onClick = entry.onClick,
        modifier = modifier.heightIn(min = 104.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides tint) {
                    // Keep shared navigation icons unchanged; enlarge only this page's drawing.
                    Box(Modifier.size(24.dp).graphicsLayer { scaleX = 7f / 6f; scaleY = 7f / 6f }) {
                        CompactTabIcon(entry.section)
                    }
                }
            }
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 23.sp),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Gentle accents on icons only, with foreground shades for both theme backgrounds. */
@Composable
private fun applicationIconColor(section: AppSection): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (section) {
        AppSection.GRADES, AppSection.REPORT_CARD_DOWNLOAD ->
            if (dark) Color(0xFFB5A0E8) else Color(0xFF7355AC)
        AppSection.SCHEDULE, AppSection.CALENDAR, AppSection.MAILBOX ->
            if (dark) Color(0xFF82B5EB) else Color(0xFF286EB8)
        AppSection.EXAMS ->
            if (dark) Color(0xFFEAB477) else Color(0xFFA96620)
        AppSection.HOMEWORK ->
            if (dark) Color(0xFF91CFAD) else Color(0xFF347A53)
        AppSection.COURSEWARE ->
            if (dark) Color(0xFF9EAFE9) else Color(0xFF566DAF)
        AppSection.CLASSROOM_OCCUPANCY, AppSection.CLASSROOMS, AppSection.PHYSICS_LAB ->
            if (dark) Color(0xFF85CBD1) else Color(0xFF267B83)
        AppSection.PHYVLAB ->
            if (dark) Color(0xFFB5ACE5) else Color(0xFF7565AD)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Clock
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTaskCard
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkCard
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkSummaryBanner
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabActivityRow
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.shell.*

@Composable
internal fun AggregatedAssignmentWorkspace(
    assignments: List<AggregatedAssignment>, sync: List<AssignmentSourceSync>,
    store: AggregateAssignmentFilterStore?, onInitialize: suspend () -> Unit,
    onOpen: (AggregatedAssignment) -> Unit, onRefreshSource: (AssignmentSource) -> Unit,
    showSyncDetails: Boolean, onDismissSyncDetails: () -> Unit, modifier: Modifier = Modifier,
) {
    var filters by remember(store) { mutableStateOf(store?.load() ?: AggregateAssignmentFilters()) }
    var showFilters by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Clock.System.now().epochSeconds) }
    val list = rememberLazyListState()
    val visible = filterAggregateAssignments(assignments, filters, now)
    LaunchedEffect(Unit) { onInitialize() }
    LaunchedEffect(Unit) { while (true) { now = Clock.System.now().epochSeconds; delay(30_000) } }
    LaunchedEffect(filters) { list.scrollToItem(0) }
    val dueSoon = visible.count { !it.submitted && it.dueTime?.let { time -> time in now..(now + 48 * 3600) } == true }
    val subtitle = (if (dueSoon > 0) "未来 48 小时内有 $dueSoon 项未提交" else "未来 48 小时内暂无临近截止项") +
        if (filters.active) " · 已筛选" else ""
    val top = LocalTopBarClearance.current
    val bottom = LocalBottomBarClearance.current
    TopScrollLazyColumn(state = list, modifier = modifier.fillMaxSize().desktopTouchScroll(list),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + top, bottom = 24.dp + bottom),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sync.filter { it.failed }.forEach { status ->
            item("failure:${status.source}") {
                AppErrorBanner(title = "${status.source.label} · ${status.status}",
                    message = status.message ?: "请检查网络或登录状态后重试。", onRetry = { onRefreshSource(status.source) })
            }
        }
        item("summary") { HomeworkSummaryBanner(visible.size, subtitle, onOpenFilter = { showFilters = true }) }
        if (visible.isEmpty()) item("empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (assignments.isEmpty()) "暂无作业" else "当前筛选下没有作业", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showFilters = true }) { Text("调整筛选") }
            }
        }
        items(visible, key = { it.key }) { item -> AggregatedAssignmentCard(item, now) { onOpen(item) } }
    }
    if (showFilters) AppleSheet(onDismissRequest = { showFilters = false }, title = "作业筛选", needsFullHeight = true, scrollableBody = true) {
        AggregateAssignmentFilterSheet(assignments, sync.map { it.source }, filters) { next ->
            store?.save(next)
            filters = next
        }
    }
    if (showSyncDetails) AppleSheet(onDismissRequest = onDismissSyncDetails, title = "作业同步状态") {
        AssignmentSyncDetails(sync, onRefreshSource)
    }
}

@Composable
internal fun AggregatedAssignmentCard(item: AggregatedAssignment, now: Long, onOpen: () -> Unit) {
    when (item.source) {
        AssignmentSource.COURSE_PLATFORM -> item.homework?.let { HomeworkCard(it, false, { onOpen() }, source = item.source) }
        AssignmentSource.PHYVLAB -> item.physical?.let { PhyVlabActivityRow(it, now, onOpen, source = item.source) }
        AssignmentSource.CITEL -> item.citel?.let { CitelTaskCard(it, onOpen, source = item.source) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.AggregateAssignmentFilterSheet(assignments: List<AggregatedAssignment>, sources: List<AssignmentSource>,
    filters: AggregateAssignmentFilters, onChange: (AggregateAssignmentFilters) -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).desktopTouchScroll(scroll)
            .sheetScrollContentPadding(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("筛选与排序", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        sources.forEach { source ->
            val courses = assignments.filter { it.source == source }.distinctBy { it.courseId }
            val options = courses.map { it.courseId }.toSet()
            val selected = filters.forSource(source)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${source.label}课程", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected.allCourses || (options.isNotEmpty() && selected.courses.containsAll(options)),
                        { onChange(filters.withSource(source, selected.toggleAll(options))) }, label = { Text("全部") })
                    courses.forEach { course ->
                        FilterChip(selected.matches(course.courseId),
                            { onChange(filters.withSource(source, selected.toggleCourse(course.courseId, options))) },
                            label = { Text(course.courseName, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("截止时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!filters.hideExpired, { onChange(filters.copy(hideExpired = false)) }, shape = RoundedCornerShape(10.dp), label = { Text("显示全部日期") })
                FilterChip(filters.hideExpired, { onChange(filters.copy(hideExpired = true)) }, shape = RoundedCornerShape(10.dp), label = { Text("隐藏已过期") })
                FilterChip(filters.hideSubmitted, { onChange(filters.copy(hideSubmitted = !filters.hideSubmitted)) }, shape = RoundedCornerShape(10.dp), label = { Text("隐藏已提交") })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("排序", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("原顺序", "由近到远", "由远到近").forEachIndexed { order, label ->
                    FilterChip(filters.sortOrder == order, { onChange(filters.copy(sortOrder = order)) }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
internal fun AssignmentSyncDetails(sync: List<AssignmentSourceSync>, onRefreshSource: (AssignmentSource) -> Unit) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            sync.forEach { status ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssignmentSourceBadge(status.source)
                            Text(status.status, color = if (status.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                        if (status.busy) CircularProgressIndicator(Modifier.size(24.dp))
                        else TextButton(onClick = { onRefreshSource(status.source) }) { Text("刷新") }
                    }
                    if (status.failed) status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
}

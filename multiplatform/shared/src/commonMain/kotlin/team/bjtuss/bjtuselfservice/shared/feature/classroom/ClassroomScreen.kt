package team.bjtuss.bjtuselfservice.shared.feature.classroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.data.classroom.ClassroomFetchFailure
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomCapacity
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomSortDirection
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomSortField
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppErrorBanner
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalTopBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.ReportTopScrollListState
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll

/** iPhone 两级列表（原生 push 详情）、macOS 列表—详情并排的教室人数估计页面。 */
@Composable
fun ClassroomWorkspace(
    model: ClassroomScreenModel,
    expanded: Boolean,
    /** 首页引导 Banner（请选择教学楼 / 人数仅供参考）；登录态只显示一次。 */
    introBannerVisible: Boolean = false,
    onDismissIntroBanner: () -> Unit = {},
    // compact 下选中教学楼后由 shell 原生 push 出详情页；expanded 列表-详情并排，用不到。
    onOpenBuilding: () -> Unit = {},
    /** 由应用壳提供会话感知刷新；独立预览/测试时回退到模型刷新。 */
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = onRefresh ?: fun() {
        scope.launch { model.refresh() }
    }

    if (expanded) {
        Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BuildingList(
                buildings = state.buildings,
                selected = state.selectedBuilding,
                introBannerVisible = introBannerVisible,
                onDismissIntroBanner = onDismissIntroBanner,
                showListHeading = true,
                onSelect = { building -> scope.launch { model.selectBuilding(building) } },
                modifier = Modifier.weight(0.32f).fillMaxHeight(),
            )
            ClassroomDetail(
                state = state,
                model = model,
                onRefresh = refresh,
                showBuildingHeader = true,
                modifier = Modifier.weight(0.68f).fillMaxHeight(),
            )
        }
    } else {
        // 紧凑端：只列教学楼；点选后先写选中再原生 push，详情页标题用楼名。
        BuildingList(
            buildings = state.buildings,
            selected = null,
            introBannerVisible = introBannerVisible,
            onDismissIntroBanner = onDismissIntroBanner,
            showListHeading = false,
            onSelect = { building ->
                scope.launch {
                    // 必须先 await 选中再 push，否则详情页打开时 selected 仍为空，
                    // 会落到「从左侧选择教学楼」的宽屏空态。
                    model.selectBuilding(building)
                    onOpenBuilding()
                }
            },
            modifier = modifier.fillMaxSize(),
        )
    }
}

/**
 * 教学楼详情页（紧凑端原生二级页）。
 * 返回依赖顶栏/系统边缘手势，页内不再放「返回教学楼」；标题由 shell 显示楼名。
 */
@Composable
fun ClassroomBuildingWorkspace(
    model: ClassroomScreenModel,
    /** 由应用壳提供会话感知刷新；独立预览/测试时回退到模型刷新。 */
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = onRefresh ?: fun() {
        scope.launch { model.refresh() }
    }
    ClassroomDetail(
        state = state,
        model = model,
        onRefresh = refresh,
        // 楼名与刷新在顶栏；页内只保留数据窗口说明与筛选列表。
        showBuildingHeader = false,
        emptyMessage = "正在打开教学楼…",
        emptyHint = "若长时间无内容，请返回后重新选择。",
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun BuildingList(
    buildings: List<String>,
    selected: String?,
    introBannerVisible: Boolean,
    onDismissIntroBanner: () -> Unit,
    showListHeading: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    // 与成绩/作业/更多一致：紧凑端水平 16.dp，避免卡片贴边占满屏宽。
    val listState = rememberLazyListState()
    // CompositionLocal 不能在 LazyColumn 的 DSL 作用域里读，提到可组合上下文。
    val topClearance = LocalTopBarClearance.current
    // 真实偏移上报给壳层算玻璃浓度（手势累加会漂，读列表状态不会）。
    ReportTopScrollListState(listState)
    LazyColumn(
        state = listState,
        modifier = modifier
            .desktopTouchScroll(listState)
            .padding(horizontal = 16.dp)
            .padding(top = if (topClearance > 0.dp) 0.dp else 8.dp),
        contentPadding = PaddingValues(
            // 首项靠内部顶边距让开原生栏（外层已不再占位）：8.dp 还原起笔位置。
            top = 8.dp + topClearance,
            bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (introBannerVisible) {
            item(key = "intro-banner") {
                ClassroomIntroBanner(onDismiss = onDismissIntroBanner)
            }
        }
        if (showListHeading) {
            item(key = "list-heading") {
                Text(
                    "选择教学楼",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
        items(buildings, key = { it }) { building ->
            ElevatedCard(
                onClick = { onSelect(building) },
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (selected == building) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    building,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected == building) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun ClassroomDetail(
    state: ClassroomUiState,
    model: ClassroomScreenModel,
    onRefresh: () -> Unit,
    showBuildingHeader: Boolean,
    emptyMessage: String = "从左侧选择教学楼",
    emptyHint: String = "选择后显示教室容量、已用人数和空位状态。",
    modifier: Modifier,
) {
    val selected = state.selectedBuilding
    if (selected == null) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(emptyMessage, style = MaterialTheme.typography.titleLarge)
                Text(
                    emptyHint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        return
    }

    // 与其它页一致水平 16.dp；矮搜索 + 两行芯片，高度留给列表。
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showBuildingHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(selected, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    ClassroomDataWindowLine(state)
                }
                Button(onClick = onRefresh, enabled = !state.isLoading) { Text("刷新") }
            }
        } else {
            ClassroomDataWindowLine(state)
        }

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        (state.buildingState as? ClassroomBuildingState.Failed)?.let { failed ->
            ErrorCard(failed.reason)
        }

        // 搜索框用本地草稿 + 防抖再写 model：避免每敲一字就 StateFlow 全量重组 + 列表重算。
        // 不用 OutlinedTextField+固定 48.dp：M3 最小高度约 56.dp，硬压高度会裁切占位文案、
        // 垂直不居中，并在 iOS 聚焦时偶发布局/输入崩溃。自绘描边 + BasicTextField 可安全居中。
        ClassroomNameSearchField(
            committedQuery = state.filter.nameQuery,
            onQueryCommit = model::setNameQuery,
            modifier = Modifier.fillMaxWidth(),
        )

        // 两行：左图标 + 横滑芯片。第一行筛选，第二行排序；行高保持芯片高度。
        ClassroomFilterAndSortRows(state = state, model = model)

        when {
            state.isLoading && state.visibleClassrooms.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("正在读取教室人数…", modifier = Modifier.padding(top = 10.dp))
                }
            }
            state.visibleClassrooms.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "没有符合筛选条件的教室",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = model::clearFilter,
                        modifier = Modifier.padding(top = 10.dp),
                    ) { Text("清除筛选") }
                }
            }
            else -> {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = true).desktopTouchScroll(listState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(state.visibleClassrooms, key = { it.name }) { room ->
                        ClassroomCard(room)
                    }
                }
            }
        }
    }
}

/**
 * 紧凑搜索框：圆角描边 + 垂直居中占位/输入。
 * 本地 [draft] 立刻跟手；停敲约 180ms 后再 [onQueryCommit]，避免 iOS 输入时卡键盘。
 * 外部 [committedQuery] 变化（清除筛选等）会回写草稿。
 * 避免 OutlinedTextField 被压到 48.dp 时裁字、偏位与 iOS 聚焦崩溃。
 */
@Composable
private fun ClassroomNameSearchField(
    committedQuery: String,
    onQueryCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(committedQuery) }
    // 清除筛选 / 换楼等外部改动：仅当草稿已提交且外部值不同时同步，避免覆盖正在输入的字。
    LaunchedEffect(committedQuery) {
        if (draft != committedQuery) {
            draft = committedQuery
        }
    }
    LaunchedEffect(draft) {
        if (draft == committedQuery) return@LaunchedEffect
        delay(180)
        if (draft != committedQuery) {
            onQueryCommit(draft)
        }
    }

    val textStyle = MaterialTheme.typography.bodyMedium.merge(
        TextStyle(color = MaterialTheme.colorScheme.onSurface),
    )
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline
    Surface(
        modifier = modifier
            .height(48.dp)
            .semantics { contentDescription = "搜索教室名" },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                "搜索教室名",
                                style = MaterialTheme.typography.bodyMedium,
                                color = placeholderColor,
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

/**
 * 筛选 / 排序两行：左侧固定图标，右侧芯片横滑。
 * 行高与 [FilterChipDefaults.Height] 一致，不额外加高。
 */
@Composable
private fun ClassroomFilterAndSortRows(
    state: ClassroomUiState,
    model: ClassroomScreenModel,
) {
    val filterScrollState = rememberScrollState()
    val sortScrollState = rememberScrollState()
    val chipColors = FilterChipDefaults.filterChipColors()
    val chipHeight = FilterChipDefaults.Height
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 筛选行：漏斗 + 仅空位 / 容量
        Row(
            modifier = Modifier.fillMaxWidth().height(chipHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClassroomFilterFunnelIcon(
                modifier = Modifier
                    .size(18.dp)
                    .semantics { contentDescription = "筛选" },
                tint = iconTint,
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(filterScrollState)
                    .desktopTouchScroll(filterScrollState, orientation = Orientation.Horizontal),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactFilterChip(
                    selected = state.filter.onlyWithFreeSeats,
                    onClick = { model.setOnlyWithFreeSeats(!state.filter.onlyWithFreeSeats) },
                    label = "仅空位",
                    colors = chipColors,
                    height = chipHeight,
                )
                CompactFilterChip(
                    selected = state.filter.minCapacity == null,
                    onClick = { model.setCapacityRange(null, null) },
                    label = "不限",
                    colors = chipColors,
                    height = chipHeight,
                )
                CompactFilterChip(
                    selected = state.filter.minCapacity == 50,
                    onClick = { model.setCapacityRange(50, null) },
                    label = "≥50",
                    colors = chipColors,
                    height = chipHeight,
                )
                CompactFilterChip(
                    selected = state.filter.minCapacity == 100,
                    onClick = { model.setCapacityRange(100, null) },
                    label = "≥100",
                    colors = chipColors,
                    height = chipHeight,
                )
                CompactFilterChip(
                    selected = state.filter.minCapacity == 200,
                    onClick = { model.setCapacityRange(200, null) },
                    label = "≥200",
                    colors = chipColors,
                    height = chipHeight,
                )
            }
        }
        // 排序行：柱条图标 + 名称 / 占用 / 已用 / 容量
        Row(
            modifier = Modifier.fillMaxWidth().height(chipHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClassroomSortBarsIcon(
                modifier = Modifier
                    .size(18.dp)
                    .semantics { contentDescription = "排序" },
                tint = iconTint,
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(sortScrollState)
                    .desktopTouchScroll(sortScrollState, orientation = Orientation.Horizontal),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClassroomSortField.entries.forEach { field ->
                    val base = when (field) {
                        ClassroomSortField.NAME -> "名称"
                        ClassroomSortField.OCCUPANCY -> "占用"
                        ClassroomSortField.USED -> "已用"
                        ClassroomSortField.CAPACITY -> "容量"
                    }
                    val arrow = if (state.sortField == field) {
                        if (state.sortDirection == ClassroomSortDirection.ASCENDING) "↑" else "↓"
                    } else {
                        ""
                    }
                    CompactFilterChip(
                        selected = state.sortField == field,
                        onClick = { model.setSortField(field) },
                        label = base + arrow,
                        colors = chipColors,
                        height = chipHeight,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    colors: androidx.compose.material3.SelectableChipColors,
    height: androidx.compose.ui.unit.Dp,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        },
        colors = colors,
        modifier = Modifier.height(height),
    )
}

/** 漏斗：筛选行左侧图标。 */
@Composable
private fun ClassroomFilterFunnelIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier = modifier) {
        val stroke = 1.6.dp.toPx()
        val left = 2.dp.toPx()
        val right = size.width - left
        val top = 2.5.dp.toPx()
        val midY = size.height * 0.48f
        val neckLeft = size.width * 0.42f
        val neckRight = size.width * 0.58f
        val bottom = size.height - 2.dp.toPx()
        drawLine(tint, Offset(left, top), Offset(right, top), stroke, StrokeCap.Round)
        drawLine(tint, Offset(left, top), Offset(neckLeft, midY), stroke, StrokeCap.Round)
        drawLine(tint, Offset(right, top), Offset(neckRight, midY), stroke, StrokeCap.Round)
        drawLine(tint, Offset(neckLeft, midY), Offset(neckLeft, bottom), stroke, StrokeCap.Round)
        drawLine(tint, Offset(neckRight, midY), Offset(neckRight, bottom), stroke, StrokeCap.Round)
        drawLine(tint, Offset(neckLeft, bottom), Offset(neckRight, bottom), stroke, StrokeCap.Round)
    }
}

/** 递增柱条：排序行左侧图标。 */
@Composable
private fun ClassroomSortBarsIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier = modifier) {
        val barWidth = 2.8.dp.toPx()
        val gap = 2.2.dp.toPx()
        val baseY = size.height - 2.dp.toPx()
        val heights = listOf(5.dp.toPx(), 9.dp.toPx(), 13.dp.toPx())
        val totalWidth = barWidth * 3 + gap * 2
        val startX = (size.width - totalWidth) / 2f
        heights.forEachIndexed { index, h ->
            val x = startX + index * (barWidth + gap)
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, baseY - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun ClassroomDataWindowLine(state: ClassroomUiState) {
    val info = when (val load = state.buildingState) {
        is ClassroomBuildingState.Loaded -> load.info
        is ClassroomBuildingState.Failed -> load.cached
        else -> null
    }
    if (info != null) {
        Text(
            "数据窗口：${info.effectiveStart} — ${info.effectiveEnd}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ClassroomCard(room: ClassroomCapacity) {
    // 圆角与教学楼列表卡片、作业卡一致，避免直角满行条带感。
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "已用 ${room.used} / 容量 ${room.capacity} · 估计占用 ${formatPercent(room.occupancyRatio)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (room.hasFreeSeat) "有空位" else "可能已满",
                style = MaterialTheme.typography.labelLarge,
                color = if (room.hasFreeSeat) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ClassroomIntroBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("请选择教学楼", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "人数为实时评估，仅作找空教室参考，不保证与现场一致。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun ErrorCard(reason: ClassroomFetchFailure) {
    AppErrorBanner(
        message = when (reason) {
            ClassroomFetchFailure.NETWORK -> "教室接口暂时不可达，请稍后重试。"
            ClassroomFetchFailure.PARSE -> "教室接口返回格式已变化，暂时无法解析。"
            ClassroomFetchFailure.SECURE_CHANNEL_UNAVAILABLE ->
                "当前平台未允许教室人数接口的明文访问。"
        },
    )
}

private fun formatPercent(value: Double): String =
    "${(value * 100).toInt().coerceIn(0, 999)}%"

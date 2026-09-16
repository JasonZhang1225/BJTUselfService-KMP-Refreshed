package team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.accessibleAlpha
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancySyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.ClassroomOccupancy
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyBuilding
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyKind
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.SLOT_TIME_RANGES
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppErrorBanner
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll

private val weekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 数据边界说明：教务教室使用查询为当前学期占用，页内常驻提示。 */
private const val DISCLAIMER = "数据来自教务系统教室使用查询，为当前学期排课/调课/考试占用情况。"

/**
 * 教室占用一级页：教学楼列表（单选）。compact 下点选后先写选中再由 shell
 * 原生 push（或压栈）出详情页；expanded 下列表与详情并排。
 */
@Composable
fun ClassroomOccupancyWorkspace(
    model: ClassroomOccupancyScreenModel,
    expanded: Boolean,
    /** compact 下选中教学楼后由 shell 原生 push 出详情页；expanded 并排布局用不到。 */
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

    // 进页面只写默认周/星期（幂等、无网络）；未选楼不查询，查询由选楼触发。
    LaunchedEffect(model) {
        model.initialize()
    }
    // 后台预取校历日期：用户还在选楼时就拉好，弹层打开时尽量已有日期、少闪一下。
    // 公开通道不占会话锁，代理下也不会堵后续 room_view。
    LaunchedEffect(model) {
        model.ensureWeekDatesLoaded()
    }

    // 选中楼变化后（selectBuilding 只同步写状态、不发起查询）由这里补一次查询；
    // 紧凑端 push 动画因此不再被网络请求延迟。
    LaunchedEffect(state.selectedBuilding) {
        val building = state.selectedBuilding
        if (building != null && state.queryState == ClassroomOccupancyQueryState.Idle) {
            refresh()
        }
    }

    if (expanded) {
        Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OccupancyBuildingList(
                buildings = state.buildings,
                showListHeading = true,
                onSelect = { building -> model.selectBuilding(building) },
                modifier = Modifier.weight(0.32f).fillMaxHeight(),
            )
            OccupancyDetail(
                state = state,
                model = model,
                onRefresh = refresh,
                showBuildingHeader = true,
                emptyMessage = "从左侧选择教学楼",
                emptyHint = "选择后显示该楼教室的排课/调课/考试占用。",
                modifier = Modifier.weight(0.68f).fillMaxHeight(),
            )
        }
    } else {
        // 紧凑端：只列教学楼；点选后同步写选中（不等待网络）再原生 push，
        // 详情页打开后由上面的 LaunchedEffect 触发查询，动画不被请求延迟。
        OccupancyBuildingList(
            buildings = state.buildings,
            showListHeading = false,
            onSelect = { building ->
                model.selectBuilding(building)
                onOpenBuilding()
            },
            modifier = modifier.fillMaxSize(),
        )
    }
}

/**
 * 教室占用二级页（紧凑端原生详情页）：周/星期筛选 + 节次时段 + 图例 + 教室卡片。
 * 返回依赖顶栏/系统边缘手势，页内不再放「返回教学楼」；标题由 shell 显示楼名。
 */
@Composable
fun ClassroomOccupancyBuildingWorkspace(
    model: ClassroomOccupancyScreenModel,
    /** 由应用壳提供会话感知刷新；独立预览/测试时回退到模型刷新。 */
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = onRefresh ?: fun() {
        scope.launch { model.refresh() }
    }
    LaunchedEffect(model) {
        // 详情页可能是原生二级页直接重建（initialize 幂等、无网络）。
        model.initialize()
    }
    // 详情页同样后台预取校历（幂等）；从一级页已拉过则瞬间返回。
    LaunchedEffect(model) {
        model.ensureWeekDatesLoaded()
    }
    // 详情页重建（原生 push 后模型已含选中楼但未查询）时补一次查询。
    LaunchedEffect(state.selectedBuilding) {
        val building = state.selectedBuilding
        if (building != null && state.queryState == ClassroomOccupancyQueryState.Idle) {
            refresh()
        }
    }
    OccupancyDetail(
        state = state,
        model = model,
        onRefresh = refresh,
        // 楼名与刷新在顶栏；页内只保留数据窗口说明与筛选。
        showBuildingHeader = false,
        emptyMessage = "正在打开教学楼…",
        emptyHint = "若长时间无内容，请返回后重新选择。",
        modifier = modifier.fillMaxSize(),
    )
}

/** 教学楼列表：整块圆角容器 + 行间细分隔线 + chevron，与“更多”页分块列表一致。 */
@Composable
private fun OccupancyBuildingList(
    buildings: List<OccupancyBuilding>,
    showListHeading: Boolean,
    onSelect: (OccupancyBuilding) -> Unit,
    modifier: Modifier,
) {
    // 与成绩/作业/更多一致：紧凑端水平 16.dp，避免卡片贴边占满屏宽。
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier
            .desktopTouchScroll(listState)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
        item(key = "building-list") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    buildings.forEachIndexed { index, building ->
                        Surface(
                            onClick = { onSelect(building) },
                            color = Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    building.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                OccupancyEntryChevron()
                            }
                        }
                        if (index < buildings.lastIndex) {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp)
                                    .height(0.5.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.accessibleAlpha(0.55f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OccupancyEntryChevron() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(8.dp, 14.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val mid = size.height / 2
        drawLine(
            color,
            Offset(2.dp.toPx(), 2.dp.toPx()),
            Offset(size.width - 1.dp.toPx(), mid),
            strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width - 1.dp.toPx(), mid),
            Offset(2.dp.toPx(), size.height - 2.dp.toPx()),
            strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 选中楼后的占用视图：周/学期选择 + 星期 chips + 节次时段说明 + 图例 + 教室卡片。
 * expanded 时楼名标题在页内（带刷新按钮）；compact 时标题在 shell 顶栏。
 * 弹层状态 [showWeekPicker] 存在本层，二级页重建（如原生返回重进）会自动复位。
 */
@Composable
private fun OccupancyDetail(
    state: ClassroomOccupancyUiState,
    model: ClassroomOccupancyScreenModel,
    onRefresh: () -> Unit,
    showBuildingHeader: Boolean,
    emptyMessage: String,
    emptyHint: String,
    modifier: Modifier,
) {
    var showWeekPicker by remember { mutableStateOf(false) }
    // 弹层选周必须挂这个 scope：弹层 dismiss 会销毁弹层自己的 scope，否则 selectWeek 被取消 → 「同步中」卡死。
    val hostScope = rememberCoroutineScope()
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

    // 与其它页一致水平 16.dp；筛选与说明留给列表剩余高度。
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showBuildingHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        selected.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        DISCLAIMER,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onRefresh, enabled = !state.isLoading) { Text("刷新") }
            }
        } else {
            Text(
                DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ClassroomOccupancyFilters(
            state = state,
            model = model,
            onOpenWeekPicker = { showWeekPicker = true },
        )
        var showSlotTimes by remember { mutableStateOf(false) }
        // 只用外层 animateContentSize 一个动画驱动高度，星期条和教室卡片严格跟随，
        // 不再嵌套第二个垂直动画（两个动画速率不一致会显得不同步）。
        Column(
            modifier = Modifier.animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OccupancyLegendRow(
                showSlotTimes = showSlotTimes,
                onToggleSlotTimes = { showSlotTimes = !showSlotTimes },
            )
            // 时段区只做淡出/淡入（不影响高度），高度变化全交给外层 animateContentSize，
            // 位移只有一个速率来源。fadeIn 初始 0f 让展开时也从透明渐显，避免突兀。
            AnimatedVisibility(
                visible = showSlotTimes,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                SlotTimeRangesLegend()
            }
        }
        // 星期条挪到图例下、紧贴教室卡片，是切换占用格的直接操作。
        OccupancyCompactDaySelector(
            selectedDay = state.selectedWeekday,
            onSelect = { model.selectWeekday(it) },
        )

        when (val query = state.queryState) {
            ClassroomOccupancyQueryState.Idle, ClassroomOccupancyQueryState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("正在查询教室占用…", modifier = Modifier.padding(top = 10.dp))
                }
            }
            is ClassroomOccupancyQueryState.Failed -> {
                AppErrorBanner(
                    message = when (query.reason) {
                        ClassroomOccupancySyncFailure.NETWORK -> "无法连接教务系统，请检查网络后重试。"
                        ClassroomOccupancySyncFailure.SESSION_EXPIRED -> "教务会话已过期，请点击右上角刷新重试登录。"
                        ClassroomOccupancySyncFailure.MALFORMED_RESPONSE -> "教务教室页面结构已变化，暂时无法解析。"
                    },
                    onRetry = onRefresh,
                )
            }
            is ClassroomOccupancyQueryState.Loaded -> {
                // 刷新进度只靠 shell 顶栏「同步中」；页内不再叠一条 LinearProgress，避免双进度条。
                // 有旧列表时刷新中继续展示；无列表时整页转圈（首查/空结果再刷）。
                if (query.rooms.isEmpty()) {
                    if (query.refreshing) {
                        Column(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Text("正在查询教室占用…", modifier = Modifier.padding(top = 10.dp))
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "当前条件下没有教室占用数据",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f, fill = true).desktopTouchScroll(listState),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(query.rooms, key = ClassroomOccupancy::room) { room ->
                            ClassroomOccupancyCard(room = room, weekday = state.selectedWeekday)
                        }
                    }
                }
            }
        }
    }

    if (showWeekPicker) {
        OccupancyWeekPickerSheet(
            state = state,
            model = model,
            // 必须用详情页 scope 发起查询：弹层 onClick 里立刻 onDismiss 会销毁弹层
            // 自己的 rememberCoroutineScope，把 selectWeek 协程取消掉 → 「同步中」永不结束。
            hostScope = hostScope,
            onDismiss = { showWeekPicker = false },
        )
    }
}

/**
 * 学期 + 教学周选择弹层。
 * [hostScope] 必须来自详情页：弹层 dismiss 会销毁弹层自己的 scope，
 * 若 selectWeek 挂在弹层 scope 上会被取消 → 「同步中」永不结束。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun OccupancyWeekPickerSheet(
    state: ClassroomOccupancyUiState,
    model: ClassroomOccupancyScreenModel,
    hostScope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        model.ensureSemestersLoaded()
        model.ensureWeekDatesLoaded()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        val pickerScrollState = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(pickerScrollState)
                .desktopTouchScroll(pickerScrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("学期与教学周", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "学期",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            SemesterChipsRow(
                state = state,
                model = model,
                hostScope = hostScope,
                onDismiss = onDismiss,
            )
            Text(
                "教学周",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "周次对应日期来自学校校历，供参考。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.selectedWeek == model.currentWeek,
                    onClick = {
                        onDismiss()
                        hostScope.launch { model.selectWeek(model.currentWeek) }
                    },
                    label = {
                        WeekChipLabel(
                            date = model.weekDateOf(model.currentWeek),
                            primary = "本周（第${model.currentWeek}周）",
                        )
                    },
                )
                (MIN_WEEK..MAX_WEEK).forEach { week ->
                    FilterChip(
                        selected = state.selectedWeek == week,
                        onClick = {
                            onDismiss()
                            hostScope.launch { model.selectWeek(week) }
                        },
                        label = {
                            WeekChipLabel(
                                date = model.weekDateOf(week),
                                primary = "第${week}周",
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 学期 chips：横滑 Row，「当前学期」在前，其后新到旧。 */
@Composable
private fun SemesterChipsRow(
    state: ClassroomOccupancyUiState,
    model: ClassroomOccupancyScreenModel,
    hostScope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val selectedIndex = state.selectedSemester?.let { semester ->
        state.semesters.indexOf(semester).takeIf { it >= 0 }?.plus(1)
    } ?: 0
    val density = LocalDensity.current
    LaunchedEffect(selectedIndex, state.semesters.size) {
        with(density) { scrollState.scrollTo((selectedIndex * 132).dp.roundToPx()) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .desktopTouchScroll(scrollState, orientation = Orientation.Horizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val currentLabel = state.currentSemesterLabel
        FilterChip(
            selected = state.selectedSemester == null,
            onClick = {
                onDismiss()
                hostScope.launch { model.selectSemester(null) }
            },
            label = {
                Text(
                    if (currentLabel != null) "当前学期（$currentLabel）" else "当前学期",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            },
        )
        state.semesters.forEach { semester ->
            FilterChip(
                selected = state.selectedSemester == semester,
                onClick = {
                    onDismiss()
                    hostScope.launch { model.selectSemester(semester) }
                },
                label = {
                    Text(semester.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                },
            )
        }
    }
}

/** 周 chip 文案：上行周次，下行校历日期（有数据时，次要色小字）。 */
@Composable
private fun WeekChipLabel(
    date: OccupancyWeekDate?,
    primary: String,
) {
    if (date == null) {
        Text(primary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(primary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(
                "${date.startMonthDay}-${date.endMonthDay}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 筛选区：周选择箭头 + 可点击周文本（开学期/周弹层）。星期条已拆到详情层。 */
@Composable
private fun ClassroomOccupancyFilters(
    state: ClassroomOccupancyUiState,
    model: ClassroomOccupancyScreenModel,
    onOpenWeekPicker: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WeekArrow(
            label = "‹",
            contentDescription = "上一周",
            enabled = state.selectedWeek > MIN_WEEK,
            onClick = { scope.launch { model.selectWeek(state.selectedWeek - 1) } },
        )
        // 中间整块可点击打开弹层：带下箭头暗示可展开，与两侧箭头同款药丸样式。
        Surface(
            onClick = onOpenWeekPicker,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "第 ${state.selectedWeek} 周",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "▾",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        WeekArrow(
            label = "›",
            contentDescription = "下一周",
            enabled = state.selectedWeek < MAX_WEEK,
            onClick = { scope.launch { model.selectWeek(state.selectedWeek + 1) } },
        )
    }
}

/** 星期选择：固定单行七等分（对齐课程表 CompactDaySelector 的写法）。 */
@Composable
private fun OccupancyCompactDaySelector(
    selectedDay: Int,
    onSelect: (Int) -> Unit,
) {
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.48f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = selectedDay == index + 1
                Surface(
                    onClick = { onSelect(index + 1) },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekArrow(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Surface onClick 自带 ripple，与“更多”菜单等可点元素一致。
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 7 个节次完整时间段（与 aa stuschedule 表头一致，每节为两小节连上的大节）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotTimeRangesLegend() {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SLOT_TIME_RANGES.forEachIndexed { index, range ->
            Text(
                "第${index + 1}节 $range",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 图例七等分：空闲/排课/调课/考试/实验/其他（合并未知）+ 「时段▾」展开钮，
 * 与星期条同为七格视觉对齐。点「时段▾」在下方展开节次时间。
 */
@Composable
private fun OccupancyLegendRow(
    showSlotTimes: Boolean,
    onToggleSlotTimes: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.48f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            legendEntries().forEach { (kind, label) ->
                val (bg, fg) = occupancyCellColors(kind)
                // 色块作背景、文字放进色块里，与教室卡片占用格同款表达。
                // 固定 40dp 高度：在 animateContentSize 的 Column 里 heightIn(min=) 无上限，
                // 若再用 fillMaxSize 会把格子撑到剩余全屏；固定高度即可让文字居中。
                Surface(
                    color = bg,
                    contentColor = fg,
                    shape = RoundedCornerShape(11.dp),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
            // 第七格：时段展开钮。
            Surface(
                onClick = onToggleSlotTimes,
                color = if (showSlotTimes) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                contentColor = if (showSlotTimes) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = RoundedCornerShape(11.dp),
                modifier = Modifier.weight(1f).height(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (showSlotTimes) "时段▴" else "时段▾",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun legendEntries(): List<Pair<OccupancyKind, String>> = listOf(
    OccupancyKind.FREE to "空闲",
    OccupancyKind.SCHEDULED to "排课",
    OccupancyKind.RESCHEDULED to "调课",
    OccupancyKind.EXAM to "考试",
    OccupancyKind.EXPERIMENT to "实验",
    // 「其他」合并未知兜底：格子仍可能染未知色，图例不单列，凑齐六格 + 时段钮。
    OccupancyKind.OTHER to "其他",
)

/**
 * 占用格配色（背景 to 文字）。
 * 空闲用明确的浅绿，和其他占用态拉开对比（原先 surfaceVariant 太接近「其他/未知」）。
 * 实验改用 secondary 偏蓝绿容器，避免与空闲纯绿撞色；其余仍对齐教务语义。
 */
@Composable
private fun occupancyCellColors(kind: OccupancyKind): Pair<Color, Color> = when (kind) {
    // 空闲：固定软绿底 + 深绿字，亮/暗主题都比灰阶好认。
    OccupancyKind.FREE ->
        Color(0xFFD8F5E2) to Color(0xFF0D6B35)
    OccupancyKind.SCHEDULED ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    OccupancyKind.RESCHEDULED ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    OccupancyKind.EXAM ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    // 实验：与空闲绿区分，用主题 secondary（偏蓝绿/紫绿，不跟 #D8F5E2 撞）。
    OccupancyKind.EXPERIMENT ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    OccupancyKind.OTHER ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    OccupancyKind.UNKNOWN ->
        MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.6f) to
            MaterialTheme.colorScheme.onSurfaceVariant
}

/** 教室卡片：`SY101 · 90 座` + 所选星期的 7 节占用格（格内含节次号与开始时间）。 */
@Composable
private fun ClassroomOccupancyCard(
    room: ClassroomOccupancy,
    weekday: Int,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (room.capacity > 0) "${room.room} · ${room.capacity} 座" else room.room,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..7).forEach { period ->
                    OccupancyCell(
                        kind = room.kindAt(weekday, period),
                        period = period,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 占用格：上行节次号 + 下行开始时间（如「1 / 08:00」）。
 * 时间来自 [SLOT_TIME_RANGES]，与教务 stuschedule 表头一致。
 */
@Composable
private fun OccupancyCell(
    kind: OccupancyKind,
    period: Int,
    modifier: Modifier,
) {
    val (background, content) = occupancyCellColors(kind)
    val startTime = SLOT_TIME_RANGES.getOrNull(period - 1)?.substringBefore('-') ?: ""
    Surface(
        color = background,
        contentColor = content,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
            .height(36.dp)
            .semantics { contentDescription = "第${period}节 $startTime ${kind.name}" },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "$period",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 12.sp,
                maxLines = 1,
            )
            Text(
                startTime,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                maxLines = 1,
            )
        }
    }
}

package team.bjtuss.bjtuselfservice.shared.feature.exam

import team.bjtuss.bjtuselfservice.shared.feature.common.WorkspaceEmptyState
import team.bjtuss.bjtuselfservice.shared.feature.common.WorkspaceLoadingState

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.bjtuss.bjtuselfservice.shared.accessibleAlpha
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarGateway
import team.bjtuss.bjtuselfservice.shared.feature.calendar.SingleExamCalendarSheet
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppErrorBanner
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExamScheduleWorkspace(
    state: ExamScheduleUiState,
    expanded: Boolean,
    model: ExamScheduleScreenModel,
    fileGateway: HomeworkFileGateway,
    systemCalendarGateway: SystemCalendarGateway,
    onRefresh: () -> Unit,
    modifier: Modifier,
) {
    // 只灌缓存；网络自动同步由 shell 在登录成功后触发。
    LaunchedEffect(model) {
        model.initialize(refreshFromNetwork = false)
    }
    var showFilterSheet by remember { mutableStateOf(false) }
    var examToCalendar by remember { mutableStateOf<ExamSchedule?>(null) }

    Column(
        modifier = if (expanded) {
            modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        } else {
            modifier.padding(horizontal = 16.dp).padding(top = 8.dp)
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 同步进度条由 DestinationPage 钉在顶栏下，此处不再重复。
        state.failure?.let { failure ->
            ExamFailureBanner(
                failure = failure,
                hasContent = state.exams.isNotEmpty(),
                onRetry = onRefresh,
                onDismiss = model::dismissFailure,
            )
        }

        when {
            state.isLoading && state.exams.isEmpty() -> ExamLoadingState()
            state.exams.isEmpty() -> ExamEmptyState(onRefresh)
            else -> {
                if (expanded) {
                    // 与移动端一致：同步态在顶栏；Banner 内筛选入口；类型 chips 进 sheet（不再页内重复）。
                    ExamSummary(
                        state = state,
                        onOpenFilter = { showFilterSheet = true },
                    )
                    if (state.visibleExams.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("当前类型下没有考试安排", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { showFilterSheet = true }) { Text("调整筛选") }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            ExamList(
                                exams = state.visibleExams,
                                selectedExamId = state.selectedExamId,
                                onOpen = model::showExamDetails,
                                modifier = Modifier.weight(0.58f).fillMaxHeight(),
                            )
                            ExamDetailPanel(
                                exam = state.selectedExam,
                                onAddToCalendar = { examToCalendar = it },
                                modifier = Modifier.weight(0.42f).fillMaxHeight(),
                            )
                        }
                    }
                } else {
                    // 紧凑端：同步态在顶栏；Banner 内筛选入口；类型 chips 进 sheet。
                    ExamScrollableContent(
                        state = state,
                        onOpenFilter = { showFilterSheet = true },
                        onOpen = model::showExamDetails,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    state.selectedExam?.let { exam ->
                        AppleSheet(
                            onDismissRequest = model::dismissExamDetails,
                            title = "考试详情",
                            needsFullHeight = true,
                        ) {
                            val detailScrollState = rememberScrollState()
                            ExamDetailSheetBody(
                                exam = exam,
                                onAddToCalendar = {
                                    model.dismissExamDetails()
                                    examToCalendar = exam
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(detailScrollState)
                                    .desktopTouchScroll(detailScrollState)
                                    .padding(horizontal = 24.dp)
                                    .padding(bottom = 28.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        AppleSheet(
            onDismissRequest = { showFilterSheet = false },
            title = "考试筛选",
            needsFullHeight = true,
        ) {
            ExamFilterSheet(
                state = state,
                model = model,
                onDone = { showFilterSheet = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            )
        }
    }

    examToCalendar?.let { exam ->
        AppleSheet(
            onDismissRequest = { examToCalendar = null },
            title = "添加到日历",
            needsFullHeight = true,
        ) {
            SingleExamCalendarSheet(
                exam = exam,
                fileGateway = fileGateway,
                systemCalendarGateway = systemCalendarGateway,
                onDone = { examToCalendar = null },
            )
        }
    }
}

@Composable
private fun ExamSummary(
    state: ExamScheduleUiState,
    onOpenFilter: (() -> Unit)? = null,
) {
    val filtered = state.selectedType != null
    val subtitle = buildString {
        append(state.selectedType ?: "全部类型")
        if (filtered) append(" · 已筛选")
    }
    // 与成绩/作业对齐：摘要 + 右侧筛选 pill；同步态在顶栏右上。
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (onOpenFilter != null) {
                            Modifier
                                .clickable(onClick = onOpenFilter)
                                .padding(vertical = 2.dp, horizontal = 4.dp)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text(
                    "考试安排：${state.visibleExams.size} 项",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.accessibleAlpha(0.78f),
                )
            }
            if (onOpenFilter != null) {
                Surface(
                    onClick = onOpenFilter,
                    color = MaterialTheme.colorScheme.surface.accessibleAlpha(0.86f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.semantics { contentDescription = "筛选考试类型" },
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExamFilterFunnelIcon(modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamFilterFunnelIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier = modifier) {
        val stroke = 1.8.dp.toPx()
        val left = 2.5.dp.toPx()
        val right = size.width - left
        val top = 3.dp.toPx()
        val midY = size.height * 0.48f
        val neckLeft = size.width * 0.42f
        val neckRight = size.width * 0.58f
        val bottom = size.height - 2.5.dp.toPx()
        drawLine(tint, Offset(left, top), Offset(right, top), stroke, StrokeCap.Round)
        drawLine(tint, Offset(left, top), Offset(neckLeft, midY), stroke, StrokeCap.Round)
        drawLine(tint, Offset(right, top), Offset(neckRight, midY), stroke, StrokeCap.Round)
        drawLine(tint, Offset(neckLeft, midY), Offset(neckLeft, bottom), stroke, StrokeCap.Round)
        drawLine(tint, Offset(neckRight, midY), Offset(neckRight, bottom), stroke, StrokeCap.Round)
        drawLine(tint, Offset(neckLeft, bottom), Offset(neckRight, bottom), stroke, StrokeCap.Round)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExamTypeFilters(state: ExamScheduleUiState, model: ExamScheduleScreenModel) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.selectedType == null,
            onClick = { model.selectType(null) },
            label = { Text("全部") },
        )
        state.typeOptions.forEach { type ->
            FilterChip(
                selected = state.selectedType == type,
                onClick = { model.selectType(type) },
                // 考试类型名往往很长，允许多行完整显示，不在 chip 内截断。
                label = { Text(type) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExamFilterSheet(
    state: ExamScheduleUiState,
    model: ExamScheduleScreenModel,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("筛选考试类型", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "选择要查看的考试类型，列表会立即更新。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExamTypeFilters(state, model)
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) {
            Text("完成")
        }
    }
}

@Composable
private fun ExamScrollableContent(
    state: ExamScheduleUiState,
    onOpenFilter: () -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.desktopTouchScroll(listState),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        item(key = "exam-summary") {
            ExamSummary(state = state, onOpenFilter = onOpenFilter)
        }
        if (state.visibleExams.isEmpty()) {
            item(key = "exam-empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("当前类型下没有考试安排", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onOpenFilter) { Text("调整筛选") }
                    }
                }
            }
        } else {
            items(state.visibleExams, key = ExamSchedule::id) { exam ->
                ExamCard(
                    exam = exam,
                    selected = exam.id == state.selectedExamId,
                    onOpen = onOpen,
                )
            }
        }
    }
}

@Composable
private fun ExamList(
    exams: List<ExamSchedule>,
    selectedExamId: Int?,
    onOpen: (Int) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.desktopTouchScroll(listState),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        items(exams, key = ExamSchedule::id) { exam ->
            ExamCard(
                exam = exam,
                selected = exam.id == selectedExamId,
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun ExamCard(
    exam: ExamSchedule,
    selected: Boolean,
    onOpen: (Int) -> Unit,
) {
    ElevatedCard(
        onClick = { onOpen(exam.id) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                exam.courseName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                exam.examType,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            ExamCardLine("时间地点", exam.examTimeAndPlace)
            ExamCardLine("状态", exam.examStatus)
            if (exam.detail.isNotBlank()) {
                ExamCardLine("详情", exam.detail)
            }
        }
    }
}

@Composable
private fun ExamCardLine(label: String, value: String) {
    Text(
        "$label · ${value.ifBlank { "未提供" }}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ExamDetailPanel(
    exam: ExamSchedule?,
    onAddToCalendar: (ExamSchedule) -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.54f),
        shape = RoundedCornerShape(22.dp),
    ) {
        if (exam == null) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("选择一项考试查看详情", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ExamDetailContent(
                exam = exam,
                onAddToCalendar = onAddToCalendar,
                contentPadding = PaddingValues(22.dp),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ExamDetailContent(
    exam: ExamSchedule,
    onAddToCalendar: (ExamSchedule) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val detailScrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(detailScrollState)
            .desktopTouchScroll(detailScrollState)
            .padding(contentPadding),
    ) {
        ExamDetailSheetBody(exam, onAddToCalendar = onAddToCalendar)
    }
}

@Composable
private fun ExamDetailSheetBody(
    exam: ExamSchedule,
    onAddToCalendar: (ExamSchedule) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(exam.courseName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        ExamDetailLine("类型", exam.examType)
        ExamDetailLine("时间地点", exam.examTimeAndPlace)
        ExamDetailLine("状态", exam.examStatus)
        ExamDetailLine("详情", exam.detail.ifBlank { "未提供" })
        Button(onClick = { onAddToCalendar(exam) }, modifier = Modifier.fillMaxWidth()) {
            Text("添加到日历")
        }
    }
}

@Composable
private fun ExamDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ExamFailureBanner(
    failure: ExamScheduleSyncFailure,
    hasContent: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppErrorBanner(
        message = when (failure) {
            ExamScheduleSyncFailure.NETWORK -> if (hasContent) {
                "同步失败，正在显示本地考试安排。"
            } else {
                "无法连接教务系统，请检查网络后重试。"
            }
            ExamScheduleSyncFailure.SESSION_EXPIRED -> "教务会话已失效，请点击右上角刷新重试登录。"
            ExamScheduleSyncFailure.MALFORMED_RESPONSE -> "教务考试页面结构已变化，暂时无法解析。"
            ExamScheduleSyncFailure.CACHE -> "本地考试缓存操作失败。"
        },
        onRetry = if (failure != ExamScheduleSyncFailure.CACHE) onRetry else null,
        onDismiss = onDismiss,
    )
}

@Composable
private fun ExamLoadingState() = WorkspaceLoadingState("正在读取本地考试安排并连接教务系统…")

@Composable
private fun ExamEmptyState(onRefresh: () -> Unit) = WorkspaceEmptyState(
    title = "暂无考试安排",
    description = "本地没有缓存，教务系统也没有返回可显示的考试。",
    onRefresh = onRefresh,
)

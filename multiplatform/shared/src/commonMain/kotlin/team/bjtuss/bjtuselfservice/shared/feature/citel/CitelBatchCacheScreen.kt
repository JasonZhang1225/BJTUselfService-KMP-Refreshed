package team.bjtuss.bjtuselfservice.shared.feature.citel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AppleSegmentedControl
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentListWithScrollToTop
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleAlert
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleAlertAction
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleAlertRole
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleMenuOption
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleMenuPickerCard
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalBottomBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalTopBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.TopScrollLazyColumn

private data class CitelCacheActionFeedback(val taskId: Int, val message: String, val failed: Boolean)

@Composable
internal fun CitelBatchCacheScreen(
    model: CitelModel,
    modifier: Modifier = Modifier,
    holdNetwork: Boolean = false,
) {
    val state by model.state.collectAsState()
    var courseKey by rememberSaveable(state.username) { mutableStateOf("all") }
    var selectedCourseName by rememberSaveable(state.username) { mutableStateOf<String?>(null) }
    var submissionIndex by rememberSaveable(state.username) { mutableStateOf(0) }
    var busy by remember(model) { mutableStateOf(false) }
    var result by remember(model, state.username) { mutableStateOf<CitelBatchCacheResult?>(null) }
    var resultScopeLabel by remember(model, state.username) { mutableStateOf<String?>(null) }
    var pendingDeletion by remember(model, state.username) { mutableStateOf<Pair<CitelTask, CitelCachedProblem>?>(null) }
    var activeTaskId by remember(model) { mutableStateOf<Int?>(null) }
    var actionFeedback by remember(model, state.username) { mutableStateOf<CitelCacheActionFeedback?>(null) }
    val scope = rememberCoroutineScope()

    fun runCacheAction(taskId: Int, successMessage: String, operation: suspend () -> String?) {
        if (activeTaskId != null || busy) return
        activeTaskId = taskId
        result = null
        resultScopeLabel = null
        actionFeedback = null
        scope.launch {
            try {
                val error = operation()
                actionFeedback = CitelCacheActionFeedback(taskId, error ?: successMessage, error != null)
            } finally {
                activeTaskId = null
            }
        }
    }
    val listState = rememberLazyListState()

    val courses = remember(state.tasks) {
        state.tasks.filter { it.programming }.associate { it.courseId to it.courseName }
    }
    val selectedCourseId = if (courseKey == "all") null else courseKey.toIntOrNull()
    val courseValid = courseKey == "all" || (selectedCourseId != null && courses.containsKey(selectedCourseId))
    val courseId = selectedCourseId
    LaunchedEffect(selectedCourseId, courses) {
        if (selectedCourseId != null) {
            courses[selectedCourseId]?.let { selectedCourseName = it }
        }
    }
    val selectedCourseLabel = when {
        courseId == null -> if (courseKey == "all") "全部课程" else (selectedCourseName ?: "已选课程")
        else -> courses[courseId] ?: selectedCourseName ?: "课程 #$courseId"
    }
    val submission = CitelProblemSubmission.entries[submissionIndex]
    val problems = state.problems
    val (missing, cached) = remember(state.tasks, problems, courseKey, courseId, submission) {
        if (courseKey != "all" && courseId == null) emptyList<CitelTask>() to emptyList()
        else state.filteredProgrammingTasks(courseId, submission).partition { it.id !in problems }
    }
    val missingByCourse = remember(missing) { missing.groupBy { it.courseId } }
    val cachedByCourse = remember(cached) { cached.groupBy { it.courseId } }
    val canManageCache = !busy && activeTaskId == null && pendingDeletion == null && !state.refreshing
    val showBatchAction = busy || (state.enabled && courseValid && missing.isNotEmpty())
    val bottomClearance = LocalBottomBarClearance.current

    fun startBatchCache() {
        if (busy || activeTaskId != null || pendingDeletion != null) return
        val batchScopeLabel = "$selectedCourseLabel · ${submission.label}"
        busy = true
        result = null
        resultScopeLabel = null
        actionFeedback = null
        scope.launch {
            try {
                val next = model.cacheMissingProblems(courseId, submission)
                resultScopeLabel = batchScopeLabel
                result = next
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(courseKey, submission) { listState.scrollToItem(0) }

    BoxWithConstraints(modifier) {
        val horizontalPadding = if (maxWidth >= 760.dp) 24.dp else 16.dp
        val batchAction: @Composable (Modifier) -> Unit = { actionModifier ->
            CitelBatchCacheAction(
                busy = busy,
                enabled = canManageCache && !holdNetwork,
                modifier = actionModifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = horizontalPadding),
                onClick = ::startBatchCache,
            )
        }
        AssignmentListWithScrollToTop(
            state = listState,
            bottomAction = batchAction.takeIf { showBatchAction },
        ) { scrollModifier, overlayClearance ->
            TopScrollLazyColumn(
                state = listState,
                modifier = scrollModifier.desktopTouchScroll(listState),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = 16.dp + LocalTopBarClearance.current,
                    bottom = 16.dp + bottomClearance + overlayClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.enabled) {
                    item { Text("请先在设置中启用 CITEL。") }
                } else if (state.tasks.isEmpty() && (state.refreshing || holdNetwork)) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (courses.isEmpty()) {
                    item {
                        Text(when {
                            state.failed -> state.message ?: "CITEL 作业列表获取失败，请刷新重试。"
                            state.lastSync == null -> "尚未同步 CITEL 作业，请使用顶栏刷新。"
                            else -> "当前未缓存 0 题，暂无可缓存的 CITEL 编程题目。"
                        })
                    }
                } else {
                    item {
                        val missingCourseOption = if (!courseValid && courseId != null) {
                            listOf(AppleMenuOption(courseId.toString(), "$selectedCourseLabel（当前未在列表）"))
                        } else emptyList()
                        AppleMenuPickerCard(
                            label = "课程 · 共 ${courses.size} 门",
                            value = if (courseValid) selectedCourseLabel else "$selectedCourseLabel（当前未在列表）",
                            options = listOf(AppleMenuOption("all", "全部课程")) +
                                missingCourseOption +
                                courses.map { (id, name) -> AppleMenuOption(id.toString(), name) },
                            selectedKey = if (courseKey == "all") "all" else (courseId?.toString() ?: courseKey),
                            onSelect = { key ->
                                courseKey = key
                                selectedCourseName = key.toIntOrNull()?.let { courses[it] }
                            },
                            accessibilityName = "筛选课程",
                        )
                    }
                    item {
                        AppleSegmentedControl(
                            options = CitelProblemSubmission.entries.map { it.label },
                            selectedIndex = submissionIndex,
                            onSelect = { submissionIndex = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("当前筛选未缓存 ${missing.size} 题",
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.titleMedium)
                            if (!courseValid) {
                                Text("当前所选课程暂未出现在最新作业列表中，已保留选择；可等待同步恢复或切换其他课程。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.failed) {
                                Text(state.message ?: "CITEL 作业列表同步失败。",
                                    color = MaterialTheme.colorScheme.error)
                            }
                            result?.let {
                                if (it.saved > 0 || it.failed > 0) {
                                    val scopePrefix = resultScopeLabel?.let { label -> "$label：" }.orEmpty()
                                    Text("${scopePrefix}已缓存 ${it.saved} 题，失败 ${it.failed} 题。")
                                }
                                it.message?.let { message ->
                                    Text(message, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (courseValid && missing.isEmpty()) {
                                Text("当前筛选没有需要拉取的题目。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    missingByCourse.forEach { (id, tasks) ->
                        item(key = "missing-course-$id") {
                            Text("${courses[id].orEmpty()} · 未缓存 ${tasks.size} 题",
                                style = MaterialTheme.typography.titleSmall)
                        }
                        items(tasks, key = { it.id }) { task ->
                            CitelCacheTaskCard(task) {
                                CitelCacheActionMessage(task, actionFeedback)
                            }
                        }
                    }
                    item(key = "cached-header") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("当前筛选已缓存 ${cached.size} 题", style = MaterialTheme.typography.titleMedium)
                            if (cached.isEmpty()) {
                                Text("当前筛选没有已缓存的题目。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    cachedByCourse.forEach { (id, tasks) ->
                        item(key = "cached-course-$id") {
                            Text("${courses[id].orEmpty()} · 已缓存 ${tasks.size} 题",
                                style = MaterialTheme.typography.titleSmall)
                        }
                        items(tasks, key = { it.id }) { task ->
                            val entry = problems.getValue(task.id)
                            CitelCacheTaskCard(task) {
                                Text("缓存时间 ${citelDateText(entry.fetchedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                CitelCacheActionMessage(task, actionFeedback)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        enabled = canManageCache && !holdNetwork,
                                        onClick = {
                                            runCacheAction(task.id, "题面缓存已更新。") { model.updateCachedProblem(entry) }
                                        },
                                    ) { Text(if (activeTaskId == task.id) "正在处理…" else "更新") }
                                    TextButton(
                                        enabled = canManageCache,
                                        onClick = { pendingDeletion = task to entry },
                                    ) {
                                        Text("删除", color = if (canManageCache) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeletion?.let { (task, entry) ->
        AppleAlert(
            title = "删除题面缓存",
            message = "删除「${task.title}」的本地题面和用例缓存？删除后可重新拉取。",
            actions = listOf(
                AppleAlertAction("取消", AppleAlertRole.Cancel) { pendingDeletion = null },
                AppleAlertAction("删除缓存", AppleAlertRole.Destructive) {
                    pendingDeletion = null
                    runCacheAction(task.id, "已删除本地题面缓存。") { model.deleteCachedProblem(entry) }
                },
            ),
            onDismissRequest = { pendingDeletion = null },
        )
    }
}

@Composable
private fun CitelBatchCacheAction(
    busy: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (enabled) colorScheme.primary else colorScheme.onSurfaceVariant
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = color,
                )
            }
            Text(
                if (busy) "正在拉取题面…" else "一键拉取未缓存题面",
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
        }
    }
}

@Composable
private fun CitelCacheTaskCard(task: CitelTask, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge)
            Text(if (task.hasProgrammingSubmission) "已提交" else "未提交",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun CitelCacheActionMessage(task: CitelTask, feedback: CitelCacheActionFeedback?) {
    if (feedback?.taskId != task.id) return
    Text(feedback.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = if (feedback.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

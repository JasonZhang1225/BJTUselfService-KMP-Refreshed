package team.bjtuss.bjtuselfservice.shared.feature.citel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AppleSegmentedControl
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleMenuOption
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleMenuPickerCard
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalBottomBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalTopBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.TopScrollLazyColumn

@Composable
internal fun CitelBatchCacheScreen(
    model: CitelModel,
    modifier: Modifier = Modifier,
    holdNetwork: Boolean = false,
) {
    val state by model.state.collectAsState()
    var courseKey by rememberSaveable(state.username) { mutableStateOf("all") }
    var submissionIndex by rememberSaveable(state.username) { mutableStateOf(0) }
    var busy by remember(model) { mutableStateOf(false) }
    var result by remember(model, state.username) { mutableStateOf<CitelBatchCacheResult?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val courses = remember(state.tasks) {
        state.tasks.filter { it.programming }.associate { it.courseId to it.courseName }
    }
    val courseId = courseKey.toIntOrNull()?.takeIf { courses.containsKey(it) }
    val submission = CitelProblemSubmission.entries[submissionIndex]
    val missing = remember(state.tasks, state.problems, courseId, submission) {
        state.missingProblems(courseId, submission)
    }
    val groups = remember(missing) { missing.groupBy { it.courseId } }

    LaunchedEffect(courseId, submission) { listState.scrollToItem(0) }

    BoxWithConstraints(modifier) {
        val horizontalPadding = if (maxWidth >= 760.dp) 24.dp else 16.dp
        TopScrollLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().desktopTouchScroll(listState),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 16.dp + LocalTopBarClearance.current,
                bottom = 16.dp + LocalBottomBarClearance.current,
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
                    AppleMenuPickerCard(
                        label = "课程 · 共 ${courses.size} 门",
                        value = courses[courseId] ?: "全部课程",
                        options = listOf(AppleMenuOption("all", "全部课程")) +
                            courses.map { (id, name) -> AppleMenuOption(id.toString(), name) },
                        selectedKey = courseId?.toString() ?: "all",
                        onSelect = { courseKey = it },
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
                        if (state.failed) {
                            Text(state.message ?: "CITEL 作业列表同步失败。",
                                color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            enabled = !busy && !state.refreshing && !holdNetwork && missing.isNotEmpty(),
                            onClick = {
                                busy = true
                                result = null
                                scope.launch {
                                    try {
                                        result = model.cacheMissingProblems(courseId, submission)
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (busy) CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(if (busy) "正在拉取题面…" else "一键拉取未缓存题面")
                            }
                        }
                        result?.let {
                            if (it.saved > 0 || it.failed > 0) {
                                Text("已缓存 ${it.saved} 题，失败 ${it.failed} 题。")
                            }
                            it.message?.let { message ->
                                Text(message, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (missing.isEmpty()) {
                            Text("当前筛选没有需要拉取的题目。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                groups.forEach { (id, tasks) ->
                    item(key = "course-$id") {
                        Text("${courses[id].orEmpty()} · 未缓存 ${tasks.size} 题",
                            style = MaterialTheme.typography.titleSmall)
                    }
                    items(tasks, key = { it.id }) { task ->
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(task.title, style = MaterialTheme.typography.bodyLarge)
                                Text(if (task.hasProgrammingSubmission) "已提交" else "未提交",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

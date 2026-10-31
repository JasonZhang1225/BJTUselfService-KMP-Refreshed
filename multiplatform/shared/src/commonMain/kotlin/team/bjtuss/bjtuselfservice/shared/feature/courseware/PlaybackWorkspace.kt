package team.bjtuss.bjtuselfservice.shared.feature.courseware

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.currentPlatform
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStreamKind
import team.bjtuss.bjtuselfservice.shared.feature.common.WorkspaceEmptyState
import team.bjtuss.bjtuselfservice.shared.feature.common.WorkspaceLoadingState
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppErrorBanner
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalBottomBarClearance
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway

/**
 * “课程回放”独立入口：选课程 → 选课节 → 选画面（带预览）→ 播放；
 * Windows / macOS 另可下载与合成。课程列表复用课件页的智慧教学课程目录。
 */
@Composable
fun PlaybackScreen(
    coursewareState: CoursewareUiState,
    coursewareModel: CoursewareScreenModel,
    model: PlaybackScreenModel,
    fileGateway: HomeworkFileGateway,
    expanded: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    onOpenDetail: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsState()
    var showCoursePicker by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    val tasks by model.taskManager.tasks.collectAsState()
    val courses = coursewareState.courses
    val course = courses.firstOrNull { it.id == state.courseId }
    LaunchedEffect(coursewareModel) { coursewareModel.initialize() }
    val selectCourse: (CoursewareCourse) -> Unit = { picked -> scope.launch { model.showCourse(picked) } }
    val onRetry: () -> Unit = { scope.launch { model.showCourse(course, force = true) } }

    Column(
        modifier = if (expanded) {
            modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        } else {
            modifier.padding(horizontal = 16.dp).padding(top = 8.dp)
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 移动端关闭新下载后，升级遗留任务仍可能在 TaskManager 里；必须保留任务页以便取消/移除。
        if (playbackRecordingTasksUiVisible(playbackRecordingDownloadAvailable, tasks)) {
            PlaybackModeTabs(showTasks = showTasks, activeCount = tasks.count { it.isActive }, onShowTasks = { showTasks = it })
            state.downloadMessage?.let { message ->
                AppErrorBanner(message = message, onDismiss = model::dismissDownloadMessage)
            }
            if (showTasks) {
                PlaybackTasksPage(tasks, model.taskManager, Modifier.weight(1f).fillMaxWidth())
                return@Column
            }
            PlaybackActiveTaskBanner(tasks) { showTasks = true }
        }
        coursewareState.failure?.takeIf { courses.isEmpty() }?.let {
            AppErrorBanner(message = playbackFailureMessage(it, "课程列表"), onRetry = onRefresh)
        }
        when {
            coursewareState.isLoading && courses.isEmpty() -> WorkspaceLoadingState("正在读取课程并连接智慧教学平台…")
            courses.isEmpty() -> WorkspaceEmptyState(
                title = "暂无课程",
                description = "智慧教学平台没有返回本学期的课程。",
                onRefresh = onRefresh,
            )
            expanded -> Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ElevatedCard(modifier = Modifier.weight(0.24f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("课程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        PlaybackCourseList(courses, state.courseId, selectCourse, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                ElevatedCard(modifier = Modifier.weight(if (onOpenDetail != null) 0.76f else 0.30f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            course?.name ?: "选择课程",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        PlaybackLessonList(
                            state = state,
                            onRetry = onRetry,
                            onSelect = { lesson -> course?.let { model.selectLesson(it, lesson.stableKey); onOpenDetail?.invoke() } },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
                if (onOpenDetail == null) ElevatedCard(modifier = Modifier.weight(0.46f).fillMaxHeight()) {
                    val lesson = state.selectedLesson
                    if (lesson == null) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("选择一节课查看回放", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        val detailScroll = rememberScrollState()
                        Column(
                            Modifier.fillMaxSize().verticalScroll(detailScroll).desktopTouchScroll(detailScroll).padding(16.dp),
                        ) {
                            PlaybackDetailBody(state, lesson, course, model, fileGateway, onOpenTasks = { showTasks = true; model.clearSelection() }) {
                                course?.let { model.selectLesson(it, lesson.stableKey) }
                            }
                        }
                    }
                }
            }
            else -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            course?.name ?: "未选择课程",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when {
                                state.lessonsLoading -> "正在读取课节…"
                                course == null -> "先选择一门课程"
                                else -> "${state.lessons.size} 节课"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(onClick = { showCoursePicker = true }, modifier = Modifier.weight(1f)) {
                        Text("点击此处选择课程", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                PlaybackLessonList(
                    state = state,
                    onRetry = onRetry,
                    onSelect = { lesson -> course?.let { model.selectLesson(it, lesson.stableKey); onOpenDetail?.invoke() } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }

    if (!expanded) {
        state.selectedLesson?.takeIf { onOpenDetail == null }?.let { lesson ->
            AppleSheet(
                onDismissRequest = model::clearSelection,
                title = "课程回放",
                needsFullHeight = true,
                scrollableBody = true,
            ) {
                val sheetScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (currentPlatform().family == PlatformFamily.IOS) Modifier.weight(1f) else Modifier.heightIn(max = 720.dp))
                        .verticalScroll(sheetScroll)
                        .desktopTouchScroll(sheetScroll)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp),
                ) {
                    PlaybackDetailBody(state, lesson, course, model, fileGateway, onOpenTasks = { showTasks = true; model.clearSelection() }) {
                        course?.let { model.selectLesson(it, lesson.stableKey) }
                    }
                }
            }
        }
        if (showCoursePicker) {
            AppleSheet(
                onDismissRequest = { showCoursePicker = false },
                title = "选择课程",
                needsFullHeight = true,
                scrollableBody = true,
            ) {
                PlaybackCourseList(
                    courses = courses,
                    selectedCourseId = state.courseId,
                    onSelect = { picked ->
                        selectCourse(picked)
                        showCoursePicker = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(
                        if (currentPlatform().family == PlatformFamily.IOS) Modifier.weight(1f) else Modifier.heightIn(max = 560.dp),
                    ),
                )
            }
        }
    }
}

/** 原生导航承载的回放详情；保存后在同一页面查看任务，不出现可下滑关闭的 sheet。 */
@Composable
fun PlaybackDetailWorkspace(
    model: PlaybackScreenModel,
    courses: List<CoursewareCourse>,
    fileGateway: HomeworkFileGateway,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val tasks by model.taskManager.tasks.collectAsState()
    var showTasks by remember { mutableStateOf(false) }
    DisposableEffect(model) { onDispose { model.clearSelection() } }
    if (showTasks) {
        PlaybackTasksPage(tasks, model.taskManager, modifier.padding(16.dp))
        return
    }
    val lesson = state.selectedLesson ?: return
    val course = courses.firstOrNull { it.id == state.courseId }
    val scroll = rememberScrollState()
    Column(modifier.verticalScroll(scroll).desktopTouchScroll(scroll).padding(16.dp)) {
        PlaybackDetailBody(state, lesson, course, model, fileGateway,
            onOpenTasks = { showTasks = true }) {
            course?.let { model.selectLesson(it, lesson.stableKey) }
        }
    }
}

@Composable
private fun PlaybackCourseList(
    courses: List<CoursewareCourse>,
    selectedCourseId: Int?,
    onSelect: (CoursewareCourse) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.desktopTouchScroll(listState),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        contentPadding = PaddingValues(bottom = 12.dp + LocalBottomBarClearance.current),
    ) {
        items(courses, key = CoursewareCourse::stableKey) { course ->
            val selected = course.id == selectedCourseId
            Surface(
                onClick = { onSelect(course) },
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    course.name,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlaybackLessonList(
    state: PlaybackUiState,
    onRetry: () -> Unit,
    onSelect: (PlaybackLesson) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.lessonsFailure?.let { failure ->
            AppErrorBanner(message = playbackFailureMessage(failure, "课节列表"), onRetry = onRetry)
        }
        when {
            state.courseId == null -> PlaybackPlaceholder("选择课程后显示这门课的上课录像")
            state.lessonsLoading && state.lessons.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.lessons.isEmpty() && state.lessonsFailure == null -> PlaybackPlaceholder("这门课还没有录播课节")
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().desktopTouchScroll(listState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp + LocalBottomBarClearance.current),
            ) {
                items(state.lessons, key = PlaybackLesson::stableKey) { lesson ->
                    PlaybackLessonRow(lesson, selected = lesson.stableKey == state.selectedLessonKey) { onSelect(lesson) }
                }
            }
        }
    }
}

@Composable
private fun PlaybackPlaceholder(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaybackLessonRow(lesson: PlaybackLesson, selected: Boolean, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    lesson.title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    lesson.timeRange.takeIf { lesson.name.isNotBlank() && it.isNotBlank() },
                    lesson.classroom.takeIf(String::isNotBlank),
                    "无录像".takeIf { !lesson.hasRecording },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaybackDetailBody(
    state: PlaybackUiState,
    lesson: PlaybackLesson,
    course: CoursewareCourse?,
    model: PlaybackScreenModel,
    fileGateway: HomeworkFileGateway,
    onOpenTasks: () -> Unit,
    onRetryDetail: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(lesson.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        course?.let { PlaybackDetailLine("课程", it.name) }
        if (lesson.name.isNotBlank() && lesson.timeRange.isNotBlank()) PlaybackDetailLine("上课时间", lesson.timeRange)
        lesson.classroom.takeIf(String::isNotBlank)?.let { PlaybackDetailLine("教室", it) }
        val detail = state.detail?.takeIf { it.lesson.stableKey == lesson.stableKey }
        when {
            !lesson.hasRecording -> PlaybackNotice("这节课没有录像。")
            state.detailLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text("正在读取录像地址…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.detailFailure != null -> AppErrorBanner(
                message = playbackFailureMessage(state.detailFailure, "录像地址"),
                onRetry = onRetryDetail,
            )
            detail == null -> Unit
            !detail.permitted -> PlaybackNotice("老师没有开放这节课的录像观看。")
            detail.streams.isEmpty() -> PlaybackNotice("学校没有返回可用的 HTTPS 录像地址。")
            else -> PlaybackStreamChooser(detail, course?.name.orEmpty(), model, fileGateway, onOpenTasks)
        }
        // 知识点是平台从课件画面自动识别的文字，可读性差，放在最后且默认折叠。
        lesson.summary.takeIf(String::isNotBlank)?.let { PlaybackSummary(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackStreamChooser(
    detail: PlaybackDetail,
    courseName: String,
    model: PlaybackScreenModel,
    fileGateway: HomeworkFileGateway,
    onOpenTasks: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var compositeEnabled by remember { mutableStateOf(true) }
    var compositeQuality by remember { mutableStateOf(PlaybackCompositeQuality.HIGH) }
    var enqueuedFeedback by remember(detail.lesson.stableKey) { mutableStateOf(false) }
    var enqueueing by remember { mutableStateOf(false) }
    var selectedKinds by remember(detail.lesson.stableKey) { mutableStateOf(detail.defaultKinds.toSet()) }
    var playing by remember(detail.lesson.stableKey) { mutableStateOf<List<PlaybackStream>?>(null) }
    var browserFeedback by remember(detail.lesson.stableKey) { mutableStateOf<String?>(null) }
    val selected = detail.streams.filter { it.kind in selectedKinds }
    val toPlay = selected.take(2)
    val canComposite = model.taskManager.canComposite
    val willComposite = selected.size == 2 && canComposite && compositeEnabled

    val downloadAvailable = playbackRecordingDownloadAvailable
    Text("选择画面", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        if (downloadAvailable) {
            "预览取自第 10 分钟左右。可多选：选两路时与网页一样并排同步播放，下载时每一路单独保存。"
        } else {
            "预览取自第 10 分钟左右。可多选：选两路时与网页一样并排同步播放。本端仅支持在线播放，录像下载请使用 Windows 或 Mac。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        detail.streams.forEach { stream ->
            PlaybackStreamCard(
                stream = stream,
                selected = stream.kind in selectedKinds,
                onToggle = {
                    selectedKinds = if (stream.kind in selectedKinds) selectedKinds - stream.kind else selectedKinds + stream.kind
                    playing = null
                },
            )
        }
    }

    val current = playing
    if (current != null && playbackPlaysInApp) {
        val html = remember(current) { buildPlaybackPlayerHtml(detail, current) }
        PlaybackPlayerView(
            html = html,
            baseUrl = originOf(current.first().hlsUrl),
            streams = current,
            // 竖屏手机两路上下排，需要更高的区域；横屏/平板会自动改成并排。
            modifier = Modifier.fillMaxWidth().height(if (current.size > 1) 560.dp else 360.dp),
        )
        OutlinedButton(onClick = { playing = null }, modifier = Modifier.fillMaxWidth()) { Text("停止播放") }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                if (playbackPlaysInApp) {
                    playing = toPlay
                } else {
                    browserFeedback = if (openPlaybackInSystemBrowser(buildPlaybackPlayerHtml(detail, toPlay))) {
                        "已在浏览器中打开：" + toPlay.joinToString(" + ") { it.kind.label } +
                            if (toPlay.size > 1) "（同步播放，可切换声音来源）" else ""
                    } else {
                        "无法打开系统浏览器。"
                    }
                }
            },
            enabled = toPlay.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                when {
                    selected.isEmpty() -> "先选择画面"
                    playbackPlaysInApp -> "播放所选（${toPlay.size} 路）"
                    else -> "在浏览器中播放（${toPlay.size} 路）"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (downloadAvailable) {
            OutlinedButton(
                onClick = {
                    enqueueing = true
                    scope.launch {
                        val result = model.download(
                            detail = detail,
                            streams = selected,
                            gateway = fileGateway,
                            courseName = courseName,
                            composite = compositeEnabled,
                            quality = compositeQuality,
                        )
                        enqueuedFeedback = result is PlaybackEnqueueResult.Started
                        enqueueing = false
                    }
                },
                enabled = selected.isNotEmpty() && !enqueueing && fileGateway.isStreamingSaveAvailable,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when {
                        selected.isEmpty() -> "下载"
                        willComposite -> "下载并合成"
                        else -> "下载所选（${selected.size} 路）"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (selected.size > 2) {
        Text(
            if (downloadAvailable) {
                "一次最多并排播放两路，将播放前两路；下载会分别保存全部所选。"
            } else {
                "一次最多并排播放两路，将播放前两路。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (downloadAvailable && selected.size == 2) {
        if (canComposite) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("合成为一个左右并排视频", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "左：${selected[0].kind.label}，右：${selected[1].kind.label}。支持硬件加速，耗时取决于设备和录像长度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = compositeEnabled, onCheckedChange = { compositeEnabled = it })
            }
            if (compositeEnabled) {
                PlaybackCompositeQualitySelector(compositeQuality) { compositeQuality = it }
            }
        } else {
            Text(
                "当前平台暂不支持合成，两路会分别保存为 MP4。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    browserFeedback?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (downloadAvailable && enqueuedFeedback) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已加入下载任务，可在后台继续。", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onOpenTasks) { Text("查看进度") }
            }
        }
    }
    if (downloadAvailable) {
        Text(
            "下载保存为 MP4（无损转换，不重新编码），各种播放器都能打开；两小时的课每路约 1–2 GB。" +
                (detail.sizeMegabytes.takeIf(String::isNotBlank)?.let { "学校标注的录像体积约 ${formatCoursewareSize(it)}（所有画面合计）。" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!fileGateway.isStreamingSaveAvailable) {
            Text("当前平台还不能保存大文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PlaybackStreamCard(stream: PlaybackStream, selected: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.widthIn(min = 180.dp, max = 260.dp),
    ) {
        Column {
            // 预览在原生视图里会吞掉点击，上面盖一层透明点击区。
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))) {
                PlaybackFramePreview(stream.hlsUrl, stream.kind.label, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(role = Role.Checkbox, onClick = onToggle)
                        .semantics {
                            this.selected = selected
                            contentDescription = "${stream.kind.label}，${if (selected) "已选" else "未选"}"
                        },
                )
            }
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onToggle).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stream.kind.label, fontWeight = FontWeight.SemiBold)
                    Text(stream.kind.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(if (selected) "✓ 已选" else "选择", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PlaybackSummary(summary: String) {
    var expanded by remember(summary) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "知识点（平台从课件画面自动识别）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (expanded) "收起" else "展开")
        }
    }
}

private val PlaybackStreamKind.description: String
    get() = when (this) {
        PlaybackStreamKind.TEACHER -> "教室摄像头：讲台、黑板与投影"
        PlaybackStreamKind.SCREEN -> "老师电脑屏幕：PPT 等课件，最清晰"
        PlaybackStreamKind.STUDENT -> "教室摄像头：学生座位方向"
        PlaybackStreamKind.TEACHER_CLOSE_UP -> "老师特写镜头"
        PlaybackStreamKind.STUDENT_CLOSE_UP -> "学生特写镜头"
        PlaybackStreamKind.MOVIE -> "平台合成画面"
    }

private fun originOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val pathStart = url.indexOf('/', schemeEnd + 3)
    return if (pathStart < 0) url else url.substring(0, pathStart) + "/"
}

@Composable
private fun PlaybackNotice(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlaybackDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun playbackFailureMessage(failure: CoursewareSyncFailure, subject: String): String = when (failure) {
    CoursewareSyncFailure.NETWORK -> "读取${subject}失败，请检查网络后重试。"
    CoursewareSyncFailure.SESSION_EXPIRED -> "智慧教学平台会话已失效，请点击右上角刷新重试登录。"
    CoursewareSyncFailure.MALFORMED_RESPONSE -> "学校返回的${subject}无法识别，请稍后重试。"
    CoursewareSyncFailure.SECURE_CHANNEL_UNAVAILABLE -> "该地址不在允许的学校通道范围内。"
    CoursewareSyncFailure.CACHE -> "本地缓存不可用。"
    CoursewareSyncFailure.DOWNLOAD_NOT_ALLOWED -> "老师没有开放这节课的录像。"
    CoursewareSyncFailure.FILE_TOO_LARGE -> "文件太大。"
}

@Composable
private fun PlaybackModeTabs(showTasks: Boolean, activeCount: Int, onShowTasks: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.FilterChip(selected = !showTasks, onClick = { onShowTasks(false) }, label = { Text("课程回放") })
        androidx.compose.material3.FilterChip(
            selected = showTasks,
            onClick = { onShowTasks(true) },
            label = { Text(if (activeCount > 0) "下载任务 · $activeCount" else "下载任务") },
        )
    }
}

/** 回放页顶部的一行进度：点它进入“下载任务”。 */
@Composable
private fun PlaybackActiveTaskBanner(tasks: List<PlaybackTask>, onOpen: () -> Unit) {
    val active = tasks.filter { it.isActive }
    val current = active.firstOrNull { it.phase != PlaybackTaskPhase.QUEUED } ?: active.firstOrNull() ?: return
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${current.lessonTitle} · ${current.phase.label}" +
                    (current.progress?.let { " ${(it * 100).toInt()}%" } ?: "") +
                    (if (active.size > 1) "（共 ${active.size} 个任务）" else "") + "  ›",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PlaybackTaskProgress(current)
            // 体积与速度：紧凑一行放在进度条下面，和任务卡片里的口径一致。
            if (current.sizeLabel != null || current.speedLabel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        current.sizeLabel.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    current.speedLabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackTaskProgress(task: PlaybackTask) {
    if (task.phase == PlaybackTaskPhase.PIPELINING) {
        Text("下载 ${((task.downloadProgress ?: 0f) * 100).toInt()}% · 合成 ${((task.stageProgress ?: 0f) * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { task.downloadProgress ?: 0f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary)
    }
    val progress = task.stageProgress ?: task.progress
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** “下载任务”页：每个任务的阶段、进度、结果，可取消或移除。 */
@Composable
fun PlaybackTasksPage(tasks: List<PlaybackTask>, manager: PlaybackTaskManager, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(tasks.firstOrNull()?.id) { if (tasks.isNotEmpty()) listState.scrollToItem(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "下载、转换和合成都在后台进行，离开这个页面不受影响。" + backgroundHint(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tasks.any { !it.isActive && !it.canResume }) {
                TextButton(onClick = manager::clearFinished) { Text("清除已结束") }
            }
        }
        if (tasks.isEmpty()) {
            PlaybackPlaceholder("还没有下载任务。在课节详情里选择画面后点“下载”。")
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().desktopTouchScroll(listState),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 18.dp + LocalBottomBarClearance.current),
        ) {
            items(tasks, key = PlaybackTask::id) { task -> PlaybackTaskCard(task, manager) }
        }
    }
}

@Composable
private fun PlaybackTaskCard(task: PlaybackTask, manager: PlaybackTaskManager) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.lessonTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(task.courseName, task.streamLabels.joinToString(if (task.composite) " + " else "、") + if (task.composite) "（合成）" else "")
                            .filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    task.phase.label + ((task.stageProgress ?: task.progress)?.takeIf { task.isActive || task.phase == PlaybackTaskPhase.PAUSED }?.let { " ${(it * 100).toInt()}%" } ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = when (task.phase) {
                        PlaybackTaskPhase.FAILED -> MaterialTheme.colorScheme.error
                        PlaybackTaskPhase.DONE -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (task.isActive || task.phase == PlaybackTaskPhase.PAUSED) {
                PlaybackTaskProgress(task)
                task.step.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                task.compositionDevice?.takeIf {
                    task.phase == PlaybackTaskPhase.PIPELINING || task.phase == PlaybackTaskPhase.COMPOSITING
                }?.let {
                    Text("合成设备：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 体积与速度放在进度条下面一行：左边已下载/预计总量，右边当前速度。
                if (task.sizeLabel != null || task.speedLabel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            task.sizeLabel.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        task.speedLabel?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            task.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
            }
            task.remainingLabel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            task.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.phase == PlaybackTaskPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.outputs.isNotEmpty()) {
                Text(
                    "保存为：" + task.outputs.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                PlaybackSavedOutputs(task)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (task.isActive) {
                    if (task.canPause) {
                        TextButton(onClick = { manager.pause(task.id) }) { Text("暂停") }
                    }
                    TextButton(onClick = { manager.cancel(task.id) }) { Text("取消") }
                } else {
                    if (task.canResume) TextButton(onClick = { manager.retry(task.id) }) { Text("继续处理") }
                    TextButton(onClick = { manager.dismiss(task.id) }) { Text("移除") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackSavedOutputs(task: PlaybackTask, actions: PlaybackSavedFileActions? = remember { playbackSavedFileActions() }) {
    if (actions == null) return
    val scope = rememberCoroutineScope()
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    var opening by remember(task.id) { mutableStateOf(false) }
    val paths = task.outputs.indices.mapNotNull { task.outputPaths.getOrNull(it)?.takeIf(String::isNotBlank) }
    if (paths.isEmpty()) {
        if (task.phase == PlaybackTaskPhase.DONE) {
            Text("保存路径：旧任务未记录完整路径", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    paths.forEach { path ->
        SelectionContainer {
            Text("保存路径：$path", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (task.phase == PlaybackTaskPhase.DONE) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun open(folder: Boolean) {
                    if (opening) return
                    opening = true
                    error = null
                    scope.launch {
                        try { error = actions.open(path, folder) } finally { opening = false }
                    }
                }
                OutlinedButton(onClick = { open(false) }, enabled = !opening) { Text("打开视频") }
                TextButton(onClick = { open(true) }, enabled = !opening) { Text("打开文件夹") }
            }
        }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

/**
 * 合成清晰度选择。学校每路录像只有 1080p 一个源，清晰度只能在合成时由编码器决定：
 * 分辨率和码率一起降低；文件体积及合成耗时还受录像内容与设备影响。
 */
@Composable
private fun PlaybackCompositeQualitySelector(
    selected: PlaybackCompositeQuality,
    onSelect: (PlaybackCompositeQuality) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("合成清晰度", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaybackCompositeQuality.entries.forEach { quality ->
                androidx.compose.material3.FilterChip(
                    selected = quality == selected,
                    onClick = { onSelect(quality) },
                    label = { Text("${quality.label} ${quality.width}×${quality.height}") },
                )
            }
        }
        val hourTenths = selected.approxGigabytesPerHourTenths
        val twoHourTenths = hourTenths * 2
        val mbpsTenths = selected.megabitsPerSecondTenths
        Text(
            "${selected.width}×${selected.height}，${mbpsTenths / 10}.${mbpsTenths % 10} Mbps，" +
                "约 ${hourTenths / 10}.${hourTenths % 10} GB/小时（两小时的课约 ${twoHourTenths / 10}.${twoHourTenths % 10} GB）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun backgroundHint(): String {
    val platform = currentPlatform()
    return when {
        platform.family == PlatformFamily.Android -> "切到后台时通知栏会显示进度。"
        platform.family == PlatformFamily.IOS ->
            "切到后台也会继续下载；合成在 iOS 26 及以上可后台进行，更早的系统需回到 App。"
        platform.isWindows ->
            "关闭窗口默认会退出 App 并中止任务；在设置里开启“关闭窗口时收进托盘”后，关窗口可继续下载。"
        else -> "关闭窗口后 App 仍在运行，任务会继续；退出 App 会中止任务。"
    }
}

/**
 * 录像下载入口关闭时仍要露出遗留任务（进度/取消/移除），避免后台仍跑却无 UI。
 * 桌面端始终有下载能力时本函数恒为 true。
 */
internal fun playbackRecordingTasksUiVisible(
    downloadAvailable: Boolean,
    tasks: List<PlaybackTask>,
): Boolean = downloadAvailable || tasks.isNotEmpty()

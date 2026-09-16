package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusFailure
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeKind
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeAgendaDay
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeDomain
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeRecord
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeStatus
import team.bjtuss.bjtuselfservice.shared.domain.home.buildHomeAgenda
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseWeekScrollAccumulator
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseWeekScrollDirection
import team.bjtuss.bjtuselfservice.shared.feature.course.courseWeekScrollNavigation
import team.bjtuss.bjtuselfservice.shared.domain.home.HOME_MAX_TEACHING_WEEK
import team.bjtuss.bjtuselfservice.shared.domain.home.resolveHomeAgendaWeekStart
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll

@Composable
fun HomeWorkspace(
    model: HomeScreenModel,
    platform: PlatformInfo,
    expanded: Boolean,
    homework: List<Homework>,
    exams: List<ExamSchedule>,
    phyVlabEvents: List<PhyVlabEvent> = emptyList(),
    currentWeek: Int,
    academicWeeks: List<OccupancyWeekDate> = emptyList(),
    now: LocalDateTime,
    timeZone: TimeZone,
    isAgendaLoading: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenMailbox: () -> Unit,
    onOpenHomework: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenPhyVlab: () -> Unit = {},
    changes: List<HomeChangeRecord>,
    onClearAllChanges: () -> Unit,
    onClearChangeDomain: (HomeChangeDomain) -> Unit,
    onOpenChangeDomain: (HomeChangeDomain) -> Unit,
    // 静默自动登录期间为 true：会话未就绪，初始化（含网络刷新）延后到登录完成。
    holdNetwork: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    val campusDestination = campusCardDestination(platform.family)
    val pageListState = rememberLazyListState()
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var selectedChangeDomain by remember { mutableStateOf<HomeChangeDomain?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model, holdNetwork) { if (!holdNetwork) model.initialize() }

    when (dialog) {
        HomeDialog.CampusCard -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("前往完美校园") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(campusDestination.message)
                    if (campusDestination.action == CampusCardAction.ShowQrCode) {
                        MiniProgramQrCode()
                        Text(
                            "用手机微信扫描",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    dialog = null
                    if (campusDestination.action == CampusCardAction.OpenUrl) {
                        val target = campusDestination.url
                        if (target == null || runCatching { uriHandler.openUri(target) }.isFailure) {
                            actionMessage = "当前无法打开完美校园链接。"
                        }
                    }
                }) { Text(campusDestination.confirmLabel) }
            },
            dismissButton = {
                if (campusDestination.action == CampusCardAction.OpenUrl) {
                    TextButton(onClick = { dialog = null }) { Text("取消") }
                }
            },
        )
        HomeDialog.Network -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("校园网充值") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NetworkPaymentQrCode()
                    NetworkPaymentInstruction(platform.family)
                }
            },
            confirmButton = {
                Button(onClick = { dialog = null }) { Text("关闭") }
            },
        )
        null -> Unit
    }
    selectedChangeDomain?.let { domain ->
        HomeChangeDialog(
            domain = domain,
            changes = changes.filter { it.domain == domain },
            onDismiss = { selectedChangeDomain = null },
            onMarkRead = {
                selectedChangeDomain = null
                onClearChangeDomain(domain)
            },
            onOpen = {
                selectedChangeDomain = null
                onOpenChangeDomain(domain)
            },
        )
    }

    val status = state.status
    LazyColumn(
        modifier = modifier.fillMaxSize().desktopTouchScroll(pageListState),
        contentPadding = PaddingValues(
            horizontal = if (expanded) 8.dp else 16.dp,
            vertical = 14.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (expanded) {
            item(key = "home-header") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("首页", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "邮件与校园账户状态来自当前 MIS 会话",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !isRefreshing) {
                        Text(if (isRefreshing) "同步中" else "刷新")
                    }
                }
            }
        }
        state.failure?.let { failure ->
            item(key = "home-failure") {
                Text(
                    failure.message(state.status != null),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        actionMessage?.let { message ->
            item(key = "home-action-message") {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
        if (expanded) {
            item(key = "home-status-cards") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MailCard(status, onOpenMailbox, Modifier.weight(1f))
                    CampusCard(status, { dialog = HomeDialog.CampusCard }, Modifier.weight(1f))
                    NetworkCard(status, { dialog = HomeDialog.Network }, Modifier.weight(1f))
                }
            }
            item(key = "home-agenda") {
                HomeAgendaSection(
                    platform = platform,
                    homework = homework,
                    exams = exams,
                    phyVlabEvents = phyVlabEvents,
                    currentWeek = currentWeek,
                    academicWeeks = academicWeeks,
                    now = now,
                    timeZone = timeZone,
                    isLoading = isAgendaLoading,
                    expanded = expanded,
                    onOpenHomework = onOpenHomework,
                    onOpenExams = onOpenExams,
                    onOpenPhyVlab = onOpenPhyVlab,
                )
            }
        } else {
            // 紧凑页：本周日程放第一栏，新邮件保持原尺寸，两张余额卡半宽并列，
            // 尽量不用滚动就能看全（2026-08-04 真机反馈）。
            item(key = "home-agenda") {
                HomeAgendaSection(
                    platform = platform,
                    homework = homework,
                    exams = exams,
                    phyVlabEvents = phyVlabEvents,
                    currentWeek = currentWeek,
                    academicWeeks = academicWeeks,
                    now = now,
                    timeZone = timeZone,
                    isLoading = isAgendaLoading,
                    expanded = expanded,
                    onOpenHomework = onOpenHomework,
                    onOpenExams = onOpenExams,
                    onOpenPhyVlab = onOpenPhyVlab,
                )
            }
            item(key = "home-mail-card") {
                MailCard(status, onOpenMailbox, Modifier.fillMaxWidth())
            }
            item(key = "home-account-cards") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CampusCard(status, { dialog = HomeDialog.CampusCard }, Modifier.weight(1f))
                    NetworkCard(status, { dialog = HomeDialog.Network }, Modifier.weight(1f))
                }
            }
        }
        item(key = "home-change-feed") {
            HomeChangeFeedSection(
                changes = changes,
                onSelectDomain = { selectedChangeDomain = it },
                onClearAll = onClearAllChanges,
            )
        }
    }
}

private enum class HomeDialog { CampusCard, Network }

@Composable
private fun MiniProgramQrCode() = QrCode(
    matrix = WECHAT_MINI_PROGRAM_QR_MATRIX,
    quietZone = 3,
    description = "完美校园微信小程序二维码",
)

@Composable
private fun NetworkPaymentQrCode() = QrCode(
    matrix = NETWORK_PAYMENT_QR_MATRIX,
    quietZone = 4,
    description = "北京交通大学卡网缴费微信二维码",
)

@Composable
private fun QrCode(
    matrix: List<String>,
    quietZone: Int,
    description: String,
) {
    Surface(
        modifier = Modifier
            .size(240.dp)
            .semantics { contentDescription = description },
        color = Color.White,
        shape = MaterialTheme.shapes.medium,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val moduleCount = matrix.size + quietZone * 2
            val moduleSize = minOf(size.width, size.height) / moduleCount
            val startX = (size.width - moduleSize * moduleCount) / 2f
            val startY = (size.height - moduleSize * moduleCount) / 2f
            matrix.forEachIndexed { row, values ->
                values.forEachIndexed { column, value ->
                    if (value == '1') {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(
                                startX + (column + quietZone) * moduleSize,
                                startY + (row + quietZone) * moduleSize,
                            ),
                            size = Size(moduleSize + 0.15f, moduleSize + 0.15f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkPaymentInstruction(family: PlatformFamily) {
    val prefix = if (family == PlatformFamily.MacOS) {
        "请使用"
    } else {
        "请将二维码截图或保存到相册，并打开"
    }
    Text(
        buildAnnotatedString {
            append(prefix)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append("微信扫一扫")
            }
            append("进入卡网缴费页面。")
        },
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun MailCard(status: HomeStatus?, onClick: () -> Unit, modifier: Modifier) = StatusCard(
    title = "新邮件",
    value = status?.newMailCount ?: "—",
    detail = if (status?.hasNewMail == true) "有新邮件，记得查看" else "当前 BJTU 邮箱状态",
    action = "查看邮箱",
    onClick = onClick,
    modifier = modifier,
)

@Composable
private fun CampusCard(status: HomeStatus?, onClick: () -> Unit, modifier: Modifier) = StatusCard(
    title = "校园卡余额",
    value = status?.campusCardBalance ?: "—",
    detail = if (status?.campusCardLow == true) "余额低于 20，请留意" else "充值由完美校园完成",
    action = "前往完美校园",
    onClick = onClick,
    modifier = modifier,
)

@Composable
private fun NetworkCard(
    status: HomeStatus?,
    onClick: () -> Unit,
    modifier: Modifier,
) = StatusCard(
    title = "校园网余额",
    value = status?.networkBalance ?: "—",
    detail = if (status?.networkEmpty == true) "余额为 0，请及时处理" else "使用微信完成卡网缴费",
    action = "显示缴费二维码",
    onClick = onClick,
    modifier = modifier,
)

@Composable
private fun StatusCard(
    title: String,
    value: String,
    detail: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 半宽卡片里按钮默认的水平内边距会挤压中文标签导致换行，收紧一些。
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(action, maxLines = 1) }
        }
    }
}

@Composable
private fun HomeAgendaSection(
    platform: PlatformInfo,
    homework: List<Homework>,
    exams: List<ExamSchedule>,
    phyVlabEvents: List<PhyVlabEvent>,
    currentWeek: Int,
    academicWeeks: List<OccupancyWeekDate>,
    now: LocalDateTime,
    timeZone: TimeZone,
    isLoading: Boolean,
    expanded: Boolean,
    onOpenHomework: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenPhyVlab: () -> Unit,
) {
    val today = now.date
    val dueSoonHomework = remember(homework, exams, phyVlabEvents, today, now, timeZone) {
        buildHomeAgenda(homework, exams, today, now, timeZone, phyVlabEvents)
            .dueSoonHomework
    }
    // currentWeek == 0 means today is in a holiday/non-teaching gap. Keep a
    // dedicated page 0 for that natural week instead of guessing teaching week 1.
    val initialWeek = currentWeek.takeIf { it in 1..HOME_MAX_TEACHING_WEEK } ?: 0
    var selectedWeek by remember(currentWeek) { mutableStateOf(initialWeek) }
    val weekScrollAccumulator = remember { CourseWeekScrollAccumulator() }
    val useFingerWeekPager = platform.family == PlatformFamily.Android ||
        platform.family == PlatformFamily.IOS
    val weekStartFor: (Int) -> LocalDate = { week ->
        resolveHomeAgendaWeekStart(currentWeek, week, today, academicWeeks)
    }
    val selectWeek: (Int) -> Unit = { week ->
        if (week in 0..HOME_MAX_TEACHING_WEEK) selectedWeek = week
    }
    val adjacentWeekFor: (Int, Int) -> Int? = { week, offset ->
        if (week == 0) {
            val currentStart = weekStartFor(0)
            val datedWeeks = academicWeeks
                .filter { it.week in 1..HOME_MAX_TEACHING_WEEK && it.startDate != null }
                .sortedBy { it.startDate }
            if (offset < 0) {
                datedWeeks.lastOrNull { it.startDate!! < currentStart }?.week
            } else {
                datedWeeks.firstOrNull { it.startDate!! > currentStart }?.week ?: 1
            }
        } else {
            (week + offset).takeIf { it in 1..HOME_MAX_TEACHING_WEEK }
        }
    }

    if (dueSoonHomework.isNotEmpty()) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DueSoonHomeworkSummary(dueSoonHomework, Modifier.weight(1f))
                    OutlinedButton(onClick = onOpenHomework) { Text("查看作业") }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DueSoonHomeworkSummary(dueSoonHomework)
                    OutlinedButton(onClick = onOpenHomework, modifier = Modifier.fillMaxWidth()) {
                        Text("查看作业")
                    }
                }
            }
        }
    }

    if (useFingerWeekPager) {
        // When today is a holiday gap, insert the natural-week page at its
        // calendar position (e.g. week 3 -> 非教学周 -> week 4), not before week 1.
        val pagerWeeks = if (currentWeek == 0) {
            (0..HOME_MAX_TEACHING_WEEK).sortedBy(weekStartFor)
        } else {
            (1..HOME_MAX_TEACHING_WEEK).toList()
        }
        val pageForWeek: (Int) -> Int = { week ->
            pagerWeeks.indexOf(week).coerceAtLeast(0)
        }
        val weekForPage: (Int) -> Int = { page ->
            pagerWeeks[page.coerceIn(pagerWeeks.indices)]
        }
        val pagerState = rememberPagerState(initialPage = pageForWeek(initialWeek)) {
            pagerWeeks.size
        }
        LaunchedEffect(pagerState.currentPage) {
            selectWeek(weekForPage(pagerState.currentPage))
        }
        LaunchedEffect(selectedWeek) {
            val targetPage = pageForWeek(selectedWeek)
            if (targetPage != pagerState.currentPage && !pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage(targetPage)
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            beyondViewportPageCount = 1,
            pageSpacing = 12.dp,
        ) { page ->
            val week = weekForPage(page)
            HomeAgendaWeekCard(
                homework = homework,
                exams = exams,
                phyVlabEvents = phyVlabEvents,
                week = week,
                weekStartDate = weekStartFor(week),
                now = now,
                timeZone = timeZone,
                isLoading = isLoading,
                onOpenHomework = onOpenHomework,
                onOpenExams = onOpenExams,
                onOpenPhyVlab = onOpenPhyVlab,
                previousWeek = adjacentWeekFor(week, -1),
                nextWeek = adjacentWeekFor(week, 1),
                onSelectWeek = selectWeek,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        HomeAgendaWeekCard(
            homework = homework,
            exams = exams,
            phyVlabEvents = phyVlabEvents,
            week = selectedWeek,
            weekStartDate = weekStartFor(selectedWeek),
            now = now,
            timeZone = timeZone,
            isLoading = isLoading,
            onOpenHomework = onOpenHomework,
            onOpenExams = onOpenExams,
            onOpenPhyVlab = onOpenPhyVlab,
            previousWeek = adjacentWeekFor(selectedWeek, -1),
            nextWeek = adjacentWeekFor(selectedWeek, 1),
            onSelectWeek = selectWeek,
            modifier = Modifier
                .fillMaxWidth()
                .courseWeekScrollNavigation(weekScrollAccumulator) { direction ->
                    when (direction) {
                        CourseWeekScrollDirection.PREVIOUS -> adjacentWeekFor(selectedWeek, -1)?.let(selectWeek)
                        CourseWeekScrollDirection.NEXT -> adjacentWeekFor(selectedWeek, 1)?.let(selectWeek)
                    }
                },
        )
    }
}

@Composable
private fun HomeAgendaWeekCard(
    homework: List<Homework>,
    exams: List<ExamSchedule>,
    phyVlabEvents: List<PhyVlabEvent>,
    week: Int,
    weekStartDate: LocalDate,
    now: LocalDateTime,
    timeZone: TimeZone,
    isLoading: Boolean,
    onOpenHomework: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenPhyVlab: () -> Unit,
    previousWeek: Int?,
    nextWeek: Int?,
    onSelectWeek: (Int) -> Unit,
    modifier: Modifier,
) {
    val today = now.date
    val weekAgenda = remember(homework, exams, phyVlabEvents, today, now, timeZone, weekStartDate) {
        buildHomeAgenda(
            homework = homework,
            exams = exams,
            today = today,
            now = now,
            timeZone = timeZone,
            phyVlabEvents = phyVlabEvents,
            weekStartDate = weekStartDate,
        )
    }
    var selectedDate by remember(week, today, weekStartDate) {
        mutableStateOf(
            if (today >= weekStartDate && today <= weekStartDate.plus(6, DateTimeUnit.DAY)) {
                today
            } else {
                weekStartDate
            },
        )
    }
    val selectedDay = weekAgenda.days.firstOrNull { it.date == selectedDate } ?: weekAgenda.days.first()

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (week == 0) "非教学周" else "第 $week 教学周",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (week == 0) {
                            "当前日期不在教学周内，仍显示本周日程"
                        } else if (phyVlabEvents.isEmpty()) {
                            "作业开始、截止与考试安排"
                        } else {
                            "作业开始、截止与考试安排（含物理在线）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    HomeWeekNavigationControls(
                        previousWeek = previousWeek,
                        nextWeek = nextWeek,
                        onPrevious = { previousWeek?.let(onSelectWeek) },
                        onNext = { nextWeek?.let(onSelectWeek) },
                    )
                    if (isLoading && homework.isEmpty() && exams.isEmpty() && phyVlabEvents.isEmpty()) {
                        Text("同步中", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                weekAgenda.days.forEach { day ->
                    AgendaDayCell(
                        day = day,
                        selected = day.date == selectedDay.date,
                        isToday = day.date == today,
                        onClick = { selectedDate = day.date },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "${shortDate(selectedDay.date)} · ${weekdayName(selectedDay.date)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            AgendaDayDetails(selectedDay, onOpenHomework, onOpenExams, onOpenPhyVlab)
        }
    }
}

@Composable
private fun HomeWeekNavigationControls(
    previousWeek: Int?,
    nextWeek: Int?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeWeekNavigationButton(
            label = "‹",
            contentDescription = previousWeek?.let { "切换到第${it}教学周" } ?: "没有更早的教学周",
            enabled = previousWeek != null,
            onClick = onPrevious,
        )
        HomeWeekNavigationButton(
            label = "›",
            contentDescription = nextWeek?.let { "切换到第${it}教学周" } ?: "没有更晚的教学周",
            enabled = nextWeek != null,
            onClick = onNext,
        )
    }
}

@Composable
private fun HomeWeekNavigationButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(34.dp)
            .semantics { this.contentDescription = contentDescription },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DueSoonHomeworkSummary(
    homework: List<Homework>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "48 小时内有 ${homework.size} 项作业截止",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
        homework.take(3).forEach { item ->
            Text(
                "${item.courseName} · ${item.title} · ${item.endTime}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (homework.size > 3) {
            Text(
                "另有 ${homework.size - 3} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgendaDayCell(
    day: HomeAgendaDay,
    selected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val hasDeadline = day.homeworkDue.isNotEmpty() || day.phyVlabEvents.any {
        it.kind == PhyVlabEventKind.DEADLINE
    }
    val cellColor = when {
        hasDeadline -> MaterialTheme.colorScheme.errorContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val cellContentColor = if (hasDeadline) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = cellColor,
        contentColor = cellContentColor,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(weekdayShortName(day.date), style = MaterialTheme.typography.labelSmall)
            Text(day.date.day.toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                if (day.eventCount == 0) "—" else "${day.eventCount}项",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = if (hasDeadline) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun AgendaDayDetails(
    day: HomeAgendaDay,
    onOpenHomework: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenPhyVlab: () -> Unit,
) {
    if (day.eventCount == 0) {
        Text("当天没有作业、考试或物理在线安排。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        day.homeworkStarting.forEach { item ->
            AgendaEventRow("开始", item.title, item.courseName, onOpenHomework)
        }
        day.homeworkDue.forEach { item ->
            AgendaEventRow("截止", item.title, "${item.courseName} · ${item.endTime}", onOpenHomework)
        }
        day.exams.forEach { exam ->
            AgendaEventRow("考试", exam.courseName, exam.examTimeAndPlace, onOpenExams)
        }
        day.phyVlabEvents.forEach { event ->
            AgendaEventRow(
                type = if (event.kind == PhyVlabEventKind.START) "物理开始" else "物理截止",
                title = event.title,
                detail = formatPhyVlabAgendaDate(event),
                onClick = onOpenPhyVlab,
            )
        }
    }
}

@Composable
private fun AgendaEventRow(
    type: String,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    val isDeadline = type.contains("截止")
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                type,
                color = if (isDeadline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun weekdayShortName(date: LocalDate): String =
    listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.isoDayNumber - 1]

private fun weekdayName(date: LocalDate): String = "星期${weekdayShortName(date)}"

private fun formatPhyVlabAgendaDate(event: PhyVlabEvent): String {
    if (Regex("·\\s*周[一二三四五六日]").containsMatchIn(event.dateText)) return event.dateText
    val weekday = runCatching {
        Instant.fromEpochSeconds(event.dayTimestamp)
            .toLocalDateTime(TimeZone.of("Asia/Shanghai"))
            .date
    }.getOrNull()?.let { date -> "周${weekdayShortName(date)}" }
    return if (weekday == null || event.dateText.isBlank()) {
        event.dateText
    } else {
        "${event.dateText} · $weekday"
    }
}

private fun shortDate(date: LocalDate): String = "${date.month.ordinal + 1}月${date.day}日"

@Composable
private fun HomeChangeFeedSection(
    changes: List<HomeChangeRecord>,
    onSelectDomain: (HomeChangeDomain) -> Unit,
    onClearAll: () -> Unit,
) {
    // 过滤历史误报：原/现文案完全相同的「修改」不算变动。
    val meaningful = changes.filterNot {
        it.kind == DataChangeKind.MODIFIED && it.beforeDetail == it.afterDetail
    }
    if (meaningful.isEmpty()) return
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("数据变动", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "同步后发现 ${meaningful.size} 项变化",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClearAll) { Text("全部标记已读") }
            }
            HomeChangeDomain.entries.forEach { domain ->
                val domainChanges = meaningful.filter { it.domain == domain }
                if (domainChanges.isNotEmpty()) {
                    ChangeDomainRow(domain, domainChanges, onSelectDomain)
                }
            }
        }
    }
}

@Composable
private fun HomeChangeDialog(
    domain: HomeChangeDomain,
    changes: List<HomeChangeRecord>,
    onDismiss: () -> Unit,
    onMarkRead: () -> Unit,
    onOpen: () -> Unit,
) {
    val changeScrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${domain.title}变动") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(changeScrollState)
                    .desktopTouchScroll(changeScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 过滤历史误报：原/现展示文案完全相同的「修改」不展示。
                changes
                    .filterNot {
                        it.kind == DataChangeKind.MODIFIED && it.beforeDetail == it.afterDetail
                    }
                    .forEach { ChangeDetailRow(it) }
            }
        },
        confirmButton = { Button(onClick = onOpen) { Text("前往页面") } },
        dismissButton = {
            Row {
                TextButton(onClick = onMarkRead) { Text("标记已读") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun ChangeDomainRow(
    domain: HomeChangeDomain,
    changes: List<HomeChangeRecord>,
    onSelectDomain: (HomeChangeDomain) -> Unit,
) {
    Surface(
        onClick = { onSelectDomain(domain) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(domain.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                changeCountSummary(changes),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChangeDetailRow(change: HomeChangeRecord) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                change.kind.label,
                color = when (change.kind) {
                    DataChangeKind.ADDED -> MaterialTheme.colorScheme.primary
                    DataChangeKind.MODIFIED -> MaterialTheme.colorScheme.tertiary
                    DataChangeKind.DELETED -> MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.SemiBold,
            )
            Text(change.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            if (change.beforeDetail.isNotBlank()) {
                Text(
                    "原：${change.beforeDetail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (change.afterDetail.isNotBlank()) {
                Text(
                    "现：${change.afterDetail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val DataChangeKind.label: String
    get() = when (this) {
        DataChangeKind.ADDED -> "新增"
        DataChangeKind.MODIFIED -> "修改"
        DataChangeKind.DELETED -> "删除"
    }

private fun changeCountSummary(changes: List<HomeChangeRecord>): String = buildList {
    DataChangeKind.entries.forEach { kind ->
        val count = changes.count { it.kind == kind }
        if (count > 0) add("${kind.label} $count")
    }
}.joinToString(" · ")

private fun HomeStatusFailure.message(hasCache: Boolean): String = when (this) {
    HomeStatusFailure.NETWORK -> if (hasCache) "网络不可用，正在显示上次状态。" else "无法连接 MIS 状态服务。"
    HomeStatusFailure.SESSION_EXPIRED -> if (hasCache) {
        "登录会话已失效，正在显示上次状态；请点击右上角刷新重试登录。"
    } else {
        "登录会话已失效，请点击右上角刷新重试登录。"
    }
    HomeStatusFailure.PARSE -> if (hasCache) "学校返回格式变化，正在显示上次状态。" else "无法读取学校返回的状态。"
    HomeStatusFailure.CACHE -> if (hasCache) "最新状态未能写入本地，仍显示上次状态。" else "无法保存最新状态。"
}

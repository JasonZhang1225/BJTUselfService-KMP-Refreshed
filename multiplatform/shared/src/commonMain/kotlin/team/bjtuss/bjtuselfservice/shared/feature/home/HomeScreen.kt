package team.bjtuss.bjtuselfservice.shared.feature.home

import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheetOrAlert
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalBottomBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.ReportTopScrollListState
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalReportTopScroll
import team.bjtuss.bjtuselfservice.shared.feature.shell.resolveVisualTopScrollOffset
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.withoutVisualEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalTopBarClearance
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.flow.collect
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.accessibleAlpha
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusFailure
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUnreadSummary
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeKind
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.AcademicWeekSlot
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.academicWeekSlots
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeAgenda
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeAgendaDay
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeDomain
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeRecord
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeStatus
import team.bjtuss.bjtuselfservice.shared.domain.home.buildHomeAgenda
import team.bjtuss.bjtuselfservice.shared.domain.home.isHomeAgendaDayFullySubmitted
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.isHomeworkSubmitted
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
    mailboxUnread: MailboxUnreadSummary? = null,
    homework: List<Homework>,
    exams: List<ExamSchedule>,
    phyVlabEvents: List<PhyVlabEvent> = emptyList(),
    currentWeek: Int,
    academicWeeks: List<OccupancyWeekDate> = emptyList(),
    /** 周数是否已由本学期校历确认；未确认时首页显示「日程加载中」。 */
    isWeekResolved: Boolean = true,
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
    val homeOverscrollEffect = rememberOverscrollEffect()
    // A short iOS home page rubber-bands without changing LazyListState's
    // logical offset. Observe the native effect's placed content as well, so
    // material fades in when that content actually enters the title bar.
    val tracksElasticPosition = !expanded && LocalTopBarClearance.current > 0.dp
    val viewportTop = remember { mutableStateOf<Float?>(null) }
    val contentTop = remember { mutableStateOf<Float?>(null) }
    val reportTopScroll = LocalReportTopScroll.current
    if (tracksElasticPosition) {
        LaunchedEffect(pageListState, reportTopScroll) {
            snapshotFlow {
                val logicalOffset = if (pageListState.firstVisibleItemIndex > 0) {
                    Float.MAX_VALUE
                } else {
                    pageListState.firstVisibleItemScrollOffset.toFloat()
                }
                resolveVisualTopScrollOffset(logicalOffset, viewportTop.value, contentTop.value)
            }.collect { reportTopScroll(it) }
        }
    } else {
        ReportTopScrollListState(pageListState)
    }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var selectedChangeDomain by remember { mutableStateOf<HomeChangeDomain?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model, holdNetwork) { if (!holdNetwork) model.initialize() }

    when (dialog) {
        // iOS 上换成从下往上的卡片（半屏透、可上拉），其余平台仍是 Material 对话框。
        HomeDialog.CampusCard -> AppleSheetOrAlert(
            onDismissRequest = { dialog = null },
            title = null,
            confirmLabel = campusDestination.confirmLabel,
            showDismissButton = true,
            onConfirm = {
                dialog = null
                if (campusDestination.action == CampusCardAction.OpenUrl) {
                    val target = campusDestination.url
                    if (target == null || runCatching { uriHandler.openUri(target) }.isFailure) {
                        actionMessage = "当前无法打开完美校园链接。"
                    }
                }
            },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    campusDestination.message,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Start,
                )
                if (campusDestination.action == CampusCardAction.ShowQrCode) {
                    MiniProgramQrCode()
                    Text(
                        "用手机微信扫描",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        HomeDialog.Network -> AppleSheet(
            onDismissRequest = { dialog = null },
            title = "校园网充值",
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                NetworkPaymentQrCode()
                NetworkPaymentInstruction(platform.family)
            }
        }
        null -> Unit
    }
    selectedChangeDomain?.let { domain ->
        HomeChangeDialog(
            domain = domain,
            changes = changes.filter { it.domain == domain },
            isIos = platform.family == PlatformFamily.IOS,
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
    Box(
        modifier = modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            if (tracksElasticPosition) viewportTop.value = coordinates.positionInRoot().y
        },
    ) {
        LazyColumn(
            state = pageListState,
            modifier = Modifier.fillMaxSize().desktopTouchScroll(pageListState)
                .then(if (tracksElasticPosition) Modifier.overscroll(homeOverscrollEffect) else Modifier)
                .onGloballyPositioned { coordinates ->
                    if (tracksElasticPosition) contentTop.value = coordinates.positionInRoot().y
                },
            // The same native effect handles events inside the list and renders once
            // outside it, where we can observe its actual rubber-band displacement.
            overscrollEffect = if (tracksElasticPosition) {
                homeOverscrollEffect?.withoutVisualEffect()
            } else {
                homeOverscrollEffect
            },
            contentPadding = PaddingValues(
                start = if (expanded) 8.dp else 16.dp,
                end = if (expanded) 8.dp else 16.dp,
                // 原生栏 underlap 时首项靠这份顶边距让开，视口本身画到屏幕顶（底栏同理）。
                top = 14.dp + LocalTopBarClearance.current,
                // 玻璃胶囊浮在列表之上。首页视口已停在胶囊上沿，这里只留末项呼吸。
                bottom = 14.dp + LocalBottomBarClearance.current,
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
                        MailCard(status, mailboxUnread, onOpenMailbox, Modifier.weight(1f))
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
                        isWeekResolved = isWeekResolved,
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
                        isWeekResolved = isWeekResolved,
                        onOpenHomework = onOpenHomework,
                        onOpenExams = onOpenExams,
                        onOpenPhyVlab = onOpenPhyVlab,
                    )
                }
                item(key = "home-mail-card") {
                    MailCard(status, mailboxUnread, onOpenMailbox, Modifier.fillMaxWidth())
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
}

private enum class HomeDialog { CampusCard, Network }

private fun mondayOf(date: LocalDate): LocalDate =
    date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

/**
 * 首页自动跟随的目标周：用户手动选周后永不覆盖（异步结果不得把用户正在看的周顶掉）；
 * 未确认时不跟随；其它情况下只要目标与当前不同就跟随。
 *
 * 比较必须用整个槽（含周号）而不仅是开始日期：启动时无校历的回退槽与校历确认后的
 * 真实槽可能落在同一天（都是本周一），只比日期会把错误的第 1 周永远留在台面上。
 */
internal fun nextAutoFollowedWeekSlot(
    selectedSlot: AcademicWeekSlot,
    automaticSlot: AcademicWeekSlot?,
    weekWasManuallySelected: Boolean,
): AcademicWeekSlot? {
    if (weekWasManuallySelected) return null
    if (automaticSlot == null) return null
    return if (automaticSlot != selectedSlot) automaticSlot else null
}

/** 首页与课表共用校历时间轴；没有校历时保留旧的 1..30 周兜底。 */
private fun homeAgendaWeekSlots(
    currentWeek: Int,
    today: LocalDate,
    academicWeeks: List<OccupancyWeekDate>,
): List<AcademicWeekSlot> {
    val calendarSlots = academicWeekSlots(academicWeeks, HOME_MAX_TEACHING_WEEK)
    if (calendarSlots.isEmpty()) {
        return (1..HOME_MAX_TEACHING_WEEK).map { week ->
            AcademicWeekSlot(
                teachingWeek = week,
                startDate = resolveHomeAgendaWeekStart(currentWeek, week, today, academicWeeks),
            )
        }
    }
    val todayMonday = mondayOf(today)
    return if (calendarSlots.any { it.startDate == todayMonday }) {
        calendarSlots
    } else {
        // 当前日期可能落在校历范围之外；保留这一个自然周，避免首页变成
        // “没有周数”而丢掉当天的作业开始/截止事件。
        (calendarSlots + AcademicWeekSlot(null, todayMonday)).sortedBy(AcademicWeekSlot::startDate)
    }
}

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
private fun MailCard(
    status: HomeStatus?,
    mailboxUnread: MailboxUnreadSummary?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    // 优先用邮箱实际未读数（Coremail 列表）；未探测到时回退 MIS 聚合值。
    val value = mailboxUnread?.let { if (it.capped) "${it.count}+" else "${it.count}" }
        ?: status?.newMailCount
        ?: "—"
    val hasUnread = mailboxUnread?.let { it.count > 0 } ?: (status?.hasNewMail == true)
    StatusCard(
        title = "新邮件",
        value = value,
        detail = if (hasUnread) "有新邮件，记得查看" else "当前 BJTU 邮箱状态",
        action = "查看邮箱",
        onClick = onClick,
        modifier = modifier,
    )
}

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
    HomeCard(modifier = modifier) {
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
    /** 周数是否已由本学期校历确认；未确认时首页显示「日程加载中」。 */
    isWeekResolved: Boolean = true,
    onOpenHomework: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenPhyVlab: () -> Unit,
) {
    val homeSchedule = LocalHomeSchedule.current
    val today = now.date
    // 校历确认之前不显示任何周数（含缓存值），统一显示「日程加载中」，
    // 拿到确切结果后一次性显示最终周；这样卡片不会随中间值反复弹跳。
    val weekValueIsPending = !isWeekResolved
    val dueSoonHomework = remember(homework, exams, phyVlabEvents, today, now, timeZone) {
        buildHomeAgenda(homework, exams, today, now, timeZone, phyVlabEvents)
            .dueSoonHomework
    }
    val weekSlots = remember(currentWeek, academicWeeks, today) {
        homeAgendaWeekSlots(currentWeek, today, academicWeeks)
    }
    val todayMonday = remember(today) { mondayOf(today) }
    val initialSlot = remember(currentWeek, academicWeeks, today) {
        when {
            currentWeek in 1..HOME_MAX_TEACHING_WEEK ->
                weekSlots.firstOrNull { it.teachingWeek == currentWeek }
                    ?: AcademicWeekSlot(currentWeek, resolveHomeAgendaWeekStart(currentWeek, currentWeek, today, academicWeeks))
            else -> weekSlots.firstOrNull { it.startDate == todayMonday }
                ?: AcademicWeekSlot(null, todayMonday)
        }
    }
    // Keep the first visible week stable while the calendar is still being
    // resolved. Otherwise a changing pager list can reuse the same page index
    // for a different week and look like an unwanted swipe on launch.
    val startupSlot = remember { initialSlot }
    var selectedSlot by remember { mutableStateOf(startupSlot) }
    var weekWasManuallySelected by remember { mutableStateOf(false) }
    val weekScrollAccumulator = remember { CourseWeekScrollAccumulator() }
    val useFingerWeekPager = platform.family == PlatformFamily.Android ||
        platform.family == PlatformFamily.IOS
    val selectedDates = remember { mutableStateMapOf<LocalDate, LocalDate>() }
    val selectedDateFor: (AcademicWeekSlot) -> LocalDate = { slot ->
        val weekStartDate = slot.startDate
        selectedDates[slot.startDate]?.takeIf { date ->
            date >= weekStartDate && date <= weekStartDate.plus(6, DateTimeUnit.DAY)
        } ?: if (today >= weekStartDate && today <= weekStartDate.plus(6, DateTimeUnit.DAY)) {
            today
        } else {
            weekStartDate
        }
    }
    val selectWeekFromUser: (AcademicWeekSlot) -> Unit = { slot ->
        weekWasManuallySelected = true
        selectedSlot = slot
    }
    val adjacentWeekFor: (AcademicWeekSlot, Int) -> AcademicWeekSlot? = { slot, offset ->
        val index = weekSlots.indexOfFirst { it.startDate == slot.startDate }
        weekSlots.getOrNull(index + offset)
    }

    // 登录后/校历刷新可能先给出缓存周，再给出校历校准周；只有用户没有手动选周时，
    // 才让首页自动跟随这个更新，避免把用户正在看的周强行跳回第 1 周。
    // 周数尚未由校历确认时不自动跟随：否则会先跳到中间值、再跳到最终值。
    val automaticSlot = when {
        !isWeekResolved -> null
        currentWeek in 1..HOME_MAX_TEACHING_WEEK ->
            weekSlots.firstOrNull { it.teachingWeek == currentWeek }
        else -> weekSlots.firstOrNull { it.startDate == todayMonday }
    }
    LaunchedEffect(currentWeek, academicWeeks, isWeekResolved) {
        nextAutoFollowedWeekSlot(selectedSlot, automaticSlot, weekWasManuallySelected)?.let { slot ->
            selectedSlot = slot
        }
    }

    if (dueSoonHomework.isNotEmpty()) {
        HomeCard(modifier = Modifier.fillMaxWidth()) {
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

    if (useFingerWeekPager && !isWeekResolved) {
        // 校历还在变时不用按页码记住位置的周页：页码会留在旧下标，
        // 后面的周列表一换，启动时就会看起来像被滑到第 1 周。
        // 点选日期仍使用下面的内容过渡；周数确认后再换成手指横滑页。
        HomeAgendaWeekCard(
            homework = homework,
            exams = exams,
            phyVlabEvents = phyVlabEvents,
            weekSlot = startupSlot,
            selectedDate = selectedDateFor(startupSlot),
            now = now,
            timeZone = timeZone,
            isLoading = isLoading,
            isWeekResolved = isWeekResolved,
            isWeekPending = weekValueIsPending,
            showWeekButtons = false,
            onOpenHomework = onOpenHomework,
            onOpenExams = onOpenExams,
            onOpenPhyVlab = onOpenPhyVlab,
            previousWeek = null,
            nextWeek = null,
            onSelectWeek = selectWeekFromUser,
            onSelectDate = { date -> selectedDates[startupSlot.startDate] = date },
            isDateCurrent = { date -> selectedDateFor(startupSlot) == date },
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (useFingerWeekPager) {
        // The pager follows the school-calendar timeline, so an internal holiday
        // is a real page between its surrounding teaching weeks.
        val pagerWeeks = weekSlots
        val pageForWeek: (AcademicWeekSlot) -> Int = { slot ->
            pagerWeeks.indexOfFirst { it.startDate == slot.startDate }.coerceAtLeast(0)
        }
        val weekForPage: (Int) -> AcademicWeekSlot = { page ->
            pagerWeeks[page.coerceIn(pagerWeeks.indices)]
        }
        val pagerTargetSlot = if (!weekWasManuallySelected) {
            automaticSlot ?: selectedSlot
        } else {
            selectedSlot
        }
        val pagerTargetPage = pageForWeek(pagerTargetSlot)
        val pagerState = rememberPagerState(initialPage = pagerTargetPage) {
            pagerWeeks.size
        }
        var pagerProgrammaticTargetPage by remember { mutableStateOf<Int?>(null) }
        val latestPagerWeeks by rememberUpdatedState(pagerWeeks)
        val latestSelectedSlot by rememberUpdatedState(selectedSlot)
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }
                .collect { page ->
                    val programmaticTarget = pagerProgrammaticTargetPage
                    if (programmaticTarget != null) {
                        if (page == programmaticTarget) {
                            pagerProgrammaticTargetPage = null
                        }
                        return@collect
                    }
                    val weeks = latestPagerWeeks
                    if (weeks.isEmpty()) return@collect
                    val slot = weeks[page.coerceIn(weeks.indices)]
                    if (latestSelectedSlot.startDate != slot.startDate) {
                        weekWasManuallySelected = true
                        selectedSlot = slot
                    }
                }
        }
        LaunchedEffect(pagerTargetPage, pagerWeeks) {
            if (pagerTargetPage != pagerState.currentPage && !pagerState.isScrollInProgress) {
                // This scroll is caused by an arrow click, a current-week refresh,
                // or a changed holiday/week mapping; it must not be interpreted as
                // a new manual swipe by the settled-page observer above.
                pagerProgrammaticTargetPage = pagerTargetPage
                pagerState.scrollToPage(pagerTargetPage)
                if (pagerState.settledPage == pagerTargetPage) {
                    pagerProgrammaticTargetPage = null
                }
            }
        }
        // The pager contains only the week header and the seven-day calendar.
        // Its height is therefore stable while a horizontal gesture is in
        // progress; the selected-day agenda below is the only part allowed to
        // change height after the new week settles.
        var calendarHeightPx by remember(pagerWeeks) { mutableStateOf(0) }
        val density = LocalDensity.current
        val calendarHeightModifier = calendarHeightPx.takeIf { it > 0 }?.let { heightPx ->
            Modifier.height(with(density) { heightPx.toDp() })
        } ?: Modifier
        val settledPage = pagerState.settledPage.coerceIn(pagerWeeks.indices)
        val settledSlot = weekForPage(settledPage)
        val settledDate = selectedDateFor(settledSlot)
        val scheduleSwipeThresholdPx = with(density) { 56.dp.toPx() }
        var scheduleSwipeTargetPage by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(scheduleSwipeTargetPage) {
            val targetPage = scheduleSwipeTargetPage ?: return@LaunchedEffect
            if (
                targetPage in pagerWeeks.indices &&
                !pagerState.isScrollInProgress &&
                targetPage != pagerState.settledPage
            ) {
                pagerState.animateScrollToPage(targetPage)
            }
            scheduleSwipeTargetPage = null
        }

        HomeCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().then(calendarHeightModifier),
                    beyondViewportPageCount = 0,
                    pageSpacing = 12.dp,
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    val weekSlot = weekForPage(page)
                    val weekStartDate = weekSlot.startDate
                    val weekAgenda = remember(
                        homework,
                        exams,
                        phyVlabEvents,
                        today,
                        now,
                        timeZone,
                        weekStartDate,
                        homeSchedule,
                    ) {
                        buildHomeAgenda(
                            homework = homework,
                            exams = exams,
                            today = today,
                            now = now,
                            timeZone = timeZone,
                            phyVlabEvents = phyVlabEvents,
                            weekStartDate = weekStartDate,
                        ).withCourses(homeSchedule)
                    }
                    HomeAgendaCalendarContent(
                        weekSlot = weekSlot,
                        weekAgenda = weekAgenda,
                        today = today,
                        homework = homework,
                        exams = exams,
                        phyVlabEvents = phyVlabEvents,
                        isLoading = isLoading,
                        isWeekPending = weekValueIsPending,
                        showWeekButtons = false,
                        previousWeek = adjacentWeekFor(weekSlot, -1),
                        nextWeek = adjacentWeekFor(weekSlot, 1),
                        onSelectWeek = selectWeekFromUser,
                        selectedDate = selectedDateFor(weekSlot),
                        // A horizontal pager drag can end over a day cell. Do
                        // not turn that release point into a date click while
                        // the pager is still settling.
                        onSelectDate = { date ->
                            if (
                                !pagerState.isScrollInProgress &&
                                    pagerState.currentPage == pagerState.settledPage
                            ) {
                                selectedDates[weekSlot.startDate] = date
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                if (size.height > calendarHeightPx) {
                                    calendarHeightPx = size.height
                                }
                            },
                    )
                }
                val dayTransition = updateTransition(
                    targetState = settledSlot to settledDate,
                    label = "home-agenda-selected-day",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Keep the old affordance that a swipe can start in
                        // the details area as well as on the calendar. The
                        // pager still owns the calendar gesture; this handler
                        // only runs below it and never changes layout size.
                        .pointerInput(pagerState, pagerWeeks.size, scheduleSwipeThresholdPx) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    totalDrag += dragAmount
                                },
                                onDragEnd = {
                                    val page = pagerState.settledPage
                                    val targetPage = when {
                                        totalDrag <= -scheduleSwipeThresholdPx -> page + 1
                                        totalDrag >= scheduleSwipeThresholdPx -> page - 1
                                        else -> page
                                    }
                                    if (targetPage in pagerWeeks.indices) {
                                        scheduleSwipeTargetPage = targetPage
                                    }
                                },
                                onDragCancel = { totalDrag = 0f },
                            )
                        },
                ) {
                    dayTransition.AnimatedContent(
                        transitionSpec = {
                            val direction = if (targetState.first.startDate >= initialState.first.startDate) 1 else -1
                            (
                                slideInVertically(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = 420f,
                                    ),
                                ) { height -> direction * height / 3 } +
                                    fadeIn(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = 420f,
                                        ),
                                    )
                                ) togetherWith (
                                slideOutVertically(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = 420f,
                                    ),
                                ) { height -> -direction * height / 3 } +
                                    fadeOut(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = 420f,
                                        ),
                                    )
                                ) using SizeTransform(
                                    clip = false,
                                    sizeAnimationSpec = { _, _ ->
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = 320f,
                                        )
                                    },
                                )
                        },
                    ) { (weekSlot, date) ->
                        val weekStartDate = weekSlot.startDate
                        val weekAgenda = remember(
                            homework,
                            exams,
                            phyVlabEvents,
                            today,
                            now,
                            timeZone,
                            weekStartDate,
                            homeSchedule,
                        ) {
                            buildHomeAgenda(
                                homework = homework,
                                exams = exams,
                                today = today,
                                now = now,
                                timeZone = timeZone,
                                phyVlabEvents = phyVlabEvents,
                                weekStartDate = weekStartDate,
                            ).withCourses(homeSchedule)
                        }
                        val selectedDay = weekAgenda.days.firstOrNull { it.date == date }
                            ?: weekAgenda.days.first()
                        AgendaSelectedDayContent(
                            day = selectedDay,
                            onOpenHomework = onOpenHomework,
                            onOpenExams = onOpenExams,
                            onOpenPhyVlab = onOpenPhyVlab,
                            canNavigate = {
                                !dayTransition.isRunning &&
                                    dayTransition.currentState == dayTransition.targetState &&
                                    dayTransition.targetState == (weekSlot to date) &&
                                    selectedDateFor(weekSlot) == date &&
                                    selectedSlot == weekSlot &&
                                    !pagerState.isScrollInProgress &&
                                    scheduleSwipeTargetPage == null
                            },
                        )
                    }
                }
            }
        }
    } else {
        val weekStartDate = selectedSlot.startDate
        HomeAgendaWeekCard(
            homework = homework,
            exams = exams,
            phyVlabEvents = phyVlabEvents,
            weekSlot = selectedSlot,
            selectedDate = selectedDateFor(selectedSlot),
            now = now,
            timeZone = timeZone,
            isLoading = isLoading,
            isWeekResolved = isWeekResolved,
            isWeekPending = weekValueIsPending,
            showWeekButtons = !useFingerWeekPager,
            onOpenHomework = onOpenHomework,
            onOpenExams = onOpenExams,
            onOpenPhyVlab = onOpenPhyVlab,
            previousWeek = adjacentWeekFor(selectedSlot, -1),
            nextWeek = adjacentWeekFor(selectedSlot, 1),
            onSelectWeek = selectWeekFromUser,
            onSelectDate = { date -> selectedDates[selectedSlot.startDate] = date },
            isDateCurrent = { date ->
                selectedSlot.startDate == weekStartDate && selectedDateFor(selectedSlot) == date
            },
            modifier = Modifier
                .fillMaxWidth()
                .courseWeekScrollNavigation(weekScrollAccumulator) { direction ->
                    when (direction) {
                        CourseWeekScrollDirection.PREVIOUS -> adjacentWeekFor(selectedSlot, -1)?.let(selectWeekFromUser)
                        CourseWeekScrollDirection.NEXT -> adjacentWeekFor(selectedSlot, 1)?.let(selectWeekFromUser)
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
    weekSlot: AcademicWeekSlot,
    selectedDate: LocalDate,
    now: LocalDateTime,
    timeZone: TimeZone,
    isLoading: Boolean,
    /** 周数是否已由本学期校历确认；未确认时明确显示「日程加载中」。 */
    isWeekResolved: Boolean = true,
    /** 周数还没着落（未确认且无缓存值）：标签显示「日程加载中」。 */
    isWeekPending: Boolean = false,
    /**
     * 是否显示「上一周 / 下一周」按钮。
     * 移动端只保留手指横滑（与课表一致），按钮只留给宽屏/桌面。
     */
    showWeekButtons: Boolean = true,
    onOpenHomework: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenPhyVlab: () -> Unit,
    previousWeek: AcademicWeekSlot?,
    nextWeek: AcademicWeekSlot?,
    onSelectWeek: (AcademicWeekSlot) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    isDateCurrent: (LocalDate) -> Boolean,
    modifier: Modifier,
) {
    val homeSchedule = LocalHomeSchedule.current
    val today = now.date
    val weekStartDate = weekSlot.startDate
    val weekAgenda = remember(homework, exams, phyVlabEvents, today, now, timeZone, weekStartDate, homeSchedule) {
        buildHomeAgenda(
            homework = homework,
            exams = exams,
            today = today,
            now = now,
            timeZone = timeZone,
            phyVlabEvents = phyVlabEvents,
            weekStartDate = weekStartDate,
        ).withCourses(homeSchedule)
    }
    val selectedDay = weekAgenda.days.firstOrNull { it.date == selectedDate } ?: weekAgenda.days.first()

    val weekIsPending = isWeekPending || !isWeekResolved
    HomeCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeAgendaCalendarContent(
                weekSlot = weekSlot,
                weekAgenda = weekAgenda,
                today = today,
                homework = homework,
                exams = exams,
                phyVlabEvents = phyVlabEvents,
                isLoading = isLoading,
                isWeekPending = weekIsPending,
                showWeekButtons = showWeekButtons,
                previousWeek = previousWeek,
                nextWeek = nextWeek,
                onSelectWeek = onSelectWeek,
                selectedDate = selectedDay.date,
                onSelectDate = onSelectDate,
                modifier = Modifier.fillMaxWidth(),
            )
            val dayTransition = updateTransition(selectedDay.date, label = "home-agenda-day")
            dayTransition.AnimatedContent(
                transitionSpec = {
                    val direction = if (targetState >= initialState) 1 else -1
                    (
                        slideInHorizontally(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = 420f,
                            ),
                        ) { width -> direction * width / 3 } +
                            fadeIn(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = 420f,
                                ),
                            )
                        ) togetherWith (
                        slideOutHorizontally(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = 420f,
                            ),
                        ) { width -> -direction * width / 3 } +
                            fadeOut(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = 420f,
                                ),
                            )
                        ) using SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ ->
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = 320f,
                                )
                            },
                        )
                },
            ) { date ->
                val day = weekAgenda.days.firstOrNull { it.date == date } ?: selectedDay
                AgendaSelectedDayContent(
                    day, onOpenHomework, onOpenExams, onOpenPhyVlab,
                    canNavigate = {
                        !dayTransition.isRunning &&
                            dayTransition.currentState == dayTransition.targetState &&
                            dayTransition.targetState == date && isDateCurrent(date)
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeAgendaCalendarContent(
    weekSlot: AcademicWeekSlot,
    weekAgenda: HomeAgenda,
    today: LocalDate,
    homework: List<Homework>,
    exams: List<ExamSchedule>,
    phyVlabEvents: List<PhyVlabEvent>,
    isLoading: Boolean,
    isWeekPending: Boolean,
    showWeekButtons: Boolean,
    previousWeek: AcademicWeekSlot?,
    nextWeek: AcademicWeekSlot?,
    onSelectWeek: (AcademicWeekSlot) -> Unit,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        isWeekPending -> "日程加载中"
                        weekSlot.isNonTeachingWeek -> "非教学周"
                        else -> "第 ${weekSlot.teachingWeek} 教学周"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!isWeekPending) {
                    Text(
                        text = when {
                            weekSlot.isNonTeachingWeek -> "校历未安排教学周，仍显示本周日程"
                            phyVlabEvents.isEmpty() -> "作业、考试与课表安排"
                            else -> "作业、考试与课表安排（含物理在线）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!isWeekPending) {
                Column(horizontalAlignment = Alignment.End) {
                    if (showWeekButtons) {
                        HomeWeekNavigationControls(
                            previousWeek = previousWeek,
                            nextWeek = nextWeek,
                            onPrevious = { previousWeek?.let(onSelectWeek) },
                            onNext = { nextWeek?.let(onSelectWeek) },
                        )
                    } else {
                        Text(
                            "左右滑动切周",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isLoading && homework.isEmpty() && exams.isEmpty() && phyVlabEvents.isEmpty()) {
                        Text("同步中", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                    selected = day.date == selectedDate,
                    isToday = day.date == today,
                    onClick = { onSelectDate(day.date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val categoryOrder = listOf("课程", "待提交作业", "已提交作业")
            val categories = weekAgenda.days.flatMap { agendaCategoryCounts(it) }
                .distinctBy { it.label }.sortedBy { categoryOrder.indexOf(it.label) }
            categories.forEach { (label, _, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Canvas(Modifier.size(5.dp)) { drawCircle(color) }
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AgendaSelectedDayContent(
    day: HomeAgendaDay,
    onOpenHomework: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenPhyVlab: () -> Unit,
    canNavigate: () -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "${shortDate(day.date)} · ${weekdayName(day.date)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        AgendaDayDetails(day, onOpenHomework, onOpenExams, onOpenPhyVlab, canNavigate)
    }
}

@Composable
private fun HomeWeekNavigationControls(
    previousWeek: AcademicWeekSlot?,
    nextWeek: AcademicWeekSlot?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeWeekNavigationButton(
            label = "‹",
            contentDescription = previousWeek?.description() ?: "没有更早的教学周",
            enabled = previousWeek != null,
            onClick = onPrevious,
        )
        HomeWeekNavigationButton(
            label = "›",
            contentDescription = nextWeek?.description() ?: "没有更晚的教学周",
            enabled = nextWeek != null,
            onClick = onNext,
        )
    }
}

private fun AcademicWeekSlot.description(): String = teachingWeek?.let { "切换到第${it}教学周" }
    ?: "切换到非教学周"

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

/** 当天截止项全部已做时的格子底色/字色；浅色深绿字、深色浅绿字，保证对比度。绿底比原先调浅。 */
@Composable
private fun doneDayContainerColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF2A603E) else Color(0xFFECF7F0)

/** 已提交作业圆点。浅色比原先绿底更深；深色用更亮的绿，避免落在深底上看不见。 */
@Composable
private fun submittedHomeworkMarkColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFB7E8C8) else Color(0xFF146C39)

@Composable
private fun doneDayContentColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFA9E2B8) else Color(0xFF0C4A26)

/** 单条已做事项的“截止”二字颜色。 */
@Composable
private fun doneDeadlineLabelColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF7EDB96) else Color(0xFF146C39)

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
    // 当天截止项全部已做 → 整天标绿；有一项没做仍标红。
    val allDone = isHomeAgendaDayFullySubmitted(day)
    val cellColor = when {
        allDone -> doneDayContainerColor()
        hasDeadline -> MaterialTheme.colorScheme.errorContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val cellContentColor = when {
        allDone -> doneDayContentColor()
        hasDeadline -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
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
            val marks = agendaWeekMarks(day)
            val homeworkMarks = buildList {
                if (marks.pendingHomework > 0) {
                    add(AgendaCategoryCount("待提交作业", marks.pendingHomework, MaterialTheme.colorScheme.error))
                }
                if (marks.submittedHomework > 0) {
                    add(AgendaCategoryCount("已提交作业", marks.submittedHomework, submittedHomeworkMarkColor()))
                }
            }
            val courseMarks = if (marks.courses > 0) {
                listOf(AgendaCategoryCount("课程", marks.courses, MaterialTheme.colorScheme.secondary))
            } else {
                emptyList()
            }
            if (homeworkMarks.isEmpty() && courseMarks.isEmpty()) {
                Text("—", style = MaterialTheme.typography.labelSmall)
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    if (homeworkMarks.isNotEmpty()) AgendaMarkLine(homeworkMarks)
                    if (courseMarks.isNotEmpty()) AgendaMarkLine(courseMarks)
                }
            }
        }
    }
}

private data class AgendaCategoryCount(val label: String, val count: Int, val color: Color)

private data class AgendaWeekMarks(
    val pendingHomework: Int,
    val submittedHomework: Int,
    val courses: Int,
)

private fun agendaWeekMarks(day: HomeAgendaDay): AgendaWeekMarks {
    var pending = 0
    var submitted = 0
    day.homeworkDue.forEach { item ->
        if (isHomeworkSubmitted(item)) submitted += 1 else pending += 1
    }
    return AgendaWeekMarks(pending, submitted, day.courses.size)
}

@Composable
private fun AgendaMarkLine(items: List<AgendaCategoryCount>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (label, count, color) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.semantics { contentDescription = "$label $count" },
            ) {
                Canvas(Modifier.size(5.dp)) { drawCircle(color) }
                Text(count.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun agendaCategoryCounts(day: HomeAgendaDay): List<AgendaCategoryCount> {
    val marks = agendaWeekMarks(day)
    return listOf(
        AgendaCategoryCount("课程", marks.courses, MaterialTheme.colorScheme.secondary),
        AgendaCategoryCount("待提交作业", marks.pendingHomework, MaterialTheme.colorScheme.error),
        AgendaCategoryCount("已提交作业", marks.submittedHomework, submittedHomeworkMarkColor()),
    ).filter { it.count > 0 }
}

@Composable
private fun AgendaCourseIcon() {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(24.dp).semantics { contentDescription = "课程" }) {
        val scale = size.width / 24f
        val outline = Path().apply {
            moveTo(12f * scale, 5f * scale)
            cubicTo(9f * scale, 3f * scale, 5f * scale, 3f * scale, 2f * scale, 4f * scale)
            lineTo(2f * scale, 20f * scale)
            cubicTo(5f * scale, 19f * scale, 9f * scale, 19f * scale, 12f * scale, 21f * scale)
            cubicTo(15f * scale, 19f * scale, 19f * scale, 19f * scale, 22f * scale, 20f * scale)
            lineTo(22f * scale, 4f * scale)
            cubicTo(19f * scale, 3f * scale, 15f * scale, 3f * scale, 12f * scale, 5f * scale)
            close()
        }
        drawPath(outline, tint, style = Stroke(1.8f * scale))
        drawLine(tint, Offset(12f * scale, 5f * scale), Offset(12f * scale, 21f * scale), 1.8f * scale)
        for (y in listOf(8f, 11f, 14f)) {
            drawLine(tint, Offset(15f * scale, y * scale), Offset(20f * scale, (y - 1f) * scale), scale)
        }
    }
}

@Composable
private fun AgendaDayDetails(
    day: HomeAgendaDay,
    onOpenHomework: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenPhyVlab: () -> Unit,
    canNavigate: () -> Boolean,
) {
    val homeSchedule = LocalHomeSchedule.current
    if (day.eventCount == 0) {
        Text("当天没有课程、作业、考试或物理在线安排。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        day.homeworkStarting.forEach { item ->
            AgendaEventRow("开始", item.title, item.courseName, onOpenHomework, canNavigate)
        }
        day.homeworkDue.forEach { item ->
            AgendaEventRow(
                type = "截止",
                title = item.title,
                detail = "${item.courseName} · ${item.endTime}",
                onClick = onOpenHomework,
                canNavigate = canNavigate,
                done = isHomeworkSubmitted(item),
            )
        }
        day.exams.forEach { exam ->
            AgendaEventRow("考试", exam.courseName, exam.examTimeAndPlace, onOpenExams, canNavigate)
        }
        day.phyVlabEvents.forEach { event ->
            AgendaEventRow(
                type = if (event.kind == PhyVlabEventKind.START) "物理开始" else "物理截止",
                title = event.title,
                detail = formatPhyVlabAgendaDate(event),
                onClick = onOpenPhyVlab,
                canNavigate = canNavigate,
                done = event.submitted,
            )
        }
        if (day.courses.isNotEmpty()) {
            Text("当天课表", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            day.courses.forEach { course ->
                val period = course.courseLocationIndex / 8
                val time = team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.SLOT_TIME_RANGES.getOrNull(period).orEmpty()
                AgendaEventRow(
                    type = "课程",
                    title = course.courseName,
                    detail = listOf(time, team.bjtuss.bjtuselfservice.shared.domain.course.displayCoursePlace(course.coursePlace), course.courseTeacher).filter(String::isNotBlank).joinToString(" · "),
                    onClick = homeSchedule.onOpenSchedule,
                    canNavigate = canNavigate,
                )
            }
        }

    }
}

@Composable
private fun AgendaEventRow(
    type: String,
    title: String,
    detail: String,
    onClick: () -> Unit,
    canNavigate: () -> Boolean,
    done: Boolean = false,
) {
    val isDeadline = type.contains("截止")
    Surface(
        onClick = { if (canNavigate()) onClick() },
        enabled = canNavigate(),
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (type == "课程") {
                AgendaCourseIcon()
            } else {
                Text(
                    type,
                    color = when {
                        done -> doneDeadlineLabelColor()
                        isDeadline -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
    HomeCard(modifier = Modifier.fillMaxWidth()) {
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
    isIos: Boolean,
    onDismiss: () -> Unit,
    onMarkRead: () -> Unit,
    onOpen: () -> Unit,
) {
    val changeScrollState = rememberScrollState()
    val visible = changes.filterNot {
        it.kind == DataChangeKind.MODIFIED && it.beforeDetail == it.afterDetail
    }
    AppleSheetOrAlert(
        onDismissRequest = onDismiss,
        title = "${domain.title}变动",
        confirmLabel = "前往页面",
        onConfirm = onOpen,
        // iOS keeps the native X in the sheet header; the Material fallback
        // still needs an explicit secondary close action.
        dismissLabel = if (isIos) null else "关闭",
        needsFullHeight = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isIos) Modifier.fillMaxHeight() else Modifier.heightIn(max = 560.dp))
                .verticalScroll(changeScrollState)
                .desktopTouchScroll(changeScrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "同步后发现 ${visible.size} 项变化",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.84f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column {
                    // One inset group reads like an iOS list section. Individual
                    // floating cards made the same content feel like a desktop
                    // dashboard inside a sheet.
                    visible.forEachIndexed { index, change ->
                        ChangeDetailRow(change)
                        if (index != visible.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 68.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.accessibleAlpha(0.7f),
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = onMarkRead,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Text("标记已读")
            }
            Spacer(Modifier.height(if (isIos) 18.dp else 4.dp))
        }
    }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    change.kind.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (change.kind) {
                        DataChangeKind.ADDED -> MaterialTheme.colorScheme.primary
                        DataChangeKind.MODIFIED -> MaterialTheme.colorScheme.tertiary
                        DataChangeKind.DELETED -> MaterialTheme.colorScheme.error
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    change.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (change.beforeDetail.isNotBlank()) {
                Text(
                    "原：${change.beforeDetail}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (change.afterDetail.isNotBlank()) {
                Text(
                    "现：${change.afterDetail}",
                    style = MaterialTheme.typography.bodyMedium,
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

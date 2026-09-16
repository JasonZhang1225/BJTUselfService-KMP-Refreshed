package team.bjtuss.bjtuselfservice.shared.feature.grade

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import team.bjtuss.bjtuselfservice.shared.LocalReduceMotion
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.WindowClass
import team.bjtuss.bjtuselfservice.shared.currentPlatform
import kotlin.math.PI
import team.bjtuss.bjtuselfservice.shared.accessibleAlpha
import team.bjtuss.bjtuselfservice.shared.data.grade.formatGradeDetailForDisplay
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppErrorBanner
import team.bjtuss.bjtuselfservice.shared.usesLegacySmartTransportFor
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusFailure
import team.bjtuss.bjtuselfservice.shared.data.home.HomeChangeFeedRepository
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancySyncFailure
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleContentSource
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleContentSource
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkContentSource
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkDetailWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareContentSource
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.OtherFunctionScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.SCHOOL_CALENDAR_ARTICLE_URL
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.SchoolCalendarArticleWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.ReportCardDownloadWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomBuildingState
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomBuildingWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomUiState
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyBuildingWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyQueryState
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.settings.AppUpdateResultDialog
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUiState
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxFailure
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxTopBarActions
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxComposeScreen
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabDetailWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabContentSource
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSyncFailure
import team.bjtuss.bjtuselfservice.shared.feature.shell.SessionRefreshCoordinator
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.home.homeIdleStatusText
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItem
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItemState
import team.bjtuss.bjtuselfservice.shared.feature.home.homeSyncDialogTitle
import team.bjtuss.bjtuselfservice.shared.webview.openExternalUrl
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommand
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommandBus
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.domain.grade.CourseType
import team.bjtuss.bjtuselfservice.shared.domain.grade.Grade
import team.bjtuss.bjtuselfservice.shared.domain.grade.GradeInfoResult
import team.bjtuss.bjtuselfservice.shared.domain.grade.GradeSortOrder
import team.bjtuss.bjtuselfservice.shared.domain.grade.displayCourseName
import team.bjtuss.bjtuselfservice.shared.domain.grade.displayName
import team.bjtuss.bjtuselfservice.shared.domain.grade.scoreForSorting
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeKind
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeDomain
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeRecord

/**
 * 教室页顶栏空闲态文案。
 * 未选楼 / 尚未请求（Idle）返回 null：一级楼列表没有「同步」语义，不显示「未同步 ✓」。
 * Loading 时 isRefreshing 会盖成「同步中」；此处仍给「未同步」作兜底。
 */
private fun classroomIdleStatusText(state: ClassroomUiState): String? = when (state.buildingState) {
    is ClassroomBuildingState.Failed -> "同步失败"
    is ClassroomBuildingState.Loaded -> "已同步"
    ClassroomBuildingState.Loading -> "未同步"
    ClassroomBuildingState.Idle -> null
}

private val partialSyncFailureStatusTexts = setOf("部分同步失败", "同步失败")

private fun buildHomeSyncItems(
    isLoggingIn: Boolean,
    homeBusy: Boolean,
    homeFailed: Boolean,
    homeReady: Boolean,
    gradeBusy: Boolean,
    gradeFailed: Boolean,
    gradeReady: Boolean,
    homeworkBusy: Boolean,
    homeworkFailed: Boolean,
    homeworkReady: Boolean,
    examBusy: Boolean,
    examFailed: Boolean,
    examReady: Boolean,
    courseBusy: Boolean,
    courseFailed: Boolean,
    courseReady: Boolean,
    phyVlabEnabled: Boolean,
    phyVlabBusy: Boolean,
    phyVlabFailed: Boolean,
    phyVlabReady: Boolean,
): List<HomeSyncItem> = buildList {
    add(
        homeSyncItem(
            title = "统一身份认证",
            busy = isLoggingIn,
            failed = false,
            ready = !isLoggingIn,
            busyDetail = "正在检查登录状态",
        ),
    )
    add(
        homeSyncItem(
            title = "首页账户状态",
            busy = homeBusy,
            failed = homeFailed,
            ready = homeReady,
            busyDetail = "正在读取邮箱与校园账户状态",
        ),
    )
    add(
        homeSyncItem(
            title = "成绩",
            busy = gradeBusy,
            failed = gradeFailed,
            ready = gradeReady,
            busyDetail = "正在同步成绩",
        ),
    )
    add(
        homeSyncItem(
            title = "课程表与校历周数",
            busy = courseBusy,
            failed = courseFailed,
            ready = courseReady,
            busyDetail = "正在同步课表并校准教学周",
        ),
    )
    add(
        homeSyncItem(
            title = "作业",
            busy = homeworkBusy,
            failed = homeworkFailed,
            ready = homeworkReady,
            busyDetail = "正在同步作业",
        ),
    )
    add(
        homeSyncItem(
            title = "考试安排",
            busy = examBusy,
            failed = examFailed,
            ready = examReady,
            busyDetail = "正在同步考试安排",
        ),
    )
    if (phyVlabEnabled) {
        add(
            homeSyncItem(
                title = "物理在线",
                busy = phyVlabBusy,
                failed = phyVlabFailed,
                ready = phyVlabReady,
                busyDetail = "正在同步物理在线安排",
            ),
        )
    }
}

private fun homeSyncItem(
    title: String,
    busy: Boolean,
    failed: Boolean,
    ready: Boolean,
    busyDetail: String,
): HomeSyncItem {
    val state = when {
        failed -> HomeSyncItemState.FAILED
        busy -> HomeSyncItemState.SYNCING
        ready -> HomeSyncItemState.SUCCESS
        else -> HomeSyncItemState.WAITING
    }
    val detail = when (state) {
        HomeSyncItemState.FAILED -> "本轮同步失败"
        HomeSyncItemState.SYNCING -> busyDetail
        HomeSyncItemState.SUCCESS -> "已完成"
        HomeSyncItemState.WAITING -> "等待同步"
    }
    return HomeSyncItem(title = title, detail = detail, state = state)
}

private sealed interface AppRoute : NavKey

private enum class AppSection(
    val title: String,
    val moreTitle: String = title,
) : AppRoute {
    HOME("首页"),
    GRADES("成绩"),
    SCHEDULE("课程表"),
    EXAMS("考试安排"),
    HOMEWORK("作业"),
    COURSEWARE("课件下载"),
    CLASSROOM_OCCUPANCY("教室占用查询"),
    CLASSROOMS("教室人数估计"),
    MAILBOX("邮箱"),
    PHYVLAB("物理在线", "物理在线（仅能在校园网下访问）"),
    CALENDAR("校历"),
    REPORT_CARD_DOWNLOAD("成绩单下载"),
    SETTINGS("设置"),
    MORE("更多"),
}

/** 紧凑布局底部导航直接暴露的一级入口；其余入口收进“更多”页。 */
private fun bottomNavSections(showPhyVlab: Boolean): List<AppSection> = buildList {
    add(AppSection.HOME)
    add(AppSection.SCHEDULE)
    add(AppSection.GRADES)
    add(AppSection.HOMEWORK)
    if (showPhyVlab) add(AppSection.PHYVLAB)
    add(AppSection.MORE)
}

/** 在底部导航中归属“更多”高亮的入口。 */
private val MoreGroupSections = setOf(
    AppSection.EXAMS,
    AppSection.COURSEWARE,
    AppSection.CLASSROOMS,
    AppSection.CLASSROOM_OCCUPANCY,
    AppSection.MAILBOX,
    AppSection.CALENDAR,
    AppSection.REPORT_CARD_DOWNLOAD,
    AppSection.SETTINGS,
    AppSection.MORE,
)

private const val LOGIN_SYNC_RETRY_DELAY_MILLIS = 700L
/** 教室详情的第三级路由：独立于一级/二级 section。 */
private data object ClassroomDetailRoute : AppRoute
const val CLASSROOM_DETAIL_ROUTE_ID = "CLASSROOM_DETAIL"

/** 教室占用的第三级路由：独立于一级/二级 section，仿教室详情。 */
private data object ClassroomOccupancyDetailRoute : AppRoute
const val CLASSROOM_OCCUPANCY_DETAIL_ROUTE_ID = "CLASSROOM_OCCUPANCY_DETAIL"

/** 作业详情的二级路由：独立于一级 section，仿教室详情。 */
private data object HomeworkDetailRoute : AppRoute
const val HOMEWORK_DETAIL_ROUTE_ID = "HOMEWORK_DETAIL"

/** 物理在线作业详情的二级路由：紧凑端仿作业详情，宽屏仍使用底部弹窗。 */
private data object PhyVlabDetailRoute : AppRoute
const val PHYVLAB_DETAIL_ROUTE_ID = "PHYVLAB_DETAIL"

/** 邮箱详情的二级路由：紧凑端使用平台原生 push，宽屏留在三栏阅读区。 */
private data object MailboxDetailRoute : AppRoute
const val MAILBOX_DETAIL_ROUTE_ID = "MAILBOX_DETAIL"

/** 邮箱写信/回复二级路由：紧凑端使用平台原生 push，宽屏在当前邮箱内容区编辑。 */
private data object MailboxComposeRoute : AppRoute
const val MAILBOX_COMPOSE_ROUTE_ID = "MAILBOX_COMPOSE"

/** Google predictive-back full-screen surface 的 SystemUI 插值。 */
private val androidPredictiveEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatedAppShell(
    session: team.bjtuss.bjtuselfservice.shared.AuthenticatedSession,
    platform: PlatformInfo,
    windowClass: WindowClass,
    appCommandBus: AppCommandBus? = null,
    nativeNavigationEnabled: Boolean = false,
    onOpenNativeRoute: (String) -> Unit = {},
    onOpenExternalUrl: (String) -> Unit = ::openExternalUrl,
    forcedRouteId: String? = null,
    onCloseNativeRoute: () -> Unit = {},
    homeworkFileGatewayOverride: HomeworkFileGateway? = null,
    coursewareDirectoryGatewayOverride: CoursewareDirectoryGateway? = null,
) {
    val profile = session.profile
    val entryLoggingIn = session.entryLoggingIn
    val gradeModel = session.gradeModel
    val courseScheduleModel = session.courseScheduleModel
    val examScheduleModel = session.examScheduleModel
    val homeworkModel = session.homeworkModel
    val coursewareModel = session.coursewareModel
    val otherFunctionModel = session.otherFunctionModel
    val classroomModel = session.classroomModel
    val classroomOccupancyModel = session.classroomOccupancyModel
    val settingsModel = session.settingsModel
    val loginSyncPreferences = session.loginSyncPreferences
    val mailboxModel = session.mailboxModel
    val phyVlabModel = session.phyVlabModel
    val homeModel = session.homeModel
    val homeChangeFeed = session.homeChangeFeed
    val homeworkFileGateway = homeworkFileGatewayOverride ?: session.homeworkFileGateway
    val coursewareDirectoryGateway = coursewareDirectoryGatewayOverride ?: session.coursewareDirectoryGateway
    val systemCalendarGateway = session.systemCalendarGateway
    val onLogout = session.onLogout
    val reauthenticateSession = session.reauthenticateSession
    val gradeState by gradeModel.state.collectAsState()
    val courseState by courseScheduleModel.state.collectAsState()
    val examState by examScheduleModel.state.collectAsState()
    val homeworkState by homeworkModel.state.collectAsState()
    val coursewareState by coursewareModel.state.collectAsState()
    val classroomState by classroomModel.state.collectAsState()
    val classroomOccupancyState by classroomOccupancyModel.state.collectAsState()
    val mailboxState by mailboxModel.state.collectAsState()
    val mailboxMessageLoading = (mailboxState as? MailboxUiState.Ready)?.isMessageLoading == true
    val phyVlabState by phyVlabModel.state.collectAsState()
    val homeState by homeModel.state.collectAsState()
    val settingsState by settingsModel.state.collectAsState()
    val phyVlabEnabled = settingsState.preferences.isPhyVlabEnabled
    val compactBottomNavSections = remember(phyVlabEnabled) {
        bottomNavSections(phyVlabEnabled)
    }
    val homeChanges by homeChangeFeed.records.collectAsState()
    var sessionRecoveryInProgress by remember(session) { mutableStateOf(false) }
    val homeSyncItems = buildHomeSyncItems(
        isLoggingIn = entryLoggingIn,
        homeBusy = homeState.isRefreshing,
        homeFailed = homeState.failure != null,
        homeReady = homeState.status != null,
        gradeBusy = gradeState.isLoading || gradeState.isRefreshing,
        gradeFailed = gradeState.failure != null,
        gradeReady = gradeState.source != null,
        homeworkBusy = homeworkState.isLoading || homeworkState.isRefreshing,
        homeworkFailed = homeworkState.failure != null,
        homeworkReady = homeworkState.source != null,
        examBusy = examState.isLoading || examState.isRefreshing,
        examFailed = examState.failure != null,
        examReady = examState.source != null,
        courseBusy = courseState.isLoading || courseState.isRefreshing || courseState.isCalendarLoading,
        courseFailed = courseState.failure != null,
        courseReady = courseState.source != null,
        phyVlabEnabled = phyVlabEnabled,
        phyVlabBusy = phyVlabEnabled && phyVlabState.isLoading,
        phyVlabFailed = phyVlabEnabled && (phyVlabState.failure != null || phyVlabState.casLoginRequired),
        phyVlabReady = phyVlabEnabled &&
            (phyVlabState.courses.isNotEmpty() || phyVlabState.agendaEvents.isNotEmpty()),
    )
    val homeSyncFailureItems = homeSyncItems
        .filter { it.state == HomeSyncItemState.FAILED }
        .map(HomeSyncItem::title)
    val homeSyncInProgress = entryLoggingIn || sessionRecoveryInProgress || homeSyncItems.any {
        it.state == HomeSyncItemState.SYNCING
    }
    var homeSyncDialogVisible by remember { mutableStateOf(false) }
    var partialSyncFailureDialogItems by remember { mutableStateOf<List<String>?>(null) }
    // 挂 session：原生二级页重建 Compose 时仍记住本登录态是否关过提示。
    // 同时必须有本地 mutableState，否则只写 session 字段不会触发重组，Banner 点了不关。
    var legacyWarningDismissed by remember(session) {
        mutableStateOf(session.legacyHttpWarningDismissed)
    }
    val dismissLegacyWarning: () -> Unit = {
        session.legacyHttpWarningDismissed = true
        legacyWarningDismissed = true
    }
    var classroomIntroBannerDismissed by remember(session) {
        mutableStateOf(session.classroomIntroBannerDismissed)
    }
    var showCourseCalendarExport by remember(session) { mutableStateOf(false) }
    val dismissClassroomIntroBanner: () -> Unit = {
        session.classroomIntroBannerDismissed = true
        classroomIntroBannerDismissed = true
    }
    val scope = rememberCoroutineScope()
    // 紧凑端底栏挂在 NavDisplay 外层（与内容解耦）：一级 tab 切换时底栏实例保持存活，
    // 避免整页销毁把 NavigationBarItem 的按压水波纹掐断。
    // 内容区预留底栏高度：Material3 NavigationBar 80.dp + navigationBars 安全区。
    val compactBottomBarOverlayPadding = if (windowClass != WindowClass.Expanded) {
        80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    } else {
        0.dp
    }
    // 宽屏（平板横屏/全屏、macOS）：二级页留在壳内，侧栏固定、右侧换内容，对齐桌面分屏。
    // 仅紧凑/中等窗口才走原生二级 Activity/UIViewController（手机式全屏 push）。
    val useNativeSecondaryRoutes =
        nativeNavigationEnabled && windowClass != WindowClass.Expanded

    // 未启用原生二级路由时，邮件详情留在邮箱目的地内；
    // 启用原生二级路由时，邮箱列表、邮件详情和写信/回复均由平台页面承载。
    // 邮箱页的唯一返回入口固定在左上角，详情正文不再另放「返回邮件列表」按钮。
    val mailboxReadyState = mailboxState as? MailboxUiState.Ready
    val mailboxInlineDetail = mailboxBackTarget(
        useNativeSecondaryRoutes = useNativeSecondaryRoutes,
        hasSelectedMessage = mailboxReadyState?.selectedMessage != null,
        isMessageLoading = mailboxReadyState?.isMessageLoading == true,
    ) == MailboxBackTarget.LIST
    val mailboxInlineCompose = !useNativeSecondaryRoutes &&
        (mailboxReadyState?.compose?.isLoading == true ||
            mailboxReadyState?.compose?.draft != null ||
            mailboxReadyState?.compose?.failure != null)

    // Navigation 3：应用直接拥有返回栈。一级 tab 总是以 HOME 为根，二/三级页继续压栈；
    // NavDisplay 负责 Android predictive back 与 iOS start-edge back 的连续手势进度。
    val initialRoute = remember(forcedRouteId) {
        forcedRouteId?.toAppRoute() ?: AppSection.HOME
    }
    val backStack = remember(forcedRouteId) { mutableStateListOf<AppRoute>(initialRoute) }
    val currentRoute = backStack.last()
    val section: AppSection = when (currentRoute) {
        ClassroomDetailRoute -> AppSection.CLASSROOMS
        ClassroomOccupancyDetailRoute -> AppSection.CLASSROOM_OCCUPANCY
        HomeworkDetailRoute -> AppSection.HOMEWORK
        PhyVlabDetailRoute -> AppSection.PHYVLAB
        MailboxDetailRoute -> AppSection.MAILBOX
        MailboxComposeRoute -> AppSection.MAILBOX
        is AppSection -> currentRoute
    }
    val popBackStack: () -> Unit = if (forcedRouteId != null) {
        onCloseNativeRoute
    } else {
        { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    }
    val mailboxBack: () -> Unit = when {
        mailboxInlineCompose -> {
            { scope.launch { mailboxModel.cancelCompose() } }
        }
        mailboxInlineDetail -> mailboxModel::clearSelectedMessage
        else -> popBackStack
    }
    // 宽屏 Android/iPad 邮箱详情留在邮箱一级页内，没有额外 route 可供系统返回弹出；
    // 系统侧滑/返回键必须和左上角返回共用同一目标，否则会直接弹回「更多」根目录。
    val handleBack: () -> Unit = {
        if (
            shouldHandleInlineMailboxBack(
                currentRouteIsMailbox = currentRoute == AppSection.MAILBOX,
                mailboxInlineDetail = mailboxInlineDetail,
                mailboxInlineCompose = mailboxInlineCompose,
            )
        ) {
            mailboxBack()
        } else {
            popBackStack()
        }
    }
    val startMailboxComposeFromTopBar: () -> Unit = {
        if (useNativeSecondaryRoutes) {
            onOpenNativeRoute(MAILBOX_COMPOSE_ROUTE_ID)
        }
        scope.launch { mailboxModel.startCompose() }
    }
    fun navigateToSection(target: AppSection) {
        if (target == AppSection.PHYVLAB && !phyVlabEnabled) return
        if (backStack.lastOrNull() != target) {
            // 先 yield 一帧：让 NavigationBarItem 的 press/ripple 先上屏，
            // 再替换 destination，避免首次点 tab 时内容重组抢掉按压反馈。
            scope.launch {
                yield()
                if (backStack.lastOrNull() == target) return@launch
                if (shouldOpenNativeSectionRoute(target.name, useNativeSecondaryRoutes)) {
                    onOpenNativeRoute(target.name)
                } else if (
                    target in MoreGroupSections &&
                        target != AppSection.MORE
                ) {
                    // 「更多」子页：固定为 [更多, 子页]，返回一定回到更多目录。
                    backStack.clear()
                    backStack.add(AppSection.MORE)
                    backStack.add(target)
                } else {
                    // 一级底栏页（含「更多」根目录）：单层替换。
                    // 旧 popUpTo(HOME) 会形成 [HOME, 课表/作业…]，边缘侧滑/系统返回会误回首页。
                    backStack.clear()
                    backStack.add(target)
                }
            }
        }
    }
    val refresh: () -> Unit = {
        scope.launch {
            // 静默自动登录期间会话未就绪，忽略刷新；登录完成后各模块会按自动同步设置初始化。
            if (entryLoggingIn) return@launch
            if (currentRoute == PhyVlabDetailRoute) {
                // 物理在线详情模型自身已在幂等读取失败时恢复一次 CAS；不要在这里重复
                // 套一层全量刷新，否则会清空当前作业选择并重复发起验证码。
                phyVlabModel.loadSelectedActivityDetail(force = true)
                return@launch
            }
            val sessionRefresh = SessionRefreshCoordinator(
                reauthenticate = reauthenticateSession,
                onRecoveryStateChanged = { sessionRecoveryInProgress = it },
            )
            suspend fun refreshModule(
                operation: suspend () -> Unit,
                sessionExpired: () -> Boolean,
            ) {
                sessionRefresh.run(operation, sessionExpired)
            }
            when (section) {
                AppSection.HOME -> coroutineScope {
                    launch {
                        refreshModule(
                            operation = homeModel::refresh,
                            sessionExpired = { homeModel.state.value.failure == HomeStatusFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        refreshModule(
                            operation = homeworkModel::refresh,
                            sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        refreshModule(
                            operation = examScheduleModel::refresh,
                            sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        refreshModule(
                            operation = courseScheduleModel::refresh,
                            sessionExpired = { courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    // 这是用户明确点下首页刷新/失败胶囊后的主动重试；功能关闭时不访问物理在线。
                    if (phyVlabEnabled) {
                        launch { phyVlabModel.refresh() }
                    }
                    launch {
                        refreshModule(
                            operation = gradeModel::refresh,
                            sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
                        )
                        if (gradeModel.state.value.courseTypesByCode == null) {
                            gradeModel.ensureProgramCourseTypes()
                        }
                    }
                }
                AppSection.GRADES -> refreshModule(
                    operation = gradeModel::refresh,
                    sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
                )
                AppSection.SCHEDULE -> {
                    refreshModule(
                        operation = courseScheduleModel::refresh,
                        sessionExpired = { courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED },
                    )
                    if (gradeModel.state.value.courseTypesByCode == null) {
                        gradeModel.ensureProgramCourseTypes()
                    }
                }
                AppSection.EXAMS -> refreshModule(
                    operation = examScheduleModel::refresh,
                    sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                )
                AppSection.HOMEWORK -> refreshModule(
                    operation = homeworkModel::refresh,
                    sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                )
                AppSection.COURSEWARE -> refreshModule(
                    operation = coursewareModel::refresh,
                    sessionExpired = { coursewareModel.state.value.failure == CoursewareSyncFailure.SESSION_EXPIRED },
                )
                AppSection.CLASSROOMS -> classroomModel.refresh()
                AppSection.CLASSROOM_OCCUPANCY -> refreshModule(
                    operation = classroomOccupancyModel::refresh,
                    sessionExpired = {
                        (classroomOccupancyModel.state.value.queryState as? ClassroomOccupancyQueryState.Failed)
                            ?.reason == ClassroomOccupancySyncFailure.SESSION_EXPIRED
                    },
                )
                AppSection.MAILBOX -> refreshModule(
                    operation = mailboxModel::refresh,
                    sessionExpired = {
                        when (val current = mailboxModel.state.value) {
                            MailboxUiState.SessionUnavailable -> true
                            is MailboxUiState.Ready -> current.failure == MailboxFailure.SESSION_EXPIRED
                            else -> false
                        }
                    },
                )
                AppSection.PHYVLAB -> if (phyVlabEnabled) phyVlabModel.refresh()
                AppSection.CALENDAR -> Unit
                AppSection.REPORT_CARD_DOWNLOAD -> Unit
                AppSection.SETTINGS -> Unit
                AppSection.MORE -> Unit
            }
        }
    }
    val retryPhyVlabDetail: () -> Unit = {
        scope.launch {
            phyVlabModel.loadSelectedActivityDetail(force = true)
        }
    }

    // 回到前台时只对已经明确处于 SESSION_EXPIRED 的当前页面自动重试一次；
    // 失败后保留当前错误页，不弹回登录页，把再次恢复的主动权交给右上角刷新。
    val latestRoute = rememberUpdatedState(currentRoute)
    LaunchedEffect(session) {
        suspend fun retryIfExpired(
            operation: suspend () -> Unit,
            sessionExpired: () -> Boolean,
        ) {
            if (sessionExpired()) operation()
        }

        session.appResumeGeneration.collect { generation ->
            if (!session.claimAppResume(generation) || entryLoggingIn) return@collect
            when (latestRoute.value) {
                PhyVlabDetailRoute -> retryIfExpired(
                    operation = { phyVlabModel.loadSelectedActivityDetail(force = true) },
                    sessionExpired = {
                        phyVlabModel.state.value.detailFailure == PhyVlabSyncFailure.SESSION_EXPIRED
                    },
                )
                AppSection.HOME -> coroutineScope {
                    launch {
                        retryIfExpired(
                            operation = homeModel::refresh,
                            sessionExpired = { homeModel.state.value.failure == HomeStatusFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        retryIfExpired(
                            operation = homeworkModel::refresh,
                            sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        retryIfExpired(
                            operation = examScheduleModel::refresh,
                            sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        retryIfExpired(
                            operation = courseScheduleModel::refresh,
                            sessionExpired = { courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    if (phyVlabEnabled) {
                        launch {
                            retryIfExpired(
                                operation = phyVlabModel::refresh,
                                sessionExpired = {
                                    val current = phyVlabModel.state.value
                                    current.failure == PhyVlabSyncFailure.SESSION_EXPIRED || current.casLoginRequired
                                },
                            )
                        }
                    }
                    launch {
                        retryIfExpired(
                            operation = gradeModel::refresh,
                            sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
                        )
                    }
                }
                AppSection.GRADES -> retryIfExpired(
                    operation = gradeModel::refresh,
                    sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
                )
                AppSection.SCHEDULE -> retryIfExpired(
                    operation = courseScheduleModel::refresh,
                    sessionExpired = { courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED },
                )
                AppSection.EXAMS -> retryIfExpired(
                    operation = examScheduleModel::refresh,
                    sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                )
                AppSection.HOMEWORK -> retryIfExpired(
                    operation = homeworkModel::refresh,
                    sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                )
                AppSection.COURSEWARE -> retryIfExpired(
                    operation = coursewareModel::refresh,
                    sessionExpired = { coursewareModel.state.value.failure == CoursewareSyncFailure.SESSION_EXPIRED },
                )
                AppSection.CLASSROOMS -> Unit
                AppSection.CLASSROOM_OCCUPANCY -> retryIfExpired(
                    operation = classroomOccupancyModel::refresh,
                    sessionExpired = {
                        (classroomOccupancyModel.state.value.queryState as? ClassroomOccupancyQueryState.Failed)
                            ?.reason == ClassroomOccupancySyncFailure.SESSION_EXPIRED
                    },
                )
                AppSection.MAILBOX -> retryIfExpired(
                    operation = mailboxModel::refresh,
                    sessionExpired = {
                        when (val current = mailboxModel.state.value) {
                            MailboxUiState.SessionUnavailable -> true
                            is MailboxUiState.Ready -> current.failure == MailboxFailure.SESSION_EXPIRED
                            else -> false
                        }
                    },
                )
                AppSection.PHYVLAB -> if (phyVlabEnabled) {
                    retryIfExpired(
                        operation = phyVlabModel::refresh,
                        sessionExpired = {
                            val current = phyVlabModel.state.value
                            current.failure == PhyVlabSyncFailure.SESSION_EXPIRED || current.casLoginRequired
                        },
                    )
                }
                AppSection.CALENDAR,
                AppSection.REPORT_CARD_DOWNLOAD,
                AppSection.SETTINGS,
                AppSection.MORE,
                -> Unit
                else -> Unit
            }
        }
    }

    LaunchedEffect(appCommandBus) {
        appCommandBus?.commands?.collect { command ->
            when (command) {
                AppCommand.NAVIGATE_HOME -> navigateToSection(AppSection.HOME)
                AppCommand.NAVIGATE_GRADES -> navigateToSection(AppSection.GRADES)
                AppCommand.NAVIGATE_SCHEDULE -> navigateToSection(AppSection.SCHEDULE)
                AppCommand.NAVIGATE_EXAMS -> navigateToSection(AppSection.EXAMS)
                AppCommand.NAVIGATE_HOMEWORK -> navigateToSection(AppSection.HOMEWORK)
                AppCommand.NAVIGATE_COURSEWARE -> navigateToSection(AppSection.COURSEWARE)
                AppCommand.NAVIGATE_CLASSROOMS -> navigateToSection(AppSection.CLASSROOMS)
                AppCommand.NAVIGATE_CLASSROOM_OCCUPANCY ->
                    navigateToSection(AppSection.CLASSROOM_OCCUPANCY)
                AppCommand.NAVIGATE_MAILBOX -> navigateToSection(AppSection.MAILBOX)
                AppCommand.NAVIGATE_SETTINGS -> navigateToSection(AppSection.SETTINGS)
                AppCommand.REFRESH_CURRENT -> refresh()
            }
        }
    }

    // 登录未完成（静默自动登录中）不触发任何网络自动同步。
    // 各 Workspace 只会 initialize(refreshFromNetwork=false) 灌缓存；真正的自动同步只在这里启动。
    LaunchedEffect(gradeModel, entryLoggingIn) {
        if (entryLoggingIn) return@LaunchedEffect
        gradeModel.initialize(loginSyncPreferences.autoSyncGrades)
        if (loginSyncPreferences.autoSyncGrades && gradeModel.state.value.failure != null) {
            delay(LOGIN_SYNC_RETRY_DELAY_MILLIS)
            gradeModel.refresh()
        }
        if (gradeModel.state.value.courseTypesByCode == null) {
            gradeModel.ensureProgramCourseTypes()
        }
    }
    LaunchedEffect(
        homeworkModel,
        examScheduleModel,
        courseScheduleModel,
        entryLoggingIn,
    ) {
        if (entryLoggingIn) return@LaunchedEffect
        coroutineScope {
            launch {
                // 作业自动同步的失败重试在 ScreenModel 内（最多 3 次），与课表一致。
                homeworkModel.initialize(loginSyncPreferences.autoSyncHomework)
            }
            launch {
                examScheduleModel.initialize(loginSyncPreferences.autoSyncExams)
                if (loginSyncPreferences.autoSyncExams && examScheduleModel.state.value.failure != null) {
                    delay(LOGIN_SYNC_RETRY_DELAY_MILLIS)
                    examScheduleModel.refresh()
                }
            }
            launch {
                // 课表：登录成功后才网络同步；失败重试在 ScreenModel 内（最多 3 次）。
                // 先灌入缓存，再加载校历校准当前周，最后才允许网络快照进入 UI；
                // 否则 getTimeList/room_view 短暂返回第 1 周时会覆盖缓存的第 26 周。
                courseScheduleModel.initialize(refreshFromNetwork = false)
                // M12 校历映射独立于“自动同步课表”偏好，但同样必须等登录完成后再取学期。
                courseScheduleModel.ensureCalendarLoaded()
                if (loginSyncPreferences.autoSyncSchedule) {
                    courseScheduleModel.initialize(refreshFromNetwork = true)
                }
            }
        }
    }

    // 物理在线总开关关闭时不读取网络；打开后在当前登录会话中主动同步一次。
    // 这样开关同时控制“是否同步”和“是否显示底栏入口”，不会留下隐藏的后台请求。
    LaunchedEffect(phyVlabModel, phyVlabEnabled, entryLoggingIn) {
        if (entryLoggingIn || !phyVlabEnabled) return@LaunchedEffect
        phyVlabModel.initialize(refreshFromNetwork = false)
        phyVlabModel.refresh()
    }

    // 进入主界面后静默检查一次更新（对齐原安卓启动时自动检测）：
    // 仅发现新版本才弹「前往下载」；失败或无更新不打扰用户。
    // settingsModel 与 login 同生命周期、按 studentId remember，每次登录各弹一次。
    LaunchedEffect(settingsModel, entryLoggingIn) {
        if (entryLoggingIn) return@LaunchedEffect
        settingsModel.checkForUpdate(silentOnMiss = true)
    }

    // 检查结果弹窗放在整个壳内容之后渲染：发现新版本时无论当前在哪个页面都能看到
    // 「前往下载」，不依赖用户停留在设置页（设置页内按钮触发的结果也走同一弹窗）。
    AppUpdateResultDialog(settingsState.updateCheck, settingsModel::dismissUpdateCheck)
    partialSyncFailureDialogItems?.let { items ->
        PartialSyncFailureDialog(
            failedItems = items,
            onRetry = {
                partialSyncFailureDialogItems = null
                refresh()
            },
            onDismiss = { partialSyncFailureDialogItems = null },
        )
    }
    if (homeSyncDialogVisible) {
        HomeSyncDetailsDialog(
            title = homeSyncDialogTitle(
                isLoggingIn = entryLoggingIn,
                isSyncing = homeSyncInProgress,
                hasFailures = homeSyncFailureItems.isNotEmpty(),
            ),
            items = homeSyncItems,
            canRetry = !entryLoggingIn && homeSyncFailureItems.isNotEmpty() && !homeSyncInProgress,
            onRetry = {
                homeSyncDialogVisible = false
                refresh()
            },
            onDismiss = { homeSyncDialogVisible = false },
        )
    }
    gradeState.pendingChangeNotice?.let { notice ->
        GradeChangeNoticeDialog(
            changes = notice,
            onDismiss = gradeModel::dismissChangeNotice,
            onOpenGrades = {
                gradeModel.dismissChangeNotice()
                navigateToSection(AppSection.GRADES)
            },
        )
    }

    // 页面骨架（顶栏 + 下拉刷新）包在各目的地内部而非 NavHost 外层：
    // 1) 外层容器若随当前页面切换（如“更多”页不可刷新、考试安排页可刷新），NavHost 会在
    //    转场瞬间移出/移入 composition 被销毁重建，表现为点击进出页面没有任何转场动画；
    // 2) 顶栏随页面一起参与转场才是原生观感——整个屏幕一起动，而非标题栏先跳变；
    // 3) 必须铺不透明背景：页面透明时转场中上下两层互相穿透（iOS 上会露出灰色窗口底色），
    //    看起来像层级重叠遮挡。
    @Composable
    fun DestinationPage(
        title: String,
        expanded: Boolean,
        refreshable: Boolean,
        isRefreshing: Boolean,
        showBack: Boolean,
        modifier: Modifier,
        onBack: (() -> Unit)? = null,
        /** 空闲时右上角状态文案（如课表「已同步/未同步」）；登录中/同步中优先覆盖。 */
        idleStatusText: String? = null,
        /** 页面级动作，显示在同步状态胶囊旁（M12 课程表加入日历）。 */
        topBarAction: (@Composable () -> Unit)? = null,
        /** 状态胶囊点击动作；首页用于查看同步详情，失败时不在点击瞬间触发重试。 */
        onStatusClick: (() -> Unit)? = null,
        /** 未显式提供 [onStatusClick] 时，首页聚合同步失败可由此生成失败详情弹窗。 */
        syncFailureItems: List<String> = emptyList(),
        content: @Composable () -> Unit,
    ) {
        // 一级页为底栏预留高度；底栏本身在 NavDisplay 外层，不随 destination 销毁。
        val reserveBottomBarSpace = !expanded && !showBack && compactBottomBarOverlayPadding > 0.dp
        Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(
                    bottom = if (reserveBottomBarSpace) compactBottomBarOverlayPadding else 0.dp,
                ),
            ) {
                // 紧凑/宽屏统一：标题 + 右上同步胶囊。宽屏不再在页内重复「同步××」按钮。
                val failureStatusClick = if (
                    idleStatusText in partialSyncFailureStatusTexts && syncFailureItems.isNotEmpty()
                ) {
                    { partialSyncFailureDialogItems = syncFailureItems }
                } else {
                    null
                }
                CompactAppTopBar(
                    title = title,
                    isRefreshing = isRefreshing || sessionRecoveryInProgress,
                    isLoggingIn = entryLoggingIn,
                    idleStatusText = idleStatusText,
                    action = topBarAction,
                    // 可刷新页：右上角状态胶囊旁放刷新按钮；不再下拉刷新（保平台原生过滚）。
                    onRefresh = if (refreshable) refresh else null,
                    onStatusClick = onStatusClick ?: failureStatusClick,
                    onBack = if (showBack) {
                        onBack ?: popBackStack
                    } else {
                        null
                    },
                )
                // 同步进度条钉在顶栏下方。
                if (isRefreshing || sessionRecoveryInProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                // 列表仅平台原生滚动/过滚，无下拉刷新包裹层。
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    content()
                }
            }
        }
    }

    // Navigation 3 的目的地渲染器：compact/expanded 两套布局共用，
    // entryProvider 只负责把类型安全 route 交给这里，页面业务保持不变。
    @Composable
    fun SectionDestination(
        route: AppRoute,
        expanded: Boolean,
        modifier: Modifier,
        usesLegacySmartTransport: Boolean,
    ) {
        when (route) {
            AppSection.HOME -> DestinationPage(
                title = AppSection.HOME.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = homeSyncInProgress,
                showBack = false,
                modifier = modifier,
                // 与成绩/课表一致：同步态并入顶栏右上胶囊，勿只留孤图标。
                // 首页聚合邮件/校园卡自身失败才写「同步失败」；作业等切片失败写「部分同步失败」，
                // 避免作业红条把整个首页说成全挂。
                idleStatusText = homeIdleStatusText(
                    homeFailed = homeState.failure != null,
                    homeworkFailed = homeworkState.failure != null,
                    examFailed = examState.failure != null,
                    courseFailed = courseState.failure != null,
                    phyVlabFailed = phyVlabEnabled &&
                        (phyVlabState.failure != null || phyVlabState.casLoginRequired),
                    hasAnySource = homeworkState.source != null ||
                        examState.source != null ||
                        courseState.source != null ||
                        homeState.status != null ||
                        (phyVlabEnabled && (
                            phyVlabState.courses.isNotEmpty() ||
                                phyVlabState.agendaEvents.isNotEmpty()
                            )),
                ),
                onStatusClick = { homeSyncDialogVisible = true },
                syncFailureItems = homeSyncFailureItems,
            ) {
                HomeWorkspace(
                    model = homeModel,
                    platform = platform,
                    expanded = expanded,
                    holdNetwork = entryLoggingIn,
                    homework = homeworkState.homework,
                    exams = examState.exams,
                    phyVlabEvents = if (phyVlabEnabled) phyVlabState.agendaEvents else emptyList(),
                    currentWeek = courseState.currentWeek,
                    now = homeworkState.now,
                    timeZone = homeworkState.timeZone,
                    isAgendaLoading = homeworkState.isLoading || examState.isLoading ||
                        courseState.isLoading || (phyVlabEnabled && phyVlabState.isLoading),
                    isRefreshing = homeSyncInProgress,
                    onRefresh = refresh,
                    onOpenMailbox = { navigateToSection(AppSection.MAILBOX) },
                    onOpenHomework = { navigateToSection(AppSection.HOMEWORK) },
                    onOpenExams = { navigateToSection(AppSection.EXAMS) },
                    onOpenPhyVlab = { navigateToSection(AppSection.PHYVLAB) },
                    changes = homeChanges,
                    onClearAllChanges = { scope.launch { homeChangeFeed.clear() } },
                    onClearChangeDomain = { domain -> scope.launch { homeChangeFeed.clear(domain) } },
                    onOpenChangeDomain = { domain -> navigateToSection(domain.toAppSection()) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.GRADES -> DestinationPage(
                title = AppSection.GRADES.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = gradeState.isRefreshing,
                showBack = false,
                modifier = modifier,
                // 与课表一致：同步态在顶栏右上，banner 内只放成绩摘要与筛选入口。
                idleStatusText = when {
                    gradeState.failure != null -> "同步失败"
                    gradeState.source == GradeContentSource.NETWORK -> "已同步"
                    gradeState.source == GradeContentSource.CACHE -> "已同步"
                    else -> "未同步"
                },
            ) {
                GradeWorkspace(
                    state = gradeState,
                    expanded = expanded,
                    model = gradeModel,
                    onRefresh = refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.SCHEDULE -> DestinationPage(
                title = AppSection.SCHEDULE.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = courseState.isRefreshing,
                showBack = false,
                modifier = modifier,
                // 同步状态放在顶栏右上；有失败横幅时不要仍显示「已同步」。
                idleStatusText = when {
                    courseState.failure != null -> "同步失败"
                    courseState.source == CourseScheduleContentSource.NETWORK -> "已同步"
                    courseState.source == CourseScheduleContentSource.CACHE -> "已同步"
                    else -> "未同步"
                },
                topBarAction = {
                    TopBarCalendarAction(
                        label = if (systemCalendarGateway.isAvailable) "添加到日历" else "导出",
                        onClick = { showCourseCalendarExport = true },
                    )
                },
            ) {
                CourseScheduleWorkspace(
                    state = courseState,
                    courseTypesByCode = gradeState.courseTypesByCode,
                    expanded = expanded,
                    model = courseScheduleModel,
                    fileGateway = homeworkFileGateway,
                    systemCalendarGateway = systemCalendarGateway,
                    showCalendarExportSheet = showCourseCalendarExport,
                    onDismissCalendarExport = { showCourseCalendarExport = false },
                    onRefresh = refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.EXAMS -> DestinationPage(
                title = AppSection.EXAMS.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = examState.isRefreshing,
                showBack = true,
                modifier = modifier,
                // 与成绩/作业一致：同步态顶栏右上，banner 内放类型筛选入口。
                idleStatusText = when {
                    examState.failure != null -> "同步失败"
                    examState.source == ExamScheduleContentSource.NETWORK -> "已同步"
                    examState.source == ExamScheduleContentSource.CACHE -> "已同步"
                    else -> "未同步"
                },
            ) {
                ExamScheduleWorkspace(
                    state = examState,
                    expanded = expanded,
                    model = examScheduleModel,
                    fileGateway = homeworkFileGateway,
                    systemCalendarGateway = systemCalendarGateway,
                    onRefresh = refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.HOMEWORK -> DestinationPage(
                title = AppSection.HOMEWORK.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = homeworkState.isRefreshing,
                showBack = false,
                modifier = modifier,
                // 与课表/成绩一致：同步态在顶栏右上，banner 内只放摘要与筛选入口。
                idleStatusText = when {
                    homeworkState.failure != null -> "同步失败"
                    homeworkState.source == HomeworkContentSource.NETWORK -> "已同步"
                    homeworkState.source == HomeworkContentSource.CACHE -> "已同步"
                    else -> "未同步"
                },
            ) {
                HomeworkWorkspace(
                    state = homeworkState,
                    expanded = expanded,
                    usesLegacySmartTransport = usesLegacySmartTransport,
                    legacyWarningVisible = usesLegacySmartTransportFor(platform.family) && !legacyWarningDismissed,
                    onDismissLegacyWarning = dismissLegacyWarning,
                    model = homeworkModel,
                    fileGateway = homeworkFileGateway,
                    onRefresh = refresh,
                    onOpenDetail = {
                        // 先写完选中再 push（onOpen 里已 selectHomework），避免详情页打开时为空。
                        if (useNativeSecondaryRoutes) {
                            onOpenNativeRoute(HOMEWORK_DETAIL_ROUTE_ID)
                        } else if (backStack.lastOrNull() != HomeworkDetailRoute) {
                            backStack.add(HomeworkDetailRoute)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.COURSEWARE -> DestinationPage(
                title = AppSection.COURSEWARE.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = coursewareState.isRefreshing,
                showBack = true,
                modifier = modifier,
                // 与成绩/作业一致：右上角「已同步」+ sync 同一胶囊，勿只留孤图标。
                idleStatusText = when {
                    coursewareState.failure != null -> "同步失败"
                    coursewareState.source == CoursewareContentSource.NETWORK -> "已同步"
                    coursewareState.source == CoursewareContentSource.CACHE -> "已同步"
                    else -> "未同步"
                },
            ) {
                CoursewareWorkspace(
                    state = coursewareState,
                    expanded = expanded,
                    usesLegacySmartTransport = usesLegacySmartTransport,
                    legacyWarningVisible = usesLegacySmartTransportFor(platform.family) && !legacyWarningDismissed,
                    onDismissLegacyWarning = dismissLegacyWarning,
                    model = coursewareModel,
                    fileGateway = homeworkFileGateway,
                    directoryGateway = coursewareDirectoryGateway,
                    onRefresh = refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.CALENDAR -> DestinationPage(
                title = AppSection.CALENDAR.title,
                expanded = expanded,
                refreshable = false,
                isRefreshing = false,
                showBack = true,
                modifier = modifier,
            ) {
                SchoolCalendarArticleWorkspace(
                    expanded = expanded,
                    onOpenArticle = { onOpenExternalUrl(SCHOOL_CALENDAR_ARTICLE_URL) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.REPORT_CARD_DOWNLOAD -> DestinationPage(
                title = AppSection.REPORT_CARD_DOWNLOAD.title,
                expanded = expanded,
                refreshable = false,
                isRefreshing = false,
                showBack = true,
                modifier = modifier,
            ) {
                ReportCardDownloadWorkspace(
                    model = otherFunctionModel,
                    expanded = expanded,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.CLASSROOMS -> DestinationPage(
                title = AppSection.CLASSROOMS.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = classroomState.isLoading,
                showBack = true,
                modifier = modifier,
                idleStatusText = classroomIdleStatusText(classroomState),
            ) {
                ClassroomWorkspace(
                    model = classroomModel,
                    expanded = expanded,
                    introBannerVisible = !classroomIntroBannerDismissed,
                    onDismissIntroBanner = dismissClassroomIntroBanner,
                    onOpenBuilding = {
                        // 先写完选中再 push，避免详情页打开时 selectedBuilding 仍为空。
                        if (useNativeSecondaryRoutes) {
                            onOpenNativeRoute(CLASSROOM_DETAIL_ROUTE_ID)
                        } else if (backStack.lastOrNull() != ClassroomDetailRoute) {
                            backStack.add(ClassroomDetailRoute)
                        }
                    },
                    onRefresh = refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.CLASSROOM_OCCUPANCY -> DestinationPage(
                title = AppSection.CLASSROOM_OCCUPANCY.title,
                expanded = expanded,
                refreshable = false,
                isRefreshing = false,
                showBack = true,
                modifier = modifier,
            ) {
                ClassroomOccupancyWorkspace(
                    model = classroomOccupancyModel,
                    expanded = expanded,
                    onOpenBuilding = {
                        // 先写完选中再 push，避免详情页打开时 selectedBuilding 仍为空。
                        if (useNativeSecondaryRoutes) {
                            onOpenNativeRoute(CLASSROOM_OCCUPANCY_DETAIL_ROUTE_ID)
                        } else if (backStack.lastOrNull() != ClassroomOccupancyDetailRoute) {
                            backStack.add(ClassroomOccupancyDetailRoute)
                        }
                    },
                    onRefresh = refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ClassroomDetailRoute -> DestinationPage(
                // 二级页标题用教学楼名；顶栏返回即原生层级返回，页内不再放「返回教学楼」。
                title = classroomState.selectedBuilding ?: AppSection.CLASSROOMS.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = classroomState.isLoading,
                showBack = true,
                modifier = modifier,
                idleStatusText = classroomIdleStatusText(classroomState),
            ) {
                ClassroomBuildingWorkspace(
                    model = classroomModel,
                    onRefresh = refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ClassroomOccupancyDetailRoute -> DestinationPage(
                // 二级页标题用教学楼名；顶栏返回即原生层级返回，页内不再放「返回教学楼」。
                title = classroomOccupancyState.selectedBuilding?.name ?: AppSection.CLASSROOM_OCCUPANCY.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = classroomOccupancyState.isLoading,
                showBack = true,
                modifier = modifier,
            ) {
                ClassroomOccupancyBuildingWorkspace(
                    model = classroomOccupancyModel,
                    onRefresh = refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            HomeworkDetailRoute -> DestinationPage(
                // 二级页顶栏固定显示「作业详情」，页内不再重复标题；返回即原生层级返回。
                title = "作业详情",
                expanded = expanded,
                refreshable = false,
                isRefreshing = false,
                showBack = true,
                modifier = modifier,
            ) {
                HomeworkDetailWorkspace(
                    model = homeworkModel,
                    fileGateway = homeworkFileGateway,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.SETTINGS -> DestinationPage(
                title = AppSection.SETTINGS.title,
                expanded = expanded,
                refreshable = false,
                isRefreshing = false,
                showBack = true,
                modifier = modifier,
            ) {
                SettingsWorkspace(
                    model = settingsModel,
                    accountName = "${profile.name} · ${profile.studentId}",
                    platform = platform,
                    expanded = expanded,
                    onLogout = onLogout,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.MAILBOX -> DestinationPage(
                title = AppSection.MAILBOX.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = mailboxState == MailboxUiState.Preparing ||
                    mailboxReadyState?.isListLoading == true,
                showBack = true,
                onBack = mailboxBack,
                // 邮箱右上角是直接可执行的列表刷新，不显示「已同步」状态文案。
                idleStatusText = "刷新",
                topBarAction = mailboxReadyState?.let {
                    {
                        MailboxTopBarActions(
                            onStartCompose = startMailboxComposeFromTopBar,
                        )
                    }
                },
                modifier = modifier,
            ) {
                MailboxWorkspace(
                    model = mailboxModel,
                    expanded = expanded,
                    onRefresh = refresh,
                    onReauthenticate = reauthenticateSession,
                    onOpenNativeDetail = if (useNativeSecondaryRoutes) {
                        { onOpenNativeRoute(MAILBOX_DETAIL_ROUTE_ID) }
                    } else {
                        null
                    },
                    onOpenNativeCompose = if (useNativeSecondaryRoutes) {
                        { onOpenNativeRoute(MAILBOX_COMPOSE_ROUTE_ID) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            MailboxDetailRoute -> DestinationPage(
                title = "邮件详情",
                expanded = expanded,
                refreshable = false,
                isRefreshing = mailboxMessageLoading,
                showBack = true,
                modifier = modifier,
            ) {
                MailboxWorkspace(
                    model = mailboxModel,
                    expanded = false,
                    nativeDetail = true,
                    onRefresh = refresh,
                    onReauthenticate = reauthenticateSession,
                    onOpenNativeCompose = { onOpenNativeRoute(MAILBOX_COMPOSE_ROUTE_ID) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            MailboxComposeRoute -> DestinationPage(
                title = if (mailboxReadyState?.compose?.draft?.isReply == true) "回复邮件" else "写信",
                expanded = expanded,
                refreshable = false,
                isRefreshing = false,
                showBack = true,
                onBack = {
                    scope.launch {
                        mailboxModel.cancelCompose()
                        popBackStack()
                    }
                },
                modifier = modifier,
            ) {
                MailboxComposeScreen(
                    model = mailboxModel,
                    onSent = popBackStack,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.PHYVLAB -> DestinationPage(
                title = AppSection.PHYVLAB.title,
                expanded = expanded,
                refreshable = true,
                isRefreshing = phyVlabState.isLoading,
                // 物理在线只从底栏进入，是与首页、课表、作业平级的一级页面。
                showBack = false,
                modifier = modifier,
                idleStatusText = when {
                    (phyVlabState.failure != null || phyVlabState.casLoginRequired) &&
                        phyVlabState.contentSource == PhyVlabContentSource.CACHE -> "同步失败·正显示缓存"
                    phyVlabState.failure != null || phyVlabState.casLoginRequired -> "同步失败"
                    phyVlabState.contentSource == PhyVlabContentSource.CACHE -> "未同步·缓存"
                    phyVlabState.contentSource == PhyVlabContentSource.NETWORK &&
                        phyVlabState.failure == null -> "已同步"
                    else -> "未同步"
                },
            ) {
                PhyVlabWorkspace(
                    model = phyVlabModel,
                    holdNetwork = entryLoggingIn,
                    fileGateway = homeworkFileGateway,
                    onRefresh = refresh,
                    showDetailSheet = !useNativeSecondaryRoutes,
                    onOpenCourse = { url -> onOpenExternalUrl(url.replace("http://", "https://")) },
                    onOpenActivity = { url -> onOpenExternalUrl(url.replace("http://", "https://")) },
                    onOpenActivityDetail = { activity ->
                        // 先写入选中作业再 push，详情页会基于同一 session model 读取并加载详情。
                        phyVlabModel.showActivityDetails(activity)
                        if (useNativeSecondaryRoutes) {
                            onOpenNativeRoute(PHYVLAB_DETAIL_ROUTE_ID)
                        }
                    },
                    onLogout = onLogout,
                    onRetryDetail = retryPhyVlabDetail,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            PhyVlabDetailRoute -> DestinationPage(
                title = "物理作业详情",
                expanded = expanded,
                refreshable = true,
                isRefreshing = phyVlabState.isDetailLoading,
                showBack = true,
                modifier = modifier,
            ) {
                PhyVlabDetailWorkspace(
                    model = phyVlabModel,
                    fileGateway = homeworkFileGateway,
                    onRetry = retryPhyVlabDetail,
                    onOpenActivity = { url -> onOpenExternalUrl(url.replace("http://", "https://")) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AppSection.MORE -> DestinationPage(
                title = AppSection.MORE.title,
                expanded = expanded,
                refreshable = false,
                isRefreshing = false,
                showBack = false,
                modifier = modifier,
            ) {
                MoreWorkspace(
                    phyVlabEnabled = phyVlabEnabled,
                    onPhyVlabEnabledChange = settingsModel::setPhyVlabEnabled,
                    onOpenSection = { target -> navigateToSection(target) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (forcedRouteId != null) {
        SectionDestination(
            route = currentRoute,
            expanded = false,
            modifier = Modifier.fillMaxSize(),
            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
        )
    } else if (windowClass == WindowClass.Expanded) {
        Row(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AppSidebar(
                profile = profile,
                section = section,
                onSectionSelected = { target -> navigateToSection(target) },
                // 随窗口比例伸缩，避免小窗时侧栏仍占固定 236dp 挤掉内容区。
                modifier = Modifier.weight(0.22f).fillMaxHeight(),
            )
            // 宽屏侧栏布局按 macOS/iPad 的并列工作区处理，不播放手机式 push/pop。
            NavDisplay(
                backStack = backStack,
                onBack = handleBack,
                modifier = Modifier.weight(0.78f).fillMaxHeight(),
                transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                predictivePopTransitionSpec = { _: Int ->
                    EnterTransition.None togetherWith ExitTransition.None
                },
                entryProvider = entryProvider {
                    entry<AppSection> { route ->
                        SectionDestination(
                            route = route,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<ClassroomDetailRoute> {
                        SectionDestination(
                            route = ClassroomDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<ClassroomOccupancyDetailRoute> {
                        SectionDestination(
                            route = ClassroomOccupancyDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<HomeworkDetailRoute> {
                        SectionDestination(
                            route = HomeworkDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<PhyVlabDetailRoute> {
                        SectionDestination(
                            route = PhyVlabDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<MailboxDetailRoute> {
                        SectionDestination(
                            route = MailboxDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<MailboxComposeRoute> {
                        SectionDestination(
                            route = MailboxComposeRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                },
            )
        }
    } else {
        val reduceMotion = LocalReduceMotion.current
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
        val appleSpatialSpec = spring<androidx.compose.ui.unit.IntOffset>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 320f,
        )
        val appleInteractiveSpec = tween<androidx.compose.ui.unit.IntOffset>(
            durationMillis = 350,
            easing = LinearEasing,
        )
        val androidOffsetSpec = tween<androidx.compose.ui.unit.IntOffset>(
            durationMillis = 350,
            easing = androidPredictiveEasing,
        )
        val androidScaleSpec = tween<Float>(
            durationMillis = 350,
            easing = androidPredictiveEasing,
        )

        // 仅五个一级 tab 显示底栏；更多子页与详情路由隐藏。底栏在 NavDisplay 外，tab 切换不重建。
        val showsCompactBottomBar = currentRoute in compactBottomNavSections

        Box(modifier = Modifier.fillMaxSize()) {
            // Android 使用 Navigation 3 的 seekable 场景内核，并按 Google full-screen surface
            // predictive-back 规范让前景 100%→90%、后景 110%→100%，同时保留小幅横向预览。
            // iOS 使用可被 NavDisplay start-edge 手势 seek 的 UINavigationController 空间路径；
            // macOS 的紧凑窗口仍遵守桌面习惯，不播放手机式整页滑动。
            NavDisplay(
                backStack = backStack,
                onBack = handleBack,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    when {
                        nativeNavigationEnabled ->
                            EnterTransition.None togetherWith ExitTransition.None
                        reduceMotion ->
                            fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        platform.family == PlatformFamily.Android ->
                            (slideInHorizontally(
                                initialOffsetX = { direction * it / 12 },
                                animationSpec = androidOffsetSpec,
                            ) + scaleIn(
                                initialScale = 0.96f,
                                animationSpec = androidScaleSpec,
                            ) + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(
                                    targetOffsetX = { -direction * it / 20 },
                                    animationSpec = androidOffsetSpec,
                                ) + scaleOut(
                                    targetScale = 0.90f,
                                    animationSpec = androidScaleSpec,
                                ) + fadeOut(tween(280)))
                        platform.family == PlatformFamily.IOS ->
                            slideInHorizontally(
                                initialOffsetX = { direction * it },
                                animationSpec = appleSpatialSpec,
                            ) togetherWith slideOutHorizontally(
                                targetOffsetX = { -direction * it / 3 },
                                animationSpec = appleSpatialSpec,
                            )
                        else -> EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                popTransitionSpec = {
                    when {
                        nativeNavigationEnabled ->
                            EnterTransition.None togetherWith ExitTransition.None
                        reduceMotion ->
                            fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        platform.family == PlatformFamily.Android ->
                            (slideInHorizontally(
                                initialOffsetX = { -direction * it / 20 },
                                animationSpec = androidOffsetSpec,
                            ) + scaleIn(
                                initialScale = 1.10f,
                                animationSpec = androidScaleSpec,
                            ) + fadeIn(tween(280))) togetherWith
                                (slideOutHorizontally(
                                    targetOffsetX = { direction * it / 20 },
                                    animationSpec = androidOffsetSpec,
                                ) + scaleOut(
                                    targetScale = 0.90f,
                                    animationSpec = androidScaleSpec,
                                ) + fadeOut(tween(220)))
                        platform.family == PlatformFamily.IOS ->
                            slideInHorizontally(
                                initialOffsetX = { -direction * it / 3 },
                                animationSpec = appleSpatialSpec,
                            ) togetherWith slideOutHorizontally(
                                targetOffsetX = { direction * it },
                                animationSpec = appleSpatialSpec,
                            )
                        else -> EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                predictivePopTransitionSpec = { swipeEdge: Int ->
                    val gestureDirection = if (swipeEdge == NavigationEvent.EDGE_RIGHT) -1 else 1
                    when {
                        nativeNavigationEnabled ->
                            EnterTransition.None togetherWith ExitTransition.None
                        reduceMotion ->
                            fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        platform.family == PlatformFamily.Android ->
                            (slideInHorizontally(
                                initialOffsetX = { -gestureDirection * it / 20 },
                                animationSpec = androidOffsetSpec,
                            ) + scaleIn(
                                initialScale = 1.10f,
                                animationSpec = androidScaleSpec,
                            ) + fadeIn(tween(280))) togetherWith
                                (slideOutHorizontally(
                                    targetOffsetX = { gestureDirection * it / 20 },
                                    animationSpec = androidOffsetSpec,
                                ) + scaleOut(
                                    targetScale = 0.90f,
                                    animationSpec = androidScaleSpec,
                                ) + fadeOut(tween(220)))
                        platform.family == PlatformFamily.IOS ->
                            slideInHorizontally(
                                initialOffsetX = { -gestureDirection * it / 3 },
                                animationSpec = appleInteractiveSpec,
                            ) togetherWith slideOutHorizontally(
                                targetOffsetX = { gestureDirection * it },
                                animationSpec = appleInteractiveSpec,
                            )
                        else -> EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                entryProvider = entryProvider {
                    entry<AppSection> { route ->
                        SectionDestination(
                            route = route,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<ClassroomDetailRoute> {
                        SectionDestination(
                            route = ClassroomDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<ClassroomOccupancyDetailRoute> {
                        SectionDestination(
                            route = ClassroomOccupancyDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<HomeworkDetailRoute> {
                        SectionDestination(
                            route = HomeworkDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<PhyVlabDetailRoute> {
                        SectionDestination(
                            route = PhyVlabDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<MailboxDetailRoute> {
                        SectionDestination(
                            route = MailboxDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<MailboxComposeRoute> {
                        SectionDestination(
                            route = MailboxComposeRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                },
            )
            if (showsCompactBottomBar && compactBottomBarOverlayPadding > 0.dp) {
                CompactBottomNavigation(
                    section = section,
                    sections = compactBottomNavSections,
                    onSectionSelected = { target -> navigateToSection(target) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

private fun String.toAppRoute(): AppRoute? =
    if (this == CLASSROOM_DETAIL_ROUTE_ID) {
        ClassroomDetailRoute
    } else if (this == CLASSROOM_OCCUPANCY_DETAIL_ROUTE_ID) {
        ClassroomOccupancyDetailRoute
    } else if (this == HOMEWORK_DETAIL_ROUTE_ID) {
        HomeworkDetailRoute
    } else if (this == PHYVLAB_DETAIL_ROUTE_ID) {
        PhyVlabDetailRoute
    } else if (this == MAILBOX_DETAIL_ROUTE_ID) {
        MailboxDetailRoute
    } else if (this == MAILBOX_COMPOSE_ROUTE_ID) {
        MailboxComposeRoute
    } else {
        AppSection.entries.firstOrNull { it.name == this }
    }

/** 邮箱页顶栏返回的目标；壳内详情回列表，原生详情页回邮箱上级页面。 */
internal enum class MailboxBackTarget {
    PARENT,
    LIST,
}

internal fun shouldHandleInlineMailboxBack(
    currentRouteIsMailbox: Boolean,
    mailboxInlineDetail: Boolean,
    mailboxInlineCompose: Boolean,
): Boolean = currentRouteIsMailbox && (mailboxInlineDetail || mailboxInlineCompose)

internal fun mailboxBackTarget(
    useNativeSecondaryRoutes: Boolean,
    hasSelectedMessage: Boolean,
    isMessageLoading: Boolean,
): MailboxBackTarget = if (
    !useNativeSecondaryRoutes &&
        (hasSelectedMessage || isMessageLoading)
) {
    MailboxBackTarget.LIST
} else {
    MailboxBackTarget.PARENT
}

/**
 * 紧凑端「更多」里的独立页面都走平台原生 push；邮箱列表、邮件详情和写信/回复
 * 分别对应 MAILBOX、MAILBOX_DETAIL、MAILBOX_COMPOSE，形成连续的原生页面层级。
 */
internal fun shouldOpenNativeSectionRoute(
    targetRouteId: String,
    useNativeSecondaryRoutes: Boolean,
): Boolean = useNativeSecondaryRoutes &&
    MoreGroupSections.any { it.name == targetRouteId } &&
    targetRouteId != AppSection.MORE.name

private fun HomeChangeDomain.toAppSection(): AppSection = when (this) {
    HomeChangeDomain.GRADES -> AppSection.GRADES
    HomeChangeDomain.COURSES -> AppSection.SCHEDULE
    HomeChangeDomain.EXAMS -> AppSection.EXAMS
    HomeChangeDomain.HOMEWORK -> AppSection.HOMEWORK
}

@Composable
private fun AppSidebar(
    profile: StudentProfile,
    section: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    modifier: Modifier,
) {
    val sidebarScrollState = rememberScrollState()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.62f),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("交大自由行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Surface(
                color = MaterialTheme.colorScheme.surface.accessibleAlpha(0.82f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        profile.department,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 宽屏侧栏保持原有五个一级入口；物理在线仍从「更多」进入。
            // 退出登录放在设置页，侧栏不再重复。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(sidebarScrollState)
                    .desktopTouchScroll(sidebarScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                bottomNavSections(showPhyVlab = false).forEach { item ->
                    val selected = if (item == AppSection.MORE) {
                        section in MoreGroupSections
                    } else {
                        section == item
                    }
                    AppSidebarItem(
                        title = item.title,
                        selected = selected,
                        onClick = { onSectionSelected(item) },
                    )
                }
                Text(
                    "作业、课件和教室功能使用 HTTP 明文传输，请勿在不可信网络中使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AppSidebarItem(title: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface.accessibleAlpha(0.82f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            title,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/**
 * 紧凑顶栏。
 *
 * 内容区固定高度，避免「作业」有同步胶囊、「更多」无胶囊时标题上下漂。
 * 一级 tab 无返回：标题左缘统一 20.dp；二级页有返回时标题跟在箭头后。
 */
@Composable
private fun CompactAppTopBar(
    title: String,
    isRefreshing: Boolean,
    isLoggingIn: Boolean = false,
    /** 非登录/非刷新时右上角文案（课表等页的「已同步/未同步」）；其它页保持 null。 */
    idleStatusText: String? = null,
    /** 非空时在状态文案旁显示刷新按钮（替代下拉刷新）。 */
    onRefresh: (() -> Unit)? = null,
    /** 状态胶囊的附加动作；通常用于查看聚合同步失败详情。 */
    onStatusClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    // 顶栏与页面背景同色：iOS 的 SwiftUI 根视图在状态栏下方铺的就是 background，
    // 顶栏若用 surface 会在状态栏下方露出一条浅色带子，破坏沉浸感。
    // iOS 的 Compose 宿主已改为全屏布局（原生 push 转场需要覆盖状态栏区域），
    // WindowInsets.statusBars 在 iOS 上恢复为真实值，顶栏统一应用状态栏内边距。
    // 内容行固定高度：大标题与右侧「已同步」胶囊垂直居中同一条线，各 tab 一致。
    val topBarContentHeight = 52.dp
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(topBarContentHeight)
                .padding(start = if (onBack != null) 6.dp else 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 二级页返回箭头；一级 tab（首页/作业/更多…）无返回，标题左缘固定。
            if (onBack != null) {
                Surface(
                    onClick = onBack,
                    color = Color.Transparent,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.size(topBarContentHeight),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "返回" },
                        contentAlignment = Alignment.Center,
                    ) {
                        BackChevron()
                    }
                }
                Spacer(modifier = Modifier.width(2.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                action()
                Spacer(Modifier.width(8.dp))
            }
            // “登录中”优先于“同步中”，再回落到页面提供的空闲状态（如课表已同步）。
            // 状态文案与刷新并入同一胶囊，避免「已同步」与孤立圆钮两截破碎感。
            val busyText = when {
                isLoggingIn -> "登录中"
                isRefreshing -> "同步中"
                else -> null
            }
            when {
                busyText != null -> {
                    TopBarBusyStatusCapsule(
                        text = busyText,
                        onClick = onStatusClick,
                    )
                }
                idleStatusText != null && onStatusClick != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TopBarStatusCapsule(
                            text = idleStatusText,
                            onClick = onStatusClick,
                        )
                        onRefresh?.let { refresh ->
                            TopBarRefreshCapsule(
                                onClick = refresh,
                                enabled = !isRefreshing && !isLoggingIn,
                            )
                        }
                    }
                }
                onRefresh != null -> {
                    Surface(
                        onClick = onRefresh,
                        color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.55f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.semantics {
                            contentDescription = if (idleStatusText != null) {
                                "$idleStatusText，点按刷新"
                            } else {
                                "刷新"
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (idleStatusText != null) {
                                Text(
                                    idleStatusText,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                // 对勾只表示「已同步」；未同步/同步失败用刷新图标，避免「未同步 ✓」语义打架。
                                if (idleStatusText == "已同步") {
                                    TopBarSyncedIcon(modifier = Modifier.size(15.dp))
                                } else {
                                    TopBarRefreshIcon(modifier = Modifier.size(15.dp))
                                }
                            } else {
                                TopBarRefreshIcon(modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
                idleStatusText != null -> {
                    Text(
                        idleStatusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBarBusyStatusCapsule(
    text: String,
    onClick: (() -> Unit)?,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.8.dp,
                color = LocalContentColor.current,
            )
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
    val modifier = Modifier.semantics {
        contentDescription = "$text，点按查看同步详情"
    }
    if (onClick == null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.55f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(999.dp),
            modifier = modifier,
            content = content,
        )
    } else {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.55f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(999.dp),
            modifier = modifier,
            content = content,
        )
    }
}

@Composable
private fun TopBarStatusCapsule(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.55f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.semantics {
            contentDescription = "$text，点按查看同步详情"
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium)
            if (text == "已同步") {
                TopBarSyncedIcon(modifier = Modifier.size(15.dp))
            } else {
                TopBarRefreshIcon(modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun TopBarRefreshCapsule(
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.55f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.semantics { contentDescription = "刷新" },
    ) {
        Text(
            "刷新",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PartialSyncFailureDialog(
    failedItems: List<String>,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("部分同步失败") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("以下内容本轮同步失败。可以稍后手动重试：")
                failedItems.forEach { item ->
                    Text("• $item", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { Button(onClick = onRetry) { Text("重试") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun HomeSyncDetailsDialog(
    title: String,
    items: List<HomeSyncItem>,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(scrollState)
                    .desktopTouchScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when {
                        canRetry -> "本次同步有失败项目，点击“重试”后才会重新请求。"
                        title == "登录中" -> "正在完成统一身份认证，页面数据会在登录完成后开始同步。"
                        title == "同步中" -> "各模块正在并行同步，完成项会显示勾选。"
                        else -> "当前登录会话与各模块的同步状态如下。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                items.forEach { item -> HomeSyncDetailRow(item) }
            }
        },
        confirmButton = {
            if (canRetry) {
                Button(onClick = onRetry) { Text("重试") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = if (canRetry) {
            { TextButton(onClick = onDismiss) { Text("关闭") } }
        } else {
            null
        },
    )
}

@Composable
private fun HomeSyncDetailRow(item: HomeSyncItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (item.state) {
                    HomeSyncItemState.SYNCING -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.8.dp,
                    )
                    HomeSyncItemState.SUCCESS -> TopBarSyncedIcon(Modifier.size(16.dp))
                    HomeSyncItemState.FAILED -> Text(
                        "!",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    HomeSyncItemState.WAITING -> Text(
                        "·",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TopBarCalendarAction(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 顶栏同步：双弧 + 两端箭头（Material/SF 风格的 sync，自绘不引入图标库）。
 * Compose 角度：0° 在右侧，顺时针为正。
 */
@Composable
private fun TopBarRefreshIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.7.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val r = size.minDimension * 0.34f
        val c = Offset(size.width / 2f, size.height / 2f)
        val topLeft = Offset(c.x - r, c.y - r)
        val arcSize = Size(r * 2, r * 2)
        val arrow = 3.4.dp.toPx()

        fun pointOnCircle(deg: Float): Offset {
            val rad = deg * PI / 180.0
            return Offset(
                c.x + r * kotlin.math.cos(rad).toFloat(),
                c.y + r * kotlin.math.sin(rad).toFloat(),
            )
        }

        /** 弧末端处画 V 形箭头，开口朝向切线（顺时针）。 */
        fun arrowAt(endDeg: Float) {
            val tip = pointOnCircle(endDeg)
            // 顺时针切线方向 = endDeg + 90°（Canvas 顺时针）
            val tangent = (endDeg + 90f) * PI / 180.0
            val tx = kotlin.math.cos(tangent).toFloat()
            val ty = kotlin.math.sin(tangent).toFloat()
            // 法向（指向圆心外侧的侧翼）
            val nx = -ty
            val ny = tx
            val back = Offset(tip.x - tx * arrow, tip.y - ty * arrow)
            drawLine(
                tint,
                tip,
                Offset(back.x + nx * arrow * 0.55f, back.y + ny * arrow * 0.55f),
                strokeWidth,
                StrokeCap.Round,
            )
            drawLine(
                tint,
                tip,
                Offset(back.x - nx * arrow * 0.55f, back.y - ny * arrow * 0.55f),
                strokeWidth,
                StrokeCap.Round,
            )
        }

        // 上半弧：约从右下扫到左上
        drawArc(
            color = tint,
            startAngle = -30f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        arrowAt(120f)

        // 下半弧：约从左上扫到右下
        drawArc(
            color = tint,
            startAngle = 150f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        arrowAt(300f)
    }
}

/** 顶栏「已同步」对勾：与 TopBarRefreshIcon 同粗细的 Canvas 自绘，不引入图标库。 */
@Composable
private fun TopBarSyncedIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.7.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(
            tint,
            Offset(w * 0.20f, h * 0.54f),
            Offset(w * 0.42f, h * 0.74f),
            strokeWidth,
            StrokeCap.Round,
        )
        drawLine(
            tint,
            Offset(w * 0.42f, h * 0.74f),
            Offset(w * 0.80f, h * 0.28f),
            strokeWidth,
            StrokeCap.Round,
        )
    }
}

/** 返回箭头：与 MoreEntryChevron 同风格的 Canvas 左尖括号，不引入图标库。 */
@Composable
private fun BackChevron() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(10.dp, 16.dp)) {
        val strokeWidth = 2.dp.toPx()
        val mid = size.height / 2
        drawLine(
            color,
            Offset(size.width - 2.dp.toPx(), 2.dp.toPx()),
            Offset(2.dp.toPx(), mid),
            strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(2.dp.toPx(), mid),
            Offset(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
            strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 紧凑底栏：Material3 官方 [NavigationBar] + [NavigationBarItem]。
 * 挂在 NavDisplay 外层，一级 tab 切换时实例不销毁，按压/水波纹才能播完。
 * windowInsets 用 [WindowInsets.navigationBars]，由组件处理 Home Indicator / 手势条，
 * 避免自绘固定高度把标签裁切或与系统安全区叠错。
 */
@Composable
private fun CompactBottomNavigation(
    section: AppSection,
    sections: List<AppSection>,
    onSectionSelected: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        windowInsets = WindowInsets.navigationBars,
    ) {
        sections.forEach { item ->
            val selected =
                if (item == AppSection.MORE) section in MoreGroupSections else section == item
            NavigationBarItem(
                selected = selected,
                onClick = { onSectionSelected(item) },
                icon = {
                    // 固定 24.dp 图标盒，保证各 tab 标签基线一致。
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompactTabIcon(item)
                    }
                },
                label = {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                },
                alwaysShowLabel = true,
            )
        }
    }
}

/** 底部导航图标：与登录页密码眼睛一致用 Canvas 绘制，不引入图标库依赖。 */
@Composable
private fun CompactTabIcon(section: AppSection) {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(24.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        when (section) {
            AppSection.HOME -> {
                // 2×2 圆角方块
                val cell = 7.dp.toPx()
                val gap = 3.dp.toPx()
                val start = (size.width - cell * 2 - gap) / 2
                val radius = CornerRadius(2.dp.toPx())
                for (row in 0..1) {
                    for (col in 0..1) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(start + col * (cell + gap), start + row * (cell + gap)),
                            size = Size(cell, cell),
                            cornerRadius = radius,
                        )
                    }
                }
            }
            AppSection.SCHEDULE -> {
                // 日历：圆角外框 + 顶部分隔线 + 两个挂环
                val left = 4.dp.toPx()
                val top = 5.dp.toPx()
                val right = size.width - left
                val bottom = size.height - 4.dp.toPx()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = strokeWidth),
                )
                val divider = top + 4.dp.toPx()
                drawLine(color, Offset(left, divider), Offset(right, divider), strokeWidth)
                drawLine(
                    color,
                    Offset(left + 4.5.dp.toPx(), top - 2.dp.toPx()),
                    Offset(left + 4.5.dp.toPx(), top + 2.dp.toPx()),
                    strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(right - 4.5.dp.toPx(), top - 2.dp.toPx()),
                    Offset(right - 4.5.dp.toPx(), top + 2.dp.toPx()),
                    strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            AppSection.GRADES -> {
                // 三根高度递增的柱条
                val barWidth = 3.4.dp.toPx()
                val baseY = size.height - 4.dp.toPx()
                val xs = listOf(5.dp.toPx(), 10.3.dp.toPx(), 15.6.dp.toPx())
                val heights = listOf(6.dp.toPx(), 10.dp.toPx(), 14.dp.toPx())
                xs.zip(heights).forEach { (x, h) ->
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, baseY - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(1.2.dp.toPx()),
                    )
                }
            }
            AppSection.HOMEWORK -> {
                // 便签框 + 对勾
                val left = 5.dp.toPx()
                val top = 4.dp.toPx()
                val right = size.width - 5.dp.toPx()
                val bottom = size.height - 4.dp.toPx()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = strokeWidth),
                )
                drawLine(
                    color,
                    Offset(left + 3.dp.toPx(), top + 7.5.dp.toPx()),
                    Offset(left + 6.dp.toPx(), top + 10.5.dp.toPx()),
                    strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(left + 6.dp.toPx(), top + 10.5.dp.toPx()),
                    Offset(right - 3.dp.toPx(), top + 5.dp.toPx()),
                    strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            AppSection.PHYVLAB -> {
                // 物理在线：简化烧杯/实验瓶图标。
                val left = 5.dp.toPx()
                val right = size.width - left
                val top = 4.dp.toPx()
                val neckBottom = 9.dp.toPx()
                val bottom = size.height - 4.dp.toPx()
                drawLine(color, Offset(9.dp.toPx(), top), Offset(9.dp.toPx(), neckBottom), strokeWidth)
                drawLine(color, Offset(15.dp.toPx(), top), Offset(15.dp.toPx(), neckBottom), strokeWidth)
                drawLine(color, Offset(7.dp.toPx(), top), Offset(17.dp.toPx(), top), strokeWidth, cap = StrokeCap.Round)
                drawLine(color, Offset(9.dp.toPx(), neckBottom), Offset(left, bottom), strokeWidth, cap = StrokeCap.Round)
                drawLine(color, Offset(15.dp.toPx(), neckBottom), Offset(right, bottom), strokeWidth, cap = StrokeCap.Round)
                drawLine(color, Offset(left, bottom), Offset(right, bottom), strokeWidth, cap = StrokeCap.Round)
                drawLine(
                    color,
                    Offset(6.5.dp.toPx(), 15.dp.toPx()),
                    Offset(17.5.dp.toPx(), 15.dp.toPx()),
                    strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawCircle(color, radius = 1.2.dp.toPx(), center = Offset(12.dp.toPx(), 12.dp.toPx()))
            }
            else -> {
                // 更多：三个圆点
                val radius = 1.7.dp.toPx()
                val cy = size.height / 2
                drawCircle(color, radius, Offset(5.5.dp.toPx(), cy))
                drawCircle(color, radius, Offset(size.width / 2, cy))
                drawCircle(color, radius, Offset(size.width - 5.5.dp.toPx(), cy))
            }
        }
    }
}

/**
 * 「更多」页：iOS 设置式分块列表。
 * - 学业：物理在线总开关、考试、课件
 * - 校园：教室占用查询、教室人数估计、邮箱
 * - 信息与下载：校历文章、成绩单
 * - 设置单独一块垫底
 */
@Composable
private fun MoreWorkspace(
    phyVlabEnabled: Boolean,
    onPhyVlabEnabledChange: (Boolean) -> Unit,
    onOpenSection: (AppSection) -> Unit,
    modifier: Modifier,
) {
    val pageScrollState = rememberScrollState()
    val sections = listOf(
        MoreListSection(
            header = "校园",
            items = listOf(AppSection.CLASSROOM_OCCUPANCY, AppSection.CLASSROOMS, AppSection.MAILBOX),
        ),
        MoreListSection(
            header = "信息与下载",
            items = listOf(AppSection.CALENDAR, AppSection.REPORT_CARD_DOWNLOAD),
        ),
        MoreListSection(
            header = null,
            items = listOf(AppSection.SETTINGS),
        ),
    )
    Column(
        modifier = modifier
            .verticalScroll(pageScrollState)
            .desktopTouchScroll(pageScrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "学业",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        MorePhyVlabToggleRow(
            enabled = phyVlabEnabled,
            onEnabledChange = onPhyVlabEnabledChange,
        )
        MoreGroupedSection(
            header = null,
            items = listOf(AppSection.EXAMS, AppSection.COURSEWARE),
            onOpenSection = onOpenSection,
        )
        sections.forEach { section ->
            MoreGroupedSection(
                header = section.header,
                items = section.items,
                onOpenSection = onOpenSection,
            )
        }
    }
}

@Composable
private fun MorePhyVlabToggleRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    AppSection.PHYVLAB.moreTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Normal,
                )
                Text(
                    "开启后同步物理在线，并在底栏显示入口；关闭后不参与同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

private data class MoreListSection(
    val header: String?,
    val items: List<AppSection>,
)

@Composable
private fun MoreGroupedSection(
    header: String?,
    items: List<AppSection>,
    onOpenSection: (AppSection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        // 整块圆角容器，行间细分隔线（类似 iOS inset grouped）。
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, item ->
                    Surface(
                        onClick = { onOpenSection(item) },
                        color = Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.moreTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            MoreEntryChevron()
                        }
                    }
                    if (index < items.lastIndex) {
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

@Composable
private fun MoreEntryChevron() {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradeWorkspace(
    state: GradeUiState,
    expanded: Boolean,
    model: GradeScreenModel,
    onRefresh: () -> Unit,
    modifier: Modifier,
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    // 成绩页会和登录后的多路后台同步共用壳层；把昂贵的筛选/排序/加权计算
    // 固定在成绩输入变化时，避免无关状态重组时反复创建列表。
    val visibleGrades = remember(
        state.grades,
        state.selectedSemesters,
        state.excludedCourseTypes,
        state.courseTypesByCode,
        state.sortOrder,
    ) { state.visibleGrades }
    val gradeInfo = remember(
        state.grades,
        state.selectedSemesters,
        state.selectionMode,
        state.selectedGradeIds,
        state.courseTypesByCode,
        state.excludedCourseTypes,
    ) { state.gradeInfo }
    val gradeRows = remember(visibleGrades, state.courseTypesByCode) {
        visibleGrades.map { grade ->
            GradeRowData(
                id = grade.id,
                courseName = grade.displayCourseName(),
                semesterTeacher = "${grade.semester} · ${grade.courseTeacher.ifBlank { "教师信息未提供" }}",
                credits = "学分 ${grade.courseCredits}",
                score = grade.courseScore,
                scoreValue = scoreForSorting(grade.courseScore),
                courseType = state.courseTypeOf(grade),
            )
        }
    }

    Column(
        modifier = if (expanded) {
            modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        } else {
            // 与课表/作业紧凑顶距对齐，避免 banner 视觉偏大。
            modifier.padding(horizontal = 16.dp).padding(top = 8.dp)
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 同步态在 DestinationPage 顶栏；此处不再放页内「同步成绩」。
        state.failure?.let { failure ->
            GradeFailureBanner(
                failure = failure,
                hasContent = state.grades.isNotEmpty(),
                onRetry = onRefresh,
                onDismiss = model::dismissFailure,
            )
        }

        when {
            state.isLoading && state.grades.isEmpty() -> GradeLoadingState()
            state.grades.isEmpty() -> GradeEmptyState(onRefresh)
            else -> {
                if (expanded) {
                    GradeSummaryCard(
                        state = state,
                        gradeInfo = gradeInfo,
                        visibleGradeCount = visibleGrades.size,
                        onOpenFilter = { showFilterSheet = true },
                    )
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        GradeList(
                            state = state,
                            rows = gradeRows,
                            model = model,
                            modifier = Modifier.weight(0.58f).fillMaxHeight(),
                        )
                        GradeDetailPanel(
                            grade = state.selectedGrade,
                            modifier = Modifier.weight(0.42f).fillMaxHeight(),
                        )
                    }
                } else {
                    // 紧凑端：Banner 放进 LazyColumn，与列表同一滚动体。
                    // 固定在列表上方时，iOS 橡皮筋只拉卡片、Banner 不动，会出现大空档并与下拉刷新抢手势。
                    GradeScrollableContent(
                        state = state,
                        rows = gradeRows,
                        gradeInfo = gradeInfo,
                        model = model,
                        onOpenFilter = { showFilterSheet = true },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    state.selectedGrade?.let { grade ->
                        val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                        ModalBottomSheet(
                            onDismissRequest = model::dismissGradeDetails,
                            sheetState = detailSheetState,
                            sheetGesturesEnabled = true,
                            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                        ) {
                            val detailScrollState = rememberScrollState()
                            GradeDetailSheetBody(
                                grade = grade,
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
            sheetGesturesEnabled = true,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            GradeFilterSheet(state = state, model = model)
        }
    }
}

@Composable
private fun GradeSummaryCard(
    state: GradeUiState,
    gradeInfo: GradeInfoResult,
    visibleGradeCount: Int,
    onOpenFilter: () -> Unit,
) {
    val title = when (val info = gradeInfo) {
        GradeInfoResult.NoGrades -> if (state.selectionMode) {
            "请选择用于计算的课程"
        } else {
            "暂无可计算成绩"
        }
        is GradeInfoResult.Calculated -> info.formattedMessage
    }
    val semesterFiltered =
        state.semesterFilterForQuery.isNotEmpty() || state.excludedCourseTypes.isNotEmpty()
    val subtitle = when {
        state.selectionMode -> "自选 ${state.selectedGradeIds.size} 门 · 显示 $visibleGradeCount 门"
        semesterFiltered -> "筛选后 $visibleGradeCount 门 · 共 ${state.grades.size} 门"
        else -> "共 ${state.grades.size} 门课程"
    }

    // 尺寸与课表 CourseSummary 对齐：18dp 圆角、14/12 内边距、右侧 pill 操作。
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
                    .clickable(onClick = onOpenFilter)
                    .padding(vertical = 2.dp, horizontal = 4.dp),
            ) {
                Text(
                    title,
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
            Surface(
                onClick = onOpenFilter,
                color = MaterialTheme.colorScheme.surface.accessibleAlpha(0.86f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.semantics { contentDescription = "筛选与排序" },
            ) {
                // 筛选 + 排序：sheet 同时承载性质/学期筛选与成绩排序，用双图标表达。
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterFunnelIcon(modifier = Modifier.size(18.dp))
                    RankBarsIcon(modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 漏斗形筛选图标（自绘，不引入 material-icons）。 */
@Composable
private fun FilterFunnelIcon(
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
        // 上宽下窄的漏斗轮廓
        drawLine(tint, Offset(left, top), Offset(right, top), stroke, StrokeCap.Round)
        drawLine(tint, Offset(left, top), Offset(neckLeft, midY), stroke, StrokeCap.Round)
        drawLine(tint, Offset(right, top), Offset(neckRight, midY), stroke, StrokeCap.Round)
        drawLine(tint, Offset(neckLeft, midY), Offset(neckLeft, bottom), stroke, StrokeCap.Round)
        drawLine(tint, Offset(neckRight, midY), Offset(neckRight, bottom), stroke, StrokeCap.Round)
        drawLine(tint, Offset(neckLeft, bottom), Offset(neckRight, bottom), stroke, StrokeCap.Round)
    }
}

/** 高度递增柱条：表示成绩排序/排名。 */
@Composable
private fun RankBarsIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier = modifier) {
        val barWidth = 3.2.dp.toPx()
        val gap = 2.4.dp.toPx()
        val baseY = size.height - 2.5.dp.toPx()
        val heights = listOf(6.dp.toPx(), 10.dp.toPx(), 14.dp.toPx())
        val totalWidth = barWidth * 3 + gap * 2
        val startX = (size.width - totalWidth) / 2f
        heights.forEachIndexed { index, h ->
            val x = startX + index * (barWidth + gap)
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, baseY - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(1.1.dp.toPx()),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradeFilterSheet(
    state: GradeUiState,
    model: GradeScreenModel,
) {
    val semesterOptions = state.semesterOptions
    val allSemestersSelected =
        semesterOptions.isNotEmpty() && state.selectedSemesters.containsAll(semesterOptions)
    val filterScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(filterScrollState)
            .desktopTouchScroll(filterScrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("筛选与计算", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        // —— 学期：小胶囊，默认全选 ——
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "学期",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (!allSemestersSelected && semesterOptions.isNotEmpty()) {
                    TextButton(onClick = model::clearSemesterFilter) { Text("全选") }
                }
            }
            if (semesterOptions.isEmpty()) {
                Text(
                    "暂无学期数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    semesterOptions.forEach { semester ->
                        FilterChip(
                            selected = semester in state.selectedSemesters,
                            onClick = { model.toggleSemester(semester) },
                            label = { Text(semester) },
                        )
                    }
                }
            }
        }

        // —— 排序：上维度（圆角矩形）+ 下方向（胶囊，随维度切换）——
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "排序",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val byScore =
                state.sortOrder == GradeSortOrder.ASCENDING ||
                    state.sortOrder == GradeSortOrder.DESCENDING
            // 维度：圆角矩形，与下方方向胶囊区分层级。
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !byScore,
                    onClick = { model.selectSortCategory(byScore = false) },
                    shape = RoundedCornerShape(10.dp),
                    label = { Text("默认顺序") },
                )
                FilterChip(
                    selected = byScore,
                    onClick = { model.selectSortCategory(byScore = true) },
                    shape = RoundedCornerShape(10.dp),
                    label = { Text("分数高低") },
                )
            }
            // 方向：胶囊形。
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (byScore) {
                    FilterChip(
                        selected = state.sortOrder == GradeSortOrder.DESCENDING,
                        onClick = { model.setSortOrder(GradeSortOrder.DESCENDING) },
                        shape = RoundedCornerShape(percent = 50),
                        label = { Text("从高到低") },
                    )
                    FilterChip(
                        selected = state.sortOrder == GradeSortOrder.ASCENDING,
                        onClick = { model.setSortOrder(GradeSortOrder.ASCENDING) },
                        shape = RoundedCornerShape(percent = 50),
                        label = { Text("从低到高") },
                    )
                } else {
                    FilterChip(
                        selected = state.sortOrder == GradeSortOrder.ORIGINAL_REVERSED,
                        onClick = { model.setSortOrder(GradeSortOrder.ORIGINAL_REVERSED) },
                        shape = RoundedCornerShape(percent = 50),
                        label = { Text("逆序") },
                    )
                    FilterChip(
                        selected = state.sortOrder == GradeSortOrder.ORIGINAL,
                        onClick = { model.setSortOrder(GradeSortOrder.ORIGINAL) },
                        shape = RoundedCornerShape(percent = 50),
                        label = { Text("正序") },
                    )
                }
            }
        }

        // —— 课程性质：筛选模式用 excluded；自选模式实时映射已选门数 ——
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.selectionMode) {
                    "按课程性质勾选（点击全选/取消该类）"
                } else {
                    "按课程性质筛选（点击可勾选或取消勾选）"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.courseTypesByCode == null) {
                Text(
                    "课程性质未同步，下拉刷新后可用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        CourseType.REQUIRED to "必修",
                        CourseType.LIMITED to "限选",
                        CourseType.ELECTIVE to "任选",
                        CourseType.PHYSICAL_EDUCATION to "体育",
                        CourseType.UNKNOWN to "其他类别",
                    ).forEach { (type, label) ->
                        val total = state.courseTypeCounts[type] ?: 0
                        if (total <= 0) return@forEach
                        val colors = courseTypeColors(type)
                        if (state.selectionMode) {
                            // 自选：严格跟 selectedGradeIds。0 门时全部 NONE（0/n、未选色），
                            // 与筛选模式「默认全选」满色脱钩，避免「自选 0 门但性质仍全亮」。
                            val selState = state.selectionStateForType(type)
                            val selectedCount = state.selectedCountForType(type)
                            val isAll = selState == CourseTypeSelectionState.ALL
                            val isPartial = selState == CourseTypeSelectionState.PARTIAL
                            val visuallyOn = isAll || isPartial
                            FilterChip(
                                selected = visuallyOn,
                                onClick = { model.toggleTypeSelection(type) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = when {
                                        isAll -> colors.container
                                        isPartial -> colors.container.copy(alpha = 0.55f)
                                        else -> colors.container
                                    },
                                    selectedLabelColor = colors.onContainer,
                                    // 未选：刻意更淡，和筛选「全选满色」区分开
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        .accessibleAlpha(0.22f),
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        .accessibleAlpha(0.42f),
                                ),
                                border = BorderStroke(
                                    width = if (visuallyOn) 1.5.dp else 1.dp,
                                    color = if (visuallyOn) {
                                        colors.onContainer.copy(alpha = if (isPartial) 0.28f else 0.42f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant.accessibleAlpha(0.4f)
                                    },
                                ),
                                label = {
                                    Text(
                                        "$label $selectedCount/$total",
                                        fontWeight = if (isAll) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                            )
                        } else {
                            val included = type !in state.excludedCourseTypes
                            FilterChip(
                                selected = included,
                                onClick = { model.toggleCourseTypeIncluded(type) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.container,
                                    selectedLabelColor = colors.onContainer,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        .accessibleAlpha(0.35f),
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        .accessibleAlpha(0.48f),
                                ),
                                border = BorderStroke(
                                    width = if (included) 1.5.dp else 1.dp,
                                    color = if (included) {
                                        colors.onContainer.copy(alpha = 0.42f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant.accessibleAlpha(0.55f)
                                    },
                                ),
                                label = {
                                    Text(
                                        "$label $total",
                                        fontWeight = if (included) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // —— 自由选择课程模式：列表逐门勾选；开关形态 ——
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "自选课程计算",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.55f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "自由选择课程模式",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "开启后列表出现勾选框，可任意点选课程。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.selectionMode,
                        onCheckedChange = model::setSelectionMode,
                    )
                }
            }

            if (state.selectionMode) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = model::selectAllVisible) { Text("全选当前") }
                    OutlinedButton(onClick = model::clearAllSelections) { Text("全部清空") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 紧凑成绩页：摘要 Banner + 列表（刷新在顶栏按钮，列表仅平台原生过滚）。 */
@Composable
private fun GradeScrollableContent(
    state: GradeUiState,
    rows: List<GradeRowData>,
    gradeInfo: GradeInfoResult,
    model: GradeScreenModel,
    onOpenFilter: () -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    // 稳定 key 重排时 LazyColumn 会锚定旧 item，导致跳到列表尾；排序变化时回顶。
    LaunchedEffect(state.sortOrder) {
        listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.desktopTouchScroll(listState),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        item(key = "grade-summary") {
            GradeSummaryCard(
                state = state,
                gradeInfo = gradeInfo,
                visibleGradeCount = rows.size,
                onOpenFilter = onOpenFilter,
            )
        }
        if (rows.isEmpty()) {
            item(key = "grade-empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "当前筛选条件下没有成绩",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(rows, key = GradeRowData::id) { row ->
                GradeRow(
                    row = row,
                    selectionMode = state.selectionMode,
                    selectedForCalculation = row.id in state.selectedGradeIds,
                    selectedForDetails = row.id == state.selectedGradeId,
                    onOpen = { model.showGradeDetails(row.id) },
                    onSelectionChange = { selected -> model.setGradeSelected(row.id, selected) },
                )
            }
        }
    }
}

@Composable
private fun GradeList(
    state: GradeUiState,
    rows: List<GradeRowData>,
    model: GradeScreenModel,
    modifier: Modifier,
) {
    if (rows.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "当前筛选条件下没有成绩",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // 宽屏 Android 只有少量固定成绩项。整列预布局一次，避免 LazyColumn 在首次
    // 触摸时批量创建下一组卡片；手机/桌面仍保留 LazyColumn 的按需布局。
    if (currentPlatform().family == PlatformFamily.Android) {
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier
                .verticalScroll(scrollState)
                .desktopTouchScroll(scrollState)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rows.forEach { row ->
                GradeRow(
                    row = row,
                    selectionMode = state.selectionMode,
                    selectedForCalculation = row.id in state.selectedGradeIds,
                    selectedForDetails = row.id == state.selectedGradeId,
                    onOpen = { model.showGradeDetails(row.id) },
                    onSelectionChange = { selected -> model.setGradeSelected(row.id, selected) },
                )
            }
        }
    } else {
        val listState = rememberLazyListState()
        LaunchedEffect(state.sortOrder) {
            listState.scrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = modifier.desktopTouchScroll(listState),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            items(rows, key = GradeRowData::id) { row ->
            GradeRow(
                row = row,
                selectionMode = state.selectionMode,
                selectedForCalculation = row.id in state.selectedGradeIds,
                selectedForDetails = row.id == state.selectedGradeId,
                onOpen = { model.showGradeDetails(row.id) },
                onSelectionChange = { selected -> model.setGradeSelected(row.id, selected) },
                )
            }
        }
    }
}

@Immutable
private data class GradeRowData(
    val id: Int,
    val courseName: String,
    val semesterTeacher: String,
    val credits: String,
    val score: String,
    val scoreValue: Int,
    val courseType: CourseType?,
)

@Composable
private fun GradeRow(
    row: GradeRowData,
    selectionMode: Boolean,
    selectedForCalculation: Boolean,
    selectedForDetails: Boolean,
    onOpen: () -> Unit,
    onSelectionChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (selectedForDetails) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        row.courseName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 性质未同步（null）或未知的课程不显示标签，避免把映射缺失误读成任选。
                    if (row.courseType != null && row.courseType != CourseType.UNKNOWN) {
                        val colors = courseTypeColors(row.courseType)
                        Text(
                            row.courseType.displayName(),
                            color = colors.onContainer,
                            modifier = Modifier
                                .background(colors.container, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    row.semesterTeacher,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.credits,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                row.score,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = gradeScoreColor(row.scoreValue),
            )
            if (selectionMode) {
                Checkbox(
                    checked = selectedForCalculation,
                    onCheckedChange = onSelectionChange,
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = "选择${row.courseName}用于计算"
                    },
                )
            }
        }
    }
}

@Composable
private fun GradeDetailPanel(grade: Grade?, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.54f),
        shape = RoundedCornerShape(22.dp),
    ) {
        if (grade == null) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "选择一门课程查看详情",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            GradeDetailContent(
                grade = grade,
                contentPadding = PaddingValues(22.dp),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 宽屏侧栏 / 弹层共用的成绩详情正文。 */
@Composable
private fun GradeDetailContent(
    grade: Grade,
    modifier: Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val detailScrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(detailScrollState)
            .desktopTouchScroll(detailScrollState)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GradeDetailSheetBody(grade = grade)
    }
}

@Composable
private fun GradeDetailSheetBody(
    grade: Grade,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "成绩详情",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            grade.displayCourseName(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        DetailLine("学期", grade.semester)
        DetailLine("教师", grade.courseTeacher.ifBlank { "未提供" })
        DetailLine("学分", grade.courseCredits)
        DetailLine("成绩", grade.courseScore)
        if (grade.detail.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.accessibleAlpha(0.45f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("组成与说明", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        formatGradeDetailForDisplay(grade.detail),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            Text(
                "教务系统未提供更多组成信息。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GradeFailureBanner(
    failure: GradeSyncFailure,
    hasContent: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppErrorBanner(
        message = when (failure) {
            GradeSyncFailure.NETWORK -> if (hasContent) {
                "同步失败，正在显示本地缓存。"
            } else {
                "无法连接教务系统，请检查网络后重试。"
            }
            GradeSyncFailure.SESSION_EXPIRED -> "教务会话已失效，请点击右上角刷新重试登录。"
            GradeSyncFailure.MALFORMED_RESPONSE -> "教务成绩页面结构已变化，暂时无法解析。"
            GradeSyncFailure.CACHE -> "本地成绩缓存操作失败，当前选择可能未保存。"
        },
        onRetry = if (failure != GradeSyncFailure.CACHE) onRetry else null,
        onDismiss = onDismiss,
    )
}

@Composable
private fun GradeLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("正在读取本地成绩并连接教务系统…")
        }
    }
}

@Composable
private fun GradeEmptyState(onRefresh: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("暂无成绩", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "本地没有缓存，教务系统也没有返回可显示的成绩。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRefresh) { Text("重新同步") }
        }
    }
}

@Composable
private fun gradeScoreColor(numeric: Int): Color = when {
    numeric in 60..100 -> MaterialTheme.colorScheme.primary
    numeric in 0..59 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun GradeChangeNoticeDialog(
    changes: List<HomeChangeRecord>,
    onDismiss: () -> Unit,
    onOpenGrades: () -> Unit,
) {
    val visible = changes.filterNot {
        it.kind == DataChangeKind.MODIFIED && it.beforeDetail == it.afterDetail
    }
    if (visible.isEmpty()) return
    val changeScrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("成绩变动") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(changeScrollState)
                    .desktopTouchScroll(changeScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visible.forEach { change ->
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
                                when (change.kind) {
                                    DataChangeKind.ADDED -> "新增"
                                    DataChangeKind.MODIFIED -> "修改"
                                    DataChangeKind.DELETED -> "删除"
                                },
                                color = when (change.kind) {
                                    DataChangeKind.ADDED -> MaterialTheme.colorScheme.primary
                                    DataChangeKind.MODIFIED -> MaterialTheme.colorScheme.tertiary
                                    DataChangeKind.DELETED -> MaterialTheme.colorScheme.error
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                change.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
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
            }
        },
        confirmButton = { Button(onClick = onOpenGrades) { Text("前往成绩") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

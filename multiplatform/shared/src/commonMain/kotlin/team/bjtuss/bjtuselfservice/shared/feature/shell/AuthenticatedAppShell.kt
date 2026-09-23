package team.bjtuss.bjtuselfservice.shared.feature.shell

import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeChangeNoticeDialog
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeContentSource
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeWorkspace


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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseCompactViewMode
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
 * 物理在线的列表和首页日程共用同一个模型，因此自动同步只能由一个稳定的宿主启动。
 *
 * 旧的共享壳只有一个 Compose 实例，`forcedRouteId == null` 就足够了。iOS Liquid
 * 壳则为每个一级 Tab 建一个宿主；它们都会执行这个文件里的副作用，如果只判断
 * `forcedRouteId != null`，首页也会被排除，物理在线便只会在用户点进页面后才同步。
 */
internal fun shouldStartPhyVlabAutoSync(
    forcedRouteId: String?,
    nativeTabBarEnabled: Boolean,
): Boolean = forcedRouteId == null ||
    (nativeTabBarEnabled && forcedRouteId == AppSection.HOME.name)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatedAppShell(
    session: team.bjtuss.bjtuselfservice.shared.AuthenticatedSession,
    platform: PlatformInfo,
    windowClass: WindowClass,
    appCommandBus: AppCommandBus? = null,
    nativeNavigationEnabled: Boolean = false,
    /** 本实例作为宿主原生 tab 的根页面：底部导航条由系统容器提供，Compose 不再自绘。 */
    nativeTabBarEnabled: Boolean = false,
    /** 二级页标题与返回按钮交给宿主导航栏，Compose 顶栏只保留仍有页面动作的一行。 */
    useNativeTitleBar: Boolean = false,
    onOpenNativeRoute: (String) -> Unit = {},
    /** 目的地内部请求切换一级入口（原生 tab 根页面之间的跳转）。 */
    onSelectNativeTab: (String) -> Unit = {},
    /** 向宿主发布当前目的地标题；宿主据此渲染系统导航栏标题。 */
    onNativeTitleChanged: (String) -> Unit = {},
    /** 向宿主发布导航栏右侧动作；传 null 表示本页没有宿主动作，宿主应撤掉按钮。 */
    onNativeActionChanged: (NativeBarAction?) -> Unit = {},
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
    val mailboxUnread by mailboxModel.unreadSummary.collectAsState()
    val mailboxMessageLoading = (mailboxState as? MailboxUiState.Ready)?.isMessageLoading == true
    val phyVlabState by phyVlabModel.state.collectAsState()
    val homeState by homeModel.state.collectAsState()
    val settingsState by settingsModel.state.collectAsState()
    val phyVlabEnabled = settingsState.preferences.isPhyVlabEnabled
    val compactBottomNavSections = remember(phyVlabEnabled) {
        bottomNavSections(phyVlabEnabled)
    }
    if (nativeTabBarEnabled) {
        // 底栏由 UIKit 装配，Compose 侧的入口集合变化必须显式回推给宿主，否则切换「物理在线」底栏不动。
        LaunchedEffect(compactBottomNavSections) {
            session.onNativeTabItemsChanged?.invoke(nativeTabItems(session).map { it.routeId })
        }
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
    val compactBottomBarOverlayPadding = if (windowClass == WindowClass.Expanded) {
        0.dp
    } else if (nativeTabBarEnabled) {
        // 底栏换成系统玻璃 TabBar：宿主是全出血的，UIKit 不会把 tab bar 算进 navigationBars
        // （那里只剩 home indicator），真实高度由宿主写进 session。宿主还没回报时退回安全区，
        // 最坏情况是尾部留白偏小，不会崩。
        maxOf(
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            session.glassTabBarBottomInsetDp.dp,
        )
    } else {
        80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    }
    // 宽屏（平板横屏/全屏、macOS）：二级页留在壳内，侧栏固定、右侧换内容，对齐桌面分屏。
    // 仅紧凑/中等窗口才走原生二级 Activity/UIViewController（手机式全屏 push）。
    val useNativeSecondaryRoutes =
        nativeNavigationEnabled && windowClass != WindowClass.Expanded
    // 本实例是「被宿主压入的页面」而不是玻璃 tab 根：两者都带 forcedRouteId，
    // 但只有压入页需要返回入口，也不该再为一条不存在的底栏预留高度。
    val isPushedHostDestination = forcedRouteId != null && !nativeTabBarEnabled

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
                if (
                    forcedRouteId != null &&
                        isNativeTabRoute(forcedRouteId) &&
                        isNativeTabRoute(target.name)
                ) {
                    // 原生 tab 根页面之间互跳：交给宿主切换 tab，本 Compose 栈只保留自己的根，
                    // 否则会出现「内容是成绩、高亮仍是首页」的壳层错位。
                    onSelectNativeTab(target.name)
                } else if (shouldOpenNativeSectionRoute(target.name, useNativeSecondaryRoutes)) {
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
                probeSession = session.probeSession,
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
                        sessionRefresh.run(
                            operation = { mailboxModel.refreshUnreadInboxCount() },
                            sessionExpired = {
                                when (val current = mailboxModel.state.value) {
                                    MailboxUiState.SessionUnavailable -> true
                                    is MailboxUiState.Ready -> current.failure == MailboxFailure.SESSION_EXPIRED
                                    else -> false
                                }
                            },
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
        val sessionRefresh = SessionRefreshCoordinator(
            reauthenticate = reauthenticateSession,
            probeSession = session.probeSession,
            onRecoveryStateChanged = { sessionRecoveryInProgress = it },
        )
        if (loginSyncPreferences.autoSyncGrades) {
            sessionRefresh.run(
                operation = { gradeModel.initialize(refreshFromNetwork = true) },
                sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
            )
        } else {
            gradeModel.initialize(refreshFromNetwork = false)
        }
        if (loginSyncPreferences.autoSyncGrades && gradeModel.state.value.failure != null) {
            delay(LOGIN_SYNC_RETRY_DELAY_MILLIS)
            sessionRefresh.run(
                operation = { gradeModel.refresh() },
                sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
            )
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
        val sessionRefresh = SessionRefreshCoordinator(
            reauthenticate = reauthenticateSession,
            probeSession = session.probeSession,
            onRecoveryStateChanged = { sessionRecoveryInProgress = it },
        )
        coroutineScope {
            launch {
                // 作业自动同步的失败重试在 ScreenModel 内（最多 3 次），与课表一致。
                if (loginSyncPreferences.autoSyncHomework) {
                    sessionRefresh.run(
                        operation = { homeworkModel.initialize(refreshFromNetwork = true) },
                        sessionExpired = {
                            homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED
                        },
                    )
                } else {
                    homeworkModel.initialize(refreshFromNetwork = false)
                }
            }
            launch {
                if (loginSyncPreferences.autoSyncExams) {
                    sessionRefresh.run(
                        operation = { examScheduleModel.initialize(refreshFromNetwork = true) },
                        sessionExpired = {
                            examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED
                        },
                    )
                } else {
                    examScheduleModel.initialize(refreshFromNetwork = false)
                }
                if (loginSyncPreferences.autoSyncExams && examScheduleModel.state.value.failure != null) {
                    delay(LOGIN_SYNC_RETRY_DELAY_MILLIS)
                    sessionRefresh.run(
                        operation = { examScheduleModel.refresh() },
                        sessionExpired = {
                            examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED
                        },
                    )
                }
            }
            launch {
                // 课表：登录成功后才网络同步；失败重试在 ScreenModel 内（最多 3 次）。
                // 先灌入缓存，再加载校历校准当前周，最后才允许网络快照进入 UI；
                // 否则 getTimeList/room_view 短暂返回第 1 周时会覆盖缓存的第 26 周。
                courseScheduleModel.initialize(refreshFromNetwork = false)
                // M12 校历映射独立于“自动同步课表”偏好，但同样必须等登录完成后再取学期。
                sessionRefresh.run(
                    operation = { courseScheduleModel.ensureCalendarLoaded() },
                    // 校历加载失败本身是静默降级；业务刷新随后会给出明确状态。
                    sessionExpired = { false },
                )
                if (loginSyncPreferences.autoSyncSchedule) {
                    sessionRefresh.run(
                        operation = { courseScheduleModel.initialize(refreshFromNetwork = true) },
                        sessionExpired = {
                            courseScheduleModel.state.value.failure ==
                                CourseScheduleSyncFailure.SESSION_EXPIRED
                        },
                    )
                }
            }
        }
    }

    // 首页「新邮件」徽标：登录完成后探测一次收件箱实际未读数（Coremail 列表），
    // 之后由首页刷新和读信动作维护；探测失败时首页回退 MIS 聚合值。
    LaunchedEffect(mailboxModel, entryLoggingIn) {
        if (entryLoggingIn) return@LaunchedEffect
        mailboxModel.refreshUnreadInboxCount()
    }

    // 物理在线总开关关闭时不读取网络；打开后在当前登录会话中主动同步一次。
    // 这样开关同时控制“是否同步”和“是否显示底栏入口”，不会留下隐藏的后台请求。
    LaunchedEffect(phyVlabModel, phyVlabEnabled, entryLoggingIn, forcedRouteId, nativeTabBarEnabled) {
        // 原生作业详情页复用同一个模型，但不能在这里重新刷新整份课程数据；
        // refresh 成功会清空 selectedActivity，导致详情页退化成“未选择物理在线作业”。
        if (
            entryLoggingIn ||
            !phyVlabEnabled ||
            !shouldStartPhyVlabAutoSync(
                forcedRouteId = forcedRouteId,
                nativeTabBarEnabled = nativeTabBarEnabled,
            )
        ) return@LaunchedEffect
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
        /** 首页可先展示同步面板，再开始刷新；其它页沿用静默刷新。 */
        refreshAction: (() -> Unit)? = null,
        isRefreshing: Boolean,
        showBack: Boolean,
        modifier: Modifier,
        onBack: (() -> Unit)? = null,
        /** 该页返回动作不只是出栈（如写信需先取消草稿）：此时不交给宿主导航栏。 */
        ownsBackAction: Boolean = false,
        /** 空闲时右上角状态文案（如课表「已同步/未同步」）；登录中/同步中优先覆盖。 */
        idleStatusText: String? = null,
        /** 页面级动作，显示在同步状态胶囊旁（M12 课程表加入日历）。 */
        topBarAction: (@Composable () -> Unit)? = null,
        /**
         * 声明式的页面级动作（文字 + 回调）。与 [topBarAction] 的区别只在于：声明式的那份
         * 宿主能画进系统导航栏右侧，Compose lambda 那份画不了（邮箱顶栏是一组按钮）。
         */
        topBarActionLabel: String? = null,
        onTopBarActionClick: (() -> Unit)? = null,
        /** 状态胶囊点击动作；首页用于查看同步详情，失败时不在点击瞬间触发重试。 */
        onStatusClick: (() -> Unit)? = null,
        /** 未显式提供 [onStatusClick] 时，首页聚合同步失败可由此生成失败详情弹窗。 */
        syncFailureItems: List<String> = emptyList(),
        /**
         * 本页不吃「内容延伸进玻璃底栏」那条特例，改回真实布局内边距。
         *
         * 玻璃底栏浮在内容之上，列表靠尾部留白让开，这样玻璃才有东西可折射（见
         * [LocalBottomBarClearance]）。但课程表的「色块概览」是一张**不可纵向滚动**的全览表格，
         * 高度全靠 `weight()` 分配：尾部留白对它没有任何作用，表格会直接画到物理底边、
         * 最后一节课被玻璃条盖住。这类页面要的恰恰是「整张表停在底栏上方」。
         */
        keepsBottomBarInset: Boolean = false,
        /**
         * 本页内容滚进原生导航栏后面（真原生模糊用）。
         *
         * 为 true 且原生标题栏接管时，不再用实心 Spacer 占位，改由滚动容器吃
         * [LocalTopBarClearance] 顶边距：列表从屏幕顶开始画、首项靠顶边距让开，
         * 滚起来后内容穿进栏后，原生玻璃才有东西可折射。默认 false：未迁移的页面
         * 保持原来的 Spacer 布局，零回归。
         */
        scrollUnderTopBar: Boolean = false,
        /**
         * 本页不吃「内容伸进原生栏」那条特例，改回真实布局内边距（顶栏版 keepsBottomBarInset）。
         *
         * 课程表「色块概览」这类**不可纵向滚动**的全览表格用它：整张表停在栏下方，
         * 另加 8.dp 呼吸（与底栏镜像）。
         */
        keepsTopBarInset: Boolean = false,
        /**
         * 原生栏不随滚动变化（无透明 ↔ 玻璃过渡），栏保持顶部时的纯色样子。
         *
         * 给视口到不了栏后的页面用（课表/课件/教室详情/占用详情：固定头挡在列表上方，
         * 内容永远滚不进栏后，过渡只会凭空闪一下）。布局保持 Spacer 老样子，
         * 顶栏右侧同步状态照常进原生栏；与其他页互不影响，逐页开关。
         */
        staticTopBar: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val effectiveRefreshAction = refreshAction ?: refresh
        // 一级页为底栏预留高度；底栏本身在 NavDisplay 外层，不随 destination 销毁。
        val reserveBottomBarSpace = !expanded && !showBack && !isPushedHostDestination &&
            compactBottomBarOverlayPadding > 0.dp
        // 玻璃 TabBar 是浮在内容之上的系统控件：净空改由滚动内容的尾部留白承担（见
        // LocalBottomBarClearance），压在布局上会让列表停在玻璃条上沿、背后只剩纯色，
        // 玻璃就没有东西可折射。自绘底栏（Android 与 iOS 26 以下）仍按老语义占位。
        val glassScrollUnderBar =
            nativeTabBarEnabled && reserveBottomBarSpace && !keepsBottomBarInset
        Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(
                    bottom = if (reserveBottomBarSpace && !glassScrollUnderBar) {
                        // 全览表格要「停在底栏上方」，但宿主回报的净空正好等于玻璃条上沿：
                        // 表格边框会贴着玻璃被压住，留 8dp 呼吸，视觉上仍是完整一张表。
                        compactBottomBarOverlayPadding + if (keepsBottomBarInset) 8.dp else 0.dp
                    } else {
                        0.dp
                    },
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
                // 原生标题栏生效的两条路：被宿主 push 的二级页，以及玻璃壳的一级 tab 根页
                // （M17 之后一级页的紧凑原生标题与同步动作也交给系统导航栏）。
                // 返回动作不只是出栈的页（写信要先取消草稿）例外，它整条顶栏都留在页内。
                val nativeTitleBarActive =
                    useNativeTitleBar && !ownsBackAction && (showBack || nativeTabBarEnabled)
                // 刷新、同步状态、页面级动作现在都能进系统栏右侧：状态文案自己就是入口
                // （首页「同步失败」点开同步详情）也一并带过去，不再因为顶栏被撤掉而丢入口。
                // 只有 Compose 画的一堆按钮（邮箱那组）没法声明成原生项，出现时整行仍留在页内。
                val statusClickHandler = onStatusClick ?: failureStatusClick
                val hostBarTakesOver = nativeTitleBarActive && topBarAction == null
                val refreshHandledByHost = hostBarTakesOver && refreshable
                val statusHandledByHost = hostBarTakesOver && (idleStatusText != null || entryLoggingIn)
                val extraHandledByHost = hostBarTakesOver && topBarActionLabel != null
                val keepsComposeTopBar = topBarAction != null ||
                    (topBarActionLabel != null && !extraHandledByHost) ||
                    (idleStatusText != null && !statusHandledByHost) ||
                    (refreshable && !refreshHandledByHost) ||
                    (entryLoggingIn && !hostBarTakesOver)
                // 声明式的页面动作：宿主接手就画进系统栏，否则由 Compose 顶栏自己画同一份。
                val declarativeActionLabel = topBarActionLabel
                val declarativeActionClick = onTopBarActionClick
                val composeAction: (@Composable () -> Unit)? = when {
                    topBarAction != null -> topBarAction
                    declarativeActionLabel == null ||
                        declarativeActionClick == null ||
                        extraHandledByHost -> null
                    else -> {
                        {
                            TopBarCalendarAction(
                                label = declarativeActionLabel,
                                onClick = declarativeActionClick,
                            )
                        }
                    }
                }
                if (nativeTitleBarActive) {
                    SideEffect { onNativeTitleChanged(title) }
                }
                val nativeSyncBusy = isRefreshing || sessionRecoveryInProgress
                val topFadeHeight = 52.dp
                val topFadeHeightPx = with(LocalDensity.current) { topFadeHeight.toPx() }
                val topFadeActive = nativeTitleBarActive && !keepsComposeTopBar && !staticTopBar
                // 页面上报的真实滚动偏移（像素），见 LocalReportTopScroll。直接读列表状态，
                // fling/跳转/回顶都不会漂移；上报源只在 composition 里读它，重组开销与之前相当。
                // lambda 用 remember 稳住实例，避免每次重组都让消费方连带重组。
                val topScrollOffsetPx = remember { mutableFloatStateOf(0f) }
                val reportTopScroll: (Float) -> Unit = remember {
                    { offsetPx -> topScrollOffsetPx.floatValue = offsetPx }
                }
                // 真原生 underlap：接管 + opt-in 的页面跳过实心 Spacer，内容从屏幕顶开始画；
                // 顶边距取宿主实测栏高与状态栏二者的较大值，首帧（宿主未回报前）也不错位。
                // 非玻璃壳/未 opt-in 页面 clearance 恒 0，走原来的 Spacer/自绘顶栏，老样子。
                // keepsTopBarInset 的不可滚页面改回布局内边距（停在栏下 + 8.dp 呼吸）。
                val useTopUnderlap = topFadeActive && scrollUnderTopBar
                val measuredTopInset = maxOf(
                    WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    session.glassTopBarInsetDp.dp,
                )
                val topBarClearance = if (useTopUnderlap && !keepsTopBarInset) {
                    measuredTopInset
                } else {
                    0.dp
                }
                // Read the scroll state during composition so page-reported offsets reach
                // the UIKit navigation bar. Glass intensity follows real depth (0 at top,
                // 1 past the transition zone): it persists through the whole return journey
                // and clears exactly when content leaves the bar — no early fade.
                val nativeScrollProgress =
                    if (topFadeActive && scrollUnderTopBar && topFadeHeightPx > 0f) {
                        (topScrollOffsetPx.floatValue / topFadeHeightPx).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                SideEffect {
                    val nativeBarAction = when {
                        !hostBarTakesOver -> null
                        entryLoggingIn || refreshable || idleStatusText != null || topBarActionLabel != null ->
                            NativeBarAction(
                                status = when {
                                    entryLoggingIn -> "登录中"
                                    nativeSyncBusy -> "同步中"
                                    else -> idleStatusText ?: ""
                                },
                                canRefresh = refreshable && !entryLoggingIn,
                                busy = entryLoggingIn || nativeSyncBusy,
                                label = when {
                                    entryLoggingIn -> "登录中"
                                    nativeSyncBusy -> "刷新中"
                                    else -> "刷新"
                                },
                                onClick = effectiveRefreshAction,
                                onStatusClick = statusClickHandler,
                                extraLabel = topBarActionLabel,
                                onExtraClick = onTopBarActionClick,
                                scrollProgress = nativeScrollProgress,
                            )
                        else ->
                            // No visible bar item is needed, but UIKit still
                            // needs the edge state for a root such as More.
                            NativeBarAction(
                                status = "",
                                canRefresh = false,
                                busy = false,
                                label = "",
                                onClick = {},
                                scrollProgress = nativeScrollProgress,
                            )
                    }
                    onNativeActionChanged(nativeBarAction)
                }
                if (useTopUnderlap) {
                    // 内容自己从屏幕顶画起，首项靠 LocalTopBarClearance 让开；这里不占位。
                } else if (nativeTitleBarActive && !keepsComposeTopBar) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars),
                    )
                } else {
                    CompactAppTopBar(
                        title = if (nativeTitleBarActive) "" else title,
                        isRefreshing = isRefreshing || sessionRecoveryInProgress,
                        isLoggingIn = entryLoggingIn,
                        idleStatusText = idleStatusText,
                        action = composeAction,
                        // 可刷新页：右上角状态胶囊旁放刷新按钮；不再下拉刷新（保平台原生过滚）。
                        onRefresh = if (refreshable) effectiveRefreshAction else null,
                        onStatusClick = onStatusClick ?: failureStatusClick,
                        onBack = if (showBack && !nativeTitleBarActive) {
                            onBack ?: popBackStack
                        } else {
                            null
                        },
                    )
                }
                // Scroll progress comes from the page's own list state
                // (LocalReportTopScroll), not from gesture accumulation: flings, snaps
                // and programmatic jumps all land in the list state itself, so the glass
                // persists until content truly leaves the bar.
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().then(
                        if (useTopUnderlap && keepsTopBarInset) {
                            Modifier.padding(top = measuredTopInset + 8.dp)
                        } else {
                            Modifier
                        },
                    ),
                ) {
                    CompositionLocalProvider(
                        LocalBottomBarClearance provides
                            if (glassScrollUnderBar) compactBottomBarOverlayPadding else 0.dp,
                        LocalTopBarClearance provides topBarClearance,
                        LocalReportTopScroll provides reportTopScroll,
                    ) {
                        content()
                    }
                    // 同步进度条悬浮在内容顶部，不参与 Column 布局，避免其出现时
                    // 把下方内容挤矮一丝。
                    // When UIKit owns the native title bar it also owns the
                    // busy indicator. Keep the Compose progress line only for
                    // fallback/self-drawn top bars; otherwise it duplicates the
                    // native spinner and makes the page look like a KMP overlay.
                    if ((isRefreshing || sessionRecoveryInProgress) &&
                        !(nativeTitleBarActive && !keepsComposeTopBar)
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
                        )
                    }
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
                refreshAction = {
                    homeSyncDialogVisible = true
                    refresh()
                },
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
                scrollUnderTopBar = true,
            ) {
                HomeWorkspace(
                    model = homeModel,
                    platform = platform,
                    expanded = expanded,
                    mailboxUnread = mailboxUnread,
                    holdNetwork = entryLoggingIn,
                    homework = homeworkState.homework,
                    exams = examState.exams,
                    phyVlabEvents = if (phyVlabEnabled) phyVlabState.agendaEvents else emptyList(),
                    currentWeek = courseState.currentWeek,
                    academicWeeks = courseState.academicWeeks,
                    // 周数只在被校历确认后显示：确认前统一「日程加载中」，
                    // 拿到确切结果后一次显示最终值，不再出现中间值弹跳。
                    isWeekResolved = courseState.weekResolved,
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
                scrollUnderTopBar = true,
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
                // 「添加到日历」声明成文字 + 回调，好让系统导航栏能直接画它。
                topBarActionLabel =
                    if (systemCalendarGateway.isAvailable) "添加到日历" else "导出",
                onTopBarActionClick = { showCourseCalendarExport = true },
                // 色块概览是不可纵向滚动的全览表格，必须整张停在玻璃底栏上方；
                // 切到按日列表（可滚动）时又回到「延伸进底栏」的常态。
                keepsBottomBarInset = courseState.compactViewMode == CourseCompactViewMode.WEEK,
                // 顶栏 underlap 暂不启用：按日列表视口被固定的摘要/模式/日期三段头挡在栏下，
                // 内容到不了栏后；硬上只会把三段头顶进状态栏。等表头随滚 redesign 再议。
                // 且关闭滚动过渡：栏后永远是纯色，过渡只会凭空闪一下。
                staticTopBar = true,
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
                scrollUnderTopBar = true,
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
                scrollUnderTopBar = true,
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
                    onReauthenticate = reauthenticateSession,
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
                // 顶栏 underlap 暂不启用：引导行与课程标题行固定在列表上方，视口到不了栏后。
                // 且关闭滚动过渡：栏后永远是纯色，过渡只会凭空闪一下。
                staticTopBar = true,
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
                scrollUnderTopBar = true,
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
                scrollUnderTopBar = true,
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
                scrollUnderTopBar = true,
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
                scrollUnderTopBar = true,
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
                // 顶栏 underlap 暂不启用：搜索框与筛选芯片固定在列表上方，视口到不了栏后。
                // 且关闭滚动过渡：栏后永远是纯色，过渡只会凭空闪一下。
                staticTopBar = true,
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
                // 顶栏 underlap 暂不启用：周/图例/星期三段筛选头固定在列表上方，视口到不了栏后。
                // 且关闭滚动过渡：栏后永远是纯色，过渡只会凭空闪一下。
                staticTopBar = true,
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
                scrollUnderTopBar = true,
            ) {
                HomeworkDetailWorkspace(
                    model = homeworkModel,
                    fileGateway = homeworkFileGateway,
                    onReauthenticate = reauthenticateSession,
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
                scrollUnderTopBar = true,
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
                // 邮箱右上角只有一个可执行的列表刷新圆钮：不要再传 idleStatusText，
                // 否则状态圆圈也会画一个刷新 glyph，和刷新圆钮重复成两个。
                // 同步中时顶栏显示 KMP 风格「同步中」胶囊。
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
                scrollUnderTopBar = true,
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
                ownsBackAction = true,
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
                // 物理在线与首页/课表/作业平级，是完整底栏里的一级页；只有真正被
                // 非底栏路由 push 进来时才显示系统返回按钮。
                showBack = isPushedHostDestination,
                modifier = modifier,
                scrollUnderTopBar = true,
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
                    onOpenCourse = { url -> onOpenExternalUrl(upgradePhyVlabUrlToHttps(url)) },
                    onOpenActivity = { url -> onOpenExternalUrl(upgradePhyVlabUrlToHttps(url)) },
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
                scrollUnderTopBar = true,
            ) {
                PhyVlabDetailWorkspace(
                    model = phyVlabModel,
                    fileGateway = homeworkFileGateway,
                    onRetry = retryPhyVlabDetail,
                    onOpenActivity = { url -> onOpenExternalUrl(upgradePhyVlabUrlToHttps(url)) },
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
                scrollUnderTopBar = true,
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
                showPhyVlab = phyVlabEnabled,
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

        // 紧凑端一级 tab 显示底栏；更多子页与详情路由隐藏。底栏在 NavDisplay 外，tab 切换不重建。
        val showsCompactBottomBar = !nativeTabBarEnabled && currentRoute in compactBottomNavSections

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

internal fun upgradePhyVlabUrlToHttps(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) "https://${url.substring(7)}" else url

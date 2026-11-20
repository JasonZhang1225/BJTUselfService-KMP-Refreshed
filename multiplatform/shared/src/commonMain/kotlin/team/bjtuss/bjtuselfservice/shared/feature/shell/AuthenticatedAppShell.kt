package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.WindowClass
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.webview.openExternalUrl
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommand
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommandBus
import team.bjtuss.bjtuselfservice.shared.files.FileGateway
import team.bjtuss.bjtuselfservice.shared.files.DirectoryGateway

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
    fileGatewayOverride: FileGateway? = null,
    directoryGatewayOverride: DirectoryGateway? = null,
    nativeHostState: NativeHostState? = null,
    onNativeTabItemsChanged: (List<String>) -> Unit = {},
) {
    val hostState = nativeHostState ?: remember { NativeHostState() }
    val settingsModel = session.settingsModel
    val settingsState by settingsModel.state.collectAsState()
    val entryLoggingIn = session.entryLoggingIn
    var sessionRecoveryInProgress by remember(session) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = rememberShellViewState(session)

    // 导航、刷新、账号任务、页面数据彼此解耦；壳层只把装配结果交给布局。
    val navigator = rememberShellNavigator(
        platform, scope, forcedRouteId, onOpenNativeRoute, onSelectNativeTab, onCloseNativeRoute,
        nativeNavigationEnabled, nativeTabBarEnabled, windowClass,
    )
    val refresher = remember(session, scope) {
        ShellRefresher(session, scope) { sessionRecoveryInProgress = it }
    }
    val compactBottomBarOverlayPadding = if (nativeTabBarEnabled) {
        // 底栏换成系统玻璃 TabBar，真实高度由宿主在布局时上报。
        maxOf(
            WindowInsets.bottomSystemBars.asPaddingValues().calculateBottomPadding(),
            hostState.glassTabBarBottomInsetDp.dp,
        )
    } else if (windowClass == WindowClass.Expanded) {
        0.dp
    } else {
        80.dp + WindowInsets.bottomSystemBars.asPaddingValues().calculateBottomPadding()
    }
    val data = rememberShellData(session, sessionRecoveryInProgress)
    val latestData by rememberUpdatedState(data)
    val actions = remember(navigator, refresher, view) {
        ShellActions(session, navigator, scope, refresher, view = view) {
            refresher.refresh(navigator.sectionFor(navigator.currentRoute), navigator.currentRoute, latestData)
        }
    }
    val fileGateway = fileGatewayOverride ?: session.fileGateway
    val directoryGateway = directoryGatewayOverride ?: session.directoryGateway
    val chrome = ShellChrome(
        entryLoggingIn = entryLoggingIn,
        sessionRecoveryInProgress = sessionRecoveryInProgress,
        compactBottomBarOverlayPadding = compactBottomBarOverlayPadding,
        nativeNavigationEnabled = nativeNavigationEnabled,
        useNativeTitleBar = useNativeTitleBar,
        popBackStack = navigator::pop,
        onNativeTitleChanged = onNativeTitleChanged,
        onNativeActionChanged = onNativeActionChanged,
        onOpenExternalUrl = onOpenExternalUrl,
        fileGateway = fileGateway,
        directoryGateway = directoryGateway,
        nativeHostState = hostState,
        systemCalendarGateway = session.systemCalendarGateway,
        assignmentCompletionStore = session.assignmentCompletionStore,
    )
    val ui = ShellUi(
        session = session,
        platform = platform,
        navigator = navigator,
        data = data,
        actions = actions,
        view = view,
        chrome = chrome,
        scope = scope,
    )

    val compactBottomNavSections = remember(settingsState.preferences) {
        bottomNavSections(settingsState.preferences)
    }
    val tabItemsChanged by rememberUpdatedState(onNativeTabItemsChanged)
    if (nativeTabBarEnabled) {
        // 底栏由 UIKit 装配，Compose 侧的入口集合变化必须显式回推给宿主，否则切换「物理在线」底栏不动。
        LaunchedEffect(compactBottomNavSections) {
            tabItemsChanged(nativeTabItems(session).map { it.routeId })
        }
    }
    // 作业详情页（含聚合页跳到的物理在线/CITEL 详情）返回时保留搜索；离开作业相关页才关闭。
    val assignmentSearchRoutes = remember {
        setOf(AppSection.ASSIGNMENTS, AppSection.HOMEWORK, HomeworkDetailRoute, PhyVlabDetailRoute, CitelDetailRoute)
    }
    LaunchedEffect(navigator.currentRoute) {
        if (navigator.currentRoute !in assignmentSearchRoutes) view.assignmentSearch.close()
    }
    LaunchedEffect(appCommandBus) {
        appCommandBus?.commands?.collect { command ->
            when (command) {
                AppCommand.NAVIGATE_HOME -> actions.navigateToSection(AppSection.HOME)
                AppCommand.NAVIGATE_GRADES -> actions.navigateToSection(AppSection.GRADES)
                AppCommand.NAVIGATE_SCHEDULE -> actions.navigateToSection(AppSection.SCHEDULE)
                AppCommand.NAVIGATE_EXAMS -> actions.navigateToSection(AppSection.EXAMS)
                AppCommand.NAVIGATE_HOMEWORK -> actions.navigateToSection(AppSection.HOMEWORK)
                AppCommand.NAVIGATE_COURSEWARE -> actions.navigateToSection(AppSection.COURSEWARE)
                AppCommand.NAVIGATE_PLAYBACK -> actions.navigateToSection(AppSection.PLAYBACK)
                AppCommand.NAVIGATE_CLASSROOMS -> actions.navigateToSection(AppSection.CLASSROOMS)
                AppCommand.NAVIGATE_CLASSROOM_OCCUPANCY -> actions.navigateToSection(AppSection.CLASSROOM_OCCUPANCY)
                AppCommand.NAVIGATE_MAILBOX -> actions.navigateToSection(AppSection.MAILBOX)
                AppCommand.NAVIGATE_SETTINGS -> actions.navigateToSection(AppSection.SETTINGS)
                AppCommand.REFRESH_CURRENT -> actions.refresh()
            }
        }
    }

    // 账号级任务（启动同步、前台/空闲补刷、作业时钟）集中在 ShellAccountTasks；
    // 小组件发布集中在 ShellCourseWidgetPublisher。壳层只负责装配与布局。
    ShellAccountTasks(
        session, data, forcedRouteId, nativeTabBarEnabled, refresher, navigator.currentRoute,
    ) { sessionRecoveryInProgress = it }
    ShellCourseWidgetPublisher(session, data, forcedRouteId, nativeTabBarEnabled)

    CompositionLocalProvider(LocalShellUi provides ui) {
        ShellOverlays(ui, forcedRouteId)
        ShellLayout(ui)
    }
}

internal fun upgradePhyVlabUrlToHttps(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) "https://${url.substring(7)}" else url

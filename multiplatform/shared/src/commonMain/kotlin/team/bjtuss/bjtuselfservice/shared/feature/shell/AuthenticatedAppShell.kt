package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
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
    val preferences by remember(session) { settingsModel.state.map { it.preferences }.distinctUntilChanged() }
        .collectAsState(initial = settingsModel.state.value.preferences)
    val entryLoggingIn = session.entryLoggingIn
    val accountRecoveryInProgress by (session.refreshService?.recoveryInProgress
        ?: remember(session) { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val globalRecoveryInProgress by (session.refreshCoordinator?.recoveryInProgress
        ?: remember(session) { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val sessionRecoveryInProgress = accountRecoveryInProgress || globalRecoveryInProgress
    val scope = rememberCoroutineScope()
    val view = rememberShellViewState(session)

    val tabItemsChanged by androidx.compose.runtime.rememberUpdatedState(onNativeTabItemsChanged)

    // 导航栈与平台回调属于当前宿主；旋转/展开只更新环境，不重建返回栈。
    val navigator = rememberShellNavigator(
        platform, scope, forcedRouteId, onOpenNativeRoute, onSelectNativeTab, onCloseNativeRoute,
        nativeNavigationEnabled, nativeTabBarEnabled, windowClass,
    )
    val refresher = remember(session) { ShellRefresher(session) }
    val compactBottomBarOverlayPadding = if (nativeTabBarEnabled) {
        // 底栏换成系统玻璃 TabBar：宿主是全出血的，真实高度由宿主写进 hostState。
        maxOf(
            WindowInsets.bottomSystemBars.asPaddingValues().calculateBottomPadding(),
            hostState.glassTabBarBottomInsetDp.dp,
        )
    } else if (windowClass == WindowClass.Expanded) {
        0.dp
    } else {
        80.dp + WindowInsets.bottomSystemBars.asPaddingValues().calculateBottomPadding()
    }
    val actions = remember(navigator, refresher, view) {
        ShellActions(session, navigator, scope, refresher, view = view) {
            refresher.refresh(navigator.sectionFor(navigator.currentRoute), navigator.currentRoute)
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
        nativeHostState = hostState,
        popBackStack = navigator::pop,
        onNativeTitleChanged = onNativeTitleChanged,
        onNativeActionChanged = onNativeActionChanged,
        onOpenExternalUrl = onOpenExternalUrl,
        fileGateway = fileGateway,
        directoryGateway = directoryGateway,
        systemCalendarGateway = session.systemCalendarGateway,
        assignmentCompletionStore = session.assignmentCompletionStore,
    )
    val ui = ShellUi(
        session = session,
        platform = platform,
        navigator = navigator,
        actions = actions,
        view = view,
        chrome = chrome,
        scope = scope,
    )

    val compactBottomNavSections = remember(preferences) {
        bottomNavSections(preferences)
    }
    if (nativeTabBarEnabled) {
        // 底栏由 UIKit 装配，Compose 侧的入口集合变化必须显式回推给宿主，否则切换「物理在线」底栏不动。
        LaunchedEffect(session, compactBottomNavSections) {
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

    // Only navigation data crosses into the account; automatic jobs are started by AppRuntime.
    SideEffect {
        if (forcedRouteId == null) session.reportRootPage(navigator.currentRoute)
    }


    CompositionLocalProvider(LocalShellUi provides ui) {
        ShellOverlays(ui, forcedRouteId)
        ShellLayout(ui)
    }
}

internal fun upgradePhyVlabUrlToHttps(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) "https://${url.substring(7)}" else url

package team.bjtuss.bjtuselfservice.shared

import team.bjtuss.bjtuselfservice.shared.auth.LoginState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSViewController
import team.bjtuss.bjtuselfservice.shared.cache.createAppleCacheStore
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommandBus
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeSidebarBus
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeSidebarItem
import team.bjtuss.bjtuselfservice.shared.feature.shell.nativeSidebarItems
import team.bjtuss.bjtuselfservice.shared.security.createAppleAccountSecurityStore

/**
 * macOS 原生宿主入口。
 *
 * Swift 侧的 `macosApp` 用它拿到三样东西：
 * - 一个承载共享 Compose 界面的原生视图控制器；
 * - 原生侧栏的条目列表（会话就绪后即可读取）；
 * - 把侧栏选中项送回共享壳层的通道。
 *
 * 侧栏与工具栏由 SwiftUI 的 `NavigationSplitView` 画，Compose 只负责右侧内容区，
 * 与 iOS 端「UIKit 承载 Compose」的分工一致。
 *
 * ObjC 导出名显式写死，Swift 侧不必猜 Kotlin/Native 的默认拼接规则。
 */
@OptIn(ExperimentalForeignApi::class)
class MacosAppController {

    /** 侧栏选中项 → 共享壳层。Swift 侧调用 [selectSidebarItem]。 */
    private val sidebarBus: NativeSidebarBus = NativeSidebarBus()

    /** 桌面菜单与快捷键用的命令总线（刷新当前页、前往某页）。 */
    private val commandBus: AppCommandBus = AppCommandBus()

    private val accountSecurityStore = createAppleAccountSecurityStore(accessibleAfterFirstUnlock = false)
    private val cacheStoreHandle = createAppleCacheStore()

    private var session: AuthenticatedSession? = null
    private var titlebarInset = 0f

    fun updateTitlebarInset(points: Double) {
        titlebarInset = points.toFloat().coerceAtLeast(0f)
        session?.glassTopBarInsetDp = titlebarInset
    }

    /**
     * 侧栏条目。未登录时也返回默认功能目录：侧栏由宿主提供，
     * 不该在登录前整条空着（对齐 Mac App Store 的分类侧栏）。
     */
    fun sidebarItems(): List<NativeSidebarItem> =
        session?.let { nativeSidebarItems(it) }
            ?: nativeSidebarItems(team.bjtuss.bjtuselfservice.shared.cache.AppPreferences())

    /** 当前登录账号的显示名；未登录时为空串。 */
    fun accountName(): String = session?.profile?.name.orEmpty()

    /** 当前登录账号的学院；未登录时为空串。 */
    fun accountDepartment(): String = session?.profile?.department.orEmpty()

    /** 是否已登录。 */
    fun isSignedIn(): Boolean = session != null

    /** 原生侧栏选中某个一级入口（routeId 为 section 名）。 */
    fun selectSidebarItem(routeId: String): Boolean = sidebarBus.select(routeId)

    /** 桌面菜单「刷新当前页面」。 */
    fun refreshCurrentPage(): Boolean = commandBus.send(team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommand.REFRESH_CURRENT)

    /**
     * 创建承载共享界面的原生视图控制器。
     *
     * [onSectionChanged] 回报当前一级入口（原生侧栏据此高亮）；
     * [onSessionChanged] 回报登录态变化（未登录为 nil）。
     */
    fun createRootViewController(
        onSectionChanged: (String) -> Unit,
        onSessionChanged: (AuthenticatedSession?) -> Unit,
        onScrollProgress: (Double) -> Unit,
        presentation: team.bjtuss.bjtuselfservice.shared.feature.shell.NativeDesktopPresentation,
        onSheetChanged: (String, MacosSheetState?) -> Unit,
    ): NSViewController {
        val sheetPresenter = MacosSheetPresenter(onSheetChanged)
        val host = MacosComposeHost {
            androidx.compose.runtime.CompositionLocalProvider(
                team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeDesktopPresentation provides presentation,
                team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeSheetPresenter provides sheetPresenter,
            ) {
            App(
                accountSecurityStore = accountSecurityStore,
                cacheStoreHandle = cacheStoreHandle,
                appCommandBus = commandBus,
                nativeSheetPresenter = sheetPresenter,
                nativeSidebarEnabled = true,
                nativeSidebarBus = sidebarBus,
                onNativeSectionChanged = onSectionChanged,
                // 共享层用像素（Float）上报滚动偏移，这里转成 Double，
                // 避免 ObjC 导出成装箱的 Kotlin Float，Swift 侧要用指针接。
                onNativeScrollProgress = { offsetPx -> onScrollProgress(offsetPx.toDouble()) },
                onAuthenticatedSessionChanged = { updated ->
                    session = updated
                    updated?.glassTopBarInsetDp = titlebarInset
                    onSessionChanged(updated)
                },
            )
            }
        }
        return NSViewController().apply {
            view = host.view
        }
    }
}

/** Offline presentation harness, independent of account storage. */
@OptIn(ExperimentalForeignApi::class)
class MacosPresentationPreview {

    /** Real shared home workspace with exclusively synthetic in-memory sources. */
    fun createHomePreview(
        presentation: team.bjtuss.bjtuselfservice.shared.feature.shell.NativeDesktopPresentation,
        onSheetChanged: (String, MacosSheetState?) -> Unit,
        darkTheme: Boolean,
        scenario: String,
    ): NSViewController {
        val sheetPresenter = MacosSheetPresenter(onSheetChanged)
        val fixedNow = kotlinx.datetime.LocalDateTime(2026, 10, 19, 9, 15)
        val source = object : team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusRepository {
            private val value = team.bjtuss.bjtuselfservice.shared.domain.home.HomeStatus("3", "", "")
            override fun load() = value
            override suspend fun refresh(): team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusRefreshResult =
                if (scenario == "error") team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusRefreshResult.Failure(value, team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusFailure.NETWORK)
                else team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusRefreshResult.Success(value)
        }
        val model = team.bjtuss.bjtuselfservice.shared.feature.home.HomeScreenModel(source)
        val courses = if (scenario == "empty" || scenario == "loading") emptyList() else (0..7).map { index ->
            team.bjtuss.bjtuselfservice.shared.domain.course.Course(
                id = index, courseId = "preview-$index", courseName = if (index == 1) "数据可视化与信息表达（用于检查较长的课程名称在窄窗口下自动换行）" else "示例课程 ${index + 1}",
                courseTeacher = "示例教师", courseLocationIndex = (index % 6) * 8 + 1,
                courseTime = "第6-10周", coursePlace = "示例校区,示例楼,${index + 1}01", isCurrentSemester = false,
            )
        }
        val host = MacosComposeHost {
            androidx.compose.runtime.CompositionLocalProvider(
                team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeDesktopPresentation provides presentation,
                team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeSheetPresenter provides sheetPresenter,
                team.bjtuss.bjtuselfservice.shared.feature.home.LocalHomeSchedule provides team.bjtuss.bjtuselfservice.shared.feature.home.HomeSchedulePresentation(
                    courses = courses, currentWeek = 6, today = fixedNow.date,
                ),
            ) {
                androidx.compose.material3.MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors) {
                    var message by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                    val showMessage: (String) -> Unit = { message = it }
                    androidx.compose.runtime.SideEffect {
                        presentation.updateNavigation("首页", false, {})
                        presentation.updateAction(team.bjtuss.bjtuselfservice.shared.feature.shell.NativeBarAction(
                            status = if (scenario == "loading") "同步中" else if (scenario == "error") "部分同步失败" else "已同步",
                            canRefresh = true, busy = scenario == "loading", label = "刷新", onClick = { showMessage("刷新入口已响应；预览不会发送网络请求。") },
                        ))
                    }
                    team.bjtuss.bjtuselfservice.shared.feature.home.HomeWorkspace(
                        model, currentPlatform(), expanded = true,
                        libraryReminder = team.bjtuss.bjtuselfservice.shared.data.library.LibraryReminder(2, emptyList(), emptyList()),
                        onOpenLibrary = { showMessage("图书馆入口已响应。") },
                        homework = emptyList(), exams = emptyList(), currentWeek = 6,
                        isWeekResolved = scenario != "loading", now = fixedNow, timeZone = kotlinx.datetime.TimeZone.of("Asia/Shanghai"),
                        isAgendaLoading = scenario == "loading",
                        onOpenMailbox = { showMessage("邮箱入口已响应。") }, onOpenHomework = { showMessage("作业入口已响应。") },
                        onOpenExams = {}, changes = emptyList(), onClearAllChanges = {}, onClearChangeDomain = {}, onOpenChangeDomain = {}, realtimeClock = false,
                    )
                    if (message != null) {
                        team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheetOrAlert(
                            onDismissRequest = { message = null }, title = "离线首页预览", confirmLabel = "确认", onConfirm = { message = null },
                        ) { androidx.compose.material3.Text(message.orEmpty()) }
                    }
                }
            }
        }
        return NSViewController().apply { view = host.view }
    }

    /** Isolated UI fixture: no account/cache writes and no school requests. */
    fun createPresentationPreview(
        presentation: team.bjtuss.bjtuselfservice.shared.feature.shell.NativeDesktopPresentation,
        onSheetChanged: (String, MacosSheetState?) -> Unit,
        darkTheme: Boolean,
    ): NSViewController {
        val sheetPresenter = MacosSheetPresenter(onSheetChanged)
        val host = MacosComposeHost {
            androidx.compose.runtime.CompositionLocalProvider(
                team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeDesktopPresentation provides presentation,
                team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeSheetPresenter provides sheetPresenter,
            ) {
                androidx.compose.material3.MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors) {
                    var guideVisible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                    var username by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                    var password by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                    var remember by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var message by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                    LoginScreen(
                        platform = currentPlatform(), windowClass = WindowClass.Expanded,
                        state = LoginState.SignedOut, username = username, password = password,
                        canRememberCredentials = true, rememberCredentials = remember,
                        storageReady = true, storageMessage = message, automationMessage = null,
                        onUsernameChange = { username = it }, onPasswordChange = { password = it },
                        onRememberCredentialsChange = { remember = it },
                        onLogin = { message = "预览登录按钮已响应；未向学校发送请求。" },
                    )
                    if (message != null) {
                        team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheetOrAlert(
                            onDismissRequest = { message = null }, title = "预览登录",
                            confirmLabel = "确认", onConfirm = { message = null },
                        ) { androidx.compose.material3.Text(message.orEmpty()) }
                    }
                    if (guideVisible) {
                        team.bjtuss.bjtuselfservice.shared.feature.shell.Release186GuideDialog(
                            phyVlabDisabledOnUpgrade = false, saveFailed = false,
                            onConfirm = { guideVisible = false },
                        )
                    }
                }
            }
        }
        return NSViewController().apply { view = host.view }
    }

}

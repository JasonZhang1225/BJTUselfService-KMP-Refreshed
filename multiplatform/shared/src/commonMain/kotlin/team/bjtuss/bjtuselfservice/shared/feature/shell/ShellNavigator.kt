package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.WindowClass
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences

/** The stack belongs to this presentation; recomposition only rebinds its platform callbacks. */
@Composable
internal fun rememberShellNavigator(
    platform: PlatformInfo,
    scope: CoroutineScope,
    forcedRouteId: String?,
    onOpenNativeRoute: (String) -> Unit,
    onSelectNativeTab: (String) -> Unit,
    onCloseNativeRoute: () -> Unit,
    nativeNavigationEnabled: Boolean,
    nativeTabBarEnabled: Boolean,
    windowClass: WindowClass,
): ShellNavigator {
    val openRoute by rememberUpdatedState(onOpenNativeRoute)
    val selectTab by rememberUpdatedState(onSelectNativeTab)
    val closeRoute by rememberUpdatedState(onCloseNativeRoute)
    return remember(forcedRouteId) {
        ShellNavigator(platform, scope, forcedRouteId, { openRoute(it) }, { selectTab(it) }, { closeRoute() },
            nativeNavigationEnabled, nativeTabBarEnabled, windowClass)
    }.also {
        it.nativeNavigationEnabled = nativeNavigationEnabled
        it.windowClass = windowClass
    }
}

/**
 * 应用壳自己的返回栈与原生导航决策。
 *
 * 这里只回答“现在在哪、下一步去哪、这次跳转交给宿主还是栈”，不持有任何业务模型，
 * 也不启动同步。页面内容与账号任务分别由 [ShellDestination] 和 [team.bjtuss.bjtuselfservice.shared.runtime.AccountSyncTasks] 负责。
 */
internal class ShellNavigator(
    private val platform: PlatformInfo,
    private val scope: CoroutineScope,
    private val forcedRouteId: String?,
    private val onOpenNativeRoute: (String) -> Unit,
    private val onSelectNativeTab: (String) -> Unit,
    private val onCloseNativeRoute: () -> Unit,
    initialNativeNavigationEnabled: Boolean,
    val nativeTabBarEnabled: Boolean,
    initialWindowClass: WindowClass,
) {
    val hasForcedRoute: Boolean = forcedRouteId != null
    val initialRoute: AppRoute = forcedRouteId?.toAppRoute() ?: AppSection.HOME
    val backStack: SnapshotStateList<AppRoute> = mutableStateListOf(initialRoute)
    val currentRoute: AppRoute get() = backStack.last()

    /**
     * 窗口宽度与“是否由原生宿主承载导航”会随旋转/展开变化，但返回栈不能重建。
     * 壳层每次组合把最新环境写进来，路由判断只读当前值。
     */
    var nativeNavigationEnabled: Boolean = initialNativeNavigationEnabled
    var windowClass: WindowClass = initialWindowClass

    /** 宽屏（平板横屏/全屏、macOS）：二级页留在壳内；已由原生宿主持有的页面继续走宿主路由。 */
    val useNativeSecondaryRoutes: Boolean
        get() = usesNativeSecondaryRoutes(nativeNavigationEnabled, windowClass, hostedDestination = forcedRouteId != null)

    /** 本实例是被宿主压入的页面，而不是玻璃 tab 根。 */
    val isPushedHostDestination: Boolean = forcedRouteId != null && !nativeTabBarEnabled
    /** 路由 → 高亮的一级入口（详情页归属它的上级）。 */
    fun sectionFor(route: AppRoute): AppSection = when (route) {
        ClassroomDetailRoute -> AppSection.CLASSROOMS
        ClassroomOccupancyDetailRoute -> AppSection.CLASSROOM_OCCUPANCY
        HomeworkDetailRoute -> AppSection.HOMEWORK
        ExamDetailRoute -> AppSection.EXAMS
        PhyVlabDetailRoute -> AppSection.PHYVLAB
        CitelDetailRoute -> AppSection.CITEL
        PlaybackDetailRoute -> AppSection.PLAYBACK
        CitelSettingsRoute -> AppSection.CITEL
        MailboxDetailRoute -> AppSection.MAILBOX
        MailboxComposeRoute -> AppSection.MAILBOX
        PhysicsLabSettingsRoute -> AppSection.PHYSICS_LAB
        PhysicsDemoRoute -> AppSection.MORE
        PhysicsDemoSettingsRoute -> AppSection.SETTINGS
        PhyVlabSettingsRoute -> AppSection.SETTINGS
        AiAssistantSettingsRoute -> AppSection.MORE
        is AppSection -> route
    }

    fun pop() {
        if (forcedRouteId != null) {
            onCloseNativeRoute()
        } else if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    /** 二级页：原生壳交给宿主 push，普通壳压进自己的栈。 */
    fun pushOrOpen(route: AppRoute, routeId: String) {
        if (useNativeSecondaryRoutes) {
            onOpenNativeRoute(routeId)
        } else if (backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    fun openPhyVlabSettings() {
        pushOrOpen(PhyVlabSettingsRoute, PHYVLAB_SETTINGS_ROUTE_ID)
    }

    /** 直接请求宿主 push 一个二级页（详情/写信等，由页面先完成模型选择）。 */
    fun openNativeRoute(routeId: String) {
        onOpenNativeRoute(routeId)
    }

    /**
     * 系统返回与顶栏返回共用同一入口。邮箱详情/写信在宽屏留在页内时，
     * 返回应先收起内层状态，而不是直接弹出邮箱一级页。
     */
    fun back(
        mailboxBack: () -> Unit,
        mailboxInlineDetail: Boolean,
        mailboxInlineCompose: Boolean,
    ) {
        if (
            shouldHandleInlineMailboxBack(
                currentRouteIsMailbox = currentRoute == AppSection.MAILBOX,
                mailboxInlineDetail = mailboxInlineDetail,
                mailboxInlineCompose = mailboxInlineCompose,
            )
        ) {
            mailboxBack()
        } else {
            pop()
        }
    }

    /**
     * 一级入口切换。保持原顺序：先处理原生宿主跳转，再处理 tab 根互跳，
     * 最后才改本栈；壳内换页延迟一帧让底栏按压反馈先上屏。
     */
    fun navigateToSection(
        requested: AppSection,
        preferences: AppPreferences,
        compactSections: List<AppSection>,
    ) {
        val target = when {
            requested == AppSection.CLASSROOMS -> AppSection.CLASSROOM_OCCUPANCY
            preferences.aggregateAssignments &&
                requested in setOf(AppSection.HOMEWORK, AppSection.PHYVLAB, AppSection.CITEL) -> AppSection.ASSIGNMENTS
            else -> requested
        }
        if (target == AppSection.PHYVLAB && !preferences.isPhyVlabEnabled) return
        if (target == AppSection.PHYSICS_LAB && !preferences.isPhysicsLabEnabled) return
        if (target == AppSection.CITEL && !preferences.isCitelEnabled) return
        if (backStack.lastOrNull() == target) return

        if (platform.family == PlatformFamily.Android &&
            shouldOpenNativeSectionRoute(target.name, useNativeSecondaryRoutes, compactSections)
        ) {
            // Activity 跳转直接处理当前点击，不依赖壳层协程的存活或 yield 后的调度。
            onOpenNativeRoute(target.name)
            return
        }
        scope.launch {
            // 先 yield 一帧：让 NavigationBarItem 的 press/ripple 先上屏。
            yield()
            if (backStack.lastOrNull() == target) return@launch
            if (
                forcedRouteId != null &&
                forcedRouteId.toAppRoute() in compactSections &&
                target in compactSections
            ) {
                // 原生 tab 根页面之间互跳：交给宿主切换 tab，本栈只保留自己的根。
                onSelectNativeTab(target.name)
            } else if (shouldOpenNativeSectionRoute(target.name, useNativeSecondaryRoutes, compactSections)) {
                onOpenNativeRoute(target.name)
            } else if (target !in compactSections && target != AppSection.MORE) {
                // 「更多」子页：固定为 [更多, 子页]，返回一定回到更多目录。
                backStack.clear()
                backStack.add(AppSection.MORE)
                backStack.add(target)
            } else {
                // 一级底栏页（含「更多」根目录）：单层替换。
                backStack.clear()
                backStack.add(target)
            }
        }
    }
}

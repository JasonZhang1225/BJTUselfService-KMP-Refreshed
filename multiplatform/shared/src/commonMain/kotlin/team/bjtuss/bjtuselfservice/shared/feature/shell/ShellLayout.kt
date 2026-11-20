package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.getValue
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.LocalReduceMotion
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.WindowClass
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.usesLegacySmartTransportFor

@Composable
internal fun ShellLayout(ui: ShellUi) {
    val session = ui.session
    val profile = session.profile
    val platform = ui.platform
    val navigator = ui.navigator
    val backStack = navigator.backStack
    val currentRoute = navigator.currentRoute
    val section = navigator.sectionFor(currentRoute)
    val preferences by remember(session) { session.settingsModel.state.map { it.preferences }.distinctUntilChanged() }
        .collectAsState(initial = session.settingsModel.state.value.preferences)
    val compactBottomNavSections = remember(preferences) { bottomNavSections(preferences) }
    val phyVlabEnabled = preferences.isPhyVlabEnabled
    val compactBottomBarOverlayPadding = ui.compactBottomBarOverlayPadding
    val useNativeSecondaryRoutes = navigator.useNativeSecondaryRoutes
    val mailboxState = if (navigator.sectionFor(currentRoute) == AppSection.MAILBOX) {
        val state by session.mailboxModel.state.collectAsState()
        state
    } else null
    val mailboxReady = mailboxState as? team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUiState.Ready
    val mailboxInlineDetail = mailboxBackTarget(
        useNativeSecondaryRoutes = useNativeSecondaryRoutes,
        hasSelectedMessage = mailboxReady?.selectedMessage != null,
        isMessageLoading = mailboxReady?.isMessageLoading == true,
    ) == MailboxBackTarget.LIST
    val mailboxInlineCompose = !useNativeSecondaryRoutes &&
        (mailboxReady?.compose?.isLoading == true ||
            mailboxReady?.compose?.draft != null ||
            mailboxReady?.compose?.failure != null)
    val mailboxBack: () -> Unit = when {
        mailboxInlineCompose -> {
            { ui.scope.launch { session.mailboxModel.cancelCompose() } }
        }
        mailboxInlineDetail -> session.mailboxModel::clearSelectedMessage
        else -> navigator::pop
    }
    val handleBack: () -> Unit = { navigator.back(mailboxBack, mailboxInlineDetail, mailboxInlineCompose) }
    val navigateToSection: (AppSection) -> Unit = ui.actions::navigateToSection
    val nativeTabBarEnabled = navigator.nativeTabBarEnabled
    val nativeNavigationEnabled = navigator.nativeNavigationEnabled

    if (navigator.hasForcedRoute) {
        ShellDestination(
            ui = ui,
            route = currentRoute,
            expanded = false,
            modifier = Modifier.fillMaxSize(),
            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
        )
    } else if (navigator.windowClass == WindowClass.Expanded) {
        Row(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.topSystemBars).padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AppSidebar(
                profile = profile,
                section = section,
                showPhyVlab = phyVlabEnabled,
                sections = compactBottomNavSections,
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
                        ShellDestination(
                            ui = ui,
                            route = route,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<ClassroomDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = ClassroomDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<ClassroomOccupancyDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = ClassroomOccupancyDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<HomeworkDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = HomeworkDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<ExamDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = ExamDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<CitelSettingsRoute> {
                        ShellDestination(
                            ui = ui,
                            route = CitelSettingsRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<CitelDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = CitelDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<PlaybackDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = PlaybackDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<PhyVlabDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = PhyVlabDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<MailboxDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = MailboxDetailRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<MailboxComposeRoute> {
                        ShellDestination(
                            ui = ui,
                            route = MailboxComposeRoute,
                            expanded = true,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family),
                        )
                    }
                    entry<AiAssistantSettingsRoute> {
                        ShellDestination(ui, route = AiAssistantSettingsRoute, expanded = false, modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family))
                    }
                    entry<PhysicsDemoSettingsRoute> {
                        ShellDestination(ui, route = PhysicsDemoSettingsRoute, expanded = false, modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family))
                    }
                    entry<PhysicsDemoRoute> {
                        ShellDestination(ui, route = PhysicsDemoRoute, expanded = false, modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family))
                    }
                    entry<PhyVlabSettingsRoute> {
                        ShellDestination(ui, route = PhyVlabSettingsRoute, expanded = false, modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family))
                    }
                    entry<PhysicsLabSettingsRoute> {
                        ShellDestination(
                            ui = ui,
                            route = PhysicsLabSettingsRoute,
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
                        ShellDestination(
                            ui = ui,
                            route = route,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<ClassroomDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = ClassroomDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<ClassroomOccupancyDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = ClassroomOccupancyDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<HomeworkDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = HomeworkDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<ExamDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = ExamDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<CitelSettingsRoute> {
                        ShellDestination(
                            ui = ui,
                            route = CitelSettingsRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<CitelDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = CitelDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<PlaybackDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = PlaybackDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<PhyVlabDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = PhyVlabDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<MailboxDetailRoute> {
                        ShellDestination(
                            ui = ui,
                            route = MailboxDetailRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<MailboxComposeRoute> {
                        ShellDestination(
                            ui = ui,
                            route = MailboxComposeRoute,
                            expanded = false,
                            modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = false,
                        )
                    }
                    entry<AiAssistantSettingsRoute> {
                        ShellDestination(ui, route = AiAssistantSettingsRoute, expanded = false, modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family))
                    }
                    entry<PhysicsDemoSettingsRoute> {
                        ShellDestination(ui, route = PhysicsDemoSettingsRoute, expanded = false, modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family))
                    }
                    entry<PhysicsDemoRoute> {
                        ShellDestination(ui, route = PhysicsDemoRoute, expanded = false, modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family))
                    }
                    entry<PhyVlabSettingsRoute> {
                        ShellDestination(ui, route = PhyVlabSettingsRoute, expanded = false, modifier = Modifier.fillMaxSize(),
                            usesLegacySmartTransport = usesLegacySmartTransportFor(platform.family))
                    }
                    entry<PhysicsLabSettingsRoute> {
                        ShellDestination(
                            ui = ui,
                            route = PhysicsLabSettingsRoute,
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

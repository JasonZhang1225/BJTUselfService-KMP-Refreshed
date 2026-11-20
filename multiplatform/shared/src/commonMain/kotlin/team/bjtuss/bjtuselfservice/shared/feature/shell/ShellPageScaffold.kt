package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import team.bjtuss.bjtuselfservice.shared.LocalReduceMotion
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*

@Composable
internal fun DestinationPage(
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
     * 宿主能画进系统导航栏右侧，Compose lambda 那份画不了。
     */
    topBarActionLabel: String? = null,
    onTopBarActionClick: (() -> Unit)? = null,
    /**
     * 页内搜索层。iOS 原生栏交给 UISearchController；Compose 顶栏则放搜索按钮，
     * 打开后整条顶栏换成 Material 搜索视图的输入框。
     */
    search: PageSearchState? = null,
    searchPrompt: String = "搜索",
    /** 状态胶囊点击动作；首页用于查看同步详情，失败时不在点击瞬间触发重试。 */
    onStatusClick: (() -> Unit)? = null,
    /** 未显式提供 [onStatusClick] 时，首页聚合同步失败可由此生成失败详情弹窗。 */
    syncFailureItems: List<String> = emptyList(),
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
     * 本页不吃「内容伸进原生栏」那条特例，改回真实布局内边距。
     *
     * 课程表「色块概览」这类**不可纵向滚动**的全览表格用它：整张表停在栏下方，
     * 另加 8.dp 呼吸（与底栏镜像）。
     */
    keepsTopBarInset: Boolean = false,
    /** 只压矮本页自绘标题栏，不改其它页。 */
    denseTopBar: Boolean = false,
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
    val ui = LocalShellUi.current
    val session = ui.session
    val hostState = ui.chrome.nativeHostState
    val platform = ui.platform
    val entryLoggingIn = ui.entryLoggingIn
    val sessionRecoveryInProgress = ui.sessionRecoveryInProgress
    val compactBottomBarOverlayPadding = ui.compactBottomBarOverlayPadding
    val isPushedHostDestination = ui.navigator.isPushedHostDestination
    val useNativeTitleBar = ui.chrome.useNativeTitleBar
    val nativeTabBarEnabled = ui.navigator.nativeTabBarEnabled
    val popBackStack: () -> Unit = ui.chrome.popBackStack
    val onNativeTitleChanged = ui.chrome.onNativeTitleChanged
    val onNativeActionChanged = ui.chrome.onNativeActionChanged
    var partialSyncFailureDialogItems by ui.view.partialSyncFailureDialogItems
    val assignmentCompletionStore = ui.chrome.assignmentCompletionStore
    val staggeredClassTimes by remember(ui.session) {
        ui.session.settingsModel.state.map { it.preferences.staggeredSecondSlot }.distinctUntilChanged()
    }.collectAsState(initial = ui.session.settingsModel.state.value.preferences.staggeredSecondSlot)
    val refresh: () -> Unit = ui.actions::refresh
    val effectiveRefreshAction = refreshAction ?: refresh
    // iOS 内容视口延伸到屏幕底，底部净空仅交给滚动内容末尾消费。
    // 宿主上报的是整个 tab bar frame 的高度，已经包含其底部安全区，不重复叠加。
    val reserveBottomBarSpace = !hostState.systemManagedContentBounds && !expanded && !showBack && !isPushedHostDestination
    val systemBottomInset = maxOf(
        WindowInsets.bottomSystemBars.asPaddingValues().calculateBottomPadding(),
        hostState.systemBottomInsetDp.dp,
    )
    val bottomContentClearance = destinationBottomClearance(
        expanded = expanded,
        hasBottomBar = reserveBottomBarSpace,
        barInset = compactBottomBarOverlayPadding,
        systemInset = systemBottomInset,
        contentGap = if (platform.family == PlatformFamily.Android) 0.dp else 8.dp,
    )
    // Android secondary pages draw behind the transparent gesture area;
    // their scroll content still consumes the system inset at its tail.
    val usesBottomUnderlap = platform.family == PlatformFamily.IOS ||
        (platform.family == PlatformFamily.Android && !reserveBottomBarSpace)
    val bottomLayoutInset = if (usesBottomUnderlap) 0.dp else bottomContentClearance
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = bottomLayoutInset),
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
            // 只有仍由 Compose 绘制的自定义操作组没法声明成原生项，出现时整行仍留在页内。
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
            val topBarTransitionHeight = 52.dp
            val topBarTransitionHeightPx = with(LocalDensity.current) { topBarTransitionHeight.toPx() }
            val topScrollTrackingActive = nativeTitleBarActive && !keepsComposeTopBar && !staticTopBar
            // 页面上报的真实滚动偏移（像素），见 LocalReportTopScroll。直接读列表状态，
            // fling/跳转/回顶都不会漂移；上报源只在 composition 里读它，重组开销与之前相当。
            // lambda 用 remember 稳住实例，避免每次重组都让消费方连带重组。
            val topScrollOffsetPx = remember(title) { mutableFloatStateOf(0f) }
            val reportTopScroll: (Float) -> Unit = remember(title) {
                { offsetPx -> topScrollOffsetPx.floatValue = offsetPx }
            }
            // 真原生 underlap：接管 + opt-in 的页面跳过实心 Spacer，内容从屏幕顶开始画；
            // 顶边距取宿主实测栏高与状态栏二者的较大值，首帧（宿主未回报前）也不错位。
            // 非玻璃壳/未 opt-in 页面 clearance 恒 0，走原来的 Spacer/自绘顶栏，老样子。
            // keepsTopBarInset 的不可滚页面改回布局内边距（停在栏下 + 8.dp 呼吸）。
            val useTopUnderlap = topScrollTrackingActive && scrollUnderTopBar
            val measuredTopInset = if (hostState.systemManagedContentBounds) hostState.glassTopBarInsetDp.dp else maxOf(
                WindowInsets.topSystemBars.asPaddingValues().calculateTopPadding(),
                hostState.glassTopBarInsetDp.dp,
            )
            val topBarClearance = if (useTopUnderlap && !keepsTopBarInset) {
                measuredTopInset
            } else {
                0.dp
            }
            // Read the Compose scroll state during composition so page-reported offsets
            // reach the fallback glass surface. Native-scrolling pages instead supply
            // a consumer for actual UIKit deltas; no position estimates cross that bridge.
            val nativeScrollProgress =
                if (topScrollTrackingActive && scrollUnderTopBar && topBarTransitionHeightPx > 0f) {
                    (topScrollOffsetPx.floatValue / topBarTransitionHeightPx).coerceIn(0f, 1f)
                } else {
                    0f
                }
            // Single primary scrolling pages share UIKit physics, including pushed
            // details. Static/split-panel pages retain their independent scrollers.
            val nativeScrollingEnabled = nativeTitleBarActive && hostBarTakesOver &&
                platform.family == PlatformFamily.IOS && hostState.systemManagedContentBounds &&
                useTopUnderlap && !keepsTopBarInset
            var nativeScrollController by remember(title) { mutableStateOf<NativeScrollController?>(null) }
            val reportNativeScrollController: (NativeScrollController?) -> Unit = remember(title) {
                { nativeScrollController = it }
            }
            // Read during composition so range/end updates invalidate this host.
            val reportedNativeScrollController = nativeScrollController.takeIf { nativeScrollingEnabled }
            SideEffect {
                val nativeBarAction = when {
                    !hostBarTakesOver -> null
                    entryLoggingIn || refreshable || idleStatusText != null || topBarActionLabel != null || search != null ->
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
                            scrollController = reportedNativeScrollController,
                            searchPrompt = searchPrompt.takeIf { search != null },
                            searchActive = search?.active == true,
                            onSearchOpen = search?.let { it::activate },
                            onSearchQueryChange = search?.let { it::update },
                            onSearchClose = search?.let { it::close },
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
                            scrollController = reportedNativeScrollController,
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
                        .height(measuredTopInset),
                )
            } else {
                // 搜索头替换整条顶栏。Material：顶栏淡出、搜索视图淡入并从 92% 放大（M3 fade through）；
                // iOS：搜索框自上方滑入，临界阻尼、无过冲。结果区由页面把列表项就地换掉。
                val searchOpen = search?.active == true
                val searchReduceMotion = LocalReduceMotion.current
                val appleSearchStyle = platform.family == PlatformFamily.IOS
                if (search != null) {
                    // Android 返回键 / 预测性返回先收起搜索，不直接退出本页。
                    NavigationBackHandler(
                        state = rememberNavigationEventState(NavigationEventInfo.None),
                        isBackEnabled = searchOpen,
                        onBackCompleted = { search.close() },
                    )
                }
                AnimatedContent(
                    targetState = searchOpen,
                    transitionSpec = {
                        when {
                            searchReduceMotion -> fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                            appleSearchStyle -> {
                                val slide = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                (slideInVertically(slide) { -it / 2 } + fadeIn(tween(220))) togetherWith
                                    (slideOutVertically(slide) { -it / 2 } + fadeOut(tween(160)))
                            }
                            else ->
                                (fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing)) +
                                    scaleIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing), initialScale = 0.92f)) togetherWith
                                    fadeOut(tween(90, easing = FastOutLinearInEasing))
                        }
                    },
                    label = "page-search-header",
                ) { showSearch ->
                if (showSearch && search != null) {
                    if (appleSearchStyle) {
                        AppleSearchHeader(state = search, prompt = searchPrompt)
                    } else {
                        MaterialSearchHeader(state = search, prompt = searchPrompt)
                    }
                } else CompactAppTopBar(
                    title = if (nativeTitleBarActive) "" else title,
                    isRefreshing = isRefreshing || sessionRecoveryInProgress,
                    isLoggingIn = entryLoggingIn,
                    idleStatusText = idleStatusText,
                    statusAsText = title == AppSection.CITEL.title || title == AppSection.ASSIGNMENTS.title,
                    dense = denseTopBar,
                    action = composeAction.takeUnless { declarativeActionLabel == "今" }
                        ?: search?.let { state -> { TopBarSearchCircle(onClick = state::activate) } },
                    leadingAction = composeAction.takeIf { declarativeActionLabel == "今" && platform.family == PlatformFamily.IOS },
                    titleAction = composeAction.takeIf { declarativeActionLabel == "今" && platform.family != PlatformFamily.IOS },
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
                    LocalBottomBarClearance provides if (usesBottomUnderlap) bottomContentClearance else 0.dp,
                    team.bjtuss.bjtuselfservice.shared.feature.assignment.LocalAssignmentCompletionStore provides session.assignmentCompletionStore,
                    LocalTopBarClearance provides topBarClearance,
                    LocalReportTopScroll provides reportTopScroll,
                    LocalNativeScrollControllerReporter provides reportNativeScrollController.takeIf { nativeScrollingEnabled },
                    team.bjtuss.bjtuselfservice.shared.feature.course.LocalStaggeredClassTimes provides staggeredClassTimes,
                ) {
                    content()
                }
                team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentIgnoreUndoBar(
                    session.assignmentCompletionStore,
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp + if (usesBottomUnderlap) bottomContentClearance else 0.dp),
                )
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

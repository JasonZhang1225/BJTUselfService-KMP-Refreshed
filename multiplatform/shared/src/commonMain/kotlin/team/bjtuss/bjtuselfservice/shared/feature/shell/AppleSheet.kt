package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.currentPlatform
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll

/**
 * A shared sheet whose iOS presentation is owned by UIKit.
 *
 * On iOS the composition is hosted inside a real
 * `UISheetPresentationController`; UIKit therefore supplies the system
 * rounded surface, grabber, detent transition, background material and
 * interactive dismissal. Compose's Material sheet is only the non-iOS
 * fallback (and remains useful in previews/tests without a host controller).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    needsFullHeight: Boolean = false,
    scrollableBody: Boolean = false,
    /**
     * iOS 按正文实际高度展开（自定义 detent）。各平台都由 sheet 负责滚动和底部安全区，
     * content 里不要再套 verticalScroll；非 iOS 的底部 sheet 本来就按内容高度展开。
     */
    fitContentHeight: Boolean = false,
    title: String? = null,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    todayLabel: String? = null,
    todayEnabled: Boolean = true,
    onToday: (() -> Unit)? = null,
    dismissLabel: String? = null,
    dismissEnabled: Boolean = true,
    showDismissButton: Boolean = true,
    /** false 时不能下滑、点外部或返回键关闭，只能点标题栏按钮（如必须确认的版本说明）。 */
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hostPresenter = LocalNativeSheetPresenter.current
    // 宿主可能同时拥有正在关闭的课程详情和刚打开的周数选择器。
    // 不能让旧组合的 onDispose 或原生关闭回调操作后一个弹层。
    val presenter = remember(hostPresenter) { hostPresenter?.createPresentation() }

    if (presenter != null) {
        // The UIKit sheet is a separate Compose root, so inherited MaterialTheme
        // locals do not cross the UIViewController boundary automatically. Capture
        // the current app scheme here; otherwise a dark-mode sheet falls back to
        // Compose's default light colors and looks unlike the system sheet.
        val appColorScheme = MaterialTheme.colorScheme
        val appTypography = MaterialTheme.typography
        val appShapes = MaterialTheme.shapes
        // iOS 默认按正文测高；只有正文自己滚动（懒加载列表、整页工作区）时才用全高 detent。
        val measuresContent = !scrollableBody
        val currentContent = @Composable {
            MaterialTheme(
                colorScheme = appColorScheme,
                typography = appTypography,
                shapes = appShapes,
            ) {
                // ComposeUIViewController is a separate root from the app shell.
                // MaterialTheme carries the palette, but it does not provide a
                // LocalContentColor by itself. Without this provider, Text with
                // an unspecified color falls back to black over the native dark
                // glass surface.
                CompositionLocalProvider(LocalContentColor provides appColorScheme.onSurface) {
                    val density = LocalDensity.current
                    val horizontalBounds = if (LocalNativeSheetContentBoundsHandled.current) Modifier else
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    if (measuresContent) {
                        CompositionLocalProvider(LocalSheetMeasuresContent provides true) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .then(horizontalBounds)
                                    .verticalScroll(rememberScrollState())
                                    // verticalScroll 会把 fillMaxHeight 的最小高度传给内容；不清掉的话
                                    // 量到的永远 ≥ 当前 sheet 高度，回报后 sheet 越长越高。
                                    .wrapContentHeight(Alignment.Top)
                                    // 底部安全区是滚动内容末尾的留白，不是视口外的边距：视口一直到 sheet 底边，
                                    // 内容可以滑到 home indicator 下面，不会在底边上方被截成一条。
                                    // 安全区不计入 detent（UIKit 另加），所以量高度时排除在外。
                                    .windowInsetsPadding(WindowInsets.bottomSystemBars)
                                    .onSizeChanged { size ->
                                        presenter.setFittedContentHeight(with(density) { size.height.toDp().value })
                                    }
                                    .padding(top = 8.dp),
                                content = content,
                            )
                        }
                        return@CompositionLocalProvider
                    }
                    // 正文自带懒加载列表或整页工作区：全高 detent，视口到 sheet 底边。底部安全区经
                    // LocalBottomBarClearance 交给正文放进滚动内容末尾（页面组件本来就消费它）。
                    val bottomInset = WindowInsets.bottomSystemBars.asPaddingValues().calculateBottomPadding()
                    CompositionLocalProvider(LocalBottomBarClearance provides bottomInset) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .then(horizontalBounds),
                            content = content,
                        )
                    }
                }
            }
        }
        SideEffect {
            presenter.setInteractiveDismissDisabled(!dismissible)
            presenter.setScrollableBody(scrollableBody)
            presenter.update(
                content = currentContent,
                onDismissRequest = onDismissRequest,
                title = title,
                confirmLabel = confirmLabel,
                confirmEnabled = confirmEnabled,
                onConfirm = onConfirm,
                todayLabel = todayLabel,
                todayEnabled = todayEnabled,
                onToday = onToday,
                dismissLabel = dismissLabel,
                dismissEnabled = dismissEnabled,
                showDismissButton = showDismissButton,
            )
        }
        LaunchedEffect(presenter, needsFullHeight) {
            presenter.present(needsFullHeight && !measuresContent)
        }
        DisposableEffect(presenter) {
            onDispose { presenter.dismiss() }
        }
        return
    }

    // Non-iOS fallback: keep the shared component usable on Android/desktop
    // and in previews without introducing a platform host dependency.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { dismissible || it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetGesturesEnabled = dismissible,
        containerColor = BottomSheetDefaults.ContainerColor,
        tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = { BottomSheetDefaults.DragHandle() },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (needsFullHeight) Modifier.fillMaxHeight() else Modifier),
            ) {
                if (title != null || confirmLabel != null || todayLabel != null ||
                    dismissLabel != null || showDismissButton
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        title?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (confirmLabel != null && onConfirm != null) {
                            TextButton(
                                onClick = onConfirm,
                                enabled = confirmEnabled,
                            ) { Text(confirmLabel) }
                        }
                        if (todayLabel != null && onToday != null) {
                            TextButton(
                                onClick = onToday,
                                enabled = todayEnabled,
                            ) { Text(todayLabel) }
                        }
                        if (dismissLabel != null) {
                            TextButton(
                                onClick = onDismissRequest,
                                enabled = dismissEnabled,
                            ) { Text(dismissLabel) }
                        } else if (showDismissButton) {
                            TextButton(onClick = onDismissRequest) { Text("关闭") }
                        }
                    }
                }
                // 与 iOS 全高 sheet 一致：自带滚动的正文经 LocalBottomBarClearance 把底部系统栏放进滚动末尾；
                // fitContentHeight 已由外层滚动加上，置 0 免得重复。
                val sheetBottomClearance = if (fitContentHeight) 0.dp else
                    WindowInsets.bottomSystemBars.asPaddingValues().calculateBottomPadding()
                CompositionLocalProvider(LocalBottomBarClearance provides sheetBottomClearance) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (needsFullHeight) Modifier.weight(1f) else Modifier)
                            // 与 iOS 同义：fitContentHeight 的 content 不自带滚动和底部安全区，由 sheet 提供。
                            .then(
                                if (fitContentHeight) {
                                    // Windows 触摸屏要靠 desktopTouchScroll 才能拖动滚动，原生滚动的平台上它不起作用。
                                    val fitScroll = rememberScrollState()
                                    Modifier
                                        .verticalScroll(fitScroll)
                                        .desktopTouchScroll(fitScroll)
                                        .windowInsetsPadding(WindowInsets.bottomSystemBars)
                                } else {
                                    Modifier
                                },
                            ),
                        content = content,
                    )
                }
            }
        },
    )
}

/** Bottom inset lives in the scrolling content so a long sheet does not leave a blank home-indicator strip. */
@Composable
internal fun Modifier.sheetScrollContentPadding(): Modifier {
    // 测高的 sheet 已由外层把安全区放进滚动内容末尾，这里不再重复。
    if (LocalSheetMeasuresContent.current) return padding(horizontal = 20.dp).padding(bottom = 16.dp)
    val bottomInset = WindowInsets.bottomSystemBars.asPaddingValues().calculateBottomPadding()
    return padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp + bottomInset)
}

/** 为 true 时外层 sheet 正在按正文测高并统一负责滚动（iOS 原生 sheet）。 */
val LocalSheetMeasuresContent = compositionLocalOf { false }

/**
 * sheet 正文的纵向滚动。iOS 测高时由 sheet 统一滚动，正文再套 verticalScroll 会在测量时崩溃，
 * 所以这里变成空操作；Android/桌面及不测高的场景照常滚动（含 Windows 触屏拖动）。
 */
@Composable
fun Modifier.sheetBodyScroll(state: ScrollState): Modifier =
    if (LocalSheetMeasuresContent.current) this else verticalScroll(state).desktopTouchScroll(state)

/** iOS uses the host-owned system sheet; other platforms keep a Material dialog. */
@Composable
fun AppleSheetOrAlert(
    onDismissRequest: () -> Unit,
    title: String?,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    todayLabel: String? = null,
    todayEnabled: Boolean = true,
    onToday: (() -> Unit)? = null,
    dismissLabel: String? = null,
    dismissEnabled: Boolean = true,
    showDismissButton: Boolean = true,
    needsFullHeight: Boolean = false,
    scrollableBody: Boolean = false,
    /** iOS 按正文高度展开；Android 对话框正文改为可滚动。两端 content 里都不要再套 verticalScroll。 */
    fitContentHeight: Boolean = false,
    /** false 时 iOS sheet 不能下滑关闭，回退对话框不响应点外部与返回键。 */
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (currentPlatform().family == PlatformFamily.IOS) {
        // On iOS the title and actions are UIBarButtonItems in the native sheet
        // navigation bar. Compose supplies only the body; it does not imitate
        // the system sheet chrome with Material buttons.
        AppleSheet(
            onDismissRequest = onDismissRequest,
            needsFullHeight = needsFullHeight,
            scrollableBody = scrollableBody,
            fitContentHeight = fitContentHeight,
            title = title,
            confirmLabel = confirmLabel,
            confirmEnabled = confirmEnabled,
            onConfirm = onConfirm,
            todayLabel = todayLabel,
            todayEnabled = todayEnabled,
            onToday = onToday,
            dismissLabel = dismissLabel,
            dismissEnabled = dismissEnabled,
            showDismissButton = showDismissButton,
            dismissible = dismissible,
        ) {
            content()
        }
    } else {
        val fallbackConfirmLabel = confirmLabel ?: if (showDismissButton) "关闭" else null
        AlertDialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
            title = title?.let { { Text(it) } },
            // AlertDialog 的正文区高度受限但不滚动，内容超高（如 HyperOS 小窗）时按钮会被裁掉。
            text = {
                // 居中对话框不需要页面的底栏净空，别让页面传下来的值在正文末尾垫出空白。
                CompositionLocalProvider(LocalBottomBarClearance provides 0.dp) {
                    Column(if (fitContentHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier) { content() }
                }
            },
            confirmButton = if (fallbackConfirmLabel != null) {
                {
                    TextButton(
                        onClick = onConfirm ?: onDismissRequest,
                        enabled = if (confirmLabel != null) confirmEnabled else true,
                    ) {
                        Text(fallbackConfirmLabel)
                    }
                }
            } else {
                {}
            },
            dismissButton = dismissLabel?.let { label ->
                { TextButton(onClick = onDismissRequest, enabled = dismissEnabled) { Text(label) } }
            },
        )
    }
}

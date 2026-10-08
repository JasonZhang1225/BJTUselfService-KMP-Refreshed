package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.withoutVisualEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

@Composable
internal fun TopScrollLazyColumn(
    state: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: LazyListScope.() -> Unit,
) {
    val nativeReporter = LocalNativeScrollControllerReporter.current
    val consumeNativeScroll = remember(state) { { delta: Float -> state.dispatchRawDelta(delta) } }
    if (nativeReporter != null) {
        val controller = NativeScrollController(-1f, state.canScrollBackward, state.canScrollForward, consumeNativeScroll)
        SideEffect { nativeReporter(controller) }
        DisposableEffect(nativeReporter, state) { onDispose { nativeReporter(null) } }
    }
    val topClearance = LocalTopBarClearance.current
    var previousClearance by remember { mutableStateOf(topClearance) }
    if (previousClearance != topClearance) {
        val wasAtTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
        SideEffect {
            // A newly measured or rotated bar changes content padding. LazyColumn
            // otherwise preserves the old physical anchor and hides the first item
            // behind the bar even when the user has never scrolled.
            if (wasAtTop) state.requestScrollToItem(0)
            previousClearance = topClearance
        }
    }
    VisualTopScrollContainer(
        modifier = modifier,
        logicalOffset = {
            if (state.firstVisibleItemIndex > 0) Float.MAX_VALUE
            else state.firstVisibleItemScrollOffset.toFloat()
        },
    ) { viewportModifier, effect ->
        CompositionLocalProvider(LocalNativeScrollControllerReporter provides null) {
            LazyColumn(
                state = state,
                modifier = viewportModifier,
                contentPadding = contentPadding,
                verticalArrangement = verticalArrangement,
                horizontalAlignment = horizontalAlignment,
                overscrollEffect = effect,
                userScrollEnabled = nativeReporter == null,
                content = content,
            )
        }
    }
}

@Composable
internal fun TopScrollColumn(
    state: ScrollState,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    enabled: Boolean = true,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val nativeReporter = LocalNativeScrollControllerReporter.current.takeIf { enabled }
    val consumeNativeScroll = remember(state) { { delta: Float -> state.dispatchRawDelta(delta) } }
    if (nativeReporter != null) {
        val range = state.maxValue.takeUnless { it == Int.MAX_VALUE }?.toFloat() ?: -1f
        val controller = NativeScrollController(range, state.canScrollBackward, state.canScrollForward, consumeNativeScroll)
        SideEffect { nativeReporter(controller) }
        DisposableEffect(nativeReporter, state) { onDispose { nativeReporter(null) } }
    }
    VisualTopScrollContainer(modifier, { state.value.toFloat() }, enabled) { viewportModifier, effect ->
        CompositionLocalProvider(LocalNativeScrollControllerReporter provides null) {
            Column(
                modifier = viewportModifier.verticalScroll(state, enabled = enabled && nativeReporter == null, overscrollEffect = effect).then(contentModifier),
                verticalArrangement = verticalArrangement,
                horizontalAlignment = horizontalAlignment,
                content = content,
            )
        }
    }
}

/** Render the native elastic effect once, outside the measured scrolling content. */
@Composable
private fun VisualTopScrollContainer(
    modifier: Modifier,
    logicalOffset: () -> Float,
    enabled: Boolean = true,
    content: @Composable (Modifier, OverscrollEffect?) -> Unit,
) {
    val effect = if (LocalNativeScrollControllerReporter.current == null) rememberOverscrollEffect() else null
    val report = LocalReportTopScroll.current
    val currentOffset by rememberUpdatedState(logicalOffset)
    val tracksPlacement = enabled && LocalTopBarClearance.current > 0.dp
    var viewportTop by remember { mutableStateOf<Float?>(null) }
    var contentTop by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(report, enabled, tracksPlacement) {
        if (enabled) {
            snapshotFlow {
                resolveVisualTopScrollOffset(
                    currentOffset(),
                    viewportTop.takeIf { tracksPlacement },
                    contentTop.takeIf { tracksPlacement },
                )
            }.collect { report(it) }
        }
    }
    if (tracksPlacement) {
        Box(modifier.onGloballyPositioned { viewportTop = it.positionInRoot().y }) {
            content(
                Modifier.fillMaxSize().overscroll(effect)
                    .onGloballyPositioned { contentTop = it.positionInRoot().y },
                effect?.withoutVisualEffect(),
            )
        }
    } else {
        content(modifier, effect)
    }
}

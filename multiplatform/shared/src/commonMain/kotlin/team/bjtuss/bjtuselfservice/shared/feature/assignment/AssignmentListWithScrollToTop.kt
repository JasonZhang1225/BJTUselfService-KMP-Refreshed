package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.LocalReduceMotion
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalBottomBarClearance

/** The overlay belongs to the list pane, including in a list/detail layout. */
@Composable
internal fun AssignmentListWithScrollToTop(
    state: LazyListState,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier, Dp) -> Unit,
) {
    val visible by remember(state) {
        derivedStateOf {
            // Foundation estimates distance for variable-height lazy items. Compare with
            // the actual viewport so the threshold follows rotation and window resizing.
            val indicator = state.scrollIndicatorState
            state.canScrollBackward && indicator != null &&
                indicator.viewportSize > 0 && indicator.scrollOffset > indicator.viewportSize
        }
    }
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val bottomClearance = LocalBottomBarClearance.current
    val undo = LocalAssignmentCompletionStore.current?.undo?.collectAsState()?.value
    // The existing undo capsule is drawn by the shell above this page. Leave room
    // for its touch target and scaled text while it is on screen.
    val undoClearance = if (undo != null) 64.dp * maxOf(1f, LocalDensity.current.fontScale) else 0.dp

    Box(modifier) {
        // Reserve space at the end of the content, keeping the scrolling viewport
        // full height so it continues behind the system bars.
        content(Modifier.fillMaxSize(), 48.dp + undoClearance)
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 12.dp + bottomClearance + undoClearance),
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClick = {
                        scope.launch {
                            if (reduceMotion) state.scrollToItem(0) else state.animateScrollToItem(0)
                        }
                    })
                    .semantics { contentDescription = "回到顶部" },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    val arrowColor = MaterialTheme.colorScheme.onPrimaryContainer
                    Box(contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(22.dp)) {
                            val tip = Offset(size.width * 0.5f, size.height * 0.2f)
                            val stroke = 2.dp.toPx()
                            drawLine(arrowColor, Offset(size.width * 0.5f, size.height * 0.8f), tip,
                                strokeWidth = stroke, cap = StrokeCap.Round)
                            drawLine(arrowColor, Offset(size.width * 0.2f, size.height * 0.5f), tip,
                                strokeWidth = stroke, cap = StrokeCap.Round)
                            drawLine(arrowColor, Offset(size.width * 0.8f, size.height * 0.5f), tip,
                                strokeWidth = stroke, cap = StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

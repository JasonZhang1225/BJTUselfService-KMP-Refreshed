package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.currentPlatform
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppErrorBanner
import kotlin.math.roundToInt

internal val LocalAssignmentCompletionStore = staticCompositionLocalOf<AssignmentCompletionStore?> { null }

/** 搜索结果里的卡片忽略后仍留在原处，滑出去的卡片回不来，所以那里只保留长按/右键菜单。 */
internal val LocalAssignmentSwipeToIgnore = staticCompositionLocalOf { true }

@Composable
internal fun assignmentCompletionState(): AssignmentCompletionState {
    val store = LocalAssignmentCompletionStore.current
    return key(store) {
        val flow = remember { store?.state ?: MutableStateFlow(AssignmentCompletionState()) }
        flow.collectAsState().value
    }
}

/** 只有学校仍显示未提交、且本人忽略过的作业才算“已忽略”；学校端已提交后标记自然失效。 */
internal fun isAssignmentIgnored(submitted: Boolean, key: AssignmentCompletionKey?, ignoredKeys: Set<AssignmentCompletionKey>): Boolean =
    !submitted && key != null && key in ignoredKeys

/** 把筛选、排序后的列表拆成“待处理”和“已忽略”两段，两段各自保持原顺序。 */
internal fun <T> partitionIgnoredAssignments(items: List<T>, ignoredKeys: Set<AssignmentCompletionKey>,
    submitted: (T) -> Boolean, key: (T) -> AssignmentCompletionKey?): Pair<List<T>, List<T>> =
    if (ignoredKeys.isEmpty()) items to emptyList()
    else items.partition { !isAssignmentIgnored(submitted(it), key(it), ignoredKeys) }

/** 待处理列表为空时的标题：区分“真没有”“全被忽略”和“被筛选挡住”。 */
internal fun assignmentEmptyTitle(hasAny: Boolean, hasIgnored: Boolean, filtered: Boolean): String = when {
    !hasAny -> "暂无作业"
    hasIgnored && !filtered -> "没有待处理的作业"
    else -> "当前筛选下没有作业"
}

private val AssignmentCardShape = RoundedCornerShape(17.dp)

/**
 * 三个来源共用的作业卡片外壳。卡片上不放常驻控件；“忽略”是次要操作，从长按（触屏）、
 * 右键（鼠标）、手机端左滑和读屏自定义操作进入。已忽略的卡片整体降低一级对比度。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssignmentCard(
    completionKey: AssignmentCompletionKey?,
    title: String,
    submitted: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val store = LocalAssignmentCompletionStore.current
    val state = assignmentCompletionState()
    // 学校已提交的作业本来就不催，不提供忽略。
    val key = completionKey?.takeIf { !submitted && store != null }
    val ignored = isAssignmentIgnored(submitted, key, state.completedKeys)
    val canToggle = key != null && state.loaded && key !in state.savingKeys
    val toggle: () -> Unit = {
        if (canToggle) store!!.requestSetCompleted(key, !ignored, undoTitle = title.takeIf { !ignored })
    }
    val actionLabel = if (ignored) "取消忽略" else "忽略这项作业"
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    val dim by animateFloatAsState(if (ignored) 0.62f else 1f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))

    val card: @Composable () -> Unit = {
        ElevatedCard(
            modifier = modifier.fillMaxWidth(),
            shape = AssignmentCardShape,
            colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        ) {
            Box {
                Column(
                    Modifier.fillMaxWidth()
                        .then(if (key == null) Modifier else Modifier.assignmentMenuTrigger(
                            onPress = { menuAnchor = it },
                            onSecondaryClick = { menuOpen = true },
                        ))
                        .combinedClickable(
                            onClick = onOpen,
                            onLongClickLabel = if (key != null) "更多操作" else null,
                            onLongClick = if (key != null) {
                                { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true }
                            } else null,
                        )
                        .semantics {
                            if (ignored) stateDescription = "已忽略"
                            if (key != null) customActions = listOf(CustomAccessibilityAction(actionLabel) { toggle(); canToggle })
                        }
                        .padding(15.dp)
                        // 只调淡内容，卡片底色保持不透明，左滑的背景不会从卡片后面透出来。
                        .alpha(dim),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    content = content,
                )
                if (key != null) {
                    // 零尺寸锚点放在按下的位置，菜单从手指/指针处出现，而不是卡片左下角。
                    Box(Modifier.offset { IntOffset(menuAnchor.x.roundToInt(), menuAnchor.y.roundToInt()) }.size(0.dp)) {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(actionLabel) },
                                enabled = canToggle,
                                onClick = { menuOpen = false; toggle() },
                            )
                        }
                    }
                }
            }
        }
    }

    val swipeable = key != null && LocalAssignmentSwipeToIgnore.current && remember { currentPlatform().isMobile }
    if (swipeable) SwipeToIgnore(store!!, key, ignored, canToggle, toggle, card) else card()
}

/** 记录按下位置供菜单定位；鼠标右键在 Initial 阶段消费，卡片的点击不会再打开详情。 */
private fun Modifier.assignmentMenuTrigger(onPress: (Offset) -> Unit, onSecondaryClick: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press) continue
                event.changes.firstOrNull()?.let { onPress(it.position) }
                if (event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    onSecondaryClick()
                }
            }
        }
    }

/** 手机端左滑：越过 40% 宽度或快速轻扫即忽略（已忽略的卡片则是取消忽略），卡片随之离开列表。 */
@Composable
private fun SwipeToIgnore(
    store: AssignmentCompletionStore,
    key: AssignmentCompletionKey,
    ignored: Boolean,
    canToggle: Boolean,
    toggle: () -> Unit,
    card: @Composable () -> Unit,
) {
    // 不用 rememberSwipeToDismissBoxState：它可保存，LazyColumn 会按 key 恢复“已滑出”状态，
    // 同一作业回到列表时会再触发一次忽略。这里的状态随卡片离开组合一起丢弃。
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { distance -> distance * 0.4f }) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        // 只在用户真的滑过阈值时调用，重组或恢复状态都不会触发。
        onDismiss = {
            val before = ignored
            if (canToggle) {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                toggle()
            }
            scope.launch {
                // 保存成功时卡片会移到另一段并离开组合；保存失败或未能开始时卡片滑回原位。
                while (key in store.state.value.savingKeys) delay(16)
                if ((key in store.state.value.completedKeys) == before) swipe.reset()
            }
        },
        backgroundContent = {
            // 只在拖动时画背景；静止时卡片后面什么都没有。
            if (swipe.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val armed = swipe.targetValue == SwipeToDismissBoxValue.EndToStart
            val labelAlpha by animateFloatAsState(if (armed) 1f else 0.72f)
            Row(
                Modifier.fillMaxSize().clip(AssignmentCardShape).background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (ignored) "取消忽略" else "忽略", Modifier.alpha(labelAlpha),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        },
    ) { card() }
}

/** 卡片头部替换“未提交”的灰色状态胶囊。 */
@Composable
internal fun AssignmentIgnoredBadge() {
    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Text("已忽略", Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 列表末尾的“已忽略 N 项”分组：默认收起，点开后在下方展开已忽略的卡片。
 * 已忽略的卡片用带前缀的 key，忽略/恢复时旧位置淡出、新位置淡入，而不是同一张卡跨段移动。
 */
internal fun <T> LazyListScope.ignoredAssignmentsSection(
    items: List<T>,
    expanded: Boolean,
    onToggle: () -> Unit,
    key: (T) -> Any,
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "ignored-assignments-header") {
        Box(Modifier.animateItem()) { IgnoredAssignmentsHeader(items.size, expanded, onToggle) }
    }
    if (expanded) items(items, key = { "ignored:${key(it)}" }) { item ->
        Box(Modifier.animateItem()) { itemContent(item) }
    }
}

@Composable
internal fun rememberIgnoredSectionExpanded(): MutableState<Boolean> = rememberSaveable { mutableStateOf(false) }

@Composable
private fun IgnoredAssignmentsHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = if (expanded) "收起" else "展开", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("已忽略 $count 项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("›", Modifier.rotate(rotation), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (!expanded) Text("不计入截止提醒", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 忽略后浮在底栏上方 6 秒的撤销提示；进入与离开沿同一条竖直路径，无回弹。 */
@Composable
internal fun AssignmentIgnoreUndoBar(store: AssignmentCompletionStore?, modifier: Modifier = Modifier) {
    if (store == null) return
    val undo by store.undo.collectAsState()
    var shown by remember { mutableStateOf<AssignmentIgnoreUndo?>(null) }
    if (undo != null) shown = undo
    LaunchedEffect(undo) {
        val current = undo ?: return@LaunchedEffect
        delay(6_000)
        store.dismissUndo(current)
    }
    val motion = spring<IntOffset>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
    AnimatedVisibility(
        visible = undo != null,
        modifier = modifier,
        enter = fadeIn(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) + slideInVertically(motion) { it / 2 },
        exit = fadeOut(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) + slideOutVertically(motion) { it / 2 },
    ) {
        val item = shown ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp),
        ) {
            Row(Modifier.padding(start = 20.dp, end = 6.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("已忽略“${item.title}”", Modifier.weight(1f, fill = false), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { store.undoIgnore(item) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)) {
                    Text("撤销", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** 详情页里的显式入口，给不知道长按/左滑的人兜底；学校已提交时不出现。 */
@Composable
internal fun AssignmentIgnoreDetailButton(item: AggregatedAssignment, onChanged: (ignored: Boolean) -> Unit) {
    val store = LocalAssignmentCompletionStore.current ?: return
    val key = item.completionKey()?.takeIf { !item.submitted } ?: return
    val state = assignmentCompletionState()
    val ignored = key in state.completedKeys
    TextButton(
        enabled = state.loaded && key !in state.savingKeys,
        onClick = {
            store.requestSetCompleted(key, !ignored)
            onChanged(!ignored)
        },
    ) { Text(if (ignored) "取消忽略" else "忽略这项作业") }
}

@Composable
internal fun AssignmentCompletionFeedback() {
    val store = LocalAssignmentCompletionStore.current ?: return
    val failure = assignmentCompletionState().failure ?: return
    val scope = rememberCoroutineScope()
    val reload: () -> Unit = { scope.launch { withContext(Dispatchers.Default) { store.reload() } } }
    if (failure == AssignmentCompletionFailure.LOAD) {
        AppErrorBanner(title = "忽略列表读取失败", message = "暂时无法读取本机保存的忽略列表，请重试。", onRetry = reload)
    } else {
        AppErrorBanner(title = "忽略状态未保存", message = "作业仍保持原来的状态，请再试一次。",
            onDismiss = reload, dismissLabel = "知道了")
    }
}

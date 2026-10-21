package team.bjtuss.bjtuselfservice.shared.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryLoan
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryReminder
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryRenewResult
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryUser
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppErrorBanner
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheetOrAlert
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalBottomBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalTopBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.TopScrollLazyColumn

/** 续借确认弹窗的待确认内容；[syncSelection] 为 true 时表示单本续借，确认后再把选择同步成这一本。 */
private data class PendingRenew(
    val loans: List<LibraryLoan>,
    val syncSelection: Boolean,
)

/**
 * 图书馆工作区：当前借阅（可多选续借）与借阅历史两个 tab。
 *
 * 网络请求只通过 [LibraryModel] 发起；本组件只负责渲染快照和收集用户事件。
 * 首次进入时由 [LaunchedEffect] 触发一次同步，避免在组合路径里直接发请求。
 */
@Composable
fun LibraryWorkspace(
    model: LibraryModel,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    holdNetwork: Boolean = false,
) {
    val state by model.state.collectAsState()
    val reminder by model.reminder.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var pendingRenew by remember { mutableStateOf<PendingRenew?>(null) }

    LaunchedEffect(Unit) {
        if (!holdNetwork) model.initialize()
    }
    // 切 tab 后从列表顶部重新看，避免沿用上一个列表的滚动位置。
    LaunchedEffect(state.tab, state.user) {
        if (state.user != null) listState.scrollToItem(0)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = if (expanded) {
                Modifier.widthIn(max = 980.dp).fillMaxSize()
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            when {
                state.user == null && state.failure != null -> LibraryStartupFailure(
                    message = state.failure!!.userMessage,
                    onRetry = { scope.launch { model.retry() } },
                )

                state.user == null -> LibraryStartupLoading()

                else -> LibraryReadyContent(
                    state = state,
                    reminder = reminder,
                    expanded = expanded,
                    listState = listState,
                    onRefresh = { scope.launch { model.refresh() } },
                    onRetry = { scope.launch { model.retry() } },
                    onTab = model::updateTab,
                    onToggleSelect = model::toggleSelect,
                    onSelectAll = model::selectAllVisible,
                    onDismissFailure = model::dismissFailure,
                    onDismissMessage = model::consumeMessage,
                    onRenewOne = { loan -> pendingRenew = PendingRenew(listOf(loan), syncSelection = true) },
                    onRenewSelected = {
                        val selected = state.selectedLoans
                        if (selected.isNotEmpty()) pendingRenew = PendingRenew(selected, syncSelection = false)
                    },
                )
            }
        }

        pendingRenew?.let { pending ->
            RenewConfirmDialog(
                pending = pending,
                onDismiss = { pendingRenew = null },
                onConfirm = {
                    pendingRenew = null
                    scope.launch {
                        if (pending.syncSelection) {
                            // 单本续借：确认后才把选择改成这一本，取消时不动用户原有选择。
                            model.clearSelection()
                            pending.loans.firstOrNull()?.let { model.toggleSelect(it.loanId) }
                        }
                        model.renewSelected()
                    }
                },
            )
        }

        state.renewResult?.let { result ->
            RenewResultDialog(result = result, message = state.message, onDismiss = model::consumeRenewResult)
        }
    }
}

@Composable
private fun LibraryReadyContent(
    state: LibraryUiState,
    reminder: LibraryReminder,
    expanded: Boolean,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onTab: (LibraryTab) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onDismissFailure: () -> Unit,
    onDismissMessage: () -> Unit,
    onRenewOne: (LibraryLoan) -> Unit,
    onRenewSelected: () -> Unit,
) {
    val topClearance = LocalTopBarClearance.current
    val bottomClearance = LocalBottomBarClearance.current
    val horizontal = if (expanded) 24.dp else 16.dp
    val showActionBar = state.tab == LibraryTab.CURRENT

    Column(modifier = Modifier.fillMaxSize()) {
        // 账户摘要与 tab 固定在列表上方（壳层对本页用 staticTopBar）：切 tab、看借阅总数时
        // 不用先滚回顶部。固定内容消费顶部留白；滚动视口从它下方开始，因此列表不再重复让位。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = horizontal, end = horizontal, top = 8.dp + topClearance),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LibraryAccountCard(
                user = state.user!!,
                loansTotal = state.loansTotal,
                currentCount = state.loans.size,
                reminder = reminder,
                refreshing = state.refreshing || state.loading,
                expanded = expanded,
                onRefresh = onRefresh,
            )
            LibraryTabRow(tab = state.tab, onTab = onTab)
        }
        TopScrollLazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().desktopTouchScroll(listState),
            contentPadding = PaddingValues(
                start = horizontal,
                end = horizontal,
                top = 10.dp,
                bottom = 24.dp + bottomClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 已有数据时刷新失败只做页内提示条，不清空列表；续借结果对话框打开时不重复亮红条。
            if (state.failure != null && state.renewResult == null) {
                item(key = "library-failure") {
                    AppErrorBanner(
                        message = state.failure.userMessage,
                        onRetry = onRetry,
                        onDismiss = onDismissFailure,
                    )
                }
            }
            if (state.message != null && state.renewResult == null && state.failure == null && !state.renewing) {
                item(key = "library-message") {
                    LibraryMessageBar(message = state.message, onDismiss = onDismissMessage)
                }
            }
            when (state.tab) {
                LibraryTab.CURRENT -> currentLoanItems(
                    state = state,
                    reminder = reminder,
                    onToggleSelect = onToggleSelect,
                    onRenewOne = onRenewOne,
                    onRefresh = onRefresh,
                )

                LibraryTab.HISTORY -> historyItems(state = state, onRefresh = onRefresh)
            }
        }
        if (showActionBar) {
            LibraryRenewBar(
                state = state,
                bottomClearance = bottomClearance,
                onToggleAll = onSelectAll,
                onRenew = onRenewSelected,
            )
        }
    }
}

@Composable
private fun LibraryAccountCard(
    user: LibraryUser,
    loansTotal: Int,
    currentCount: Int,
    reminder: LibraryReminder,
    refreshing: Boolean,
    expanded: Boolean,
    onRefresh: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (expanded) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = user.name.ifBlank { "图书馆读者" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = libraryUserSubtitle(user),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    Text(if (refreshing) "同步中…" else "刷新")
                }
            }
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "当前借阅 ${loansTotal.coerceAtLeast(currentCount)} 本",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (user.isFrozen) "卡状态 · 冻结" else "卡状态 · 正常",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (user.isFrozen) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            if (user.isFrozen) {
                Text(
                    text = "冻结状态无法续借，请先到图书馆解除冻结。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            libraryReminderWarning(reminder)?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LibraryTabRow(tab: LibraryTab, onTab: (LibraryTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = tab == LibraryTab.CURRENT,
            onClick = { onTab(LibraryTab.CURRENT) },
            label = { Text("当前借阅") },
        )
        FilterChip(
            selected = tab == LibraryTab.HISTORY,
            onClick = { onTab(LibraryTab.HISTORY) },
            label = { Text("借阅历史") },
        )
    }
}

private fun LazyListScope.currentLoanItems(
    state: LibraryUiState,
    reminder: LibraryReminder,
    onToggleSelect: (Long) -> Unit,
    onRenewOne: (LibraryLoan) -> Unit,
    onRefresh: () -> Unit,
) {
    if (state.loans.isEmpty()) {
        item(key = "library-current-empty") {
            LibraryEmptyHint(
                title = "暂无当前借阅",
                description = "在图书馆借书后，这里会显示应还日期和续借入口。",
                onRefresh = onRefresh,
            )
        }
        return
    }
    val overdueIds = reminder.overdue.mapTo(mutableSetOf(), LibraryLoan::loanId)
    val dueSoonIds = reminder.dueSoon.mapTo(mutableSetOf(), LibraryLoan::loanId)
    items(items = state.loans, key = { it.loanId }) { loan ->
        LibraryLoanCard(
            loan = loan,
            checked = loan.loanId in state.selectedLoanIds,
            renewing = state.renewing,
            frozen = state.user?.isFrozen == true,
            overdue = loan.loanId in overdueIds,
            dueSoon = loan.loanId in dueSoonIds,
            onToggle = { onToggleSelect(loan.loanId) },
            onRenew = { onRenewOne(loan) },
        )
    }
}

private fun LazyListScope.historyItems(state: LibraryUiState, onRefresh: () -> Unit) {
    if (state.history.isEmpty()) {
        item(key = "library-history-empty") {
            LibraryEmptyHint(
                title = "暂无借阅历史",
                description = "已归还的图书会保留在这里，方便回看借还时间。",
                onRefresh = onRefresh,
            )
        }
        return
    }
    // 历史记录只读、不可交互，不设 key：同一本书多次借还时服务器可能复用 loanId，
    // 用 loanId 作 key 会让 LazyColumn 因重复 key 崩溃。
    items(items = state.history) { loan ->
        LibraryHistoryCard(loan)
    }
    if (state.historyHasMore) {
        item(key = "library-history-more") {
            Text(
                text = "仅显示最近 ${state.history.size} 条，共 ${state.historyTotal} 条。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryLoanCard(
    loan: LibraryLoan,
    checked: Boolean,
    renewing: Boolean,
    frozen: Boolean,
    overdue: Boolean,
    dueSoon: Boolean,
    onToggle: () -> Unit,
    onRenew: () -> Unit,
) {
    // 整张卡片可点选：复选框、续借按钮本身会消费点击，不会与卡片点击重复触发。
    ElevatedCard(
        onClick = onToggle,
        enabled = !renewing,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp, end = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = !renewing,
                modifier = Modifier.semantics { contentDescription = "选择《${loan.title}》" },
            )
            Column(
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = loan.title.ifBlank { "未命名图书" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (loan.author.isNotBlank()) {
                    Text(
                        text = loan.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LibraryLoanLine("索书号", loan.callNo.ifBlank { "未提供" })
                LibraryLoanLine(
                    "馆藏地",
                    listOf(loan.locationName, loan.libName)
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString(" · ")
                        .ifBlank { "未提供" },
                )
                // 借出/应还：放得下就同一行，放不下时整段换行，避免“应还日期”被拆成两行。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "借出 ${loan.loanDate.ifBlank { "未提供" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = libraryDueDateText(loan, overdue, dueSoon),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (overdue || dueSoon) FontWeight.Medium else FontWeight.Normal,
                        color = when {
                            overdue -> MaterialTheme.colorScheme.error
                            dueSoon -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    text = libraryRenewalText(loan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onRenew,
                enabled = !renewing && !frozen,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(if (renewing) "续借中…" else "续借")
            }
        }
    }
}

@Composable
private fun LibraryHistoryCard(loan: LibraryLoan) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = loan.title.ifBlank { "未命名图书" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (loan.author.isNotBlank()) {
                Text(
                    text = loan.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "借出 ${loan.loanDate.ifBlank { "未提供" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "应还 ${loan.dueDate.ifBlank { "未提供" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "归还 ${loan.returnDate?.takeIf(String::isNotBlank) ?: "未归还"}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LibraryRenewBar(
    state: LibraryUiState,
    bottomClearance: Dp,
    onToggleAll: () -> Unit,
    onRenew: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.allVisibleSelected,
                    onCheckedChange = { onToggleAll() },
                    enabled = state.loans.isNotEmpty() && !state.renewing,
                    modifier = Modifier.semantics { contentDescription = "全选当前借阅" },
                )
                Text("全选", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Button(onClick = onRenew, enabled = state.canRenew) {
                    if (state.renewing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在续借…")
                    } else {
                        Text("续借(${state.selectedLoanIds.size})")
                    }
                }
            }
        }
        // 底栏净空只留透明空位，不绘制占位条，避免盖住 iOS 原生玻璃底栏。
        if (bottomClearance > 0.dp) Spacer(Modifier.height(bottomClearance))
    }
}

@Composable
private fun RenewConfirmDialog(
    pending: PendingRenew,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppleSheetOrAlert(
        onDismissRequest = onDismiss,
        title = "确认续借",
        confirmLabel = "续借",
        onConfirm = onConfirm,
        dismissLabel = "取消",
        showDismissButton = false,
        fitContentHeight = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "将续借以下 ${pending.loans.size} 本图书：",
                style = MaterialTheme.typography.bodyMedium,
            )
            pending.loans.take(5).forEach { loan ->
                Text(
                    text = "· ${loan.title.ifBlank { "未命名图书" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pending.loans.size > 5) {
                Text(
                    text = "等 ${pending.loans.size} 本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "续借结果以图书馆返回的应还日期为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RenewResultDialog(result: LibraryRenewResult, message: String?, onDismiss: () -> Unit) {
    AppleSheetOrAlert(
        onDismissRequest = onDismiss,
        title = renewResultTitle(result),
        confirmLabel = "关闭",
        onConfirm = onDismiss,
        showDismissButton = false,
        fitContentHeight = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = message ?: "续借操作已完成，请核对新的应还日期。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (result.renewedDueDates.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "新的应还日期",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                result.renewedDueDates.take(8).forEach { dueDate ->
                    Text(
                        text = "· $dueDate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (result.failureReasons.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "失败原因",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                result.failureReasons.take(8).forEach { reason ->
                    Text(
                        text = "· $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (result.failureReasons.size > 8) {
                    Text(
                        text = "另有 ${result.failureReasons.size - 8} 条原因未显示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryMessageBar(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable
private fun LibraryLoanLine(label: String, value: String) {
    Text(
        text = "$label · $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LibraryEmptyHint(title: String, description: String, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRefresh) { Text("刷新") }
    }
}

@Composable
private fun LibraryStartupLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("正在同步图书借阅…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LibraryStartupFailure(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        ElevatedCard(
            shape = RoundedCornerShape(17.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "图书馆暂时无法加载",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

private fun libraryUserSubtitle(user: LibraryUser): String = listOf(
    user.userGroupName,
    user.department,
    user.major,
).map(String::trim).filter(String::isNotBlank).distinct().joinToString(" · ").ifBlank { "图书馆账户" }

private fun libraryReminderWarning(reminder: LibraryReminder): String? {
    if (!reminder.hasWarning) return null
    val parts = buildList {
        if (reminder.overdue.isNotEmpty()) add("逾期 ${reminder.overdue.size} 本")
        if (reminder.dueSoon.isNotEmpty()) add("即将到期 ${reminder.dueSoon.size} 本")
    }
    return parts.joinToString(" / ")
}

private fun libraryDueDateText(loan: LibraryLoan, overdue: Boolean, dueSoon: Boolean): String {
    val base = "应还 ${loan.dueDate.ifBlank { "未提供" }}"
    return when {
        overdue -> "$base · 已逾期"
        dueSoon -> "$base · 即将到期"
        else -> base
    }
}

private fun libraryRenewalText(loan: LibraryLoan): String {
    val base = "已续借 ${loan.renewTimes} 次"
    return loan.remainingRenewals?.let { "$base · 剩余 $it 次" } ?: base
}

private fun renewResultTitle(result: LibraryRenewResult): String = when {
    result.allSucceeded -> "续借完成"
    result.anySucceeded -> "部分续借成功"
    else -> "续借失败"
}

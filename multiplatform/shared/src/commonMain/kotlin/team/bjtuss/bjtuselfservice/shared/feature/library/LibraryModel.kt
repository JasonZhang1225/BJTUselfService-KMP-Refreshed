package team.bjtuss.bjtuselfservice.shared.feature.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryException
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryFailure
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryFailureKind
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryLoan
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryReminder
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryRepository
import team.bjtuss.bjtuselfservice.shared.data.library.libraryReminder

/** 图书馆列表每页条数；本轮不做「加载更多」界面，但分页字段必须如实回填。 */
private const val LIBRARY_PAGE_SIZE = 100

/**
 * 图书馆页面模型：不可变 [LibraryUiState] 快照 + 明确事件入口。
 *
 * 网络副作用只在这里发生，Composable 只发事件、读快照。刷新与续借共用一把 [Mutex]
 * 串行执行：opac 的登录/查询/续借都挂在同一个会话 Cookie 上，并发握手会互相覆盖状态。
 * 首页提醒只通过 [reminder] 读取当前借阅派生出的逾期/临期清单。
 */
class LibraryModel(
    private val repository: LibraryRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()

    private val mutex = Mutex()

    /** 首页提醒的自动同步每个模型实例（≈ 每个登录会话）最多一次。 */
    private var autoSyncRequested = false

    private val mutableReminder = MutableStateFlow(libraryReminder(emptyList(), today()))
    val reminder: StateFlow<LibraryReminder> = mutableReminder.asStateFlow()

    /**
     * 首次进入页面时同步一次；已经拿到过用户信息或完成过一次同步就不重复请求。
     * 首次失败后再次进入页面允许重试（失败不会被永久记住）。
     */
    suspend fun initialize() {
        if (state.value.user != null || state.value.lastSyncEpochSeconds != null) return
        mutex.withLock {
            if (state.value.user != null || state.value.lastSyncEpochSeconds != null) return@withLock
            loadAll(silent = true)
        }
    }

    /**
     * 首页/磁贴提醒用：每个会话只自动同步一次，之后的提醒刷新交给页面内刷新。
     * 页面已经同步过时直接跳过，避免同一会话重复握手。
     */
    suspend fun refreshIfNeeded() {
        if (autoSyncRequested) return
        mutex.withLock {
            if (autoSyncRequested) return@withLock
            autoSyncRequested = true
            if (state.value.user != null) return@withLock
            loadAll(silent = true)
        }
    }

    /**
     * 用户主动刷新（按钮/重试）。
     *
     * @param silent 静默同步（首页提醒/首次进入）失败时只记 [LibraryUiState.failure]，
     *   不覆盖已有消息；用户主动刷新失败则把失败文案放进 message 供页面提示。
     */
    suspend fun refresh(silent: Boolean = false) {
        mutex.withLock { loadAll(silent = silent) }
    }

    /** 切换 tab 不联网；历史数据在 [loadAll] 中与当前借阅一起拉取。 */
    fun updateTab(tab: LibraryTab) {
        if (state.value.tab == tab) return
        mutableState.value = state.value.copy(tab = tab)
    }

    /** 只有「当前借阅」里的书能被勾选；历史记录不能续借。 */
    fun toggleSelect(loanId: Long) {
        val current = state.value
        if (current.loans.none { it.loanId == loanId }) return
        val selected = current.selectedLoanIds.toMutableSet().apply {
            if (!add(loanId)) remove(loanId)
        }
        mutableState.value = current.copy(selectedLoanIds = selected)
    }

    /** 当前借阅全选/全不选切换。 */
    fun selectAllVisible() {
        val current = state.value
        if (current.loans.isEmpty()) return
        val next = if (current.allVisibleSelected) {
            emptySet()
        } else {
            current.loans.mapTo(mutableSetOf(), LibraryLoan::loanId)
        }
        mutableState.value = current.copy(selectedLoanIds = next)
    }

    fun clearSelection() {
        if (state.value.selectedLoanIds.isEmpty()) return
        mutableState.value = state.value.copy(selectedLoanIds = emptySet())
    }

    /**
     * 续借当前选中的图书。
     *
     * 成功后重新拉取当前借阅（应还日期/续借次数以服务器为准）并清空选择；
     * 全部失败时把 [LibraryFailure] 放进状态，部分失败时保留 [LibraryUiState.renewResult]
     * 让界面展示失败原因。
     */
    suspend fun renewSelected() {
        mutex.withLock {
            val current = state.value
            if (current.renewing) return@withLock
            val user = current.user
            if (user == null) {
                mutableState.value = current.copy(message = "请先同步图书馆信息后再续借。")
                return@withLock
            }
            if (user.isFrozen) {
                mutableState.value = current.copy(message = "借阅卡已冻结，冻结状态无法续借，请联系图书馆。")
                return@withLock
            }
            val ids = current.selectedLoans.map(LibraryLoan::loanId)
            if (ids.isEmpty()) {
                mutableState.value = current.copy(message = "请先选择要续借的图书。")
                return@withLock
            }

            mutableState.value = current.copy(renewing = true, failure = null, renewResult = null, message = null)
            try {
                val result = repository.renew(ids)
                val message = when {
                    result.allSucceeded -> result.newDueDate?.let { "已续借 ${result.renewed} 本，新应还日期 $it" }
                        ?: "已续借 ${result.renewed} 本，请留意新的应还日期"
                    result.anySucceeded -> "成功续借 ${result.renewed} 本，失败 ${result.failed} 本"
                    else -> "续借失败，${result.failed} 本图书未续借成功"
                }
                mutableState.value = mutableState.value.copy(
                    renewing = false,
                    renewResult = result.takeIf { it.attempted > 0 },
                    message = message,
                    // 全部失败时同时给出失败横幅文案；部分失败只弹结果对话框，不重复亮红条。
                    failure = if (result.renewed == 0 && result.failed > 0) {
                        LibraryFailure(LibraryFailureKind.SERVER, detail = result.failureReasons.firstOrNull())
                    } else {
                        null
                    },
                    selectedLoanIds = if (result.anySucceeded) emptySet() else mutableState.value.selectedLoanIds,
                )
                if (result.anySucceeded) reloadLoansAfterRenew()
            } catch (e: CancellationException) {
                mutableState.value = mutableState.value.copy(renewing = false)
                throw e
            } catch (e: Exception) {
                val failure = e.asLibraryFailure()
                mutableState.value = mutableState.value.copy(renewing = false, failure = failure)
            }
        }
    }

    /** 结果对话框关闭后清掉本轮续借结果。 */
    fun consumeRenewResult() {
        if (state.value.renewResult == null) return
        mutableState.value = state.value.copy(renewResult = null)
    }

    /** 页面内消息条关闭。 */
    fun consumeMessage() {
        if (state.value.message == null) return
        mutableState.value = state.value.copy(message = null)
    }

    fun dismissFailure() {
        if (state.value.failure == null) return
        mutableState.value = state.value.copy(failure = null)
    }

    /** 失败卡片上的重试：先清掉旧失败，再走一次用户主动刷新。 */
    suspend fun retry() {
        dismissFailure()
        refresh()
    }

    /**
     * 依次拉取用户信息、当前借阅、借阅历史；三步全部成功才算刷新完成。
     * 失败时保留旧数据（不清空 loans/history），避免页面闪空。
     */
    private suspend fun loadAll(silent: Boolean) {
        val before = mutableState.value
        val firstLoad = before.user == null
        mutableState.value = before.copy(
            loading = firstLoad,
            refreshing = !firstLoad && !silent,
            failure = null,
            message = if (silent) before.message else null,
        )
        try {
            val user = repository.loadUser()
            val loansPage = repository.loadLoans(page = 1, rows = LIBRARY_PAGE_SIZE)
            val historyPage = repository.loadHistory(page = 1, rows = LIBRARY_PAGE_SIZE)
            val syncAt = clock.now().epochSeconds
            val current = mutableState.value
            val loanIds = loansPage.loans.mapTo(mutableSetOf(), LibraryLoan::loanId)
            mutableState.value = current.copy(
                loading = false,
                refreshing = false,
                user = user,
                loans = loansPage.loans,
                loansTotal = loansPage.total,
                history = historyPage.loans,
                historyTotal = historyPage.total,
                historyHasMore = historyPage.hasMore,
                // 刷新后已不再借出的书要从选择里摘掉，避免续借到过期 id。
                selectedLoanIds = current.selectedLoanIds.filterTo(mutableSetOf(), loanIds::contains),
                failure = null,
                message = if (silent) current.message else null,
                lastSyncEpochSeconds = syncAt,
            )
            publishReminder(loansPage.loans)
        } catch (e: CancellationException) {
            // 取消也要结束加载态，否则页面停在进度条上；取消信号本身必须继续抛。
            mutableState.value = mutableState.value.copy(loading = false, refreshing = false)
            throw e
        } catch (e: Exception) {
            val failure = e.asLibraryFailure()
            mutableState.value = mutableState.value.copy(
                loading = false,
                refreshing = false,
                failure = failure,
                // silent 同步不覆盖页面已有消息；用户主动刷新开始时已清空 message，失败只走红色横幅。
                message = mutableState.value.message,
            )
        }
    }

    /** 续借成功后只刷新「当前借阅」，历史与用户信息保持不变；失败不覆盖续借成功消息。 */
    private suspend fun reloadLoansAfterRenew() {
        try {
            val page = repository.loadLoans(page = 1, rows = LIBRARY_PAGE_SIZE)
            val current = mutableState.value
            val loanIds = page.loans.mapTo(mutableSetOf(), LibraryLoan::loanId)
            mutableState.value = current.copy(
                loans = page.loans,
                loansTotal = page.total,
                selectedLoanIds = current.selectedLoanIds.filterTo(mutableSetOf(), loanIds::contains),
                lastSyncEpochSeconds = clock.now().epochSeconds,
            )
            publishReminder(page.loans)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 续借本身已经成功，列表刷新失败只提示，不回滚结果对话框。
            mutableState.value = mutableState.value.copy(failure = e.asLibraryFailure())
        }
    }

    private fun publishReminder(loans: List<LibraryLoan>) {
        mutableReminder.value = libraryReminder(loans, today())
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(timeZone).date

    private fun Throwable.asLibraryFailure(): LibraryFailure = when (this) {
        is LibraryException -> failure
        // 数据层会把网络/解析失败都包成 LibraryException；走到这里的多半是未预期的服务端错误。
        else -> LibraryFailure(LibraryFailureKind.SERVER)
    }
}

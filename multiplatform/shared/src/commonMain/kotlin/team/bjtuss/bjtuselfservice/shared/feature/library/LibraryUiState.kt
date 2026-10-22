package team.bjtuss.bjtuselfservice.shared.feature.library

import team.bjtuss.bjtuselfservice.shared.data.library.LibraryFailure
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryLoan
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryRenewResult
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryUser

enum class LibraryTab { CURRENT, HISTORY }

/**
 * 图书馆页面状态：不可变快照 + 明确事件入口，网络副作用只由 [LibraryModel] 触发。
 *
 * 当前借阅与历史借阅分开存；[selectedLoanIds] 只对「当前借阅」生效（历史记录不能续借）。
 */
data class LibraryUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    /** 已完成一次 SSO→opac 握手或已拿到用户信息。 */
    val user: LibraryUser? = null,
    val tab: LibraryTab = LibraryTab.CURRENT,
    val loans: List<LibraryLoan> = emptyList(),
    val loansTotal: Int = 0,
    val history: List<LibraryLoan> = emptyList(),
    val historyTotal: Int = 0,
    val historyHasMore: Boolean = false,
    val selectedLoanIds: Set<Long> = emptySet(),
    val renewing: Boolean = false,
    val renewResult: LibraryRenewResult? = null,
    val failure: LibraryFailure? = null,
    val message: String? = null,
    val lastSyncEpochSeconds: Long? = null,
) {
    val isReady: Boolean get() = user != null
    val selectedLoans: List<LibraryLoan>
        get() = loans.filter { it.loanId in selectedLoanIds }
    val canRenew: Boolean
        get() = user != null && !user.isFrozen && !renewing && selectedLoanIds.isNotEmpty()
    val allVisibleSelected: Boolean
        get() = loans.isNotEmpty() && loans.all { it.loanId in selectedLoanIds }
}

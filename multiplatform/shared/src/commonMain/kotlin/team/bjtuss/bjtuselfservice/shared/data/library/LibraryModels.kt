package team.bjtuss.bjtuselfservice.shared.data.library

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** 图书馆接口失败分类；UI 只按分类给文案，不解析英文/中文原始错误。 */
enum class LibraryFailureKind {
    NETWORK,
    SESSION_EXPIRED,
    MALFORMED_RESPONSE,
    LOGIN_REQUIRED,
    LOGIN_REJECTED,
    CAPTCHA_REQUIRED,
    SERVER,
}

data class LibraryFailure(
    val kind: LibraryFailureKind,
    /** 学校返回的原始提示，仅用于日志/调试，界面优先用 [userMessage]。 */
    val detail: String? = null,
) {
    val userMessage: String
        get() = when (kind) {
            LibraryFailureKind.NETWORK -> "图书馆服务暂时无法连接，请检查校园网后重试。"
            LibraryFailureKind.SESSION_EXPIRED -> "图书馆登录已过期，请下拉刷新重新登录。"
            LibraryFailureKind.MALFORMED_RESPONSE -> "图书馆返回的数据格式有变化，暂时无法解析。"
            LibraryFailureKind.LOGIN_REQUIRED -> "需要重新登录统一身份认证后才能使用图书馆。"
            LibraryFailureKind.LOGIN_REJECTED -> "统一身份认证拒绝了登录，请先在应用内重新登录。"
            LibraryFailureKind.CAPTCHA_REQUIRED -> "学校要求验证码，请在应用内重新登录后再试。"
            LibraryFailureKind.SERVER -> detail?.takeIf { it.isNotBlank() } ?: "图书馆服务暂时不可用，请稍后重试。"
        }

    override fun toString(): String = "LibraryFailure(kind=$kind, detail=<redacted>)"
}

/** 解析或网络失败时由数据层抛出，界面只读取 [failure]。 */
class LibraryException(val failure: LibraryFailure, cause: Throwable? = null) :
    Exception(failure.userMessage, cause)

/** `GET /find/userInfo/getUserInfo` 中与借阅相关的字段子集。 */
data class LibraryUser(
    val name: String,
    val readerBarcode: String,
    /** "1" 正常，"2" 挂失/暂停，"3" 冻结（冻结时学校界面禁止续借）。 */
    val cardStatus: String,
    val userGroupName: String,
    val department: String,
    val major: String,
    val deposit: Int,
    val prepayment: Int,
    val expiredBookCount: Int,
    val unprocessedHours: Int,
) {
    val isFrozen: Boolean get() = cardStatus == "3"
    val hasDebt: Boolean get() = deposit > 0 || prepayment > 0

    override fun toString(): String =
        "LibraryUser(name=<redacted>, cardStatus=$cardStatus, group=$userGroupName)"
}

/** 单条借阅记录；当前借阅与历史借阅共用一个模型，历史记录带 [returnDate]。 */
data class LibraryLoan(
    val loanId: Long,
    val recordId: Long? = null,
    val itemId: Long? = null,
    val title: String,
    val author: String = "",
    val publisher: String = "",
    val isbn: String = "",
    val callNo: String = "",
    val barcode: String = "",
    val locationName: String = "",
    val libName: String = "",
    /** yyyy-MM-dd，学校返回空串时保留空串。 */
    val loanDate: String = "",
    val dueDate: String = "",
    val returnDate: String? = null,
    val renewTimes: Int = 0,
    val remainingRenewals: Int? = null,
) {
    val isReturned: Boolean get() = !returnDate.isNullOrBlank()
    val dueDateOrNull: LocalDate? get() = dueDate.toLocalDateOrNull()
    val loanDateOrNull: LocalDate? get() = loanDate.toLocalDateOrNull()

    fun isOverdue(today: LocalDate): Boolean =
        !isReturned && dueDateOrNull?.let { it < today } == true

    fun isDueWithin(today: LocalDate, days: Int): Boolean =
        !isReturned && dueDateOrNull?.let { it >= today && it <= today.plus(DatePeriod(days = days)) } == true
}

data class LibraryLoanPage(
    val loans: List<LibraryLoan>,
    val total: Int,
    val page: Int = 1,
    val rows: Int = loans.size,
) {
    val hasMore: Boolean get() = loans.isNotEmpty() && page * rows < total
}

/** `POST /find/lendbook/reNew` 的归一化结果：成功/失败本数、失败原因与新的应还日期。 */
data class LibraryRenewResult(
    val attempted: Int,
    val renewed: Int,
    val failed: Int,
    val failureReasons: List<String> = emptyList(),
    /**
     * 全部成功时学校会在 `data.result` 里回每本书的新应还日期（如 `2026-11-20 17:50:25`），
     * 与「失败原因」是同一个字段、靠 `fail` 是否为 0 区分语义；不要把它当失败原因展示。
     */
    val renewedDueDates: List<String> = emptyList(),
) {
    val allSucceeded: Boolean get() = attempted > 0 && failed == 0
    val anySucceeded: Boolean get() = renewed > 0
    val newDueDate: String? get() = renewedDueDates.firstOrNull()
}

/** 首页/磁贴提醒；只包含当前借阅，历史记录不参与。 */
data class LibraryReminder(
    val total: Int,
    val dueSoon: List<LibraryLoan>,
    val overdue: List<LibraryLoan>,
) {
    val hasWarning: Boolean get() = overdue.isNotEmpty() || dueSoon.isNotEmpty()
    val warningCount: Int get() = overdue.size + dueSoon.size

    companion object {
        val Empty = LibraryReminder(0, emptyList(), emptyList())
    }
}

const val LIBRARY_DUE_SOON_DAYS = 7

fun libraryReminder(
    loans: List<LibraryLoan>,
    today: LocalDate,
    dueSoonDays: Int = LIBRARY_DUE_SOON_DAYS,
): LibraryReminder {
    val current = loans.filterNot { it.isReturned }
    return LibraryReminder(
        total = current.size,
        dueSoon = current.filter { it.isDueWithin(today, dueSoonDays) }.sortedBy { it.dueDateOrNull },
        overdue = current.filter { it.isOverdue(today) }.sortedBy { it.dueDateOrNull },
    )
}

fun libraryReminderNow(loans: List<LibraryLoan>): LibraryReminder {
    val timeZone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(timeZone).date
    return libraryReminder(loans, today)
}

/** 数据层给 UI 的最小接口；失败抛 [LibraryException]，会话过期由实现负责重登一次。 */
interface LibraryRepository {
    suspend fun loadUser(): LibraryUser

    suspend fun loadLoans(page: Int = 1, rows: Int = 100): LibraryLoanPage

    suspend fun loadHistory(page: Int = 1, rows: Int = 100): LibraryLoanPage

    suspend fun renew(loanIds: List<Long>): LibraryRenewResult
}

/** 会话恢复入口：返回值 null 表示已恢复，否则返回失败原因。 */
fun interface LibraryAuthenticator {
    suspend fun ensureSession(): LibraryFailure?
}

internal fun String.toLocalDateOrNull(): LocalDate? =
    trim().takeIf { it.length >= 10 }?.let { text ->
        runCatching { LocalDate.parse(text.substring(0, 10)) }.getOrNull()
    }

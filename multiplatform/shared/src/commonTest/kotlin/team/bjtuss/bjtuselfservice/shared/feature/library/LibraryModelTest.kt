package team.bjtuss.bjtuselfservice.shared.feature.library

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryException
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryFailure
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryFailureKind
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryLoan
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryLoanPage
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryRenewResult
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryRepository
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryUser

/**
 * 图书馆页面模型测试：全部用合成数据与固定时钟，不碰真实网络和系统时间。
 * 每条用例只验证 [LibraryModel] 的状态迁移与续借副作用，不覆盖 UI。
 */
class LibraryModelTest {
    /** 2026-07-20T00:00:00Z；配合 UTC 时区让「逾期/7 天内到期」判断可复现。 */
    private val testNow: Long = 1_784_505_600L

    @Test
    fun refreshFillsUserLoansHistoryAndLastSync() = runBlocking {
        val fake = FakeLibraryRepository(
            loans = listOf(
                loan(1, dueDate = "2026-07-25"),
                loan(2, dueDate = "2026-07-18"),
            ),
            history = listOf(historyLoan(9)),
            historyTotal = 150,
        )
        val model = libraryModel(fake)

        model.refresh()
        val state = model.state.value

        assertEquals("读者甲", state.user?.name)
        assertFalse(state.loading)
        assertFalse(state.refreshing)
        assertEquals(listOf(1L, 2L), state.loans.map(LibraryLoan::loanId))
        assertEquals(2, state.loansTotal)
        assertEquals(listOf(9L), state.history.map(LibraryLoan::loanId))
        assertEquals(150, state.historyTotal)
        assertTrue(state.historyHasMore, "total=150 rows=100 时历史应标记还有下一页")
        assertEquals(1, fake.userLoads)
        assertEquals(1, fake.loanLoads)
        assertEquals(1, fake.historyLoads)
        assertEquals(testNow, state.lastSyncEpochSeconds)

        // 首页提醒由当前借阅派生：一笔逾期、一笔 7 天内到期，历史不参与。
        val reminder = model.reminder.value
        assertEquals(2, reminder.total)
        assertEquals(listOf(2L), reminder.overdue.map(LibraryLoan::loanId))
        assertEquals(listOf(1L), reminder.dueSoon.map(LibraryLoan::loanId))
    }

    @Test
    fun refreshFailureKeepsExistingDataAndSetsFailure() = runBlocking {
        val fake = FakeLibraryRepository()
        val model = libraryModel(fake)
        model.refresh()
        val before = model.state.value
        val syncBefore = before.lastSyncEpochSeconds

        fake.loansFailure = LibraryFailure(LibraryFailureKind.NETWORK)
        model.refresh()
        val after = model.state.value

        assertFalse(after.loading)
        assertFalse(after.refreshing)
        assertEquals(LibraryFailureKind.NETWORK, after.failure?.kind)
        // 失败保留旧数据，不闪空；lastSync 也不应被这次失败覆盖。
        assertEquals(before.user, after.user)
        assertEquals(before.loans, after.loans)
        assertEquals(before.history, after.history)
        assertEquals(syncBefore, after.lastSyncEpochSeconds)
    }

    @Test
    fun initialFailureLeavesNoDataAndRetryRecovers() = runBlocking {
        val fake = FakeLibraryRepository(userFailure = LibraryFailure(LibraryFailureKind.SESSION_EXPIRED))
        val model = libraryModel(fake)

        model.initialize()
        assertNull(model.state.value.user)
        assertNull(model.state.value.lastSyncEpochSeconds)
        assertEquals(LibraryFailureKind.SESSION_EXPIRED, model.state.value.failure?.kind)
        assertFalse(model.state.value.loading)

        fake.userFailure = null
        model.retry()
        assertNotNull(model.state.value.user)
        assertNull(model.state.value.failure)
    }

    @Test
    fun initializeAndHomeAutoSyncLoadOnlyOncePerSession() = runBlocking {
        val pageFirst = FakeLibraryRepository()
        val pageModel = libraryModel(pageFirst)
        pageModel.initialize()
        pageModel.initialize()
        assertEquals(1, pageFirst.userLoads, "页面重复进入不应重复握手")
        // 页面已经同步过，首页提醒不再触发第二次自动同步。
        pageModel.refreshIfNeeded()
        pageModel.refreshIfNeeded()
        assertEquals(1, pageFirst.userLoads)

        val homeFirst = FakeLibraryRepository()
        val homeModel = libraryModel(homeFirst)
        homeModel.refreshIfNeeded()
        homeModel.refreshIfNeeded()
        assertEquals(1, homeFirst.userLoads, "每个会话只自动同步一次")
        homeModel.initialize()
        assertEquals(1, homeFirst.userLoads, "首页已同步后页面进入不再重复请求")
    }

    @Test
    fun selectionTogglesSelectAllAndClears() = runBlocking {
        val model = libraryModel(FakeLibraryRepository())
        model.refresh()

        model.selectAllVisible()
        assertEquals(setOf(1L, 2L), model.state.value.selectedLoanIds)
        assertTrue(model.state.value.allVisibleSelected)

        model.selectAllVisible()
        assertEquals(emptySet<Long>(), model.state.value.selectedLoanIds)
        assertFalse(model.state.value.allVisibleSelected)

        model.toggleSelect(1L)
        assertEquals(setOf(1L), model.state.value.selectedLoanIds)
        model.toggleSelect(1L)
        assertEquals(emptySet<Long>(), model.state.value.selectedLoanIds)

        model.toggleSelect(2L)
        model.toggleSelect(99L) // 不在当前借阅里的 id 不能选中
        assertEquals(setOf(2L), model.state.value.selectedLoanIds)

        model.clearSelection()
        assertEquals(emptySet<Long>(), model.state.value.selectedLoanIds)
    }

    @Test
    fun refreshPrunesSelectionThatNoLongerExists() = runBlocking {
        val fake = FakeLibraryRepository()
        val model = libraryModel(fake)
        model.refresh()
        model.toggleSelect(1L)

        fake.loans = listOf(loan(2))
        model.refresh()

        assertEquals(emptySet<Long>(), model.state.value.selectedLoanIds)
    }

    @Test
    fun renewSelectedSuccessReloadsLoansAndClearsSelection() = runBlocking {
        val fake = FakeLibraryRepository()
        val model = libraryModel(fake)
        model.refresh()
        model.toggleSelect(1L)
        model.toggleSelect(2L)
        fake.renewResult = LibraryRenewResult(attempted = 2, renewed = 2, failed = 0)
        val loadsBefore = fake.loanLoads

        model.renewSelected()
        val state = model.state.value

        assertEquals(1, fake.renewCalls)
        assertEquals(listOf(1L, 2L), fake.renewedIds)
        assertEquals(loadsBefore + 1, fake.loanLoads, "续借成功后应重新拉取当前借阅")
        assertEquals(emptySet<Long>(), state.selectedLoanIds)
        assertFalse(state.renewing)
        assertEquals("已续借 2 本，请留意新的应还日期", state.message)
        assertTrue(state.renewResult?.allSucceeded == true)
        // 服务器续借次数已通过重新加载反映到列表里。
        assertEquals(listOf(1, 1), state.loans.map(LibraryLoan::renewTimes))

        model.consumeRenewResult()
        assertNull(model.state.value.renewResult)
    }

    @Test
    fun renewSelectedSuccessMessageShowsNewDueDateWhenServerReturnsIt() = runBlocking {
        val fake = FakeLibraryRepository()
        val model = libraryModel(fake)
        model.refresh()
        model.toggleSelect(1L)
        fake.renewResult = LibraryRenewResult(
            attempted = 1,
            renewed = 1,
            failed = 0,
            renewedDueDates = listOf("2026-11-20 17:50:25"),
        )

        model.renewSelected()
        val state = model.state.value

        assertEquals("已续借 1 本，新应还日期 2026-11-20 17:50:25", state.message)
        assertTrue(state.renewResult?.failureReasons.orEmpty().isEmpty())
        assertEquals(listOf("2026-11-20 17:50:25"), state.renewResult?.renewedDueDates)
    }

    @Test
    fun renewSelectedPartialFailureKeepsFailureReasons() = runBlocking {
        val fake = FakeLibraryRepository()
        val model = libraryModel(fake)
        model.refresh()
        model.toggleSelect(1L)
        model.toggleSelect(2L)
        fake.renewResult = LibraryRenewResult(
            attempted = 2,
            renewed = 1,
            failed = 1,
            failureReasons = listOf("已达最大续借次数"),
        )

        model.renewSelected()
        val state = model.state.value

        assertEquals("成功续借 1 本，失败 1 本", state.message)
        assertEquals(listOf("已达最大续借次数"), state.renewResult?.failureReasons)
        assertTrue(state.renewResult?.anySucceeded == true)
        assertFalse(state.renewResult?.allSucceeded == true)
        // 部分失败只弹结果对话框，不额外亮失败横幅。
        assertNull(state.failure)
        assertEquals(emptySet<Long>(), state.selectedLoanIds)
    }

    @Test
    fun renewSelectedTotalFailureSetsFailureAndKeepsSelection() = runBlocking {
        val fake = FakeLibraryRepository()
        val model = libraryModel(fake)
        model.refresh()
        model.toggleSelect(1L)
        fake.renewResult = LibraryRenewResult(
            attempted = 1,
            renewed = 0,
            failed = 1,
            failureReasons = listOf("图书已逾期，不能续借"),
        )

        model.renewSelected()
        val state = model.state.value

        assertEquals("续借失败，1 本图书未续借成功", state.message)
        assertEquals(LibraryFailureKind.SERVER, state.failure?.kind)
        assertEquals(listOf("图书已逾期，不能续借"), state.renewResult?.failureReasons)
        assertFalse(state.renewing)
        // 全部失败时保留选择，方便用户处理后重试；也不重新拉列表。
        assertEquals(setOf(1L), state.selectedLoanIds)
        assertEquals(1, fake.loanLoads)
    }

    @Test
    fun renewNetworkFailureKeepsSelectionAndClearsRenewing() = runBlocking {
        val fake = FakeLibraryRepository()
        val model = libraryModel(fake)
        model.refresh()
        model.toggleSelect(2L)
        fake.renewFailure = LibraryFailure(LibraryFailureKind.NETWORK)

        model.renewSelected()
        val state = model.state.value

        assertEquals(1, fake.renewCalls)
        assertEquals(LibraryFailureKind.NETWORK, state.failure?.kind)
        assertNull(state.renewResult)
        assertEquals(setOf(2L), state.selectedLoanIds)
        assertFalse(state.renewing)
    }

    @Test
    fun renewWithoutSelectionDoesNotCallRepository() = runBlocking {
        val fake = FakeLibraryRepository()
        val model = libraryModel(fake)
        model.refresh()

        model.renewSelected()
        val state = model.state.value

        assertEquals(0, fake.renewCalls)
        assertEquals("请先选择要续借的图书。", state.message)
        assertNull(state.renewResult)
    }

    @Test
    fun frozenUserCannotRenew() = runBlocking {
        val fake = FakeLibraryRepository(user = libraryUser(cardStatus = "3"))
        val model = libraryModel(fake)
        model.refresh()
        model.toggleSelect(1L)

        model.renewSelected()
        val state = model.state.value

        assertEquals(0, fake.renewCalls, "冻结用户不应发出续借请求")
        assertNotNull(state.message)
        assertTrue(state.message!!.contains("冻结"), "冻结提示应说明无法续借")
        assertNull(state.renewResult)
        assertEquals(setOf(1L), state.selectedLoanIds)
        assertFalse(state.renewing)
    }

    private fun libraryModel(repository: LibraryRepository): LibraryModel =
        LibraryModel(repository = repository, clock = FixedClock(testNow), timeZone = TimeZone.UTC)
}

/** kotlin.time.Clock 的最小固定实现：测试不依赖系统时间。 */
private class FixedClock(private val epochSeconds: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds)
}

/** 手写假仓库：可注入失败、可观察调用次数，续借成功时模拟服务器更新续借次数。 */
private class FakeLibraryRepository(
    var user: LibraryUser = libraryUser(),
    var loans: List<LibraryLoan> = listOf(loan(1), loan(2)),
    var history: List<LibraryLoan> = listOf(historyLoan(9)),
    var historyTotal: Int? = null,
    var userFailure: LibraryFailure? = null,
    var loansFailure: LibraryFailure? = null,
    var renewFailure: LibraryFailure? = null,
    var renewResult: LibraryRenewResult = LibraryRenewResult(attempted = 0, renewed = 0, failed = 0),
) : LibraryRepository {
    var userLoads = 0
    var loanLoads = 0
    var historyLoads = 0
    var renewCalls = 0
    var renewedIds: List<Long> = emptyList()

    override suspend fun loadUser(): LibraryUser {
        userLoads++
        userFailure?.let { throw LibraryException(it) }
        return user
    }

    override suspend fun loadLoans(page: Int, rows: Int): LibraryLoanPage {
        loanLoads++
        loansFailure?.let { throw LibraryException(it) }
        return LibraryLoanPage(loans = loans, total = loans.size, page = page, rows = rows)
    }

    override suspend fun loadHistory(page: Int, rows: Int): LibraryLoanPage {
        historyLoads++
        return LibraryLoanPage(
            loans = history,
            total = historyTotal ?: history.size,
            page = page,
            rows = rows,
        )
    }

    override suspend fun renew(loanIds: List<Long>): LibraryRenewResult {
        renewCalls++
        renewedIds = loanIds
        renewFailure?.let { throw LibraryException(it) }
        if (renewResult.anySucceeded) {
            loans = loans.map { it.copy(renewTimes = it.renewTimes + 1) }
        }
        return renewResult
    }
}

private fun libraryUser(cardStatus: String = "1"): LibraryUser = LibraryUser(
    name = "读者甲",
    readerBarcode = "R0000000001",
    cardStatus = cardStatus,
    userGroupName = "本科生",
    department = "计算机学院",
    major = "计算机科学与技术",
    deposit = 0,
    prepayment = 0,
    expiredBookCount = 0,
    unprocessedHours = 0,
)

private fun loan(loanId: Long, dueDate: String = "2026-08-05", renewTimes: Int = 0): LibraryLoan = LibraryLoan(
    loanId = loanId,
    title = "合成图书 $loanId",
    author = "合成作者 $loanId",
    callNo = "TP312/$loanId",
    locationName = "主馆",
    libName = "图书馆",
    loanDate = "2026-07-10",
    dueDate = dueDate,
    renewTimes = renewTimes,
)

private fun historyLoan(loanId: Long, returnDate: String = "2026-06-01"): LibraryLoan = LibraryLoan(
    loanId = loanId,
    title = "历史图书 $loanId",
    author = "合成作者 $loanId",
    loanDate = "2026-05-01",
    dueDate = "2026-05-30",
    returnDate = returnDate,
)

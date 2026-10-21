package team.bjtuss.bjtuselfservice.shared.data.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.SchoolNetworkException

/**
 * 图书馆数据层测试。全部使用合成数据（虚构书名、作者、条码和读者信息），
 * 只验证协议解析、失败分类和会话恢复策略，不接触真实服务器。
 */
class LibraryRemoteDataSourceTest {

    @Test
    fun parsesUserInfoAndFallsBackToDepartment() = runBlocking {
        val transport = QueueTransport(response(USER_INFO_BODY, url = LibraryEndpoints.USER_INFO))

        val user = SchoolLibraryRemoteDataSource(transport).userInfo()

        assertEquals("示*读者", user.name)
        assertEquals("SYN0001", user.readerBarcode)
        assertEquals("1", user.cardStatus)
        assertEquals("本科生", user.userGroupName)
        // collegeDept 为空时退回 department。
        assertEquals("示例学院", user.department)
        assertEquals("示例专业", user.major)
        assertEquals(0, user.deposit)
        assertEquals(0, user.prepayment)
        assertEquals(2, user.expiredBookCount)
        assertEquals(5, user.unprocessedHours)
        assertFalse(user.isFrozen)
        assertFalse(user.hasDebt)

        val request = transport.requests.single()
        assertEquals(SchoolHttpMethod.GET, request.method)
        assertEquals(LibraryEndpoints.USER_INFO, request.url)
        assertEquals("", request.headers["mappingPath"])
        assertEquals("", request.headers["groupCode"])
        assertEquals("CHI", request.headers["x-lang"])
        assertEquals("zh_CN", request.headers["content-language"])
        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
    }

    @Test
    fun parsesLoanListWithTotalAndMissingFields() = runBlocking {
        val transport = QueueTransport(response(LOAN_LIST_BODY))
        val page = SchoolLibraryRemoteDataSource(transport).loans(page = 2, rows = 20)

        // numFound 是 3，本页只有 2 条：总数必须以服务端为准。
        assertEquals(3, page.total)
        assertEquals(2, page.page)
        assertEquals(20, page.rows)
        assertEquals(2, page.loans.size)
        // 第 2 页已越过总数（3 条），不应再显示“加载更多”。
        assertFalse(page.hasMore)

        val first = page.loans[0]
        assertEquals(5001L, first.loanId)
        assertEquals(1001L, first.recordId)
        assertEquals(7001L, first.itemId)
        assertEquals("合成数据：图书馆借阅规则", first.title)
        assertEquals("示例作者", first.author)
        assertEquals("示例出版社", first.publisher)
        assertEquals("978-7-000-00000-0", first.isbn)
        assertEquals("G25/1", first.callNo)
        assertEquals("SYN-B-1", first.barcode)
        assertEquals("示例馆一层", first.locationName)
        assertEquals("示例馆", first.libName)
        assertEquals("2026-03-01", first.loanDate)
        assertEquals("2026-04-01", first.dueDate)
        assertNull(first.returnDate)
        assertFalse(first.isReturned)
        assertEquals(1, first.renewTimes)
        assertEquals(2, first.remainingRenewals)

        // 缺字段的记录按空串/0/null 容错，不能让整页失败。
        val second = page.loans[1]
        assertEquals(5002L, second.loanId)
        assertEquals("合成数据：缺少可选字段", second.title)
        assertEquals("", second.author)
        assertEquals("", second.callNo)
        assertEquals("", second.dueDate)
        assertNull(second.remainingRenewals)
        assertEquals(0, second.renewTimes)
        assertNull(second.recordId)
        assertNull(second.itemId)

        val request = transport.requests.single()
        assertEquals(SchoolHttpMethod.POST, request.method)
        assertEquals(LibraryEndpoints.LOAN_LIST, request.url)
        assertEquals(LibraryEndpoints.API_JSON_CONTENT_TYPE, request.rawBodyContentType)
        assertEquals("CHI", request.headers["x-lang"])
        val body = assertNotNull(request.rawBody).decodeToString()
        assertTrue(body.contains("\"page\":2"))
        assertTrue(body.contains("\"rows\":20"))
        assertTrue(body.contains("\"searchType\":1"))
        assertTrue(body.contains("\"searchContent\":\"\""))
        assertTrue(body.contains("\"sortType\":0"))
        assertTrue(body.contains("\"startDate\":null"))
        assertTrue(body.contains("\"endDate\":null"))
    }

    @Test
    fun parsesLoanHistoryWithReturnDate() = runBlocking {
        val transport = QueueTransport(response(LOAN_HISTORY_BODY, url = LibraryEndpoints.LOAN_HISTORY))
        val page = SchoolLibraryRemoteDataSource(transport).history(page = 1, rows = 50)

        // 历史响应没有 numFound，用本页条数兜底。
        assertEquals(1, page.total)
        assertEquals(1, page.page)
        assertEquals(50, page.rows)
        val loan = page.loans.single()
        assertEquals(4001L, loan.loanId)
        assertEquals(9001L, loan.recordId)
        assertEquals("合成数据：已归还记录", loan.title)
        assertEquals("2026-01-20", loan.returnDate)
        assertTrue(loan.isReturned)
        // 历史记录通常没有续借字段，按 0/null 处理。
        assertEquals(0, loan.renewTimes)
        assertNull(loan.remainingRenewals)
        assertEquals(LibraryEndpoints.LOAN_HISTORY, transport.requests.single().url)
    }

    @Test
    fun normalizesRenewSuccessResult() = runBlocking {
        val transport = QueueTransport(response(RENEW_SUCCESS_BODY, url = LibraryEndpoints.RENEW))

        val result = SchoolLibraryRemoteDataSource(transport).renew(listOf(5001L, 5002L))

        assertEquals(LibraryRenewResult(attempted = 2, renewed = 2, failed = 0), result)
        assertTrue(result.allSucceeded)
        val request = transport.requests.single()
        assertEquals(SchoolHttpMethod.POST, request.method)
        assertEquals(LibraryEndpoints.RENEW, request.url)
        assertEquals(LibraryEndpoints.API_JSON_CONTENT_TYPE, request.rawBodyContentType)
        assertEquals("{\"loanIds\":[5001,5002]}", assertNotNull(request.rawBody).decodeToString())
    }

    @Test
    fun normalizesSuccessfulRenewDueDatesWithoutTreatingThemAsFailures() = runBlocking {
        // 真实抓包：全部成功时 data.result 是 loanId -> 新应还日期；曾把它误当失败原因展示。
        val transport = QueueTransport(response(RENEW_SUCCESS_WITH_DATES_BODY, url = LibraryEndpoints.RENEW))

        val result = SchoolLibraryRemoteDataSource(transport).renew(listOf(5001L))

        assertEquals(0, result.failed)
        assertTrue(result.failureReasons.isEmpty(), "成功响应里的日期不能被当成失败原因")
        assertEquals(listOf("2026-11-20 17:50:25"), result.renewedDueDates)
        assertEquals("2026-11-20 17:50:25", result.newDueDate)
        assertTrue(result.allSucceeded)
    }

    @Test
    fun normalizesPartialRenewFailureReasons() = runBlocking {
        val transport = QueueTransport(response(RENEW_PARTIAL_BODY, url = LibraryEndpoints.RENEW))

        val result = SchoolLibraryRemoteDataSource(transport).renew(listOf(5001L, 5002L))

        assertEquals(2, result.attempted)
        assertEquals(1, result.renewed)
        assertEquals(1, result.failed)
        assertEquals(listOf("已达最大续借次数"), result.failureReasons)
        assertFalse(result.allSucceeded)
        assertTrue(result.anySucceeded)
    }

    @Test
    fun treatsMissingRenewDataAsAllFailed() = runBlocking {
        val transport = QueueTransport(response(RENEW_NO_DATA_BODY, url = LibraryEndpoints.RENEW))

        val result = SchoolLibraryRemoteDataSource(transport).renew(listOf(5001L, 5002L, 5003L))

        assertEquals(LibraryRenewResult(attempted = 3, renewed = 0, failed = 3), result)
        assertFalse(result.allSucceeded)
        assertFalse(result.anySucceeded)
    }

    @Test
    fun classifiesServerFailureFromEnvelope() = runBlocking {
        val error = assertFailsWith<LibraryException> {
            SchoolLibraryRemoteDataSource(QueueTransport(response(SERVER_ERROR_BODY)))
                .loans(page = 1, rows = 20)
        }

        assertEquals(LibraryFailureKind.SERVER, error.failure.kind)
        assertEquals("参数校验失败", error.failure.detail)
    }

    @Test
    fun classifiesSessionExpiredFromErrCodeAndHttpStatus() = runBlocking {
        val byEnvelope = assertFailsWith<LibraryException> {
            SchoolLibraryRemoteDataSource(
                QueueTransport(response(ERRCODE_401_BODY, url = LibraryEndpoints.USER_INFO)),
            ).userInfo()
        }
        assertEquals(LibraryFailureKind.SESSION_EXPIRED, byEnvelope.failure.kind)

        val byStatus = assertFailsWith<LibraryException> {
            SchoolLibraryRemoteDataSource(
                QueueTransport(response("", url = LibraryEndpoints.LOAN_HISTORY, statusCode = 401)),
            ).history(page = 1, rows = 20)
        }
        assertEquals(LibraryFailureKind.SESSION_EXPIRED, byStatus.failure.kind)
    }

    @Test
    fun classifiesBrokenJsonAsMalformedResponse() = runBlocking {
        val error = assertFailsWith<LibraryException> {
            SchoolLibraryRemoteDataSource(QueueTransport(response("{\"success\":true,")))
                .loans(page = 1, rows = 20)
        }

        assertEquals(LibraryFailureKind.MALFORMED_RESPONSE, error.failure.kind)
    }

    @Test
    fun classifiesTransportFailureAsNetwork() = runBlocking {
        val transport = QueueTransport()
        transport.failure = SchoolNetworkException("合成网络异常")

        val error = assertFailsWith<LibraryException> {
            SchoolLibraryRemoteDataSource(transport).renew(listOf(5001L))
        }

        assertEquals(LibraryFailureKind.NETWORK, error.failure.kind)
    }

    @Test
    fun repositoryRetriesOnceAfterSuccessfulSessionRecovery() = runBlocking {
        var attempts = 0
        val expected = samplePage()
        val remote = LambdaRemote(
            onLoans = {
                attempts++
                if (attempts == 1) throw LibraryException(LibraryFailure(LibraryFailureKind.SESSION_EXPIRED))
                expected
            },
        )
        val authenticator = RecordingAuthenticator()

        val page = DefaultLibraryRepository(remote, authenticator).loadLoans()

        assertEquals(expected, page)
        assertEquals(2, remote.loansCalls)
        assertEquals(1, authenticator.calls)
    }

    @Test
    fun repositoryRetriesRenewAfterSuccessfulSessionRecovery() = runBlocking {
        var attempts = 0
        val expected = LibraryRenewResult(attempted = 2, renewed = 2, failed = 0)
        val remote = LambdaRemote(
            onRenew = {
                attempts++
                if (attempts == 1) throw LibraryException(LibraryFailure(LibraryFailureKind.SESSION_EXPIRED))
                expected
            },
        )
        val authenticator = RecordingAuthenticator()

        val result = DefaultLibraryRepository(remote, authenticator).renew(listOf(5001L, 5002L))

        assertEquals(expected, result)
        assertEquals(2, remote.renewCalls)
        assertEquals(1, authenticator.calls)
    }

    @Test
    fun repositoryThrowsOriginalFailureWhenRecoveryFails() = runBlocking {
        val remote = LambdaRemote(
            onLoans = { throw LibraryException(LibraryFailure(LibraryFailureKind.SESSION_EXPIRED)) },
        )
        val authenticator = RecordingAuthenticator(LibraryFailure(LibraryFailureKind.LOGIN_REJECTED))

        val error = assertFailsWith<LibraryException> {
            DefaultLibraryRepository(remote, authenticator).loadLoans()
        }

        assertEquals(LibraryFailureKind.SESSION_EXPIRED, error.failure.kind)
        assertEquals(1, remote.loansCalls)
        assertEquals(1, authenticator.calls)
    }

    @Test
    fun repositoryRetriesOnlyOnceWhenSessionStaysExpired() = runBlocking {
        val remote = LambdaRemote(
            onLoans = { throw LibraryException(LibraryFailure(LibraryFailureKind.SESSION_EXPIRED)) },
        )
        val authenticator = RecordingAuthenticator()

        val error = assertFailsWith<LibraryException> {
            DefaultLibraryRepository(remote, authenticator).loadLoans()
        }

        assertEquals(LibraryFailureKind.SESSION_EXPIRED, error.failure.kind)
        assertEquals(2, remote.loansCalls)
        assertEquals(1, authenticator.calls)
    }

    @Test
    fun repositoryDoesNotRetryNonSessionFailures() = runBlocking {
        val remote = LambdaRemote(
            onLoans = { throw LibraryException(LibraryFailure(LibraryFailureKind.SERVER, "参数校验失败")) },
        )
        val authenticator = RecordingAuthenticator()

        val error = assertFailsWith<LibraryException> {
            DefaultLibraryRepository(remote, authenticator).loadLoans()
        }

        assertEquals(LibraryFailureKind.SERVER, error.failure.kind)
        assertEquals(1, remote.loansCalls)
        assertEquals(0, authenticator.calls)
    }

    /** 记录请求并按顺序返回响应；可注入一次传输层异常。 */
    private class QueueTransport(vararg responses: SchoolHttpResponse) : SchoolHttpTransport {
        private val queue = responses.toMutableList()
        val requests = mutableListOf<SchoolHttpRequest>()
        var failure: Exception? = null

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            requests += request
            failure?.let { throw it }
            return queue.removeFirst()
        }

        override fun clearSession() = Unit
    }

    /** 只实现被调用的方法，其余方法调用即报错，防止测试意外走到未声明的路径。 */
    private class LambdaRemote(
        private val onUser: suspend () -> LibraryUser = { error("unexpected userInfo call") },
        private val onLoans: suspend () -> LibraryLoanPage = { error("unexpected loans call") },
        private val onHistory: suspend () -> LibraryLoanPage = { error("unexpected history call") },
        private val onRenew: suspend () -> LibraryRenewResult = { error("unexpected renew call") },
    ) : LibraryRemoteDataSource {
        var userCalls = 0
        var loansCalls = 0
        var historyCalls = 0
        var renewCalls = 0

        override suspend fun userInfo(): LibraryUser {
            userCalls++
            return onUser()
        }

        override suspend fun loans(page: Int, rows: Int): LibraryLoanPage {
            loansCalls++
            return onLoans()
        }

        override suspend fun history(page: Int, rows: Int): LibraryLoanPage {
            historyCalls++
            return onHistory()
        }

        override suspend fun renew(loanIds: List<Long>): LibraryRenewResult {
            renewCalls++
            return onRenew()
        }
    }

    private class RecordingAuthenticator(
        private val failure: LibraryFailure? = null,
    ) : LibraryAuthenticator {
        var calls = 0

        override suspend fun ensureSession(): LibraryFailure? {
            calls++
            return failure
        }
    }

    private fun response(
        body: String,
        url: String = LibraryEndpoints.LOAN_LIST,
        statusCode: Int = 200,
    ): SchoolHttpResponse = SchoolHttpResponse(
        statusCode = statusCode,
        finalUrl = url,
        body = body.encodeToByteArray(),
    )

    @Test
    fun repositoryDropsCurrentLoansWithoutUsableIdAndDeduplicates() = runBlocking {
        fun loan(id: Long) = LibraryLoan(loanId = id, title = "合成数据", loanDate = "2026-03-01", dueDate = "2026-04-01")
        val remote = LambdaRemote(
            onLoans = {
                LibraryLoanPage(
                    loans = listOf(loan(0L), loan(5001L), loan(0L), loan(5001L), loan(5002L)),
                    total = 5,
                    page = 1,
                    rows = 100,
                )
            },
        )

        val page = DefaultLibraryRepository(remote, RecordingAuthenticator()).loadLoans()

        assertEquals(listOf(5001L, 5002L), page.loans.map(LibraryLoan::loanId))
    }

    private fun samplePage(): LibraryLoanPage = LibraryLoanPage(
        loans = listOf(
            LibraryLoan(
                loanId = 5001L,
                title = "合成数据：借阅记录",
                loanDate = "2026-03-01",
                dueDate = "2026-04-01",
            ),
        ),
        total = 1,
        page = 1,
        rows = 100,
    )

    private companion object {
        private val USER_INFO_BODY = """
            {"success":true,"message":"","errCode":0,"data":{"countExpiredBook":2,"unProcessBevihours":5,"user":{"name":"示*读者","primaryId":"SYN0001","cardStatus":"1","userGroupName":"本科生","collegeDept":"","department":"示例学院","majorName":"示例专业","deposit":0,"prepayment":0,"userBarcode":"SYN0001"}}}
        """.trimIndent()

        private val LOAN_LIST_BODY = """
            {"success":true,"message":"","errCode":0,"data":{"numFound":3,"searchResult":[
            {"recordId":1001,"loanId":5001,"itemId":7001,"title":"合成数据：图书馆借阅规则","author":"示例作者","publisher":"示例出版社","isbn":"978-7-000-00000-0","callNo":"G25/1","barcode":"SYN-B-1","locationName":"示例馆一层","phyLibName":"示例馆","loanDate":"2026-03-01","normReturnDate":"2026-04-01","renewTimes":1,"surplusRenewTimes":2,"isRequested":"否"},
            {"loanId":5002,"title":"合成数据：缺少可选字段"}
            ]}}
        """.trimIndent()

        private val LOAN_HISTORY_BODY = """
            {"success":true,"message":"","errCode":0,"data":{"searchResult":[
            {"recordId":9001,"loanId":4001,"title":"合成数据：已归还记录","author":"示例作者","publisher":"示例出版社","isbn":"","barcode":"SYN-H-1","locationName":"示例馆二层","phyLibName":"示例馆","loanDate":"2026-01-05","normReturnDate":"2026-02-05","returnDate":"2026-01-20","isRequested":"否"}
            ]}}
        """.trimIndent()

        private val RENEW_SUCCESS_BODY =
            """{"success":true,"message":"","errCode":0,"data":{"success":2,"fail":0,"result":{}}}"""

        private val RENEW_SUCCESS_WITH_DATES_BODY =
            """{"success":true,"message":"","errCode":0,"data":{"success":1,"fail":0,"result":{"5001":"2026-11-20 17:50:25"}}}"""

        private val RENEW_PARTIAL_BODY =
            """{"success":true,"message":"","errCode":0,"data":{"success":1,"fail":1,"result":{"5001":"已达最大续借次数"}}}"""

        private val RENEW_NO_DATA_BODY = """{"success":true,"message":"","errCode":0}"""

        private val SERVER_ERROR_BODY =
            """{"success":false,"message":"参数校验失败","errCode":9999}"""

        private val ERRCODE_401_BODY =
            """{"success":false,"message":"会话已失效","errCode":401}"""
    }
}

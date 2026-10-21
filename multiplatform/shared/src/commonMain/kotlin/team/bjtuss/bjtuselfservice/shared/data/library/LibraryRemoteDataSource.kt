package team.bjtuss.bjtuselfservice.shared.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.looksLikeSessionExpired
import team.bjtuss.bjtuselfservice.shared.network.parseOffMain

/** opac 的响应是同源 JSON，但部分字段类型不稳定；解析保持宽松，缺字段按空值处理。 */
private val libraryJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 真实抓包得到的固定请求头。
 * 缺少 x-lang/content-language 时服务端会返回空壳 data（看起来像“没有借阅”），必须一并发送。
 */
private val libraryRequestHeaders = mapOf(
    "mappingPath" to "",
    "groupCode" to "",
    "x-lang" to "CHI",
    "content-language" to "zh_CN",
    "X-Requested-With" to "XMLHttpRequest",
)

/** 图书馆数据源；会话由外部 Cookie jar 维护，这里不做登录。 */
interface LibraryRemoteDataSource {
    suspend fun userInfo(): LibraryUser

    suspend fun loans(page: Int, rows: Int): LibraryLoanPage

    suspend fun history(page: Int, rows: Int): LibraryLoanPage

    suspend fun renew(loanIds: List<Long>): LibraryRenewResult
}

/**
 * opac.bjtu.edu.cn 的 JSON 适配器。
 *
 * 只依赖已建立的登录 Cookie（见 [LibraryEndpoints] 的 SSO 授权链）；
 * 会话过期在这里统一抛 [LibraryFailureKind.SESSION_EXPIRED]，
 * 由 `DefaultLibraryRepository` 决定是否重新登录并重试一次。
 */
class SchoolLibraryRemoteDataSource(
    private val transport: SchoolHttpTransport,
) : LibraryRemoteDataSource {

    override suspend fun userInfo(): LibraryUser {
        val root = requestJson(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = LibraryEndpoints.USER_INFO,
                headers = libraryRequestHeaders,
            ),
        )
        val data = root["data"] as? JsonObject ?: malformed()
        val user = data["user"] as? JsonObject ?: malformed()
        return LibraryUser(
            // 学校把姓名脱敏成“张*”这类形式，原样保留，不做还原。
            name = user.string("name"),
            readerBarcode = user.string("userBarcode"),
            cardStatus = user.string("cardStatus"),
            userGroupName = user.string("userGroupName"),
            // collegeDept 为空时退回 department 字段（不同账号两种字段只有一种有值）。
            department = user.string("collegeDept").ifBlank { user.string("department") },
            major = user.string("majorName"),
            deposit = user.int("deposit"),
            prepayment = user.int("prepayment"),
            expiredBookCount = data.int("countExpiredBook"),
            unprocessedHours = data.int("unProcessBevihours"),
        )
    }

    override suspend fun loans(page: Int, rows: Int): LibraryLoanPage =
        requestLoanPage(LibraryEndpoints.LOAN_LIST, page, rows)

    override suspend fun history(page: Int, rows: Int): LibraryLoanPage =
        requestLoanPage(LibraryEndpoints.LOAN_HISTORY, page, rows)

    override suspend fun renew(loanIds: List<Long>): LibraryRenewResult {
        val body = buildJsonObject {
            put(
                "loanIds",
                buildJsonArray {
                    loanIds.forEach { loanId -> add(JsonPrimitive(loanId)) }
                },
            )
        }.toString().encodeToByteArray()
        val root = requestJson(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = LibraryEndpoints.RENEW,
                headers = libraryRequestHeaders,
                rawBody = body,
                rawBodyContentType = LibraryEndpoints.API_JSON_CONTENT_TYPE,
            ),
        )
        // data 缺失时无法区分哪一本失败，按“全部尝试都失败”处理，界面可以提示重试。
        val data = root["data"] as? JsonObject
            ?: return LibraryRenewResult(
                attempted = loanIds.size,
                renewed = 0,
                failed = loanIds.size,
            )
        // data.result 的语义取决于 fail：失败时是 loanId -> 失败原因，全部成功时是
        // loanId -> 新的应还日期。之前把成功时的日期当失败原因显示，是续借成功却提示失败的根因。
        val failed = data.int("fail")
        val resultValues = (data["result"] as? JsonObject)
            ?.values
            ?.mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return LibraryRenewResult(
            attempted = loanIds.size,
            renewed = data.int("success"),
            failed = failed,
            failureReasons = if (failed > 0) resultValues.distinct() else emptyList(),
            renewedDueDates = if (failed == 0) resultValues.distinct() else emptyList(),
        )
    }

    /** 当前借阅与历史借阅共用同一套分页请求和字段映射，只是历史记录多一个 returnDate。 */
    private suspend fun requestLoanPage(url: String, page: Int, rows: Int): LibraryLoanPage {
        val body = buildJsonObject {
            put("page", page)
            put("rows", rows)
            put("searchType", 1)
            put("searchContent", "")
            put("sortType", 0)
            put("startDate", JsonNull)
            put("endDate", JsonNull)
        }.toString().encodeToByteArray()
        val root = requestJson(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = url,
                headers = libraryRequestHeaders,
                rawBody = body,
                rawBodyContentType = LibraryEndpoints.API_JSON_CONTENT_TYPE,
            ),
        )
        val data = root["data"] as? JsonObject ?: malformed()
        val rawLoans = data["searchResult"] as? JsonArray
        val loans = rawLoans.orEmpty()
            .mapNotNull { item -> item as? JsonObject }
            .map(::parseLoan)
        return LibraryLoanPage(
            loans = loans,
            // numFound 缺失时（空结果或接口裁剪）用本页条数兜底，避免界面显示“0 条”却列出了记录。
            total = data.intOrNull("numFound") ?: loans.size,
            page = page,
            rows = rows,
        )
    }

    private fun parseLoan(raw: JsonObject): LibraryLoan = LibraryLoan(
        // 历史记录偶尔没有 loanId；退回 recordId，再退回 0，续借时界面按 0 过滤。
        loanId = raw.longOrNull("loanId") ?: raw.longOrNull("recordId") ?: 0L,
        recordId = raw.longOrNull("recordId"),
        itemId = raw.longOrNull("itemId"),
        title = raw.string("title"),
        author = raw.string("author"),
        publisher = raw.string("publisher"),
        isbn = raw.string("isbn"),
        callNo = raw.string("callNo"),
        barcode = raw.string("barcode"),
        locationName = raw.string("locationName"),
        libName = raw.string("phyLibName"),
        loanDate = raw.string("loanDate"),
        dueDate = raw.string("normReturnDate"),
        returnDate = raw.stringOrNull("returnDate")?.takeIf { it.isNotBlank() },
        renewTimes = raw.int("renewTimes"),
        remainingRenewals = raw.intOrNull("surplusRenewTimes"),
    )

    /**
     * 发送请求并拆掉 `{success,message,errCode,data}` 外壳。
     *
     * 失败分类顺序：HTTP 401/403 或 errCode==401 → 会话过期；success=false → 服务端错误；
     * 解析不出 JSON 对象 → 格式变化；传输层异常 → 网络失败。
     */
    private suspend fun requestJson(request: SchoolHttpRequest): JsonObject {
        val response = execute(request)
        if (response.statusCode == 401 || response.statusCode == 403) sessionExpired()
        if (response.statusCode !in 200..299) {
            throw LibraryException(LibraryFailure(LibraryFailureKind.NETWORK))
        }
        // 会话过期时学校可能用 HTTP 200 返回 SSO/登录页而不是 401；先排除这种页面，
        // 否则会被误报成“数据格式有变化”。整页判断和 JSON 解析都放到后台线程。
        val (expired, parsed) = parseOffMain {
            val looksExpired = response.looksLikeSessionExpired()
            val element = if (looksExpired) {
                null
            } else {
                runCatching { libraryJson.parseToJsonElement(response.bodyText()) }.getOrNull()
            }
            looksExpired to element
        }
        if (expired) sessionExpired()
        val root = parsed as? JsonObject ?: malformed()
        if (root.intOrNull("errCode") == 401) sessionExpired()
        if (root.booleanOrNull("success") == false) {
            throw LibraryException(
                LibraryFailure(LibraryFailureKind.SERVER, root.stringOrNull("message")),
            )
        }
        return root
    }

    private suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = try {
        transport.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // SchoolNetworkException 与平台传输异常都归为网络失败；不把异常文本带进界面。
        throw LibraryException(LibraryFailure(LibraryFailureKind.NETWORK), error)
    }

    private fun malformed(): Nothing =
        throw LibraryException(LibraryFailure(LibraryFailureKind.MALFORMED_RESPONSE))

    private fun sessionExpired(): Nothing =
        throw LibraryException(LibraryFailure(LibraryFailureKind.SESSION_EXPIRED))
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.string(key: String): String = stringOrNull(key).orEmpty()

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonObject.int(key: String): Int = intOrNull(key) ?: 0

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.toBooleanStrictOrNull()

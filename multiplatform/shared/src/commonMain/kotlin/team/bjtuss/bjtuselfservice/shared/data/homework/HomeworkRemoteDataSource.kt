package team.bjtuss.bjtuselfservice.shared.data.homework

import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import team.bjtuss.bjtuselfservice.shared.auth.ParseResult
import team.bjtuss.bjtuselfservice.shared.auth.parseAcademicRedirectUrl
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkDetail
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.homework.SubmittedHomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.domain.homework.stableKey
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.SchoolMultipartFile

private const val SMART_MODULE_URL = "https://mis.bjtu.edu.cn/module/module/28/"
private const val ARTICLE_PATH = "/ve/back/coursePlatform/message.shtml"
private const val SEMESTER_PATH = "/ve/back/rp/common/teachCalendar.shtml"
private const val COURSE_PATH = "/ve/back/coursePlatform/course.shtml"
private const val HOMEWORK_PATH = "/ve/back/coursePlatform/homeWork.shtml"
private const val GRADE_PATH = "/ve/back/course/courseWorkInfo.shtml"
private const val ATTACHMENT_PATH = "/ve/back/coursePlatform/dataSynAction.shtml"
private const val SUBMITTED_ATTACHMENT_PATH = "/ve//downloadZyFj.shtml"
private const val UPLOAD_PATH = "/ve/back/rp/common/rpUpload.shtml"
private const val MAX_CONCURRENT_HOMEWORK_REQUESTS = 3

enum class HomeworkRemoteFailure {
    NETWORK,
    SESSION_EXPIRED,
    MALFORMED_RESPONSE,
    SECURE_CHANNEL_UNAVAILABLE,
}

class HomeworkRemoteException(
    val reason: HomeworkRemoteFailure,
) : Exception("Unable to refresh homework: ${reason.name}")

interface HomeworkRemoteDataSource {
    suspend fun fetchHomework(): List<Homework>
    suspend fun fetchDetail(homework: Homework): HomeworkDetail
    suspend fun fetchSubmittedAttachments(homework: Homework): List<SubmittedHomeworkAttachment>
    suspend fun downloadTeacherAttachment(
        homeworkId: Int,
        attachment: HomeworkAttachment,
    ): HomeworkFileContent
    suspend fun downloadSubmittedAttachment(
        attachment: SubmittedHomeworkAttachment,
    ): HomeworkFileContent
    suspend fun submitHomework(
        homework: Homework,
        content: String,
        files: List<HomeworkFileContent>,
    )
    fun attachmentDownloadUrl(homeworkId: Int, attachmentId: Int): String
}

/**
 * 默认只允许学校 HTTPS 域名。macOS 可在用户明确授权后注入封闭的旧 HTTP 端点；
 * 每次重定向仍重新校验 origin，sessionid 只保存在该实例内存中。
 */
class SchoolHomeworkRemoteDataSource(
    private val transport: SchoolHttpTransport,
    private val requestDelayMillis: Long = 0,
    private val endpoint: SmartPlatformEndpoint = SmartPlatformEndpoint.VerifiedHttps,
) : HomeworkRemoteDataSource {
    private var initialized = false
    private var sessionId: String? = null
    private var courses: List<SmartCourse> = emptyList()

    override suspend fun fetchHomework(): List<Homework> {
        ensureInitialized()
        val listRequests = buildList {
            courses.forEach { course ->
                (0..2).forEach { homeworkType ->
                    add(HomeworkListRequest(course, homeworkType))
                }
            }
        }
        val listSlots = Semaphore(MAX_CONCURRENT_HOMEWORK_REQUESTS)
        val responses = coroutineScope {
            listRequests.map { request ->
                async {
                    listSlots.withPermit {
                        val response = smartGet(
                            path = HOMEWORK_PATH,
                            query = linkedMapOf(
                                "method" to "getHomeWorkList",
                                "cId" to request.course.id.toString(),
                                "subType" to request.homeworkType.toString(),
                                "page" to "1",
                                "pagesize" to "100",
                            ),
                        )
                        when (val parsed = parseHomeworkList(response.bodyText(), request.homeworkType)) {
                            is HomeworkJsonParseResult.Failure -> HomeworkListResponse(
                                homework = emptyList(),
                                malformed = true,
                            )
                            is HomeworkJsonParseResult.Success -> HomeworkListResponse(
                                homework = parsed.value,
                                malformed = false,
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        if (responses.any(HomeworkListResponse::malformed)) malformed()
        val unique = responses.flatMap(HomeworkListResponse::homework).distinctBy(Homework::stableKey)
        val scoreSlots = Semaphore(MAX_CONCURRENT_HOMEWORK_REQUESTS)
        return coroutineScope {
            unique.map { item ->
                async { scoreSlots.withPermit { item.withBestEffortScore() } }
            }.awaitAll()
        }
    }

    override suspend fun fetchDetail(homework: Homework): HomeworkDetail {
        ensureInitialized()
        val teacherId = courses.firstOrNull { it.id == homework.courseId }?.teacherId
            ?: malformed()
        val response = smartGet(
            path = HOMEWORK_PATH,
            query = linkedMapOf(
                "method" to "queryStudentCourseNote",
                "id" to homework.upId.toString(),
                "courseId" to homework.courseId.toString(),
                "teacherId" to teacherId.toString(),
            ),
        )
        return when (val parsed = parseHomeworkDetail(response.bodyText(), homework.content)) {
            is HomeworkJsonParseResult.Failure -> malformed()
            is HomeworkJsonParseResult.Success -> parsed.value
        }
    }

    override suspend fun fetchSubmittedAttachments(
        homework: Homework,
    ): List<SubmittedHomeworkAttachment> {
        ensureInitialized()
        val response = smartGet(
            path = GRADE_PATH,
            query = linkedMapOf(
                "method" to "piGaiDiv",
                "upId" to homework.upId.toString(),
                "id" to homework.idSnId.orEmptyNumber(),
                "score" to homework.score,
                "uLevel" to "1",
                "type" to "1",
                "username" to "null",
                "userId" to homework.userId.toString(),
            ),
        )
        // 已提交附件接口以 GBK 返回中文文件名（真实观察为西里尔/拉丁扩展乱码）；
        // bodyTextGbk 在不支持 GB18030 的平台安全回退 UTF-8。
        return when (val parsed = parseSubmittedHomeworkAttachments(response.bodyTextGbk())) {
            SubmittedHomeworkParseResult.Failure -> malformed()
            is SubmittedHomeworkParseResult.Success -> parsed.attachments
        }
    }

    override suspend fun downloadTeacherAttachment(
        homeworkId: Int,
        attachment: HomeworkAttachment,
    ): HomeworkFileContent {
        ensureInitialized()
        val response = smartGetUrl(attachmentDownloadUrl(homeworkId, attachment.id))
        return response.toFileContent(attachment.fileName)
    }

    override suspend fun downloadSubmittedAttachment(
        attachment: SubmittedHomeworkAttachment,
    ): HomeworkFileContent {
        ensureInitialized()
        val response = smartGet(
            path = SUBMITTED_ATTACHMENT_PATH,
            query = linkedMapOf(
                "path" to attachment.sourcePath,
                "filename" to attachment.fileName,
                "id" to attachment.id,
            ),
        )
        return response.toFileContent(attachment.fileName)
    }

    override suspend fun submitHomework(
        homework: Homework,
        content: String,
        files: List<HomeworkFileContent>,
    ) {
        require(files.isNotEmpty()) { "At least one homework file is required" }
        ensureInitialized()
        val receipts = files.map { file ->
            val upload = smartRequest(
                method = SchoolHttpMethod.POST,
                path = UPLOAD_PATH,
                query = linkedMapOf(),
                // 原 Android 1.7.0 的 HomeworkUploader 直接用 OkHttp Request，
                // 不附加 X-Requested-With / sessionid / Referer 业务头，只让 CookieJar
                // 携带登录 Cookie。旧上传接口会因 AJAX 头得到 STATUS/MSG 错误回执。
                includeSmartHeaders = false,
                multipartFiles = listOf(
                    SchoolMultipartFile(
                        fieldName = "file",
                        fileName = file.fileName,
                        // 与原作者 Android 1.7.0 的 HomeworkUploader 一致：
                        // rpUpload.shtml 按通用二进制流接收文件，不使用系统推断的 MIME。
                        contentType = "application/octet-stream",
                        bytes = file.bytes,
                    ),
                ),
            )
            val uploadBody = upload.bodyText()
            when (val parsed = parseHomeworkUploadReceipt(uploadBody)) {
                is HomeworkJsonParseResult.Failure -> {
                    if (uploadResponseLooksLikeSessionExpired(upload)) {
                        invalidateSmartSession()
                        sessionExpired()
                    }
                    println(
                        "Homework upload receipt rejected: field=${parsed.field}, " +
                            uploadReceiptShape(upload),
                    )
                    malformed()
                }
                is HomeworkJsonParseResult.Success -> parsed.value
            }
        }
        val submit = smartRequest(
            method = SchoolHttpMethod.POST,
            path = GRADE_PATH,
            query = linkedMapOf("method" to "sendStuHomeWorks"),
            // 与原作者 Android 1.7.0 的 FormBody 请求保持一致：写请求不附加
            // 智慧平台查询接口专用的 AJAX 和 sessionid 头。
            includeSmartHeaders = false,
            formFields = linkedMapOf(
                // v1.7.0 在 FormBody 编码前先做一次 URLEncoder；服务端按两层解码处理。
                "content" to content.formValuePreEncode(),
                "groupName" to "",
                "groupId" to "",
                "courseId" to homework.courseId.toString(),
                "contentType" to homework.homeworkType.toString(),
                "fz" to "0",
                "jxrl_id" to "",
                "fileList" to receipts.toUploadFileListJson(),
                "upId" to homework.upId.toString(),
                "return_num" to "",
                "isTeacher" to "0",
            ),
        )
        // 原作者 Android 1.7.0 的实现只依赖 HTTP 成功。这个老接口的 2xx 响应正文
        // 在不同网关上可能为空、返回中文，或返回不稳定的文本；不能把正文关键字
        // 当成提交失败依据，否则服务端已接收时客户端会误报失败。
    }

    override fun attachmentDownloadUrl(homeworkId: Int, attachmentId: Int): String = endpoint.apiUrl(
        path = ATTACHMENT_PATH,
        query = linkedMapOf(
            "method" to "downLoadPic",
            "id" to attachmentId.toString(),
            "noteId" to homeworkId.toString(),
        ),
    )

    private suspend fun ensureInitialized() {
        if (initialized) return
        val module = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = SMART_MODULE_URL,
                headers = mapOf("Referer" to "https://mis.bjtu.edu.cn/home/"),
            ),
        )
        // 登录态下 module 28 直接以裸 3xx 指向智慧平台明文入口；Ktor 拒绝
        // HTTPS→HTTP 降级跟随，因此这里逐跳手动跟随 OAuth 链（明文跳限
        // 精确 apiOrigin，HTTPS 跳限 cas/mis 学校主机），直到落地。
        val settled = endpoint.followSmartHandshakeRedirects(
            first = module,
            referer = SMART_MODULE_URL,
        ) { request -> execute(request) }
        if (settled !== module || settled.statusCode in 300..399) {
            // 走过了至少一跳；最终落地必须是白名单握手地址且 2xx。
            if (settled.statusCode in 300..399) {
                // 握手链停在了未放行的跳转（HTTPS 策略下即明文降级目标），
                // 属于安全拒绝而非网络故障，避免误报为"请检查网络"。
                secureChannelUnavailable()
            }
            if (settled.statusCode !in 200..299) network()
            if (!endpoint.acceptsHandshakeUrl(settled.finalUrl)) secureChannelUnavailable()
        }

        if (!endpoint.acceptsHandshakeUrl(settled.finalUrl) && settled === module) {
            // 兼容旧行为：响应为 200 HTML 表单跳转时从 <form id="redirect"> 解析。
            run {
                val redirect = when (val parsed = parseAcademicRedirectUrl(module.bodyText())) {
                    is ParseResult.Failure -> secureChannelUnavailable()
                    is ParseResult.Success -> parsed.value
                }
                if (!endpoint.acceptsHandshakeUrl(redirect)) secureChannelUnavailable()
                val linked = execute(
                    SchoolHttpRequest(
                        method = SchoolHttpMethod.GET,
                        url = redirect,
                        headers = mapOf("Referer" to SMART_MODULE_URL),
                    ),
                )
                if (linked.statusCode !in 200..299) network()
                if (!endpoint.acceptsHandshakeUrl(linked.finalUrl)) secureChannelUnavailable()
            }
        }

        // 智慧平台会话经握手最后一跳的 Set-Cookie: JSESSIONID 下发（见 settled 响应）；
        // 优先从 transport Cookie 存储读取，旧版 article JSON 解析仅作回退。
        // Cookie 同时被 transport 自动携带，sessionid 自定义头沿用原 Android 协议语义。
        sessionId = transport.sessionCookiesFor(endpoint.apiOrigin)
            .firstOrNull { it.name.equals("JSESSIONID", ignoreCase = true) }
            ?.value

        val article = smartGet(
            path = ARTICLE_PATH,
            query = linkedMapOf("method" to "getArticleList"),
            includeSession = sessionId != null,
        )
        if (sessionId == null) {
            sessionId = when (val parsed = parseSmartSessionId(article.bodyText())) {
                is HomeworkJsonParseResult.Failure -> malformed()
                is HomeworkJsonParseResult.Success -> parsed.value
            }
        }

        val semester = smartGet(
            path = SEMESTER_PATH,
            query = linkedMapOf("method" to "queryCurrentXq"),
        )
        val semesterCode = when (val parsed = parseCurrentSemesterCode(semester.bodyText())) {
            is HomeworkJsonParseResult.Failure -> malformed()
            is HomeworkJsonParseResult.Success -> parsed.value
        }
        if (semesterCode.isBlank()) {
            courses = emptyList()
            initialized = true
            return
        }

        val courseResponse = smartGet(
            path = COURSE_PATH,
            query = linkedMapOf(
                "method" to "getCourseList",
                "pagesize" to "100",
                "page" to "1",
                "xqCode" to semesterCode,
            ),
        )
        courses = when (val parsed = parseSmartCourses(courseResponse.bodyText())) {
            is HomeworkJsonParseResult.Failure -> malformed()
            is HomeworkJsonParseResult.Success -> parsed.value
        }
        initialized = true
    }

    private suspend fun Homework.withBestEffortScore(): Homework {
        if (scoreId == 0) return this
        return try {
            val response = smartGet(
                path = GRADE_PATH,
                query = linkedMapOf(
                    "method" to "piGaiDiv",
                    "upId" to upId.toString(),
                    "id" to idSnId.orEmptyNumber(),
                    "uLevel" to "1",
                ),
            )
            val parsedScore = Ksoup.parse(response.bodyText())
                .selectFirst("#oldScore")
                ?.attr("value")
                .orEmpty()
            if (parsedScore.isBlank()) this else copy(score = parsedScore)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            this
        }
    }

    private suspend fun smartGet(
        path: String,
        query: LinkedHashMap<String, String>,
        includeSession: Boolean = true,
    ): SchoolHttpResponse = smartRequest(
        method = SchoolHttpMethod.GET,
        path = path,
        query = query,
        includeSession = includeSession,
    )

    private suspend fun smartGetUrl(url: String): SchoolHttpResponse {
        if (!endpoint.acceptsApiUrl(url)) secureChannelUnavailable()
        return smartRequest(
            method = SchoolHttpMethod.GET,
            path = url.removePrefix(endpoint.apiOrigin).substringBefore('?'),
            query = url.queryParameters(),
        )
    }

    private suspend fun smartRequest(
        method: SchoolHttpMethod,
        path: String,
        query: LinkedHashMap<String, String>,
        formFields: LinkedHashMap<String, String> = linkedMapOf(),
        multipartFiles: List<SchoolMultipartFile> = emptyList(),
        includeSession: Boolean = true,
        includeSmartHeaders: Boolean = true,
    ): SchoolHttpResponse {
        val headers = if (includeSmartHeaders) {
            linkedMapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                "Referer" to endpoint.apiOrigin,
                "X-Requested-With" to "XMLHttpRequest",
            ).also {
                if (includeSession) {
                    sessionId?.let { session -> it["sessionid"] = session }
                }
            }
        } else {
            linkedMapOf()
        }
        val response = execute(
            SchoolHttpRequest(
                method = method,
                url = endpoint.apiUrl(path, query),
                headers = headers,
                formFields = formFields,
                multipartFiles = multipartFiles,
            ),
        )
        if (
            !endpoint.isLegacyInsecure &&
            path == ARTICLE_PATH &&
            response.statusCode == 404 &&
            endpoint.acceptsApiUrl(response.finalUrl)
        ) {
            secureChannelUnavailable()
        }
        if (response.statusCode !in 200..299) network()
        if (!endpoint.acceptsApiUrl(response.finalUrl)) {
            invalidateSmartSession()
            sessionExpired()
        }
        return response
    }

    private fun invalidateSmartSession() {
        initialized = false
        sessionId = null
        courses = emptyList()
    }

    private suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = try {
        if (requestDelayMillis > 0) delay(requestDelayMillis)
        transport.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        network()
    }
}

private data class HomeworkListRequest(
    val course: SmartCourse,
    val homeworkType: Int,
)

private data class HomeworkListResponse(
    val homework: List<Homework>,
    val malformed: Boolean,
)

private fun String.queryParameters(): LinkedHashMap<String, String> {
    val query = substringAfter('?', missingDelimiterValue = "")
    if (query.isEmpty()) return linkedMapOf()
    return query.split('&').mapNotNull { part ->
        val name = part.substringBefore('=', missingDelimiterValue = "")
        val value = part.substringAfter('=', missingDelimiterValue = "")
        if (name.isEmpty()) null else name.urlDecode() to value.urlDecode()
    }.toMap(linkedMapOf())
}

private fun String.formValuePreEncode(): String = buildString {
    encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        val isFormSafe = value in 'A'.code..'Z'.code ||
            value in 'a'.code..'z'.code ||
            value in '0'.code..'9'.code ||
            value == '-'.code || value == '_'.code || value == '.'.code || value == '*'.code
        when {
            isFormSafe -> append(value.toChar())
            value == ' '.code -> append('+')
            else -> {
                append('%')
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }
}

private fun String.urlDecode(): String {
    val result = mutableListOf<Byte>()
    var cursor = 0
    while (cursor < length) {
        if (this[cursor] == '%' && cursor + 2 < length) {
            val high = this[cursor + 1].hexValueOrNull()
            val low = this[cursor + 2].hexValueOrNull()
            if (high != null && low != null) {
                result += ((high shl 4) or low).toByte()
                cursor += 3
                continue
            }
        }
        result += this[cursor].code.toByte()
        cursor++
    }
    return result.toByteArray().decodeToString()
}

private fun Char.hexValueOrNull(): Int? = when (this) {
    in '0'..'9' -> code - '0'.code
    in 'a'..'f' -> code - 'a'.code + 10
    in 'A'..'F' -> code - 'A'.code + 10
    else -> null
}

private fun SchoolHttpResponse.toFileContent(suggestedName: String): HomeworkFileContent {
    if (body.isEmpty()) malformed()
    val contentType = headers.entries.firstOrNull { (name, _) ->
        name.equals("Content-Type", ignoreCase = true)
    }?.value?.firstOrNull()?.substringBefore(';')?.trim().orEmpty()
    return HomeworkFileContent(
        fileName = suggestedName,
        contentType = contentType.ifBlank { "application/octet-stream" },
        bytes = body,
    )
}

/** 只记录上传回执的结构，避免把文件名、路径、正文或会话值写入日志。 */
private fun uploadReceiptShape(response: SchoolHttpResponse): String {
    val body = response.bodyText().trim()
    val keys = parseStrictJsonObject(body)?.keys
        ?.sorted()
        ?.joinToString(",")
        ?: "non-json"
    val contentType = response.header("Content-Type")
        ?.substringBefore(';')
        ?.trim()
        ?.take(80)
        .orEmpty()
        .ifBlank { "unknown" }
    return "status=${response.statusCode},bytes=${response.body.size},contentType=$contentType,keys=$keys"
}

/** 老接口把会话失效混成 HTTP 200 的 STATUS/MSG 或登录 HTML，不能按普通 JSON 缺字段处理。 */
private fun uploadResponseLooksLikeSessionExpired(response: SchoolHttpResponse): Boolean {
    val normalized = (response.bodyText() + "\n" + response.bodyTextGbk()).lowercase()
    if (listOf(
            "会话结束",
            "会话失效",
            "未登录",
            "登录失效",
            "登录超时",
            "重新登录",
            "请退出系统",
            "session expired",
            "session timeout",
            "not logged",
        ).any(normalized::contains)
    ) {
        return true
    }
    return false
}

private fun List<HomeworkUploadReceipt>.toUploadFileListJson(): String = joinToString(
    prefix = "[",
    postfix = "]",
) { receipt ->
    "{" +
        "\"fileNameNoExt\":\"${receipt.fileNameNoExt.jsonEscape()}\"," +
        "\"fileExtName\":\"${receipt.fileExtName.jsonEscape()}\"," +
        "\"fileSize\":\"${receipt.fileSize.jsonEscape()}\"," +
        "\"visitName\":\"${receipt.visitName.jsonEscape()}\"," +
        "\"pid\":\"\",\"ftype\":\"insert\"}"
}

private fun String.jsonEscape(): String = buildString {
    this@jsonEscape.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}

private fun Int?.orEmptyNumber(): String = this?.toString().orEmpty()

private fun network(): Nothing = throw HomeworkRemoteException(HomeworkRemoteFailure.NETWORK)
private fun sessionExpired(): Nothing = throw HomeworkRemoteException(HomeworkRemoteFailure.SESSION_EXPIRED)
private fun malformed(): Nothing = throw HomeworkRemoteException(HomeworkRemoteFailure.MALFORMED_RESPONSE)
private fun secureChannelUnavailable(): Nothing =
    throw HomeworkRemoteException(HomeworkRemoteFailure.SECURE_CHANNEL_UNAVAILABLE)

private const val HEX = "0123456789ABCDEF"

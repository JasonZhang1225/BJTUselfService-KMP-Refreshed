package team.bjtuss.bjtuselfservice.shared.data.courseware

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkJsonParseResult
import team.bjtuss.bjtuselfservice.shared.data.homework.SmartCourse
import team.bjtuss.bjtuselfservice.shared.data.homework.SmartPlatformEndpoint
import team.bjtuss.bjtuselfservice.shared.data.homework.openSmartPlatform
import team.bjtuss.bjtuselfservice.shared.data.homework.SmartHandshakeException
import team.bjtuss.bjtuselfservice.shared.data.homework.SmartHandshakeFailure
import team.bjtuss.bjtuselfservice.shared.data.homework.parseCurrentSemesterCode
import team.bjtuss.bjtuselfservice.shared.data.homework.parseSmartCourses
import team.bjtuss.bjtuselfservice.shared.data.homework.parseSmartSessionId
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNode
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNodeKind
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareSnapshot
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson
import team.bjtuss.bjtuselfservice.shared.data.homework.isSchoolPlaybackStreamUrl
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.files.StreamingFileSink
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.looksLikeSessionExpired

private const val ARTICLE_PATH = "/ve/back/coursePlatform/message.shtml"
private const val SEMESTER_PATH = "/ve/back/rp/common/teachCalendar.shtml"
private const val COURSE_PATH = "/ve/back/coursePlatform/course.shtml"
private const val RESOURCE_PATH = "/ve/back/coursePlatform/courseResource.shtml"
private const val DOWNLOAD_TICKET_PATH = "/ve/back/resourceSpace.shtml"
private const val COURSE_PLATFORM_PATH = "/ve/back/coursePlatform/coursePlatform.shtml"
private const val RESOURCE_INFO_PATH = "/ve/back/coursePlatform/courseResource.shtml"
private const val PLAYBACK_PATH = "/ve/back/rp/common/teachCalendar.shtml"
/** 课程平台左侧“视频回放”模块编号；打开它的页面才会内联当前用户的 uId。 */
private const val PLAYBACK_MODULE_PAGE = "10480"
/** 目录导出等仍需整块读入内存的场景，备用路径上的文件超过此大小就跳过，改为单独下载。 */
private const val STORED_IN_MEMORY_LIMIT_BYTES = 150L * 1024 * 1024
private const val STORED_RANGE_CHUNK_BYTES = 4L * 1024 * 1024

data class CoursewareStoredDownload(
    val url: String,
    val fileName: String,
    val contentType: String,
    val totalBytes: Long,
    /** true 表示原文件取不到，拿的是平台在线预览用的转换件（PDF 或转码视频）。 */
    val isConvertedCopy: Boolean,
)

enum class CoursewareRemoteFailure {
    NETWORK,
    SESSION_EXPIRED,
    MALFORMED_RESPONSE,
    SECURE_CHANNEL_UNAVAILABLE,
    /** 文件太大，不能整块读进内存（只出现在备用下载的内存路径上）。 */
    TOO_LARGE,
}

class CoursewareRemoteException(
    val reason: CoursewareRemoteFailure,
) : Exception("Unable to refresh courseware: ${reason.name}")

interface CoursewareRemoteDataSource {
    suspend fun fetchSnapshot(): CoursewareSnapshot
    suspend fun fetchChildren(course: CoursewareCourse, parentId: Int): List<CoursewareNode> =
        throw CoursewareRemoteException(CoursewareRemoteFailure.MALFORMED_RESPONSE)
    suspend fun downloadResource(node: CoursewareNode): HomeworkFileContent
    suspend fun streamResource(node: CoursewareNode, sink: StreamingFileSink, onProgress: (Long, Long) -> Unit) {
        val file = downloadResource(node)
        sink.write(file.bytes)
        onProgress(file.bytes.size.toLong(), file.bytes.size.toLong())
    }
    suspend fun downloadTeachingCalendar(course: CoursewareCourse): HomeworkFileContent
    /** 未开放下载文件的备用路径：先确定实际可读的存储地址与文件名，再由 [streamStoredDownload] 分段写盘。 */
    suspend fun planStoredDownload(node: CoursewareNode): CoursewareStoredDownload =
        throw CoursewareRemoteException(CoursewareRemoteFailure.MALFORMED_RESPONSE)
    suspend fun streamStoredDownload(
        plan: CoursewareStoredDownload,
        sink: StreamingFileSink,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Unit = throw CoursewareRemoteException(CoursewareRemoteFailure.MALFORMED_RESPONSE)
    suspend fun fetchPlaybackLessons(course: CoursewareCourse): List<PlaybackLesson> =
        throw CoursewareRemoteException(CoursewareRemoteFailure.MALFORMED_RESPONSE)
    suspend fun fetchPlaybackDetail(course: CoursewareCourse, lesson: PlaybackLesson): PlaybackDetail =
        throw CoursewareRemoteException(CoursewareRemoteFailure.MALFORMED_RESPONSE)
}

class SchoolCoursewareRemoteDataSource(
    private val transport: SchoolHttpTransport,
    private val requestDelayMillis: Long = 0,
    private val endpoint: SmartPlatformEndpoint = SmartPlatformEndpoint.VerifiedHttps,
) : CoursewareRemoteDataSource {
    // 并发预加载各课顶层时，多个协程会同时 ensureInitialized；必须串行握手，否则会话互相踩坏。
    private val initMutex = Mutex()
    private var initialized = false
    private var sessionId: String? = null
    private var courses: List<SmartCourse> = emptyList()
    private var platformUserId: String? = null

    override suspend fun fetchSnapshot(): CoursewareSnapshot {
        ensureInitialized()
        val seenCourseIds = mutableSetOf<Int>()
        courses.forEach { course ->
            if (!seenCourseIds.add(course.id)) malformed()
            if (
                course.courseNumber.isBlank() ||
                course.groupId.isBlank() ||
                course.semesterCode.isBlank()
            ) {
                malformed()
            }
        }
        val snapshotCourses = courses.map { course ->
            CoursewareCourse(
                id = course.id,
                name = course.name,
                courseNumber = course.courseNumber,
                groupId = course.groupId,
                semesterCode = course.semesterCode,
                teacherId = course.teacherId,
                children = emptyList(),
                childrenLoaded = false,
            )
        }
        return CoursewareSnapshot(snapshotCourses)
    }

    override suspend fun fetchChildren(
        course: CoursewareCourse,
        parentId: Int,
    ): List<CoursewareNode> {
        ensureInitialized()
        if (courses.none { it.id == course.id }) malformed()
        val response = smartGet(
            path = RESOURCE_PATH,
            query = linkedMapOf(
                "method" to "stuQueryUploadResourceForCourseList",
                "courseId" to course.courseNumber,
                "cId" to course.courseNumber,
                "xkhId" to course.groupId,
                "xqCode" to course.semesterCode,
                "docType" to "1",
                "up_id" to parentId.toString(),
                "searchName" to "",
            ),
        )
        val parsedNodes = when (val parsed = parseCoursewareChildren(response.bodyText(), course.id)) {
            is CoursewareJsonParseResult.Failure -> malformed()
            is CoursewareJsonParseResult.Success -> parsed.value
        }
        val seenKeys = mutableSetOf<String>()
        return parsedNodes.map { node ->
            if (!seenKeys.add(node.stableKey)) malformed()
            if (node.isFolder) node.copy(children = emptyList(), childrenLoaded = false) else node
        }
    }

    override suspend fun downloadResource(node: CoursewareNode): HomeworkFileContent {
        require(node.kind == CoursewareNodeKind.RESOURCE && node.rpId.isNotBlank())
        ensureInitialized()
        // 老师未开放下载时网页不给按钮，下载票据换来的 download.shtml 也只会 500；
        // 直接改走存储路径。开放下载的文件仍优先走网页同款票据，失败（非 2xx）再兜底。
        if (!node.downloadAllowed) return downloadStoredResource(node)
        return try {
            downloadTicketResource(node)
        } catch (error: CoursewareRemoteException) {
            if (error.reason != CoursewareRemoteFailure.NETWORK) throw error
            runCatching { downloadStoredResource(node) }
                .getOrElse { fallback -> if (fallback is CancellationException) throw fallback else throw error }
        }
    }

    /**
     * 备用下载（整块读入内存，供目录导出等需要字节的场景）：按 [planStoredDownload] 选出的地址读取。
     * 超过 [STORED_IN_MEMORY_LIMIT_BYTES] 的文件不在这里读，避免手机内存不足闪退。
     */
    private suspend fun downloadStoredResource(node: CoursewareNode): HomeworkFileContent {
        val plan = planStoredDownload(node)
        if (plan.totalBytes > STORED_IN_MEMORY_LIMIT_BYTES) throw CoursewareRemoteException(CoursewareRemoteFailure.TOO_LARGE)
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = plan.url,
                headers = buildHeaders(includeSession = false),
                largeTransfer = true,
            ),
        )
        if (response.statusCode !in 200..299 || response.body.isEmpty()) network()
        if (!endpoint.acceptsResourceUrl(response.finalUrl)) secureChannelUnavailable()
        return HomeworkFileContent(fileName = plan.fileName, contentType = plan.contentType, bytes = response.body)
    }

    /**
     * `queryRpInfoById` 取服务器存储路径，逐个候选用 1 字节 Range 请求确认可读并拿到总长度：
     * 先原文件（`RP_PATH_1`），再在线预览用的转换件（`RP_PATH_2`，文档是 PDF、视频是转码 MP4）。
     * 转换件的扩展名与原文件不同时按实际扩展名命名，免得保存出打不开的文件。
     */
    override suspend fun planStoredDownload(node: CoursewareNode): CoursewareStoredDownload {
        require(node.kind == CoursewareNodeKind.RESOURCE && node.rpId.isNotBlank())
        ensureInitialized()
        val info = smartGet(
            path = RESOURCE_INFO_PATH,
            query = linkedMapOf("method" to "queryRpInfoById", "rpId" to node.rpId),
        )
        val paths = when (val parsed = parseCoursewareStoredPaths(info.bodyText())) {
            is CoursewareJsonParseResult.Failure -> {
                if (info.looksLikeHtmlPage()) {
                    invalidateSession()
                    sessionExpired()
                }
                malformed()
            }
            is CoursewareJsonParseResult.Success -> parsed.value
        }
        var lastFailure = CoursewareRemoteFailure.MALFORMED_RESPONSE
        for ((path, converted) in listOf(paths.originalPath to false, paths.previewPath to true)) {
            if (path.isBlank()) continue
            val url = endpoint.storedResourceUrl(path)
            if (url == null) {
                lastFailure = CoursewareRemoteFailure.SECURE_CHANNEL_UNAVAILABLE
                continue
            }
            val head = readBoundedRange(url, 0, 0)
            val total = head.totalLengthFromRange()
            if (head.statusCode != 206 || head.body.size != 1 || total == null || total <= 0L ||
                !endpoint.acceptsResourceUrl(head.finalUrl) ||
                // 静态目录找不到文件时可能回一张 HTML 错误页，不能当成课件保存。
                (head.looksLikeHtmlPage() && !node.extension.trim().equals("html", ignoreCase = true))
            ) {
                lastFailure = CoursewareRemoteFailure.NETWORK
                continue
            }
            val storedExtension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            val originalExtension = node.extension.trim().trimStart('.').lowercase()
            val renamed = converted && storedExtension.isNotBlank() && storedExtension != originalExtension
            return CoursewareStoredDownload(
                url = url,
                fileName = if (renamed) node.fileNameWithExtension(storedExtension) else node.suggestedFileName(),
                contentType = head.contentTypeOrDefault(),
                totalBytes = total,
                isConvertedCopy = converted,
            )
        }
        throw CoursewareRemoteException(lastFailure)
    }

    override suspend fun streamStoredDownload(
        plan: CoursewareStoredDownload,
        sink: StreamingFileSink,
        onProgress: (written: Long, total: Long) -> Unit,
    ) {
        if (!endpoint.acceptsResourceUrl(plan.url)) secureChannelUnavailable()
        var offset = 0L
        onProgress(0L, plan.totalBytes)
        // 逐段取、按序写：课件直写用户选好的文件，服务器的并发 Range 行为没有实测过，
        // 这里保持串行（课程回放的 HLS 分片才是并发抓的）。
        while (offset < plan.totalBytes) {
            val end = minOf(offset + STORED_RANGE_CHUNK_BYTES, plan.totalBytes) - 1
            val chunk = fetchRangeWithRetry(plan.url, offset, end)
            sink.write(chunk)
            offset += chunk.size
            onProgress(offset, plan.totalBytes)
        }
    }

    private suspend fun fetchRangeWithRetry(url: String, start: Long, end: Long): ByteArray {
        var attempt = 0
        while (true) {
            attempt++
            val body = try {
                val response = readBoundedRange(url, start, end)
                // 只接受与请求区间一致的 206；服务器忽略 Range 回整文件时不能拼进去。
                if (response.statusCode == 206 && response.contentRangeStart() == start &&
                    response.body.size.toLong() == end - start + 1 && endpoint.acceptsResourceUrl(response.finalUrl)
                ) {
                    response.body
                } else {
                    null
                }
            } catch (error: CoursewareRemoteException) {
                null
            }
            if (body != null) return body
            if (attempt >= 3) network()
            delay(500L * attempt)
        }
    }

    /** 不接受 Range 的服务器可能返回整份大文件；先检查响应头，再最多读取请求的区间。 */
    private suspend fun readBoundedRange(url: String, start: Long, end: Long): SchoolHttpResponse {
        val size = (end - start + 1).toInt()
        val buffer = ByteArray(size)
        var count = 0
        var headers: SchoolHttpResponse? = null
        try {
            if (requestDelayMillis > 0) delay(requestDelayMillis)
            transport.stream(SchoolHttpRequest(SchoolHttpMethod.GET, url,
                headers = buildHeaders(includeSession = false) + ("Range" to "bytes=$start-$end"),
                largeTransfer = true), { response ->
                headers = response
                if (response.statusCode != 206 || response.contentRangeStart() != start ||
                    !endpoint.acceptsResourceUrl(response.finalUrl)) throw StopRangeResponse()
            }, { bytes ->
                if (bytes.size > size - count) throw StopRangeResponse()
                bytes.copyInto(buffer, count)
                count += bytes.size
            })
        } catch (error: CancellationException) {
            throw error
        } catch (_: StopRangeResponse) {
            return (headers ?: throw CoursewareRemoteException(CoursewareRemoteFailure.NETWORK)).copy(body = byteArrayOf())
        } catch (_: Exception) {
            network()
        }
        return (headers ?: throw CoursewareRemoteException(CoursewareRemoteFailure.NETWORK)).copy(body = buffer.copyOf(count))
    }

    private class StopRangeResponse : Exception()

    private suspend fun ticketUrl(node: CoursewareNode): String {
        val ticketResponse = smartRequest(
            method = SchoolHttpMethod.POST,
            path = DOWNLOAD_TICKET_PATH,
            query = linkedMapOf(
                "method" to "rpinfoDownloadUrl",
                "rpId" to node.rpId,
            ),
        )
        val ticket = when (val parsed = parseCoursewareDownloadTicket(ticketResponse.bodyText())) {
            is CoursewareJsonParseResult.Failure -> {
                // 会话过期时智慧教学接口有时仍返回 200，但正文已经是登录页；
                // 若只按 JSON 解析失败处理，界面会误报“无效的文件信息”。
                if (ticketResponse.looksLikeHtmlPage()) {
                    invalidateSession()
                    sessionExpired()
                }
                malformed()
            }
            is CoursewareJsonParseResult.Success -> parsed.value
        }
        if (!endpoint.acceptsResourceUrl(ticket.url)) secureChannelUnavailable()
        return ticket.url
    }

    override suspend fun streamResource(node: CoursewareNode, sink: StreamingFileSink, onProgress: (Long, Long) -> Unit) {
        require(node.kind == CoursewareNodeKind.RESOURCE && node.rpId.isNotBlank())
        ensureInitialized()
        if (!node.downloadAllowed) {
            streamStoredDownload(planStoredDownload(node), sink, onProgress)
            return
        }
        var total = 0L
        var written = 0L
        var lastProgress = TimeSource.Monotonic.markNow()
        try {
            val url = ticketUrl(node)
            transport.stream(SchoolHttpRequest(method = SchoolHttpMethod.GET, url = url,
                headers = buildHeaders(includeSession = false), largeTransfer = true), { response ->
                if (response.statusCode !in 200..299) network()
                if (!endpoint.acceptsResourceUrl(response.finalUrl)) secureChannelUnavailable()
                if (response.contentTypeOrDefault().contains("text/html", ignoreCase = true) &&
                    !node.extension.trim().equals("html", ignoreCase = true)) malformed()
                total = response.header("Content-Length")?.toLongOrNull() ?: 0L
                onProgress(0, total)
            }, { chunk ->
                sink.write(chunk)
                written += chunk.size
                if (lastProgress.elapsedNow().inWholeMilliseconds >= 150 || (total > 0 && written >= total)) {
                    onProgress(written, total)
                    lastProgress = TimeSource.Monotonic.markNow()
                }
            })
        } catch (error: CancellationException) {
            throw error
        } catch (error: CoursewareRemoteException) {
            if (error.reason == CoursewareRemoteFailure.NETWORK && written == 0L) {
                val fallback = try {
                    planStoredDownload(node)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    throw error
                }
                // 用户已确认原文件名；不能把转换后的 PDF/视频放进原文件扩展名。
                if (fallback.isConvertedCopy) throw error
                streamStoredDownload(fallback, sink, onProgress)
                return
            }
            throw error
        } catch (error: team.bjtuss.bjtuselfservice.shared.files.StreamingSinkIoException) {
            throw error
        } catch (_: Exception) {
            if (written == 0L) {
                val fallback = try { planStoredDownload(node) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { network() }
                if (!fallback.isConvertedCopy) {
                    streamStoredDownload(fallback, sink, onProgress)
                    return
                }
            }
            network()
        }
        if (written == 0L || (total > 0 && written != total)) network()
        onProgress(written, total)
    }

    private suspend fun downloadTicketResource(node: CoursewareNode): HomeworkFileContent {
        val url = ticketUrl(node)
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = url,
                headers = buildHeaders(includeSession = false),
                largeTransfer = true,
            ),
        )
        if (response.statusCode !in 200..299) network()
        if (!endpoint.acceptsResourceUrl(response.finalUrl)) secureChannelUnavailable()
        if (response.body.isEmpty()) malformed()
        return HomeworkFileContent(
            fileName = node.suggestedFileName(),
            contentType = response.contentTypeOrDefault(),
            bytes = response.body,
        )
    }

    override suspend fun downloadTeachingCalendar(course: CoursewareCourse): HomeworkFileContent {
        ensureInitialized()
        val platform = smartGet(
            path = COURSE_PLATFORM_PATH,
            query = linkedMapOf(
                "method" to "toCoursePlatform",
                "courseId" to course.courseNumber,
                "dataSource" to "1",
                "cId" to course.id.toString(),
                "xkhId" to course.groupId,
                "xqCode" to course.semesterCode,
            ),
        )
        when (parseCoursewareTeacherId(platform.bodyText())) {
            is CoursewareHtmlParseResult.Failure -> malformed()
            is CoursewareHtmlParseResult.Success -> Unit
        }
        val calendarPage = smartGet(
            path = COURSE_PLATFORM_PATH,
            query = linkedMapOf(),
        )
        val rawUrl = when (val parsed = parseTeachingCalendarFrameUrl(calendarPage.bodyText())) {
            is CoursewareHtmlParseResult.Failure -> malformed()
            is CoursewareHtmlParseResult.Success -> parsed.value
        }
        val calendarUrl = endpoint.resolveTeachingCalendarUrl(rawUrl) ?: secureChannelUnavailable()
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = calendarUrl,
                headers = buildHeaders(includeSession = false),
            ),
        )
        if (response.statusCode !in 200..299) network()
        if (!endpoint.acceptsCalendarUrl(response.finalUrl)) secureChannelUnavailable()
        if (response.body.isEmpty()) malformed()
        val contentType = response.contentTypeOrDefault()
        if (!contentType.equals("application/pdf", ignoreCase = true) && !response.body.isPdfBytes()) {
            malformed()
        }
        return HomeworkFileContent(
            fileName = "${course.name}_教学日历.pdf",
            contentType = "application/pdf",
            bytes = response.body,
        )
    }

    override suspend fun fetchPlaybackLessons(course: CoursewareCourse): List<PlaybackLesson> {
        ensureInitialized()
        if (courses.none { it.id == course.id }) malformed()
        val response = smartGet(
            path = PLAYBACK_PATH,
            query = linkedMapOf("method" to "toDisplyTeachCourses", "courseId" to course.id.toString()),
        )
        return when (val parsed = parsePlaybackLessons(response.bodyText(), course.id)) {
            is CoursewareJsonParseResult.Failure -> malformed()
            is CoursewareJsonParseResult.Success -> parsed.value
        }
    }

    override suspend fun fetchPlaybackDetail(course: CoursewareCourse, lesson: PlaybackLesson): PlaybackDetail {
        ensureInitialized()
        if (courses.none { it.id == course.id } || lesson.courseId != course.id) malformed()
        val userId = platformUserId ?: loadPlatformUserId(course).also { platformUserId = it }
        val response = smartGet(
            path = PLAYBACK_PATH,
            query = linkedMapOf(
                "method" to "toDisplyCourseSchedDetail",
                "courseSchedId" to lesson.id.toString(),
                "userLevel" to "1",
                "userId" to userId,
            ),
        )
        return when (val parsed = parsePlaybackDetail(response.bodyText(), lesson, ::isSchoolPlaybackStreamUrl)) {
            is CoursewareJsonParseResult.Failure -> {
                // uId 与会话不匹配时学校只回 {"STATUS":"4","message":"出现异常"}；下次重新读取。
                platformUserId = null
                malformed()
            }
            is CoursewareJsonParseResult.Success -> parsed.value
        }
    }

    private suspend fun loadPlatformUserId(course: CoursewareCourse): String {
        val page = smartGet(
            path = COURSE_PLATFORM_PATH,
            query = linkedMapOf(
                "method" to "toCoursePlatform",
                "courseToPage" to PLAYBACK_MODULE_PAGE,
                "courseId" to course.courseNumber,
                "dataSource" to "1",
                "cId" to course.id.toString(),
                "xkhId" to course.groupId,
                "xqCode" to course.semesterCode,
                "teacherId" to (course.teacherId?.toString() ?: ""),
            ),
        )
        return parseCoursePlatformUserId(page.bodyText()) ?: malformed()
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            ensureInitializedLocked()
        }
    }

    private suspend fun ensureInitializedLocked() {
        try {
            endpoint.openSmartPlatform { request -> executeWithoutRedirects(request) }
        } catch (error: SmartHandshakeException) {
            if (error.reason == SmartHandshakeFailure.SESSION_EXPIRED) invalidateSession()
            throw CoursewareRemoteException(CoursewareRemoteFailure.valueOf(error.reason.name))
        }

        // 与作业一致：会话经 Set-Cookie: JSESSIONID 下发，优先读 Cookie，
        // 旧版 article JSON 解析仅作回退。
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

    private suspend fun smartRequest(
        method: SchoolHttpMethod,
        path: String,
        query: LinkedHashMap<String, String>,
        includeSession: Boolean = true,
    ): SchoolHttpResponse {
        val response = executeWithoutRedirects(
            SchoolHttpRequest(
                method = method,
                url = endpoint.apiUrl(path, query),
                headers = buildHeaders(includeSession),
            ),
        )
        if (response.statusCode in 300..399) {
            invalidateSession()
            sessionExpired()
        }
        if (
            !endpoint.isLegacyInsecure &&
            path == ARTICLE_PATH &&
            response.statusCode == 404 &&
            endpoint.acceptsApiUrl(response.finalUrl)
        ) {
            secureChannelUnavailable()
        }
        if (response.statusCode == 401 || response.statusCode == 403) {
            invalidateSession()
            sessionExpired()
        }
        if (response.statusCode !in 200..299) network()
        if (response.looksLikeSessionExpired()) {
            invalidateSession()
            sessionExpired()
        }
        if (!endpoint.acceptsApiUrl(response.finalUrl)) {
            invalidateSession()
            sessionExpired()
        }
        return response
    }

    private fun invalidateSession() {
        initialized = false
        sessionId = null
        platformUserId = null
        courses = emptyList()
    }

    private fun buildHeaders(includeSession: Boolean = true): Map<String, String> = linkedMapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer" to endpoint.apiOrigin,
        "X-Requested-With" to "XMLHttpRequest",
    ).apply {
        if (includeSession) sessionId?.let { put("sessionid", it) }
    }

    private suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = try {
        if (requestDelayMillis > 0) delay(requestDelayMillis)
        transport.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        network()
    }

    private suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse = try {
        if (requestDelayMillis > 0) delay(requestDelayMillis)
        transport.executeWithoutRedirects(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        network()
    }
}

private fun ByteArray.isPdfBytes(): Boolean = size >= 5 &&
    this[0] == '%'.code.toByte() &&
    this[1] == 'P'.code.toByte() &&
    this[2] == 'D'.code.toByte() &&
    this[3] == 'F'.code.toByte() &&
    this[4] == '-'.code.toByte()

internal fun CoursewareNode.suggestedFileName(): String {
    val clean = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "resource" }
    val normalizedExtension = extension.trim().trimStart('.')
    return if (normalizedExtension.isBlank() || clean.substringAfterLast('.', "").isNotBlank()) {
        clean
    } else {
        "$clean.$normalizedExtension"
    }
}

private fun CoursewareNode.fileNameWithExtension(newExtension: String): String {
    val clean = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "resource" }
    val extension = extension.trim().trimStart('.')
    val base = if (extension.isNotBlank() && clean.endsWith(".$extension", ignoreCase = true)) {
        clean.dropLast(extension.length + 1)
    } else {
        clean
    }
    return if (base.endsWith(".$newExtension", ignoreCase = true)) base else "$base.$newExtension"
}

/** `Content-Range: bytes 0-0/322420000` 里的总长度；服务器不认 Range 回 200 时退回 Content-Length。 */
private fun SchoolHttpResponse.totalLengthFromRange(): Long? {
    header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()?.let { return it }
    return if (statusCode == 200) header("Content-Length")?.trim()?.toLongOrNull() ?: body.size.toLong().takeIf { it > 0 } else null
}

private fun SchoolHttpResponse.contentRangeStart(): Long? =
    header("Content-Range")?.removePrefix("bytes")?.trim()?.substringBefore('-')?.trim()?.toLongOrNull()

private fun SchoolHttpResponse.contentTypeOrDefault(): String = headers.entries
    .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
    ?.value?.firstOrNull()?.substringBefore(';')?.trim()
    ?.takeIf(String::isNotBlank)
    ?: "application/octet-stream"

private fun SchoolHttpResponse.looksLikeHtmlPage(): Boolean {
    val contentType = contentTypeOrDefault()
    if (contentType.equals("text/html", ignoreCase = true)) return true
    val prefix = bodyText().trimStart().take(160)
    return prefix.startsWith("<!doctype html", ignoreCase = true) ||
        prefix.startsWith("<html", ignoreCase = true) ||
        prefix.startsWith("<form", ignoreCase = true)
}

private fun network(): Nothing = throw CoursewareRemoteException(CoursewareRemoteFailure.NETWORK)
private fun sessionExpired(): Nothing = throw CoursewareRemoteException(CoursewareRemoteFailure.SESSION_EXPIRED)
private fun malformed(): Nothing = throw CoursewareRemoteException(CoursewareRemoteFailure.MALFORMED_RESPONSE)
private fun secureChannelUnavailable(): Nothing =
    throw CoursewareRemoteException(CoursewareRemoteFailure.SECURE_CHANNEL_UNAVAILABLE)

private const val HEX = "0123456789ABCDEF"

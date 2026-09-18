package team.bjtuss.bjtuselfservice.shared.data.phyvlab

import team.bjtuss.bjtuselfservice.shared.network.SchoolEndpoints

import kotlinx.coroutines.CancellationException
import com.fleeksoft.ksoup.Ksoup
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAssignmentDetail
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabCourse
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

private val PHYVLAB_COURSES_URL = "${SchoolEndpoints.PHYVLAB_ORIGIN}/my/courses.php"
private val PHYVLAB_COURSE_VIEW_URL = "${SchoolEndpoints.PHYVLAB_ORIGIN}/course/view.php"
private val PHYVLAB_CALENDAR_URL = "${SchoolEndpoints.PHYVLAB_ORIGIN}/calendar/view.php"
private val PHYVLAB_REPOSITORY_UPLOAD_URL = "${SchoolEndpoints.PHYVLAB_ORIGIN}/repository/repository_ajax.php"

enum class PhyVlabRemoteFailure {
    NETWORK,
    PARSE,
    SESSION_EXPIRED,
}

class PhyVlabRemoteException(
    val reason: PhyVlabRemoteFailure,
) : Exception("PhyVlab request failed: ${reason.name}")

interface PhyVlabRemoteDataSource {
    suspend fun fetchCourses(): List<PhyVlabCourse>
    suspend fun fetchCourseActivities(course: PhyVlabCourse): List<PhyVlabActivity>
    suspend fun fetchEvents(monthTimestampSeconds: Long): List<PhyVlabEvent>
    suspend fun fetchAssignmentDetail(activity: PhyVlabActivity): PhyVlabAssignmentDetail
    suspend fun submitAssignment(activity: PhyVlabActivity, files: List<HomeworkFileContent>)
}

/**
 * 物理在线只读数据源。只访问 HTTPS 的 phyvlab 域名；登录会话复用 App 的
 * CAS/Ktor Cookie jar，未登录时服务器会跳回登录入口，按会话失效处理。
 */
class SchoolPhyVlabRemoteDataSource(
    private val transport: SchoolHttpTransport,
) : PhyVlabRemoteDataSource {
    private val submissionContexts = mutableMapOf<Int, PhyVlabAssignmentSubmissionContext>()

    override suspend fun fetchCourses(): List<PhyVlabCourse> {
        val response = fetchPage(PHYVLAB_COURSES_URL, referer = "${SchoolEndpoints.PHYVLAB_ORIGIN}/?redirect=0")
        return when (val parsed = parsePhyVlabCourses(response.bodyText())) {
            is PhyVlabParseResult.Failure -> parse()
            is PhyVlabParseResult.Success -> parsed.value.also {
                phyVlabDebug("courses parsed count=${it.size}")
            }
        }
    }

    override suspend fun fetchCourseActivities(course: PhyVlabCourse): List<PhyVlabActivity> {
        val response = fetchPage(
            url = "$PHYVLAB_COURSE_VIEW_URL?id=${course.id}",
            referer = course.courseUrl,
        )
        return when (val parsed = parsePhyVlabActivities(response.bodyText(), course.id, course.name)) {
            is PhyVlabParseResult.Failure -> parse()
            is PhyVlabParseResult.Success -> parsed.value
        }
    }

    override suspend fun fetchEvents(monthTimestampSeconds: Long): List<PhyVlabEvent> {
        val response = fetchPage(
            url = "$PHYVLAB_CALENDAR_URL?view=month&time=$monthTimestampSeconds",
            referer = PHYVLAB_CALENDAR_URL,
        )
        return when (val parsed = parsePhyVlabEvents(response.bodyText())) {
            is PhyVlabParseResult.Failure -> parse()
            is PhyVlabParseResult.Success -> parsed.value
        }
    }

    override suspend fun fetchAssignmentDetail(activity: PhyVlabActivity): PhyVlabAssignmentDetail {
        val response = fetchPage(activity.activityUrl, referer = "${SchoolEndpoints.PHYVLAB_ORIGIN}/course/view.php?id=${activity.courseId}")
        return when (val parsed = parsePhyVlabAssignmentPage(response.bodyText(), activity)) {
            is PhyVlabParseResult.Failure -> parse()
            is PhyVlabParseResult.Success -> {
                var page = parsed.value
                page.submissionContext?.let { submissionContexts[activity.id] = it }
                // Moodle 默认详情页只给“编辑提交”链接，真正的 filemanager 草稿上下文
                // 在编辑页生成；该 GET 仍是只读，不会改变提交状态。
                if (page.submissionContext == null) {
                    // 主题可能把“添加/编辑提交”渲染成无 href 的按钮；Moodle
                    // 的标准编辑入口仍是该活动 id + action=editsubmission。
                    val editUrl = page.editSubmissionUrl
                        ?: "${SchoolEndpoints.PHYVLAB_ORIGIN}/mod/assign/view.php?id=${activity.id}&action=editsubmission"
                    val editResponse = try {
                        fetchPage(editUrl, referer = activity.activityUrl)
                    } catch (error: PhyVlabRemoteException) {
                        // 编辑页只是为了补充原生上传所需的 filemanager 上下文；
                        // 某些 Moodle 主题/作业状态会让该入口返回 404。主详情页
                        // 已经成功时不能把这个可选请求的失败升级成详情失败。
                        // 会话失效则必须继续向上抛出，交给统一恢复逻辑处理。
                        if (error.reason == PhyVlabRemoteFailure.SESSION_EXPIRED) throw error
                        phyVlabDebug("optional edit page unavailable reason=${error.reason}")
                        null
                    }
                    editResponse?.let { response ->
                        when (val editPage = parsePhyVlabAssignmentPage(response.bodyText(), activity)) {
                            is PhyVlabParseResult.Failure -> Unit
                            is PhyVlabParseResult.Success -> {
                                page = editPage.value.copy(
                                    detail = mergeAssignmentDetails(page.detail, editPage.value.detail),
                                )
                                page.submissionContext?.let { submissionContexts[activity.id] = it }
                            }
                        }
                    }
                }
                phyVlabDebug("assignment detail ready canSubmit=${page.detail.canSubmit}")
                page.detail
            }
        }
    }

    private fun mergeAssignmentDetails(
        original: PhyVlabAssignmentDetail,
        secondary: PhyVlabAssignmentDetail,
    ): PhyVlabAssignmentDetail = secondary.copy(
        description = secondary.description.ifBlank { original.description },
        submissionStatus = secondary.submissionStatus.ifBlank { original.submissionStatus },
        submissionDateText = secondary.submissionDateText ?: original.submissionDateText,
        submissionDateTimestamp = secondary.submissionDateTimestamp ?: original.submissionDateTimestamp,
        gradingStatus = secondary.gradingStatus ?: original.gradingStatus,
        gradeText = secondary.gradeText ?: original.gradeText,
        feedbackText = secondary.feedbackText ?: original.feedbackText,
        submittedFiles = secondary.submittedFiles.ifEmpty { original.submittedFiles },
        canSubmit = secondary.canSubmit || original.canSubmit,
    )

    override suspend fun submitAssignment(activity: PhyVlabActivity, files: List<HomeworkFileContent>) {
        require(files.isNotEmpty()) { "At least one physical-online assignment file is required" }
        val context = submissionContexts[activity.id] ?: run {
            fetchAssignmentDetail(activity)
            submissionContexts[activity.id] ?: parse()
        }
        if (!context.isUploadReady) parse()
        val draftItemId = context.draftItemId ?: parse()
        files.forEach { file -> uploadDraftFile(context, draftItemId, file) }

        val formFields = LinkedHashMap(context.formFields)
        formFields["sesskey"] = context.sesskey
        // Moodle filemanager 接收的是草稿区 item id；服务端随后把草稿移动到本次提交。
        val fileManagerName = formFields.keys.firstOrNull { it.contains("filemanager") }
            ?: "assignsubmission_file_filemanager"
        formFields[fileManagerName] = draftItemId
        formFields["id"] = formFields["id"] ?: activity.id.toString()
        formFields["action"] = formFields["action"] ?: "savesubmission"
        val submitButton = formFields.keys.firstOrNull { it.equals("submitbutton", ignoreCase = true) }
        if (submitButton == null) formFields["submitbutton"] = "保存更改"
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = context.formUrl,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
                    "Referer" to activity.activityUrl,
                ),
                formFields = formFields,
            ),
        )
        validateWriteResponse(response)
        submissionContexts.remove(activity.id)
    }

    private suspend fun uploadDraftFile(
        context: PhyVlabAssignmentSubmissionContext,
        draftItemId: String,
        file: HomeworkFileContent,
    ) {
        val fields = linkedMapOf(
            "action" to "upload",
            "repo_id" to (context.repositoryId ?: "4"),
            "itemid" to draftItemId,
            "ctx_id" to (context.contextId ?: ""),
            "client_id" to (context.clientId ?: ""),
            "sesskey" to context.sesskey,
            "p" to "",
            "page" to "",
            "env" to "filepicker",
            "maxbytes" to "-1",
            "areamaxbytes" to "-1",
            "savepath" to "/",
            "filepath" to "/",
            "title" to file.fileName,
            "author" to "",
            "license" to "unknown",
        )
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = PHYVLAB_REPOSITORY_UPLOAD_URL,
                headers = mapOf(
                    "Accept" to "application/json,text/plain,*/*",
                    "Referer" to context.formUrl,
                ),
                formFields = fields,
                multipartFiles = listOf(
                    team.bjtuss.bjtuselfservice.shared.network.SchoolMultipartFile(
                        fieldName = "repo_upload_file",
                        fileName = file.fileName,
                        contentType = file.contentType,
                        bytes = file.bytes,
                    ),
                ),
            ),
        )
        validateWriteResponse(response)
        val body = response.bodyText()
        if (body.contains("\"error\"", ignoreCase = true) &&
            Regex("\"error\"\\s*:\\s*(true|1)", RegexOption.IGNORE_CASE).containsMatchIn(body)
        ) {
            parse()
        }
    }

    private fun validateWriteResponse(response: SchoolHttpResponse) {
        if (response.statusCode !in 200..299) network()
        if (!response.finalUrl.startsWith(SchoolEndpoints.PHYVLAB_ORIGIN)) sessionExpired()
        if (response.finalUrl.contains("/login/index.php") || looksLikePhyVlabLoginPage(response.bodyText())) {
            sessionExpired()
        }
        if (response.bodyText().contains("系统发生了未处理的异常", ignoreCase = true)) parse()
    }

    private suspend fun fetchPage(url: String, referer: String): SchoolHttpResponse {
        phyVlabDebug("page GET start ${safePhyVlabEndpoint(url)}")
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = url,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
                    "Referer" to referer,
                ),
            ),
        )
        phyVlabDebug(
            "page GET ${safePhyVlabEndpoint(url)} -> ${response.statusCode} " +
                "${safePhyVlabEndpoint(response.finalUrl)} bytes=${response.body.size}",
        )
        if (response.statusCode !in 200..299) network()
        if (!response.finalUrl.startsWith(SchoolEndpoints.PHYVLAB_ORIGIN)) sessionExpired()
        if (response.finalUrl.contains("/login/index.php") ||
            response.finalUrl.contains("/enrol/index.php")
        ) {
            sessionExpired()
        }
        if (response.body.isEmpty()) parse()
        // 某些反向代理会在原请求地址直接返回登录页（不发生 HTTP 跳转）。
        // 不能把这类页面误判成“已登录但没有课程”。
        if (looksLikePhyVlabLoginPage(response.bodyText())) {
            phyVlabDebug("page classified as login")
            sessionExpired()
        }
        return response
    }

    private suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = try {
        transport.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val causeType = error.cause?.let { it::class.simpleName }
        phyVlabDebug(
            "transport failed method=${request.method} endpoint=${safePhyVlabEndpoint(request.url)} " +
                "error=${error::class.simpleName ?: "unknown"} cause=${causeType ?: "none"}",
        )
        network()
    }
}

private fun network(): Nothing = throw PhyVlabRemoteException(PhyVlabRemoteFailure.NETWORK)
private fun parse(): Nothing = throw PhyVlabRemoteException(PhyVlabRemoteFailure.PARSE)
private fun sessionExpired(): Nothing = throw PhyVlabRemoteException(PhyVlabRemoteFailure.SESSION_EXPIRED)

private fun looksLikePhyVlabLoginPage(html: String): Boolean {
    val document = Ksoup.parse(html)
    return document.selectFirst("a[href*='/auth/oauth2/login.php']") != null ||
        document.selectFirst("form[action*='/login/index.php']") != null ||
        (document.text().contains("用户名或邮箱") && document.text().contains("密码"))
}

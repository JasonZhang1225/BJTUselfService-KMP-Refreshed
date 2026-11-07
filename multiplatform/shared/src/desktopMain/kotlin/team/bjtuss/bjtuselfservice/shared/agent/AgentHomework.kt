package team.bjtuss.bjtuselfservice.shared.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshIpcCallResult
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshIpcCode
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshIpcOp
import team.bjtuss.bjtuselfservice.shared.agent.host.callRefreshIpc
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkDetailResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkOperationResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkRepository
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkDetail
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.homework.isHomeworkSubmitted
import team.bjtuss.bjtuselfservice.shared.domain.homework.parseSchoolLocalDateTime
import team.bjtuss.bjtuselfservice.shared.domain.homework.typeLabel
import team.bjtuss.bjtuselfservice.shared.domain.time.schoolEpochSeconds
import team.bjtuss.bjtuselfservice.shared.feature.shell.SessionRefreshCoordinator
import team.bjtuss.bjtuselfservice.shared.files.safeExportFileName

/**
 * 本机命令行读取与提交课程平台作业（仅本地 CLI，不进远程插件）。
 *
 * 流程约束：
 * - `submit` 默认只预览，带 `--yes` 才提交；已交过的作业还要带 `--replace`（服务端是覆盖式提交）。
 * - 学校请求只在执行者进程里发：App 开着时经本机 IPC 用 App 的会话，否则在持锁进程里无窗口登录。
 * - 提交不自动重试：结果不明时交给调用方查询，避免重复提交或覆盖。
 */
private const val MAX_UPLOAD_FILES = 20
private const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024
/** 文件要整块读进执行者进程（可能是 App）的内存，总量也要有上限。 */
private const val MAX_TOTAL_UPLOAD_BYTES = MAX_UPLOAD_BYTES
private const val MAX_UPLOAD_NAME_CHARS = 120
private const val MAX_NOTE_CHARS = 2_000
/** IPC 响应一行有长度上限；作业说明超长时截断并标记。 */
private const val MAX_CONTENT_CHARS = 40_000
private const val COURSE_PLATFORM_PREFIX = "course_platform:"
private val forbiddenNameCharacters = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/** 同一进程里“同步、判断已交、提交”整段串行：否则两个请求都可能读到未提交，后者覆盖前者。 */
private val submitLock = Mutex()

object AgentHomeworkFailureCode {
    const val NOT_FOUND = "not_found"
    const val ALREADY_SUBMITTED = "already_submitted"
    const val SESSION_EXPIRED = "session_expired"
    const val REJECTED = "rejected"
    const val NETWORK = "network"
    const val IO = "io"
}

@Serializable
data class AgentHomeworkUpload(val path: String, val uploadName: String)

@Serializable
data class AgentHomeworkRequest(
    /** show / submit */
    val action: String,
    val homeworkId: Int,
    /** course_platform / phyvlab / citel；提交只支持课程平台。 */
    val source: String = "course_platform",
    /** show：把老师附件下载到这个新目录。 */
    val outDir: String? = null,
    val uploads: List<AgentHomeworkUpload> = emptyList(),
    val note: String = "",
    /** false 只预览，不上传、不改动学校数据。 */
    val execute: Boolean = false,
    /** 允许覆盖已有提交。 */
    val replace: Boolean = false,
) {
    val mutates: Boolean get() = action == "submit" && execute
}

@Serializable
data class AgentHomeworkAttachment(val id: Int, val name: String, val sizeBytes: Long, val savedAs: String? = null, val attachmentId: String? = null)

@Serializable
data class AgentHomeworkStudent(val studentId: String, val name: String)

@Serializable
data class AgentHomeworkSubmission(
    /** preview / submitted */
    val state: String,
    val note: String,
    val files: List<AgentHomeworkUpload>,
    /** 这次提交会覆盖已有提交。 */
    val replaces: Boolean,
    /** 提交后重新同步确认过状态为已交；预览时为 null。 */
    val verified: Boolean? = null,
)

@Serializable
data class AgentHomeworkResult(
    val id: String,
    val course: String,
    val title: String,
    val type: String,
    val open: String? = null,
    val due: String? = null,
    val overdue: Boolean,
    val submitted: Boolean,
    val submissionKnown: Boolean = true,
    val score: String? = null,
    val content: String? = null,
    val contentTruncated: Boolean = false,
    val attachments: List<AgentHomeworkAttachment> = emptyList(),
    val submittedFiles: List<String> = emptyList(),
    /** 只在 show 里给，用于按作业要求命名。 */
    val student: AgentHomeworkStudent? = null,
    val warnings: List<String> = emptyList(),
    val submission: AgentHomeworkSubmission? = null,
)

sealed interface AgentHomeworkOutcome {
    data class Done(val result: AgentHomeworkResult) : AgentHomeworkOutcome
    data class Failed(val code: String, val message: String) : AgentHomeworkOutcome
}

/** 在持有登录会话的进程里执行；[refreshHomework] 走公共刷新，App 界面会一起更新。 */
class AgentHomeworkExecutor(
    private val repository: HomeworkRepository,
    private val profile: StudentProfile,
    reauthenticate: (suspend () -> Boolean)?,
    private val refreshHomework: suspend () -> Boolean,
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val details: AgentAssignmentDetailService? = null,
) {
    /** 同一请求最多恢复一次会话，与刷新批次的规则一致。 */
    private val recovery = SessionRefreshCoordinator(reauthenticate)

    suspend fun run(request: AgentHomeworkRequest): AgentHomeworkOutcome = try {
        when (request.action) {
            "show" -> if (request.source == "course_platform") show(request) else showOther(request)
            "submit" -> if (request.execute) submitLock.withLock { submit(request) } else submit(request)
            else -> AgentHomeworkOutcome.Failed(RefreshIpcCode.BAD_REQUEST, "不支持的作业操作。")
        }
    } catch (error: CancellationException) {
        throw error
    } catch (failure: StepFailure) {
        failure.outcome
    }

    private class StepFailure(val outcome: AgentHomeworkOutcome.Failed) : Exception()

    private fun stop(code: String, message: String): Nothing = throw StepFailure(AgentHomeworkOutcome.Failed(code, message))

    private fun find(id: Int): Homework? {
        val matches = repository.load().homework.filter { it.id == id }
        if (matches.size > 1) stop(AgentHomeworkFailureCode.NOT_FOUND, "有多门课程的作业使用这个 id，无法确定是哪一项，请在 App 里提交。")
        return matches.singleOrNull()
    }

    private fun missing(): Nothing = stop(AgentHomeworkFailureCode.NOT_FOUND, "作业列表里没有这项作业，或它不属于课程平台。")

    private suspend fun show(request: AgentHomeworkRequest): AgentHomeworkOutcome {
        // 这条命令也用来确认提交结果，不能把缓存里的状态当成联网结果返回。
        if (!refreshHomework()) stop(AgentHomeworkFailureCode.NETWORK, "无法从学校同步作业状态，不能确认是否已提交。请检查网络或登录后重试。")
        val homework = find(request.homeworkId) ?: missing()
        val detail = loadDetail(homework)
        val submitted = if (isHomeworkSubmitted(homework)) submittedNames(homework) else emptyList()
        val saved = request.outDir?.let { downloadAttachments(homework, detail, File(it)) }.orEmpty()
        val text = plainText(detail.content)
        return AgentHomeworkOutcome.Done(base(homework).copy(
            content = text.take(MAX_CONTENT_CHARS).takeIf(String::isNotBlank),
            contentTruncated = text.length > MAX_CONTENT_CHARS,
            attachments = detail.attachments.map { AgentHomeworkAttachment(it.id, it.fileName, it.sizeBytes, saved[it.id]) },
            submittedFiles = submitted,
            student = AgentHomeworkStudent(profile.studentId, profile.name),
        ))
    }

    /** 物理在线与 CITEL：走统一详情服务，附件大小不限，`--out` 全部下载。 */
    private suspend fun showOther(request: AgentHomeworkRequest): AgentHomeworkOutcome {
        val service = details ?: stop(AgentHomeworkFailureCode.NOT_FOUND, "当前会话不能读取这个来源的详情。")
        val id = "${request.source}:${request.homeworkId}"
        try {
            val detail = service.refresh(id)
            val saved = request.outDir?.takeIf { detail.attachments.isNotEmpty() }?.let { out ->
                val files = detail.attachments.map { it.id to service.attachment(id, it.id).bytes }
                writeAttachments(File(out), uniqueAttachmentNames(detail.attachments.map { it.name }), files)
            }.orEmpty()
            val summary = service.summary(id)
            return AgentHomeworkOutcome.Done(AgentHomeworkResult(
                id = id, course = summary?.course.orEmpty().trim(), title = summary?.title.orEmpty().trim(),
                type = if (request.source == "citel") "CITEL 作业" else "物理在线作业",
                due = summary?.dueEpoch?.let { agentTimePoint(it, deadline = true).text },
                overdue = summary?.dueEpoch?.let { it < nowEpochSeconds() } == true,
                submitted = detail.submitted ?: summary?.submitted ?: false,
                submissionKnown = detail.submitted != null || summary?.submitted != null,
                content = detail.content,
                attachments = detail.attachments.map { attachment ->
                    AgentHomeworkAttachment(0, attachment.name, attachment.sizeBytes ?: 0, saved[attachment.id], attachment.id)
                },
                submittedFiles = detail.submittedFiles,
                student = AgentHomeworkStudent(profile.studentId, profile.name),
            ))
        } catch (error: AgentDetailException) {
            stop(if (error.code == "not_found") AgentHomeworkFailureCode.NOT_FOUND else AgentHomeworkFailureCode.NETWORK, error.message.orEmpty())
        }
    }

    private suspend fun submit(request: AgentHomeworkRequest): AgentHomeworkOutcome {
        // 缓存可能落后于网页端的提交：先同步一次，“已交过”的判断才算数。
        if (!refreshHomework()) stop(AgentHomeworkFailureCode.NETWORK, "无法从学校同步作业状态，这次没有提交。请检查网络或登录后重试。")
        val homework = find(request.homeworkId) ?: missing()
        val alreadySubmitted = isHomeworkSubmitted(homework)
        val existing = if (alreadySubmitted) submittedNames(homework) else emptyList()
        val summary = base(homework)
        if (!request.execute) {
            return AgentHomeworkOutcome.Done(summary.copy(submittedFiles = existing, warnings = warnings(summary, alreadySubmitted),
                submission = AgentHomeworkSubmission("preview", request.note, request.uploads, alreadySubmitted)))
        }
        if (alreadySubmitted && !request.replace) {
            val names = existing.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "（已交文件：$it）" }.orEmpty()
            stop(AgentHomeworkFailureCode.ALREADY_SUBMITTED, "这项作业已经提交过$names，重新提交会覆盖原提交。确认覆盖后加 --replace。")
        }
        val files = readUploads(request.uploads)
        val result = withRecovery { repository.submitHomework(homework, request.note, files) }
        if (result is HomeworkOperationResult.Failure) {
            stop(result.reason.code(), result.serverMessage?.let { "学校拒绝了这次提交：$it" } ?: result.reason.describe("提交"))
        }
        val synced = runCatching { refreshHomework() }.getOrDefault(false)
        val after = find(request.homeworkId) ?: homework
        val verified = synced && isHomeworkSubmitted(after)
        return AgentHomeworkOutcome.Done(base(after).copy(
            submittedFiles = if (verified) runCatching { submittedNames(after) }.getOrDefault(emptyList()) else emptyList(),
            submission = AgentHomeworkSubmission("submitted", request.note, request.uploads, alreadySubmitted, verified),
        ))
    }

    private fun base(homework: Homework): AgentHomeworkResult {
        val dueEpoch = parseSchoolLocalDateTime(homework.endTime)?.schoolEpochSeconds()
        val openEpoch = parseSchoolLocalDateTime(homework.openDate)?.schoolEpochSeconds()
        return AgentHomeworkResult(
            id = "$COURSE_PLATFORM_PREFIX${homework.id}",
            course = homework.courseName.trim(),
            title = homework.title.trim(),
            type = homework.typeLabel(),
            open = openEpoch?.let { agentTimePoint(it, deadline = false).text },
            due = dueEpoch?.let { agentTimePoint(it, deadline = true).text },
            overdue = dueEpoch != null && dueEpoch < nowEpochSeconds(),
            submitted = isHomeworkSubmitted(homework),
            score = homework.score.takeIf(String::isNotBlank),
        )
    }

    private fun warnings(summary: AgentHomeworkResult, alreadySubmitted: Boolean): List<String> = buildList {
        if (summary.overdue) add("这项作业已过截止时间（${summary.due}），学校可能拒收或按迟交处理。")
        if (alreadySubmitted) add("这项作业已经提交过，再次提交会覆盖原提交，需要加 --replace。")
        if (summary.score != null) add("这项作业已有评分：${summary.score}。")
    }

    private suspend fun loadDetail(homework: Homework): HomeworkDetail {
        var detail: HomeworkDetail? = null
        val result = withRecovery {
            when (val loaded = repository.loadDetail(homework)) {
                is HomeworkDetailResult.Failure -> HomeworkOperationResult.Failure(loaded.reason)
                is HomeworkDetailResult.Success -> HomeworkOperationResult.Success(loaded.detail).also { detail = loaded.detail }
            }
        }
        if (result is HomeworkOperationResult.Failure) stop(result.reason.code(), result.reason.describe("读取作业详情"))
        return detail ?: stop(AgentHomeworkFailureCode.NETWORK, HomeworkSyncFailure.MALFORMED_RESPONSE.describe("读取作业详情"))
    }

    private suspend fun submittedNames(homework: Homework): List<String> =
        when (val result = withRecovery { repository.loadSubmittedAttachments(homework) }) {
            is HomeworkOperationResult.Success -> result.value.map { it.fileName }
            is HomeworkOperationResult.Failure -> stop(result.reason.code(), result.reason.describe("读取已交附件"))
        }

    /** 先写到同级暂存目录，全部成功才移动到目标位置；文件名来自学校，一律清洗后再落盘。 */
    private suspend fun downloadAttachments(homework: Homework, detail: HomeworkDetail, destination: File): Map<Int, String> {
        if (detail.attachments.isEmpty()) return emptyMap()
        val downloads = detail.attachments.map { attachment ->
            when (val result = withRecovery { repository.downloadTeacherAttachment(homework.upId, attachment) }) {
                is HomeworkOperationResult.Success -> attachment.id to result.value.bytes
                is HomeworkOperationResult.Failure -> stop(result.reason.code(), result.reason.describe("下载老师附件"))
            }
        }
        return writeAttachments(destination, uniqueAttachmentNames(detail.attachments.map { it.fileName }), downloads)
    }

    private suspend fun <K> writeAttachments(destination: File, names: List<String>, downloads: List<Pair<K, ByteArray>>): Map<K, String> =
        withContext(Dispatchers.IO) {
            val target = destination.toPath()
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) stop(AgentHomeworkFailureCode.IO, "保存目录已存在，请指定新目录。")
            val staging = runCatching { Files.createTempDirectory(target.parent, ".homework-export-") }
                .getOrElse { stop(AgentHomeworkFailureCode.IO, "无法写入保存目录。") }
            try {
                downloads.forEachIndexed { index, (_, bytes) -> Files.write(staging.resolve(names[index]), bytes) }
                Files.move(staging, target)
            } catch (error: java.io.IOException) {
                stop(AgentHomeworkFailureCode.IO, "保存老师附件失败，请检查目录后重试。")
            } finally {
                staging.toFile().deleteRecursively()
            }
            downloads.mapIndexed { index, (id, _) -> id to target.resolve(names[index]).toString() }.toMap()
        }

    private suspend fun readUploads(uploads: List<AgentHomeworkUpload>): List<HomeworkFileContent> = withContext(Dispatchers.IO) {
        totalUploadProblem(uploads.map { File(it.path) })?.let { stop(AgentHomeworkFailureCode.IO, "$it 这次没有提交。") }
        uploads.map { upload ->
            val file = File(upload.path)
            val problem = uploadFileProblem(file)
            if (problem != null) stop(AgentHomeworkFailureCode.IO, "$problem 这次没有提交。")
            try {
                HomeworkFileContent(upload.uploadName, Files.probeContentType(file.toPath()) ?: "application/octet-stream", file.readBytes())
            } catch (_: Exception) {
                stop(AgentHomeworkFailureCode.IO, "无法读取 ${file.name}，这次没有提交。")
            }
        }
    }

    private suspend fun <T> withRecovery(operation: suspend () -> HomeworkOperationResult<T>): HomeworkOperationResult<T> {
        var result: HomeworkOperationResult<T>? = null
        recovery.run(
            operation = { result = operation() },
            sessionExpired = { (result as? HomeworkOperationResult.Failure)?.reason == HomeworkSyncFailure.SESSION_EXPIRED },
        )
        return result ?: HomeworkOperationResult.Failure(HomeworkSyncFailure.SESSION_EXPIRED)
    }
}

private fun HomeworkSyncFailure.code(): String = when (this) {
    HomeworkSyncFailure.SESSION_EXPIRED -> AgentHomeworkFailureCode.SESSION_EXPIRED
    HomeworkSyncFailure.SUBMIT_REJECTED -> AgentHomeworkFailureCode.REJECTED
    else -> AgentHomeworkFailureCode.NETWORK
}

private fun HomeworkSyncFailure.describe(action: String): String = when (this) {
    HomeworkSyncFailure.SESSION_EXPIRED -> "${action}失败：登录已过期且自动恢复没有成功，请在 App 里重新登录。"
    HomeworkSyncFailure.SUBMIT_REJECTED -> "学校拒绝了这次提交。"
    HomeworkSyncFailure.SECURE_CHANNEL_UNAVAILABLE -> "${action}失败：无法建立与学校的安全连接。"
    HomeworkSyncFailure.CACHE -> "${action}失败：本地缓存不可用。"
    HomeworkSyncFailure.MALFORMED_RESPONSE -> "${action}失败：学校返回了无法识别的内容。"
    HomeworkSyncFailure.NETWORK -> "${action}失败：无法连接学校系统。"
}

internal fun uniqueAttachmentNames(names: List<String>): List<String> {
    val used = HashSet<String>()
    return names.map { raw ->
        val safe = safeExportFileName(raw)
        val stem = safe.substringBeforeLast('.', safe)
        val extension = safe.substring(stem.length)
        generateSequence(1) { it + 1 }
            .map { index -> if (index == 1) safe else "$stem ($index)$extension" }
            .first { used.add(it.lowercase()) }
    }
}

/** 文件本身不能提交的原因；可以提交时返回 null。 */
internal fun uploadFileProblem(file: File): String? = when {
    !file.isFile -> "${file.name} 不存在或不是文件（文件夹请先压缩）。"
    file.length() == 0L -> "${file.name} 是空文件。"
    file.length() > MAX_UPLOAD_BYTES -> "${file.name} 超过 ${MAX_UPLOAD_BYTES / (1024 * 1024)} MB，请在 App 里提交。"
    else -> null
}

internal fun totalUploadProblem(files: List<File>): String? =
    "文件总大小超过 ${MAX_TOTAL_UPLOAD_BYTES / (1024 * 1024)} MB，请在 App 里提交。".takeIf { files.sumOf(File::length) > MAX_TOTAL_UPLOAD_BYTES }

/**
 * 无窗口宿主的生命周期跟着它自己的命令走，收尾时会取消 IPC 协程：经 IPC 转来的提交可能停在上传之后、
 * 提交之前。这里在发出任何学校请求前拒绝，调用方可以安全重试；只读请求被取消没有副作用，照常处理。
 */
internal fun headlessIpcHomeworkRefusal(request: AgentHomeworkRequest): AgentHomeworkOutcome.Failed? =
    if (request.mutates) AgentHomeworkOutcome.Failed(RefreshIpcCode.BUSY, "后台命令行正在运行，这次没有提交。请稍后重试。") else null

/** 提交时显示的文件名：只改名，不改扩展名（学校按扩展名校验，改扩展名不等于转换格式）。 */
internal fun uploadNameProblem(name: String, source: File): String? {
    val sourceExtension = source.name.substringAfterLast('.', "")
    val extension = name.substringAfterLast('.', "")
    return when {
        name.isBlank() || name != name.trim() -> "文件名不能为空，首尾也不能有空格：$name"
        name.startsWith(".") -> "文件名不能以点开头：$name"
        name.length > MAX_UPLOAD_NAME_CHARS -> "文件名太长（上限 $MAX_UPLOAD_NAME_CHARS 字）：$name"
        name.any { it.isISOControl() || it in forbiddenNameCharacters } -> "文件名含有不允许的字符：$name"
        !extension.equals(sourceExtension, ignoreCase = true) ->
            "--as 不能改变扩展名（${source.name} → $name）。作业要求其他格式时请先转换文件。"
        else -> null
    }
}

internal data class AgentHomeworkCommand(val request: AgentHomeworkRequest, val json: Boolean)

/** 全部参数在碰缓存、锁和系统安全存储之前校验。 */
internal fun parseAgentHomeworkCommand(args: List<String>): AgentHomeworkCommand {
    val action = args.getOrNull(1)?.takeIf { it == "show" || it == "submit" }
        ?: throw AgentUsageException("homework 只支持 show <id> 或 submit <id> --file <路径>。")
    val rawId = args.getOrNull(2) ?: throw AgentUsageException("缺少作业 id，例如 course_platform:12345。")
    val source = rawId.substringBefore(':', "course_platform")
    if (source !in setOf("course_platform", "phyvlab", "citel")) throw AgentUsageException("作业 id 无效：未知来源 $source。")
    if (action == "submit" && source != "course_platform") {
        throw AgentUsageException("命令行目前只能提交课程平台作业；物理在线和 CITEL 请在 App 里提交。")
    }
    val id = rawId.substringAfter(':').toIntOrNull()?.takeIf { it > 0 }
        ?: throw AgentUsageException("作业 id 无效：应为 course_platform:<数字>。")
    fun value(option: String, next: String?): String =
        next?.takeIf { it.isNotBlank() && !it.startsWith("--") } ?: throw AgentUsageException("$option 后需要一个值。")
    val uploads = mutableListOf<Pair<File, String?>>()
    var out: File? = null
    var note = ""
    var noteSeen = false
    var execute = false
    var replace = false
    var json = false
    var i = 3
    while (i < args.size) {
        when (val option = args[i++]) {
            "--json" -> json = true
            "--file", "--as", "--note", "--out", "--yes", "--replace" -> {
                if (action == "show" && option != "--out") throw AgentUsageException("$option 只用于 submit。")
                if (action == "submit" && option == "--out") throw AgentUsageException("--out 只用于 show。")
                when (option) {
                    "--file" -> uploads += File(value(option, args.getOrNull(i++))).absoluteFile to null
                    "--as" -> {
                        val last = uploads.lastOrNull() ?: throw AgentUsageException("--as 要跟在对应的 --file 后面。")
                        if (last.second != null) throw AgentUsageException("每个 --file 只能有一个 --as。")
                        uploads[uploads.lastIndex] = last.first to value(option, args.getOrNull(i++))
                    }
                    "--note" -> {
                        if (noteSeen) throw AgentUsageException("--note 只能指定一次。")
                        noteSeen = true
                        note = value(option, args.getOrNull(i++))
                    }
                    "--out" -> {
                        if (out != null) throw AgentUsageException("--out 只能指定一次。")
                        val destination = File(value(option, args.getOrNull(i++))).absoluteFile
                        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) throw AgentUsageException("保存目录已存在，请指定新目录。")
                        if (destination.parentFile?.isDirectory != true) throw AgentUsageException("保存目录的父目录不存在。")
                        out = destination
                    }
                    "--yes" -> execute = true
                    "--replace" -> replace = true
                }
            }
            else -> throw AgentUsageException("未知选项：$option")
        }
    }
    if (action == "show") {
        return AgentHomeworkCommand(AgentHomeworkRequest("show", id, source, outDir = out?.path), json)
    }
    if (uploads.isEmpty()) throw AgentUsageException("submit 至少需要一个 --file <路径>。")
    if (uploads.size > MAX_UPLOAD_FILES) throw AgentUsageException("一次最多提交 $MAX_UPLOAD_FILES 个文件。")
    if (note.length > MAX_NOTE_CHARS) throw AgentUsageException("--note 太长（上限 $MAX_NOTE_CHARS 字）。")
    val resolved = uploads.map { (file, alias) ->
        uploadFileProblem(file)?.let { throw AgentUsageException(it) }
        val name = alias ?: file.name
        uploadNameProblem(name, file)?.let { throw AgentUsageException(it) }
        AgentHomeworkUpload(file.path, name)
    }
    if (resolved.distinctBy { it.uploadName.lowercase() }.size != resolved.size) throw AgentUsageException("提交的文件名有重复，请用 --as 区分。")
    totalUploadProblem(uploads.map { it.first })?.let { throw AgentUsageException(it) }
    return AgentHomeworkCommand(AgentHomeworkRequest("submit", id, uploads = resolved, note = note, execute = execute, replace = replace), json)
}

private val outputJson = Json { prettyPrint = true; explicitNulls = false; encodeDefaults = true }

private fun commandName(request: AgentHomeworkRequest) = "homework ${request.action}"

internal fun AgentHomeworkOutcome.toOutput(options: AgentHomeworkCommand, executor: String): AgentOutput = when (this) {
    is AgentHomeworkOutcome.Failed -> failureOutput(options, code, message, when (code) {
        AgentHomeworkFailureCode.NOT_FOUND -> AgentExit.NO_DATA
        AgentHomeworkFailureCode.ALREADY_SUBMITTED -> AgentExit.SUBMIT_BLOCKED
        AgentHomeworkFailureCode.SESSION_EXPIRED, RefreshIpcCode.NOT_SIGNED_IN -> AgentExit.LOGIN_FAILED
        RefreshIpcCode.TIMEOUT -> AgentExit.REFRESH_PENDING
        else -> AgentExit.PARTIAL
    })
    is AgentHomeworkOutcome.Done -> AgentOutput(if (options.json) {
        outputJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("schemaVersion", AGENT_SCHEMA_VERSION)
            put("command", commandName(options.request))
            put("executor", executor)
            put("data", outputJson.encodeToJsonElement(result))
        }) + "\n"
    } else result.markdown())
}

private fun failureOutput(options: AgentHomeworkCommand, code: String, message: String, exit: Int) = AgentOutput(
    if (options.json) outputJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("schemaVersion", AGENT_SCHEMA_VERSION); put("command", commandName(options.request)); put("error", message); put("code", code)
    }) + "\n" else "",
    message, exit,
)

private fun AgentHomeworkResult.markdown(): String = buildString {
    appendLine("# $title")
    appendLine()
    appendLine("- 作业 id：$id")
    appendLine("- 课程：$course（$type）")
    due?.let { appendLine("- 截止：$it" + if (overdue) "（已截止）" else "") }
    if (submissionKnown) appendLine("- 状态：" + if (submitted) "已提交" else "未提交")
    score?.let { appendLine("- 评分：$it") }
    student?.let { appendLine("- 学生：${it.name}（${it.studentId}）") }
    if (submittedFiles.isNotEmpty()) appendLine("- 已交文件：${submittedFiles.joinToString("、")}")
    warnings.forEach { appendLine("- 注意：$it") }
    if (attachments.isNotEmpty()) {
        appendLine()
        appendLine("## 老师附件")
        attachments.forEach { appendLine("- ${it.name}" + (it.savedAs?.let { path -> " → $path" } ?: "")) }
    }
    content?.let {
        appendLine()
        appendLine("## 作业说明")
        appendLine(it)
        if (contentTruncated) appendLine("（说明过长，已截断）")
    }
    submission?.let { submission ->
        appendLine()
        appendLine(when {
            submission.state == "preview" -> "## 提交预览（没有上传。确认后加 --yes）"
            submission.verified == true -> "## 已提交，同步后确认状态为已交"
            else -> "## 已提交，但同步后没能确认状态，请用 homework show 再查"
        })
        submission.files.forEach { appendLine("- ${it.uploadName} ← ${it.path}") }
        if (submission.note.isNotBlank()) appendLine("- 提交说明：${submission.note}")
    }
}

internal fun runAgentHomework(args: List<String>, platform: AgentCliPlatform, ipcDeadlineMillis: Long = HOMEWORK_IPC_DEADLINE_MILLIS): AgentOutput {
    val options = try {
        parseAgentHomeworkCommand(args)
    } catch (error: AgentUsageException) {
        return AgentOutput("", error.message.orEmpty(), AgentExit.USAGE)
    }
    val request = options.request
    fun fail(message: String, exit: Int, code: String = RefreshIpcCode.BAD_REQUEST) = failureOutput(options, code, message, exit)

    when (val call = callRefreshIpc(platform.cacheDirectory, RefreshIpcOp.HOMEWORK, homework = request, deadlineMillis = ipcDeadlineMillis)) {
        RefreshIpcCallResult.NoServer -> Unit
        is RefreshIpcCallResult.Completed -> return call.homework?.let { AgentHomeworkOutcome.Done(it).toOutput(options, call.executor ?: "app") }
            ?: fail("执行者没有返回结果。", AgentExit.PARTIAL)
        is RefreshIpcCallResult.AcceptedWithoutResult -> return fail(
            if (request.mutates) "请求已交给 App，但在时限内没有收到结果，提交可能已经完成。不要直接重试：先运行 homework show 确认已交文件。"
            else "请求已交给 App，但在时限内没有收到结果，请稍后重试。",
            AgentExit.REFRESH_PENDING, RefreshIpcCode.TIMEOUT,
        )
        is RefreshIpcCallResult.Rejected -> return when (call.code) {
            RefreshIpcCode.PROTOCOL_MISMATCH, RefreshIpcCode.UNSUPPORTED ->
                fail("正在运行的 App 不支持命令行交作业，请更新到同一版本后重试。", AgentExit.VERSION_MISMATCH, call.code)
            else -> AgentHomeworkOutcome.Failed(call.code, call.message).toOutput(options, call.executor ?: "app")
        }
    }
    return runHeadless(
        platform = platform,
        fail = { message, exit -> fail(message, exit) },
        onLocked = { fail("交大自由行 App 正在运行，但没有响应（可能是旧版本或正在启动）。请稍后重试，或在 App 里操作。", AgentExit.APP_RUNNING) },
    ) { host ->
        val result = host.runHomework(request)
        when {
            result.handedOff -> fail("App 已启动并接手，这次没有提交。请重新运行命令。", AgentExit.REFRESH_PENDING, RefreshIpcCode.HANDING_OFF)
            result.accountMismatch -> fail("学校返回的账号与记住的账号不一致，请在 App 里重新登录。", AgentExit.LOGIN_FAILED)
            result.homework == null -> loginFailureMessage(result.login).let { (code, message) -> fail(message, AgentExit.LOGIN_FAILED, code) }
            else -> result.homework.toOutput(options, "headless")
        }
    } ?: fail("执行者没有返回结果。", AgentExit.PARTIAL)
}

/** 超过默认值时 CLI 按“结果不明”处理；上传大文件要留够时间。 */
private const val HOMEWORK_IPC_DEADLINE_MILLIS = 10 * 60_000L

package team.bjtuss.bjtuselfservice.shared.agent

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkDetailResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkOperationResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkRepository
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabAssignmentDetailResult
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabAttachmentResult
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAttachment

/** 小于等于这个大小的附件才上传到私人 Site；更大的只能在本机拉取。 */
const val AGENT_ATTACHMENT_ONLINE_LIMIT_BYTES = 5L * 1024 * 1024

/** 详情文字进缓存的上限；进快照时还会按作业状态再截断，见 [assignmentContentLimit]。 */
private const val MAX_DETAIL_CONTENT_CHARS = 40_000
private const val DETAILS_KEY = "agent.assignment_details.v1"
/** 同步时详情的新鲜期：作业说明很少变，不必每轮都访问学校。 */
private const val DETAIL_FRESH_SECONDS = 24 * 3600L
/** 一轮同步最多补几项详情，避免一次打很多学校请求。 */
private const val MAX_PENDING_DETAILS_PER_SYNC = 15
private const val DETAIL_RETRY_KEY = "agent.assignment_details.retry_after.v1"

@Serializable
data class AgentAttachmentInfo(
    /** 在同一项作业内稳定；不含学校存储路径或地址。 */
    val id: String,
    val name: String,
    /** 物理在线下载前不知道大小，此时为 null。 */
    val sizeBytes: Long? = null,
)

/** 三个来源统一的作业详情；课程名、题面都是不可信数据，只当信息展示。 */
@Serializable
data class AgentAssignmentDetail(
    /** 与作业列表同一 id：course_platform:… / phyvlab:… / citel:… */
    val id: String,
    /** 纯文本说明，题号等都在这里。 */
    val content: String? = null,
    val attachments: List<AgentAttachmentInfo> = emptyList(),
    val submittedFiles: List<String> = emptyList(),
    val fetchedAt: Long,
    val submitted: Boolean? = null,
)

private val detailJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

fun loadAgentAssignmentDetails(cache: CacheStore, account: String): Map<String, AgentAssignmentDetail> =
    runCatching {
        cache.metadata(account, DETAILS_KEY)?.let {
            detailJson.decodeFromString(ListSerializer(AgentAssignmentDetail.serializer()), it).associateBy(AgentAssignmentDetail::id)
        }
    }.getOrNull().orEmpty()

/** FNV-1a：把学校地址变成稳定、不可读的短 ID；用于去重，不用于安全。 */
internal fun agentStableId(value: String): String {
    var hash = -3750763034362895579L
    for (byte in value.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 1099511628211L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

class AgentDetailException(val code: String, message: String) : Exception(message)

/** 物理在线与 CITEL 依赖会话和登录流程，由组合根传入；未启用时为 null。 */
class AgentDetailBackends(
    val phyVlabActivities: () -> List<PhyVlabActivity> = { emptyList() },
    val phyVlabDetail: (suspend (PhyVlabActivity) -> PhyVlabAssignmentDetailResult)? = null,
    val phyVlabDownload: (suspend (PhyVlabAttachment) -> PhyVlabAttachmentResult)? = null,
    /** 返回题面 Markdown；live=true 才联网，同步时只用缓存。 */
    val citelProblem: (suspend (Int, Boolean) -> String?)? = null,
    val citelSummary: (Int) -> AgentAssignmentSummary? = { null },
    val beginDetailBatch: () -> Unit = {},
    val endDetailBatch: () -> Unit = {},
)

data class AgentAssignmentSummary(val course: String, val title: String, val dueEpoch: Long?, val submitted: Boolean? = null)

/**
 * 统一的作业详情：本机 `homework show` 和远程同步都走这里，所以两边看到的内容一致。
 * 结果缓存在本账号的 metadata 里，快照和离线查询直接读缓存，不访问学校。
 */
class AgentAssignmentDetailService(
    private val account: String,
    private val cache: CacheStore,
    private val homework: HomeworkRepository,
    private val backends: AgentDetailBackends,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    fun cached(): Map<String, AgentAssignmentDetail> = loadAgentAssignmentDetails(cache, account)

    fun summary(id: String): AgentAssignmentSummary? {
        val (source, number) = splitId(id)
        return when (source) {
            "phyvlab" -> backends.phyVlabActivities().firstOrNull { it.id == number }
                ?.let { activity -> AgentAssignmentSummary(activity.courseName, activity.title, activity.dueTimestamp, cached()[id]?.submitted) }
            "citel" -> backends.citelSummary(number)
            else -> null
        }
    }

    /** 联网取一项详情并写缓存；失败抛 [AgentDetailException]，旧缓存保留。 */
    suspend fun refresh(id: String, live: Boolean = true): AgentAssignmentDetail {
        val detail = fetch(id, live)
        val existing = cached()
        save(existing + (id to mergeKnownAttachmentSizes(existing[id], detail)))
        return detail
    }

    /** 同步时补未交作业的详情：单项失败不影响其他项，也不影响快照上传。 */
    suspend fun refreshPending(ids: List<String>): Int {
        val existing = cached().toMutableMap()
        val cutoff = now() - DETAIL_FRESH_SECONDS
        val retryAfter = runCatching { detailJson.decodeFromString<Map<String, Long>>(cache.metadata(account, DETAIL_RETRY_KEY).orEmpty()) }.getOrDefault(emptyMap()).toMutableMap()
        val stale = ids.filter { (existing[it]?.fetchedAt ?: 0L) < cutoff && (retryAfter[it] ?: 0L) <= now() }
            .take(MAX_PENDING_DETAILS_PER_SYNC)
        var refreshed = 0
        backends.beginDetailBatch()
        try {
            for (id in stale) {
                try {
                    existing[id] = mergeKnownAttachmentSizes(existing[id], fetch(id, live = false))
                    retryAfter.remove(id)
                    refreshed++
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    retryAfter[id] = now() + DETAIL_FRESH_SECONDS
                    continue
                }
            }
        } finally {
            backends.endDetailBatch()
        }
        if (refreshed > 0) save(existing)
        cache.putMetadata(account, DETAIL_RETRY_KEY, detailJson.encodeToString(retryAfter))
        return refreshed
    }

    /** 作业从列表消失后丢掉它的详情，缓存不无限增长。 */
    fun retain(ids: Set<String>) {
        val existing = cached()
        val kept = existing.filterKeys { it in ids }
        if (kept.size != existing.size) save(kept)
    }

    suspend fun attachment(id: String, attachmentId: String): HomeworkFileContent {
        val (source, number) = splitId(id)
        return when (source) {
            "course_platform" -> {
                val item = homework.load().homework.firstOrNull { it.id == number } ?: missing()
                val detail = (homework.loadDetail(item) as? HomeworkDetailResult.Success)?.detail ?: network("读取作业详情")
                val attachment = detail.attachments.firstOrNull { it.id.toString() == attachmentId } ?: missing()
                when (val result = homework.downloadTeacherAttachment(item.upId, attachment)) {
                    is HomeworkOperationResult.Success -> result.value
                    is HomeworkOperationResult.Failure -> network("下载附件")
                }
            }
            "phyvlab" -> {
                val download = backends.phyVlabDownload ?: throw AgentDetailException("not_enabled", "物理在线未启用。")
                val detail = phyVlabDetail(number)
                val attachment = detail.attachments.firstOrNull { agentStableId(it.url) == attachmentId } ?: missing()
                when (val result = download(attachment)) {
                    is PhyVlabAttachmentResult.Success -> result.file
                    is PhyVlabAttachmentResult.Failure -> network("下载附件")
                }
            }
            else -> throw AgentDetailException("not_supported", "这个来源没有可下载的附件。")
        }
    }

    /** 下载后才知道大小的附件，记下来：超过上限的不用每轮再下载一次。 */
    fun rememberAttachmentSize(id: String, attachmentId: String, sizeBytes: Long) {
        val existing = cached()
        val detail = existing[id] ?: return
        save(existing + (id to detail.copy(attachments = detail.attachments.map {
            if (it.id == attachmentId) it.copy(sizeBytes = sizeBytes) else it
        })))
    }

    private suspend fun fetch(id: String, live: Boolean): AgentAssignmentDetail {
        val (source, number) = splitId(id)
        return when (source) {
            "course_platform" -> {
                val item = homework.load().homework.firstOrNull { it.id == number } ?: missing()
                val detail = (homework.loadDetail(item) as? HomeworkDetailResult.Success)?.detail ?: network("读取作业详情")
                AgentAssignmentDetail(id, text(detail.content),
                    detail.attachments.map { AgentAttachmentInfo(it.id.toString(), it.fileName, it.sizeBytes.takeIf { size -> size > 0 }) },
                    fetchedAt = now())
            }
            "phyvlab" -> {
                val detail = phyVlabDetail(number)
                AgentAssignmentDetail(id, detail.description.take(MAX_DETAIL_CONTENT_CHARS).ifBlank { null },
                    detail.attachments.map { AgentAttachmentInfo(agentStableId(it.url), it.fileName) },
                    detail.submittedFiles.map { it.fileName },
                    fetchedAt = now(),
                    submitted = team.bjtuss.bjtuselfservice.shared.feature.phyvlab.phyVlabAssignmentDetailSubmissionState(detail))
            }
            "citel" -> {
                val problem = backends.citelProblem ?: throw AgentDetailException("not_enabled", "CITEL 未启用。")
                AgentAssignmentDetail(id, problem(number, live)?.take(MAX_DETAIL_CONTENT_CHARS)
                    ?: throw AgentDetailException("not_cached", "没有这道题的题面。CITEL 书面作业暂不支持读取详情。"), fetchedAt = now())
            }
            else -> missing()
        }
    }

    private suspend fun phyVlabDetail(activityId: Int) = run {
        val load = backends.phyVlabDetail ?: throw AgentDetailException("not_enabled", "物理在线未启用。")
        val activity = backends.phyVlabActivities().firstOrNull { it.id == activityId } ?: missing()
        when (val result = load(activity)) {
            is PhyVlabAssignmentDetailResult.Success -> result.detail
            is PhyVlabAssignmentDetailResult.Failure -> network("读取作业详情")
        }
    }

    private fun text(html: String) = plainText(html).take(MAX_DETAIL_CONTENT_CHARS).ifBlank { null }

    private fun save(details: Map<String, AgentAssignmentDetail>) {
        cache.putMetadata(account, DETAILS_KEY, detailJson.encodeToString(ListSerializer(AgentAssignmentDetail.serializer()), details.values.toList()))
    }

    private fun mergeKnownAttachmentSizes(old: AgentAssignmentDetail?, fresh: AgentAssignmentDetail): AgentAssignmentDetail {
        if (old == null) return fresh
        val knownSizes = old.attachments.associate { it.id to it.sizeBytes }
        return fresh.copy(attachments = fresh.attachments.map { it.copy(sizeBytes = it.sizeBytes ?: knownSizes[it.id]) })
    }

    private fun splitId(id: String): Pair<String, Int> {
        val source = id.substringBefore(':')
        val number = id.substringAfter(':', "").toIntOrNull()?.takeIf { it > 0 } ?: missing()
        return source to number
    }

    private fun missing(): Nothing = throw AgentDetailException("not_found", "作业列表里没有这项作业或附件。")
    private fun network(action: String): Nothing = throw AgentDetailException("network", "${action}失败，请检查网络或登录后重试。")
}

package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import team.bjtuss.bjtuselfservice.shared.agent.AgentSnapshot
import team.bjtuss.bjtuselfservice.shared.agent.buildAgentDataset
import team.bjtuss.bjtuselfservice.shared.agent.remoteScheduleWindow
import team.bjtuss.bjtuselfservice.shared.agent.toJson
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

/** 每个上云范围包含的来源；只上报已授权范围的来源状态。 */
val REMOTE_SCOPE_SOURCES: Map<RemoteScope, List<RefreshSource>> = mapOf(
    RemoteScope.SCHEDULE to listOf(RefreshSource.COURSES, RefreshSource.PHYSICS_LAB, RefreshSource.PHYSICS_DEMO),
    RemoteScope.EXAMS to listOf(RefreshSource.EXAMS),
    RemoteScope.ASSIGNMENTS to listOf(RefreshSource.HOMEWORK, RefreshSource.PHYVLAB, RefreshSource.CITEL, RefreshSource.PHYSICS_LAB),
    RemoteScope.GRADES to listOf(RefreshSource.GRADES),
    RemoteScope.MAIL to listOf(RefreshSource.MAIL_UNREAD),
)

data class RemoteSnapshotContent(
    val sources: Map<String, RemoteSourceState>,
    val data: JsonObject,
)

/**
 * 从本机缓存生成经范围过滤的快照内容。`data` 就是 Agent 数据集（schema 2）的 JSON：
 * 与本机 CLI 用同一个生成函数，Site 用同构的查询引擎读取，手机端和本机看到同一种结构、同一套日期换算。
 *
 * - 课表展开前 7 天到后 8 周；作业、考试、成绩、邮箱摘要全部放入（不按日期截断）。
 * - 来源状态取最近一次刷新报告；没有报告时标为 skipped/not_refreshed，同步时间仍取缓存里的真实时间。
 * - 不含学号、密码、Cookie、令牌；课程名、邮件、题面等是不可信内容，由手机端按数据处理。
 */
fun buildRemoteSnapshotContent(
    snapshot: AgentSnapshot,
    scopes: Set<RemoteScope>,
    lastStates: Map<String, RemoteSourceState>,
    fetchedAt: (RefreshSource) -> Long?,
    now: Instant,
): RemoteSnapshotContent {
    val sources = remoteSourceStates(scopes, lastStates, fetchedAt)
    val (from, to) = remoteScheduleWindow(now)
    val dataset = buildAgentDataset(snapshot, remoteDatasetParts(scopes), from, to, now, gradesAllowed = true)
    return RemoteSnapshotContent(sources, dataset.toJson())
}

fun remoteSourceStates(
    scopes: Set<RemoteScope>,
    lastStates: Map<String, RemoteSourceState>,
    fetchedAt: (RefreshSource) -> Long?,
): Map<String, RemoteSourceState> = scopes.flatMap { REMOTE_SCOPE_SOURCES[it].orEmpty() }.distinct()
    .sortedBy { it.ordinal }
    .associate { source ->
        val last = lastStates[source.wire]
        source.wire to RemoteSourceState(
            status = last?.status ?: RefreshStatus.SKIPPED.wire,
            fetchedAt = fetchedAt(source),
            reason = if (last == null) "not_refreshed" else last.reason,
        )
    }

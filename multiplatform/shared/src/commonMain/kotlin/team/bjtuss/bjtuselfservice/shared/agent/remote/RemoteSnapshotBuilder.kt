package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.bjtuss.bjtuselfservice.shared.agent.AGENT_TIME_ZONE
import team.bjtuss.bjtuselfservice.shared.agent.AgentCalendarKind
import team.bjtuss.bjtuselfservice.shared.agent.AgentCalendarOptions
import team.bjtuss.bjtuselfservice.shared.agent.AgentHomeworkOptions
import team.bjtuss.bjtuselfservice.shared.agent.AgentSnapshot
import team.bjtuss.bjtuselfservice.shared.agent.agentCalendar
import team.bjtuss.bjtuselfservice.shared.agent.agentHomework
import team.bjtuss.bjtuselfservice.shared.agent.calendarJson
import team.bjtuss.bjtuselfservice.shared.agent.citelJson
import team.bjtuss.bjtuselfservice.shared.agent.examsJson
import team.bjtuss.bjtuselfservice.shared.agent.gradesJson
import team.bjtuss.bjtuselfservice.shared.agent.homeworkJson
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

/** 每个上云范围包含的来源；只上报已授权范围的来源状态。 */
val REMOTE_SCOPE_SOURCES: Map<RemoteScope, List<RefreshSource>> = mapOf(
    RemoteScope.SCHEDULE to listOf(RefreshSource.COURSES, RefreshSource.PHYSICS_LAB, RefreshSource.PHYSICS_DEMO),
    RemoteScope.EXAMS to listOf(RefreshSource.EXAMS),
    RemoteScope.ASSIGNMENTS to listOf(RefreshSource.HOMEWORK, RefreshSource.PHYVLAB, RefreshSource.CITEL,
        RefreshSource.PHYSICS_LAB, RefreshSource.PHYSICS_DEMO),
    RemoteScope.GRADES to listOf(RefreshSource.GRADES),
    RemoteScope.MAIL to listOf(RefreshSource.MAIL_UNREAD),
)

/** 快照里日程覆盖的天数（含昨天）；更远的安排用即时查询（PR 5）。 */
const val REMOTE_CALENDAR_DAYS = 21

data class RemoteSnapshotContent(
    val sources: Map<String, RemoteSourceState>,
    val data: JsonObject,
)

/**
 * 从本机缓存生成经范围过滤的快照内容。数据投影直接复用本机 CLI 的 JSON（北京时间、零点截止归前一天、
 * 实验预约过滤、CITEL 已提交未 AC 等规则），手机端与本机 Skill 对同一数据的含义一致。
 *
 * - 来源状态取最近一次刷新报告；没有报告时标为 skipped/not_refreshed，同步时间仍取缓存里的真实时间。
 * - 邮箱未读数没有落盘，`mail` 范围暂只上报来源状态，不带数据字段。
 * - 不含学号、密码、Cookie、令牌；课程名、题面等是不可信内容，由手机端按数据处理。
 */
fun buildRemoteSnapshotContent(
    snapshot: AgentSnapshot,
    scopes: Set<RemoteScope>,
    lastStates: Map<String, RemoteSourceState>,
    fetchedAt: (RefreshSource) -> Long?,
    now: Instant,
): RemoteSnapshotContent {
    val sources = scopes.flatMap { REMOTE_SCOPE_SOURCES[it].orEmpty() }.distinct()
        .sortedBy { it.ordinal }
        .associate { source ->
            val last = lastStates[source.wire]
            source.wire to RemoteSourceState(
                status = last?.status ?: RefreshStatus.SKIPPED.wire,
                fetchedAt = fetchedAt(source),
                reason = if (last == null) "not_refreshed" else last.reason,
            )
        }
    val today = now.toLocalDateTime(AGENT_TIME_ZONE).date
    val from = today.minus(1, DateTimeUnit.DAY)
    val to = today.plus(REMOTE_CALENDAR_DAYS - 2, DateTimeUnit.DAY)
    val data = buildJsonObject {
        if (RemoteScope.SCHEDULE in scopes) {
            val items = agentCalendar(snapshot, from, to, now, AgentCalendarOptions(kinds = setOf(AgentCalendarKind.CLASS, AgentCalendarKind.LAB)))
            put(RemoteScope.SCHEDULE.wire, buildJsonObject {
                put("timeZone", AGENT_TIME_ZONE.id)
                put("currentWeek", snapshot.currentWeek)
                put("calendar", calendarJson(items, from to to, snapshot))
            })
        }
        if (RemoteScope.EXAMS in scopes) {
            put(RemoteScope.EXAMS.wire, buildJsonObject { put("exams", examsJson(snapshot)) })
        }
        if (RemoteScope.ASSIGNMENTS in scopes) {
            put(RemoteScope.ASSIGNMENTS.wire, buildJsonObject {
                put("homework", homeworkJson(agentHomework(snapshot, AgentHomeworkOptions(), now), snapshot))
                put("calendar", calendarJson(agentCalendar(snapshot, from, to, now, AgentCalendarOptions(kinds = setOf(
                    AgentCalendarKind.ASSIGNMENT_OPEN, AgentCalendarKind.ASSIGNMENT_DISCOUNT, AgentCalendarKind.ASSIGNMENT_DUE,
                ))), from to to, snapshot))
                if (snapshot.citelEnabled) put("citel", citelJson(snapshot))
            })
        }
        if (RemoteScope.GRADES in scopes) {
            put(RemoteScope.GRADES.wire, buildJsonObject { put("grades", gradesJson(snapshot)) })
        }
    }
    return RemoteSnapshotContent(sources, data)
}

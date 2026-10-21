package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
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
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSource
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AggregateAssignmentFilters

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
    calendarDate: LocalDate? = null,
    querySources: Set<RefreshSource>? = null,
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
    val from = calendarDate ?: today.minus(1, DateTimeUnit.DAY)
    val to = calendarDate ?: today.plus(REMOTE_CALENDAR_DAYS - 2, DateTimeUnit.DAY)
    fun includes(source: RefreshSource) = querySources == null || source in querySources
    // 每个范围的提示也独立生成，随后关闭其他范围时，旧快照不能从提示里泄露其课程名。
    val scheduleFormatting = snapshot.copy(
        courses = if (includes(RefreshSource.COURSES)) snapshot.courses else emptyList(),
        homework = emptyList(), phyVlab = null, citelTasks = emptyList(), assignmentFilters = AggregateAssignmentFilters(),
    )
    val assignmentFormatting = snapshot.copy(
        courses = emptyList(),
        homework = if (includes(RefreshSource.HOMEWORK)) snapshot.homework else emptyList(),
        phyVlab = if (includes(RefreshSource.PHYVLAB)) snapshot.phyVlab else null,
        citelTasks = if (includes(RefreshSource.CITEL)) snapshot.citelTasks else emptyList(),
    )
    val homeworkOptions = AgentHomeworkOptions(sources = AssignmentSource.entries.filter { source ->
        includes(when (source) {
            AssignmentSource.COURSE_PLATFORM -> RefreshSource.HOMEWORK
            AssignmentSource.PHYVLAB -> RefreshSource.PHYVLAB
            AssignmentSource.CITEL -> RefreshSource.CITEL
        })
    }.toSet())
    val data = buildJsonObject {
        if (RemoteScope.SCHEDULE in scopes) {
            // 保留来源标识，云端按物理实验/演示实验精确筛选时无需猜标题。
            val items = listOf(RefreshSource.COURSES, RefreshSource.PHYSICS_LAB, RefreshSource.PHYSICS_DEMO)
                .filter(::includes).flatMap { source ->
                    val selected = snapshot.copy(
                        courses = if (source == RefreshSource.COURSES) snapshot.courses else emptyList(),
                        exams = emptyList(),
                        physicsLabs = if (source == RefreshSource.PHYSICS_LAB) snapshot.physicsLabs else null,
                        physicsDemos = if (source == RefreshSource.PHYSICS_DEMO) snapshot.physicsDemos else null,
                    )
                    agentCalendar(selected, from, to, now, AgentCalendarOptions(kinds = setOf(AgentCalendarKind.CLASS, AgentCalendarKind.LAB)))
                        .map { it.copy(source = source.wire) }
                }.sortedBy { it.start }
            put(RemoteScope.SCHEDULE.wire, buildJsonObject {
                put("timeZone", AGENT_TIME_ZONE.id)
                put("currentWeek", snapshot.currentWeek)
                put("calendar", calendarJson(items, from to to, scheduleFormatting))
            })
        }
        if (RemoteScope.EXAMS in scopes) {
            put(RemoteScope.EXAMS.wire, buildJsonObject { put("exams", examsJson(snapshot)) })
        }
        if (RemoteScope.ASSIGNMENTS in scopes) {
            put(RemoteScope.ASSIGNMENTS.wire, buildJsonObject {
                put("homework", homeworkJson(agentHomework(snapshot, homeworkOptions, now), assignmentFormatting))
                put("calendar", calendarJson(agentCalendar(snapshot, from, to, now, AgentCalendarOptions(kinds = setOf(
                    AgentCalendarKind.ASSIGNMENT_OPEN, AgentCalendarKind.ASSIGNMENT_DISCOUNT, AgentCalendarKind.ASSIGNMENT_DUE,
                ), homework = homeworkOptions)), from to to, assignmentFormatting))
                if (snapshot.citelEnabled && includes(RefreshSource.CITEL)) put("citel", citelJson(snapshot))
            })
        }
        if (RemoteScope.GRADES in scopes) {
            put(RemoteScope.GRADES.wire, buildJsonObject { put("grades", gradesJson(snapshot)) })
        }
    }
    return RemoteSnapshotContent(sources, data)
}

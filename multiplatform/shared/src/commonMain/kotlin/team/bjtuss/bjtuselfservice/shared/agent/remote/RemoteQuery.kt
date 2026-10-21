package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import team.bjtuss.bjtuselfservice.shared.agent.AgentDatasetPart
import team.bjtuss.bjtuselfservice.shared.agent.AgentQuery
import team.bjtuss.bjtuselfservice.shared.agent.AgentQueryCommand
import team.bjtuss.bjtuselfservice.shared.agent.AgentQueryException
import team.bjtuss.bjtuselfservice.shared.agent.parseAgentQuery
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource

/**
 * 即时查询（request_live_query）：参数就是对应 MCP 查询工具的参数，再加 `command` 与 `freshness`。
 * 电脑端用与 CLI 相同的引擎在本机缓存上执行，返回结构与 get_homework 等完全一致，只是 origin 不同。
 */
data class RemoteLiveQuery(val query: AgentQuery, val latest: Boolean)

/** 每个查询至少需要其中一个上云范围；calendar 用已授权的部分拼出日程。 */
fun remoteCommandScopes(command: String): Set<RemoteScope> = when (command) {
    AgentQueryCommand.CALENDAR -> setOf(RemoteScope.SCHEDULE, RemoteScope.EXAMS, RemoteScope.ASSIGNMENTS)
    AgentQueryCommand.HOMEWORK -> setOf(RemoteScope.ASSIGNMENTS)
    AgentQueryCommand.EXAMS -> setOf(RemoteScope.EXAMS)
    AgentQueryCommand.GRADES -> setOf(RemoteScope.GRADES)
    AgentQueryCommand.MAIL -> setOf(RemoteScope.MAIL)
    else -> RemoteScope.entries.toSet()
}

fun remoteDatasetParts(scopes: Set<RemoteScope>): Set<AgentDatasetPart> = scopes.map { scope ->
    when (scope) {
        RemoteScope.SCHEDULE -> AgentDatasetPart.SCHEDULE
        RemoteScope.EXAMS -> AgentDatasetPart.EXAMS
        RemoteScope.ASSIGNMENTS -> AgentDatasetPart.ASSIGNMENTS
        RemoteScope.GRADES -> AgentDatasetPart.GRADES
        RemoteScope.MAIL -> AgentDatasetPart.MAIL
    }
}.toSet()

/** freshness=latest 时只刷新这个查询用得到、且已授权的来源。 */
fun remoteRefreshSources(command: String, scopes: Set<RemoteScope>): Set<RefreshSource> =
    remoteCommandScopes(command).filter { it in scopes }.flatMap { REMOTE_SCOPE_SOURCES[it].orEmpty() }.toSet()

/** 抛出 IllegalArgumentException：message 为 bad_request 或 scope_denied。 */
fun parseRemoteLiveQuery(arguments: JsonObject, scopes: Set<RemoteScope>): RemoteLiveQuery {
    val command = (arguments["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it in AgentQueryCommand.ALL } ?: throw IllegalArgumentException("bad_request")
    val freshness = (arguments["freshness"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "local"
    if (freshness !in setOf("local", "latest")) throw IllegalArgumentException("bad_request")
    if (remoteCommandScopes(command).none { it in scopes }) throw IllegalArgumentException("scope_denied")
    val query = try {
        parseAgentQuery(command, JsonObject(arguments - "command" - "freshness"))
    } catch (_: AgentQueryException) {
        throw IllegalArgumentException("bad_request")
    }
    return RemoteLiveQuery(query, freshness == "latest")
}

/** 读取单封邮件正文的参数：Coremail 邮件 ID。 */
fun parseRemoteMailMessage(arguments: JsonObject, scopes: Set<RemoteScope>): String {
    if (RemoteScope.MAIL !in scopes) throw IllegalArgumentException("scope_denied")
    if (arguments.keys.any { it != "messageId" }) throw IllegalArgumentException("bad_request")
    return (arguments["messageId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { Regex("^[A-Za-z0-9:._-]{1,128}$").matches(it) } ?: throw IllegalArgumentException("bad_request")
}

/** 任务在当前授权下是否还允许执行（领取时与提交前各查一次）。 */
fun remoteTaskAllowed(operation: String, arguments: JsonObject, scopes: Set<RemoteScope>): Boolean = when (operation) {
    RemoteOperation.QUERY -> runCatching { parseRemoteLiveQuery(arguments, scopes) }.isSuccess
    RemoteOperation.CITEL_PROBLEM -> RemoteScope.ASSIGNMENTS in scopes
    RemoteOperation.MAIL_MESSAGE -> runCatching { parseRemoteMailMessage(arguments, scopes) }.isSuccess
    else -> true
}

data class RemoteQueryOutcome(val status: String, val result: JsonObject, val error: RemoteError? = null)

package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource

/** 白名单查询 DTO；与 Site query 参数一致，不转为 shell、路径或任意 URL。 */
data class RemoteQuery(
    val sources: Set<RefreshSource>, val latest: Boolean = false,
    val course: String? = null, val date: LocalDate? = null, val taskId: String? = null,
)

fun parseRemoteQuery(arguments: JsonObject, scopes: Set<RemoteScope>): RemoteQuery {
    require(arguments.keys.all { it in setOf("sources", "freshness", "course", "date", "taskId") }) { "bad_request" }
    val names = (arguments["sources"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: error("bad_request") }
        ?: error("bad_request")
    require(names.isNotEmpty()) { "bad_request" }
    val sources = names.map { name -> RefreshSource.entries.firstOrNull { it.wire == name } ?: error("bad_request") }.toSet()
    val allowed = scopes.flatMap { REMOTE_SCOPE_SOURCES[it].orEmpty() }.toSet()
    require(sources.all { it in allowed }) { "scope_denied" }
    fun string(key: String): String? = arguments[key]?.let {
        require(it is JsonPrimitive && it.isString) { "bad_request" }
        it.content.also { value -> require(value.isNotBlank() && value.length <= 128) { "bad_request" } }
    }
    val freshness = string("freshness") ?: "local"
    require(freshness in setOf("local", "latest")) { "bad_request" }
    return RemoteQuery(sources, freshness == "latest", string("course"), string("date")?.let(LocalDate::parse), string("taskId"))
}

/** 日期优先沿用 CLI 的 agendaDate/dueAgendaDate，保留午夜归日前一天的含义。 */
fun projectRemoteQuery(data: JsonObject, query: RemoteQuery): JsonObject {
    fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
    fun project(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.filter { item ->
            if (item !is JsonObject) true else {
                val source = item.text("source")?.let { if (it in setOf("course", "course_platform")) "homework" else it }
                val due = item.text("dueTime", "dueAt")
                val dueDate = due?.take(10)?.let(LocalDate::parse)?.let { day ->
                    if (due?.contains("T00:00:00+08:00") == true) kotlinx.datetime.LocalDate.fromEpochDays(day.toEpochDays() - 1) else day
                }?.toString()
                val date = item.text("agendaDate", "dueAgendaDate", "date", "start")?.take(10) ?: dueDate
                (source == null || query.sources.any { it.wire == source }) &&
                    (query.course == null || item.text("courseName", "course", "name", "title").orEmpty().contains(query.course, ignoreCase = true)) &&
                    (query.taskId == null || item.text("id", "taskId") == query.taskId) &&
                    (query.date == null || date == query.date.toString())
            }
        })
        is JsonObject -> JsonObject(value.mapValues { project(it.value) })
        else -> value
    }
    val scopes = REMOTE_SCOPE_SOURCES.filterValues { sources -> sources.any { it in query.sources } }.keys
    return JsonObject(data.filterKeys { name -> scopes.any { it.wire == name } }.mapValues { project(it.value) })
}

data class RemoteQueryOutcome(val status: String, val result: JsonObject, val error: RemoteError? = null)

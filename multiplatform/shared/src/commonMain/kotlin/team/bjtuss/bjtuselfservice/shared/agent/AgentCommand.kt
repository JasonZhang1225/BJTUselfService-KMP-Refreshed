package team.bjtuss.bjtuselfservice.shared.agent

import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** 命令行输出：stdout 给调用方（agent）解析，stderr 只放人能读的提示。 */
data class AgentOutput(val stdout: String, val stderr: String = "", val exitCode: Int = AgentExit.OK)

object AgentExit {
    const val OK = 0
    const val USAGE = 64
    /** 没有缓存，或无法确定账号：请先打开 App 登录并同步一次。 */
    const val NO_DATA = 2
    /** 用户在 App 里没有允许 Agent 读取这一类数据。 */
    const val NOT_ALLOWED = 5
    /** cache update：自动登录失败（验证码多次失败、密码变更、未记住密码或网络）。 */
    const val LOGIN_FAILED = 3
    /** cache update：部分数据源刷新失败，失败项保留原缓存。 */
    const val PARTIAL = 4
    /** cache update：App 正在运行但没有响应刷新请求（旧版本 App 或正在启动），请在 App 里刷新。 */
    const val APP_RUNNING = 6
    /** cache update：请求已交给 App/后台执行者，但在时限内没有收到结果；刷新可能仍在进行。 */
    const val REFRESH_PENDING = 7
    /** cache update：命令行与运行中的 App 刷新协议版本不一致，需要更新到同一版本。 */
    const val VERSION_MISMATCH = 8
}

/** CLI JSON 外层版本；与远程查询、数据集同为 2。 */
const val AGENT_SCHEMA_VERSION = AGENT_DATASET_SCHEMA_VERSION

/** 打开缓存的结果由平台层提供；命令本身是纯逻辑，便于测试。 */
fun interface AgentSnapshotProvider {
    fun load(today: LocalDate): AgentSnapshotResult
}

sealed interface AgentSnapshotResult {
    data class Ready(val snapshot: AgentSnapshot, val gradesAllowed: Boolean, val appVersion: String) : AgentSnapshotResult
    data class Unavailable(val reason: String) : AgentSnapshotResult
}

val AGENT_USAGE = """
    用法: bjtu <命令> [选项] [--json]
      status                       数据可用情况与各来源同步时间
      homework [筛选]              作业：一次返回课程平台、物理在线、CITEL 全部来源（默认只列未交且未截止的）
      calendar [范围] [筛选]       按天列出课程、实验、考试、作业截止（默认未来 7 天）
      courses [范围]               只看课程与实验（默认本周）
      exams [--status upcoming|all] [--course 关键词]
      grades [--course 关键词] [--term 学期]   需要在 App 里允许
      mail [--unread] [--keyword 关键词] [--limit N]   收件箱最近邮件摘要
      citel                        CITEL 任务元数据
      citel show <id> [--fetch] [--json]   缓存题面与用例；--fetch 才联网
      citel export <id> --out <新目录> [--fetch]
      cache update [all|来源,...]  全量刷新（App 开着时交给 App 执行）
      install [--no-claude] | uninstall | doctor   安装/移除 Skill 与启动脚本（仅桌面端）
      uninstall-app [--yes]        Mac 卸载清理：默认预览
    范围（calendar、courses，以及 homework 按截止日筛选；只能选一种）:
      --range today|tomorrow|this_week|next_week|next_7_days   （--today/--tomorrow/--week/--next-week 是简写）
      --date YYYY-MM-DD | --from YYYY-MM-DD --to YYYY-MM-DD | --week N（第 N 教学周）
    筛选:
      --status pending|unsubmitted|overdue|submitted|all   homework，默认 pending（未交且未截止）
      --sources course_platform,phyvlab,citel               homework，默认全部
      --kinds class,lab,exam,assignment_due,assignment_discount,assignment_open   calendar
      --course 关键词   --include-unbooked（含没约的物理实验）  --detail（含作业说明与链接）
    与远程 MCP 工具参数同名同义：get_homework、get_calendar、get_exams、get_grades、get_mail、get_status。
    时间均为北京时间；due 字段是换算好的截止（00:00 截止写作前一天 24:00），直接引用即可。
""".trimIndent()

private val prettyJson = Json { prettyPrint = true }

fun runAgentCommand(args: List<String>, provider: AgentSnapshotProvider, now: Instant): AgentOutput {
    val json = "--json" in args
    val rest = args.filter { it != "--json" }
    val command = rest.firstOrNull() ?: return AgentOutput("", AGENT_USAGE, AgentExit.USAGE)
    val (name, options) = if (command == "cache") {
        if (rest.getOrNull(1) != "read") return AgentOutput("", "cache 只支持 read 和 update。\n$AGENT_USAGE", AgentExit.USAGE)
        val target = rest.getOrNull(2) ?: return AgentOutput("", AGENT_USAGE, AgentExit.USAGE)
        target to rest.drop(3)
    } else command to rest.drop(1)
    if (name in setOf("help", "--help", "-h")) return AgentOutput(AGENT_USAGE)
    if (name !in KNOWN_COMMANDS) return AgentOutput("", "未知命令：$name\n$AGENT_USAGE", AgentExit.USAGE)

    // 参数错误必须在碰缓存（和系统安全存储）之前就报出来。
    val query = try {
        if (name == "citel") {
            if (options.isNotEmpty()) throw AgentQueryException("bad_request", "citel 列表不接受选项")
            null
        } else parseAgentQuery(if (name == "courses") AgentQueryCommand.CALENDAR else name, cliArguments(name, options))
    } catch (error: AgentQueryException) {
        return AgentOutput("", "${error.message}\n$AGENT_USAGE", AgentExit.USAGE)
    }
    val today = now.toLocalDateTime(AGENT_TIME_ZONE).date
    val ready = when (val result = provider.load(today)) {
        is AgentSnapshotResult.Unavailable -> return AgentOutput(
            if (json) render(agentQueryError(null, name, now.epochSeconds, "local_cache", "no_data", result.reason)) else "",
            result.reason, AgentExit.NO_DATA,
        )
        is AgentSnapshotResult.Ready -> result
    }
    val snapshot = ready.snapshot
    if (query == null) {
        val data = citelJson(snapshot)
        return AgentOutput(if (json) render(buildJsonObject {
            put("schemaVersion", AGENT_SCHEMA_VERSION); put("command", "citel"); put("origin", "local_cache"); put("data", data)
        }) else citelMarkdown(snapshot))
    }
    val result = localAgentQuery(snapshot, query, now, ready.gradesAllowed).let { result ->
        if (query.command != AgentQueryCommand.STATUS) result
        else JsonObject(result + mapOf("appVersion" to JsonPrimitive(ready.appVersion), "account" to JsonPrimitive(maskStudentId(snapshot.studentId))))
    }
    val error = result["error"] as? JsonObject
    val code = (error?.get("code") as? JsonPrimitive)?.content
    val exit = when (code) {
        null -> AgentExit.OK
        "not_allowed" -> AgentExit.NOT_ALLOWED
        "bad_request" -> AgentExit.USAGE
        else -> AgentExit.NO_DATA
    }
    val message = (error?.get("message") as? JsonPrimitive)?.content.orEmpty()
    return AgentOutput(if (json) render(result) else if (error == null) agentResultMarkdown(result) else "",
        if (error != null) message else "", exit)
}

/** 用本机缓存执行统一查询：数据集只展开查询需要的课表日期。 */
fun localAgentQuery(snapshot: AgentSnapshot, query: AgentQuery, now: Instant, gradesAllowed: Boolean, origin: String = "local_cache"): JsonObject {
    val weeks = snapshot.academicWeeks.map { AgentWeek(it.week, it.startDate.toString()) }
    val (from, to) = agentScheduleWindow(query, weeks, now.epochSeconds)
    val dataset = buildAgentDataset(snapshot, AgentDatasetPart.entries.toSet(), from, to, now, gradesAllowed)
    val sources = snapshot.syncedAt.mapValues { AgentSourceState(fetchedAt = it.value) }
    return runAgentQuery(dataset, sources, query, now.epochSeconds, origin)
}

/** citel show/export 等桌面子命令的参数错误。 */
class AgentUsageException(message: String) : IllegalArgumentException(message)

private val KNOWN_COMMANDS = setOf("status", "calendar", "courses", "homework", "exams", "grades", "mail", "citel", "help", "--help", "-h")

private fun render(result: JsonObject): String = prettyJson.encodeToString(JsonObject.serializer(), result) + "\n"

// region CLI 选项 → 统一查询参数

/**
 * 把 `--名字 值` 转成与 MCP 工具相同的 JSON 参数，再交给 [parseAgentQuery] 统一校验。
 * 旧选项保留为简写：--today/--tomorrow/--week [N]/--next-week、--source、--kind、--pending、--all。
 */
internal fun cliArguments(command: String, options: List<String>): JsonObject {
    val values = linkedMapOf<String, JsonElement>()
    val lists = linkedMapOf<String, MutableList<String>>()
    var index = 0
    fun value(name: String): String {
        val next = options.getOrNull(index + 1)?.takeIf { !it.startsWith("--") }
            ?: throw AgentQueryException("bad_request", "$name 缺少参数")
        index += 1
        return next
    }
    fun set(key: String, element: JsonElement) {
        if (key in values) throw AgentQueryException("bad_request", "--$key 重复")
        values[key] = element
    }
    fun addList(key: String, raw: String, alias: (String) -> String = { it }) {
        lists.getOrPut(key) { mutableListOf() } += raw.split(',', '，').map(String::trim).filter(String::isNotEmpty).map(alias)
    }
    while (index < options.size) {
        when (val option = options[index]) {
            "--today" -> set("range", JsonPrimitive("today"))
            "--tomorrow" -> set("range", JsonPrimitive("tomorrow"))
            "--next-week" -> set("range", JsonPrimitive("next_week"))
            "--week" -> {
                val next = options.getOrNull(index + 1)?.takeIf { !it.startsWith("--") }
                if (next == null) set("range", JsonPrimitive("this_week")) else {
                    index += 1
                    set("week", JsonPrimitive(next.toIntOrNull() ?: throw AgentQueryException("bad_request", "--week 后应为周次数字")))
                }
            }
            "--range" -> set("range", JsonPrimitive(value(option).replace('-', '_')))
            "--date", "--from", "--to", "--course", "--status", "--keyword", "--term" -> set(option.drop(2), JsonPrimitive(value(option)))
            "--limit" -> set("limit", JsonPrimitive(value(option).toIntOrNull() ?: throw AgentQueryException("bad_request", "--limit 应为数字")))
            "--sources", "--source" -> addList("sources", value(option)) { source ->
                when (source.lowercase()) {
                    "course", "platform", "course_platform" -> "course_platform"
                    "physics", "phyvlab" -> "phyvlab"
                    else -> source.lowercase()
                }
            }
            "--kinds", "--kind" -> addList("kinds", value(option)) { it.lowercase() }
            "--pending" -> set("status", JsonPrimitive("pending"))
            "--include-unbooked", "--all" -> set("includeUnbooked", JsonPrimitive(true))
            "--detail" -> set("detail", JsonPrimitive(true))
            "--unread" -> set("unread", JsonPrimitive(true))
            else -> throw AgentQueryException("bad_request", "未知选项：$option")
        }
        index += 1
    }
    lists.forEach { (key, items) -> set(key, JsonArray(items.distinct().map(::JsonPrimitive))) }
    if (command == "courses") {
        if ("kinds" in values) throw AgentQueryException("bad_request", "courses 固定为课程与实验，不接受 --kinds")
        values["kinds"] = JsonArray(listOf(JsonPrimitive("class"), JsonPrimitive("lab")))
        if (listOf("range", "date", "from", "to", "week").none { it in values }) values["range"] = JsonPrimitive("this_week")
    }
    return JsonObject(values)
}

// endregion

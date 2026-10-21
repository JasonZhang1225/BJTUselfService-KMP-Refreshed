package team.bjtuss.bjtuselfservice.shared.agent

import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.feature.citel.completed

// 输出格式：JSON 给 agent 解析，Markdown 给人看。只输出业务数据，不输出凭据、Cookie 或内部链接参数。

internal fun isoTime(epochSeconds: Long): String {
    val t = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(AGENT_TIME_ZONE)
    return "${t.date}T${t.hour.pad()}:${t.minute.pad()}:00+08:00"
}

private fun Int.pad(): String = toString().padStart(2, '0')

private fun JsonObjectBuilder.putIfNotNull(key: String, value: String?) {
    if (value != null) put(key, value)
}

/** 学号只露首尾，足够区分账号又不完整暴露。 */
internal fun maskStudentId(id: String): String =
    if (id.length <= 4) "*".repeat(id.length) else id.take(2) + "*".repeat(id.length - 4) + id.takeLast(2)

// region citel

internal fun citelJson(snapshot: AgentSnapshot): JsonElement = buildJsonObject {
    put("enabled", snapshot.citelEnabled)
    putIfNotNull("lastSync", snapshot.citelLastSync)
    put("tasks", buildJsonArray {
        snapshot.citelTasks.sortedWith(compareBy({ it.dueTime ?: Long.MAX_VALUE }, { it.title })).forEach { task ->
            add(buildJsonObject {
                put("id", task.id)
                put("courseName", task.courseName)
                put("title", task.title)
                put("programming", task.programming)
                put("submitted", task.completed)
                putIfNotNull("status", task.status.ifBlank { null })
                putIfNotNull("grade", task.grade)
                task.openTime?.let { put("open", agentTimePoint(it, deadline = false).text) }
                task.discountTime?.let { put("discountFrom", agentTimePoint(it, deadline = false).text) }
                put("due", task.dueTime?.let { agentTimePoint(it, deadline = true).text } ?: "无截止时间")
                task.discount?.let { put("discount", it) }
                task.allowLate?.let { put("allowLate", it) }
                put("url", task.url)
            })
        }
    })
}

internal fun citelMarkdown(snapshot: AgentSnapshot): String = buildString {
    if (!snapshot.citelEnabled) {
        appendLine("CITEL 未在 App 中启用。")
        return@buildString
    }
    appendLine("# CITEL 任务（${snapshot.citelTasks.size} 个）")
    snapshot.citelTasks.sortedWith(compareBy({ it.dueTime ?: Long.MAX_VALUE }, { it.title })).forEach { task ->
        val due = task.dueTime?.let { "截止 ${agentTimePoint(it, deadline = true).text}" } ?: "无截止时间"
        appendLine("- [${task.id}] ${task.courseName} · ${task.title}（${if (task.programming) "编程" else "书面"}，$due，${if (task.completed) "已提交" else "未提交"}）")
    }
}

// endregion

// region Markdown：把统一查询结果渲染成给人看的文字（JSON 才是给 agent 的）

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray)?.map { it.jsonObject }.orEmpty()

internal fun agentResultMarkdown(result: JsonObject): String = buildString {
    result["error"]?.jsonObject?.let { error ->
        appendLine(error.text("message").orEmpty())
        return@buildString
    }
    val freshness = result["freshness"]?.jsonObject
    val stale = freshness?.array("sources").orEmpty().filter { row ->
        freshness?.get("stale")?.jsonArray?.any { it.jsonPrimitive.content == row.text("source") } == true
    }
    if (stale.isNotEmpty()) {
        appendLine("> 缓存可能不是最新：" + stale.joinToString("、") { "${it.text("label")}（${it.text("age")}）" } + "。可先刷新后再查看。")
        appendLine()
    }
    val summary = result["summary"]?.jsonObject ?: JsonObject(emptyMap())
    when (result.text("command")) {
        AgentQueryCommand.HOMEWORK -> {
            appendLine("# 作业（${summary.text("shown")} 项）")
            appendLine()
            appendLine(summary.array("bySource").joinToString("；") { row ->
                if (row.text("enabled") == "false") "${row.text("label")}：未启用"
                else "${row.text("label")}：未交 ${row.text("pending")}、已截止 ${row.text("overdue")}、已交 ${row.text("submitted")}"
            })
            result.array("items").forEach { item ->
                appendLine()
                appendLine("- **${item.text("title")}**（${item.text("sourceLabel")} · ${item.text("course")}）")
                appendLine("  截止 ${item.text("due")} · ${item.text("statusLabel")}" + (item.text("remaining")?.let { " · $it" } ?: ""))
                item.text("judgeStatus")?.let { appendLine("  判题：$it") }
            }
        }
        AgentQueryCommand.CALENDAR -> {
            val query = result["query"]?.jsonObject
            appendLine("# 日程 ${query?.text("from")} ~ ${query?.text("to")}")
            result.array("days").forEach { day ->
                appendLine()
                appendLine("## ${day.text("date")} ${day.text("weekday")}" + (day.text("week")?.let { "（第 $it 周）" } ?: ""))
                val items = day.array("items")
                if (items.isEmpty()) appendLine("（没有安排）")
                items.forEach { item ->
                    val detail = listOfNotNull(item.text("course"), item.text("location"), item.text("teacher"), item.text("statusLabel"))
                        .joinToString(" · ")
                    appendLine("- ${item.text("time")} [${item.text("kindLabel")}] ${item.text("title")}" + if (detail.isNotEmpty()) " — $detail" else "")
                }
            }
        }
        AgentQueryCommand.EXAMS -> {
            appendLine("# 考试（${summary.text("shown")} 项）")
            result.array("items").forEach { item ->
                val time = item.text("date")?.let { "$it（${item.text("weekday")}）${item.text("time").orEmpty()}" } ?: item.text("raw")
                appendLine("- ${item.text("course")}（${item.text("type")}）：$time" + (item.text("location")?.let { " · $it" } ?: "") + " · ${item.text("status")}")
            }
        }
        AgentQueryCommand.GRADES -> {
            appendLine("# 成绩（${summary.text("shown")} 门）")
            result.array("items").forEach { appendLine("- ${it.text("year")} ${it.text("semester")} ${it.text("course")}：${it.text("score")}（${it.text("credits")} 学分）") }
        }
        AgentQueryCommand.MAIL -> {
            appendLine("# 收件箱（显示 ${summary.text("shown")} 封，未读 ${summary.text("unread")} 封）")
            result.array("items").forEach { item ->
                appendLine("- ${if (item.text("unread") == "true") "● " else ""}${item.text("subject")} — ${item.text("from")} · ${item.text("received") ?: "时间未知"}")
            }
        }
        else -> {
            appendLine("# 交大自由行 · 缓存状态")
            result.text("appVersion")?.let { appendLine("- 版本：$it") }
            result.text("account")?.let { appendLine("- 账号：$it") }
            result.text("currentWeek")?.let { appendLine("- 当前教学周：$it") }
            freshness?.array("sources")?.forEach { row ->
                appendLine("- ${row.text("label")}：${row.text("syncedAt")?.let { "$it 同步（${row.text("age")}）" } ?: "同步时间未知"}")
            }
        }
    }
    result["notes"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { notes ->
        appendLine()
        notes.forEach { appendLine("> ${it.jsonPrimitive.content}") }
    }
}

// endregion

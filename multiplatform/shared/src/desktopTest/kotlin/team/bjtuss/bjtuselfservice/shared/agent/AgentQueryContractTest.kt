package team.bjtuss.bjtuselfservice.shared.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTask

/**
 * 与 Site 模板共用 fixtures/agent-query：Kotlin 引擎（本机 CLI、电脑即时查询）与 JavaScript 引擎（Site MCP 工具）
 * 对同一份数据集和查询必须给出完全相同的 JSON。期望输出由 `node scripts/update-agent-query-fixture.mjs` 生成并人工审阅。
 */
class AgentQueryContractTest {
    private val dir = File("../remote-site-template/fixtures/agent-query")
    private fun read(name: String): JsonElement = Json.parseToJsonElement(File(dir, name).readText())

    @Test
    fun kotlinEngineMatchesTheSharedFixture() {
        val fixture = read("dataset.json").jsonObject
        val dataset = agentDatasetFromJson(fixture.getValue("data").jsonObject)
        val sources = agentDatasetJson.decodeFromJsonElement(MapSerializer(String.serializer(), AgentSourceState.serializer()), fixture.getValue("sources"))
        val now = fixture.getValue("now").jsonPrimitive.long
        val queries = read("queries.json").jsonArray.map { it.jsonObject }
        val expected = read("expected.json").jsonArray.map { it.jsonObject }
        assertEquals(queries.size, expected.size)
        queries.zip(expected).forEach { (q, want) ->
            val name = q.getValue("name").jsonPrimitive.content
            val actual: JsonObject = try {
                val query = parseAgentQuery(q.getValue("command").jsonPrimitive.content, q.getValue("args").jsonObject)
                buildJsonObject { put("name", name); put("result", runAgentQuery(dataset, sources, query, now, "cloud_snapshot")) }
            } catch (error: AgentQueryException) {
                buildJsonObject {
                    put("name", name)
                    put("parseError", buildJsonObject { put("code", error.code); put("message", error.message) })
                }
            }
            // 数字统一按 JSON 文本比较：两端都只输出整数。
            assertEquals(Json.parseToJsonElement(want.toString()), Json.parseToJsonElement(actual.toString()), name)
        }
    }

    @Test
    fun datasetMatchesFixtureShape() {
        // 数据集经 Kotlin 序列化往返不丢字段（Site 读取的就是这个 JSON）。
        val data = read("dataset.json").jsonObject.getValue("data").jsonObject
        assertEquals(Json.parseToJsonElement(data.toString()), agentDatasetFromJson(data).toJson())
    }

    @Test
    fun midnightDeadlineIsPreviousDay24AndOtherTimesStayActual() {
        val due = agentTimePoint(Instant.parse("2026-10-25T16:00:00Z").epochSeconds, deadline = true)
        assertEquals(AgentTimePoint("2026-10-25", "24:00", "2026-10-25（周日）24:00", 1792944000), due)
        val open = agentTimePoint(Instant.parse("2026-10-25T16:00:00Z").epochSeconds, deadline = false)
        assertEquals("2026-10-26（周一）00:00", open.text)
        assertEquals("2026-10-23（周五）23:59", agentTimePoint(1792771140, deadline = true).text)
    }

    @Test
    fun citelListUsesTheSameDeadlineText() {
        val task = CitelTask(9265, 1, "数据结构", "实验 3", "https://citel.example/9265", programming = true, dueTime = 1792944000)
        val text = citelJson(snapshotWith(task)).toString()
        assertTrue("2026-10-25（周日）24:00" in text)
        assertTrue("2026-10-26" !in text)
    }

    private fun snapshotWith(task: CitelTask) = AgentSnapshot(
        studentId = "20990001", profileName = null,
        preferences = team.bjtuss.bjtuselfservice.shared.cache.AppPreferences(citelEnabled = true),
        courses = emptyList(), currentWeek = 0, academicWeeks = emptyList(), academicWeeksInferred = true,
        exams = emptyList(), grades = emptyList(), homework = emptyList(), phyVlab = null, citelTasks = listOf(task),
        citelLastSync = null, physicsLabs = null, physicsDemos = null,
        assignmentFilters = team.bjtuss.bjtuselfservice.shared.feature.assignment.AggregateAssignmentFilters(),
    )
}

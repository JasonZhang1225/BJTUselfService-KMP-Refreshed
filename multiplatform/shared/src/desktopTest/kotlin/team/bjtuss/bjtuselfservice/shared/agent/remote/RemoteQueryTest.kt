package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlin.test.*
import kotlinx.serialization.json.*
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openDesktopCacheStore

class RemoteQueryTest {
    private fun args(text: String) = remoteJson.parseToJsonElement(text).jsonObject

    @Test fun explicitSourcesAndPermissionAreRequired() {
        assertFails { parseRemoteQuery(args("{}"), RemoteScope.DEFAULT) }
        assertFails { parseRemoteQuery(args("""{"sources":["grades"],"freshness":"latest"}"""), RemoteScope.DEFAULT) }
        assertFails { parseRemoteQuery(args("""{"sources":["courses"],"url":"https://example.com"}"""), RemoteScope.DEFAULT) }
        assertFails { parseRemoteQuery(args("""{"sources":["courses"],"date":"2026-02-30"}"""), RemoteScope.DEFAULT) }
        val query = parseRemoteQuery(args("""{"sources":["citel"],"taskId":"9265","freshness":"latest"}"""), RemoteScope.DEFAULT)
        assertEquals(setOf(RefreshSource.CITEL), query.sources)
        assertTrue(query.latest)
        assertEquals("9265", query.taskId)
    }

    @Test fun queryPreservesMidnightAgendaAndExcludesOtherSourcesAndScopes() {
        val data = args("""{"assignments":{"homework":{"notes":["午夜归前日"],"items":[
          {"source":"citel","courseName":"示例课程","dueTime":"2026-10-23T00:00:00+08:00","dueAgendaDate":"2026-10-22","midnightDeadline":true},
          {"source":"course","courseName":"示例课程","dueAgendaDate":"2026-10-22"}
        ]}},"grades":{"grades":[{"courseName":"示例课程","score":"90"}]}}""")
        val query = parseRemoteQuery(args("""{"sources":["citel"],"course":"示例","date":"2026-10-22"}"""), RemoteScope.DEFAULT)
        val result = projectRemoteQuery(data, query)
        assertFalse("grades" in result)
        val items = result.getValue("assignments").jsonObject.getValue("homework").jsonObject.getValue("items").jsonArray
        assertEquals(1, items.size)
        assertTrue(items.single().jsonObject.getValue("midnightDeadline").jsonPrimitive.boolean)
    }

    @Test fun queryTaskIsDeduplicatedAndStoredResultCannotBypassRevokedScope() = runBlocking {
        val dir = Files.createTempDirectory("remote-query-test").toFile()
        val cache = openDesktopCacheStore(dir, JvmAesCacheValueProtector(ByteArray(32) { 7 })).store
        try {
            val base = FakeExecutor()
            var queries = 0
            val executor = object : RemoteRefreshExecutor by base {
                override suspend fun query(task: RemoteTask, scopes: Set<RemoteScope>): RemoteQueryOutcome {
                    queries++
                    return RemoteQueryOutcome(RemoteTaskStatus.SUCCEEDED, args("""{"data":{"grades":[]}}"""))
                }
            }
            val config = MutableStateFlow(RemoteAgentConfig(enabled = true, siteUrl = "https://example.test",
                deviceId = "dev_queryTest01", accountBindingId = "bind_queryTest01", accountScope = "20990001",
                scopes = listOf("grades", "schedule"), consentAtEpochSeconds = 1))
            val client = FakeSiteClient()
            val runner = RemoteAgentRunner(config, { client }, executor, RemoteAgentStore(cache), { 100L })
            val task = RemoteTask("req_queryTest01", "dev_queryTest01", "bind_queryTest01", "query",
                args("""{"sources":["grades"],"freshness":"local"}"""), 1, 500, "running", 1, "lease_queryTest01", 220)
            runner.handleTask(client, task)
            runner.handleTask(client, task.copy(leaseId = "lease_queryTest02"))
            assertEquals(1, queries)
            assertEquals(0, base.refreshes.get(), "local 查询不得触发全量刷新")
            assertEquals(RemoteTaskStatus.SUCCEEDED, client.results.last().second.status)
            config.value = config.value.copy(scopes = listOf("schedule"))
            runner.handleTask(client, task.copy(leaseId = "lease_queryTest03"))
            assertEquals(1, queries)
            assertEquals("scope_denied", client.results.last().second.error?.code)
            assertTrue(client.results.last().second.result.isEmpty())
        } finally { cache.close(); dir.deleteRecursively() }
    }
}

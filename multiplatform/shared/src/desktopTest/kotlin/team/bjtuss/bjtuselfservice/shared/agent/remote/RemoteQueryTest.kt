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

    @Test fun liveQueryUsesTheSameParametersAsTheQueryTools() {
        assertFailsWith<IllegalArgumentException> { parseRemoteLiveQuery(args("{}"), RemoteScope.DEFAULT) }
        assertEquals("scope_denied", assertFailsWith<IllegalArgumentException> {
            parseRemoteLiveQuery(args("""{"command":"grades","freshness":"latest"}"""), RemoteScope.DEFAULT)
        }.message)
        assertEquals("bad_request", assertFailsWith<IllegalArgumentException> {
            parseRemoteLiveQuery(args("""{"command":"homework","url":"https://example.com"}"""), RemoteScope.DEFAULT)
        }.message)
        assertFailsWith<IllegalArgumentException> { parseRemoteLiveQuery(args("""{"command":"calendar","date":"2026-02-30"}"""), RemoteScope.DEFAULT) }
        assertFailsWith<IllegalArgumentException> { parseRemoteLiveQuery(args("""{"command":"homework","freshness":"soon"}"""), RemoteScope.DEFAULT) }
        val live = parseRemoteLiveQuery(args("""{"command":"homework","sources":["citel"],"status":"all","freshness":"latest"}"""), RemoteScope.DEFAULT)
        assertTrue(live.latest)
        assertEquals("homework", live.query.command)
        assertEquals(listOf("citel"), live.query.sources)
        assertEquals("all", live.query.status)
    }

    @Test fun calendarNeedsAnyScheduleLikeScopeAndRefreshesOnlyAllowedSources() {
        val onlyExams = setOf(RemoteScope.EXAMS)
        assertEquals("calendar", parseRemoteLiveQuery(args("""{"command":"calendar"}"""), onlyExams).query.command)
        assertEquals(setOf(RefreshSource.EXAMS), remoteRefreshSources("calendar", onlyExams))
        assertEquals(setOf(RefreshSource.HOMEWORK, RefreshSource.PHYVLAB, RefreshSource.CITEL, RefreshSource.PHYSICS_LAB),
            remoteRefreshSources("homework", RemoteScope.DEFAULT))
        assertFalse(remoteTaskAllowed(RemoteOperation.MAIL_MESSAGE, args("""{"messageId":"1:abc"}"""), RemoteScope.DEFAULT))
        assertTrue(remoteTaskAllowed(RemoteOperation.MAIL_MESSAGE, args("""{"messageId":"1:abc"}"""), RemoteScope.DEFAULT + RemoteScope.MAIL))
        assertFalse(remoteTaskAllowed(RemoteOperation.MAIL_MESSAGE, args("""{"messageId":"../x y"}"""), RemoteScope.DEFAULT + RemoteScope.MAIL))
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
                args("""{"command":"grades","freshness":"local"}"""), 1, 500, "running", 1, "lease_queryTest01", 220)
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

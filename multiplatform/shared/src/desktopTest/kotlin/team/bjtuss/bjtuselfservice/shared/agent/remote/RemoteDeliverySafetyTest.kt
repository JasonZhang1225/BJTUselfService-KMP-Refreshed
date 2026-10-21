package team.bjtuss.bjtuselfservice.shared.agent.remote

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.agent.loadAgentSnapshot
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTask
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openDesktopCacheStore

class RemoteDeliverySafetyTest {
    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("remote-delivery-test").toFile()
        val cache = openDesktopCacheStore(directory, JvmAesCacheValueProtector(ByteArray(32) { 9 })).store
        val store = RemoteAgentStore(cache)
        val site = FakeSiteClient()
        val base = FakeExecutor()
        val config = MutableStateFlow(RemoteAgentConfig(enabled = true, siteUrl = "https://example.test",
            deviceId = "dev_delivery01", accountBindingId = "bind_delivery01", accountScope = "20990001", consentAtEpochSeconds = 1))
        fun runner(executor: RemoteRefreshExecutor = base) = RemoteAgentRunner(config, { site }, executor, store, { 100L })
        override fun close() { cache.close(); directory.deleteRecursively() }
    }

    @Test fun changingScopesOrAccountDuringRefreshDiscardsTheUpload() = runBlocking {
        for (change in listOf("scopes", "account", "disabled")) {
            Fixture().use { f ->
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val executor = object : RemoteRefreshExecutor by f.base {
                    override suspend fun refreshAll(remote: Boolean) = f.base.refreshAll(remote).also { entered.complete(Unit); release.await() }
                }
                val upload = async { f.runner(executor).runUploadCycle(refreshFirst = true, remote = false) }
                entered.await()
                when (change) {
                    "scopes" -> f.config.value = f.config.value.copy(scopes = listOf("schedule"), scopesRevision = 2)
                    "account" -> f.base.account = "20990002"
                    else -> f.config.value = f.config.value.copy(enabled = false)
                }
                release.complete(Unit)
                assertEquals("configuration_changed", assertIs<RemoteUploadOutcome.Skipped>(upload.await()).reason)
                assertTrue(f.site.snapshots.isEmpty(), change)
                assertEquals(0, f.base.contentReads.get(), "账号/范围变化后不能生成错绑的快照")
            }
        }
    }

    @Test fun accountChangeWhileReadingSnapshotIsCheckedAgainBeforeNetwork() = runBlocking {
        Fixture().use { f ->
            val executor = object : RemoteRefreshExecutor by f.base {
                override suspend fun snapshotContent(scopes: Set<RemoteScope>, lastStates: Map<String, RemoteSourceState>): RemoteSnapshotContent {
                    f.base.account = "20990002"
                    return RemoteSnapshotContent(emptyMap(), JsonObject(emptyMap()))
                }
            }
            assertIs<RemoteUploadOutcome.Skipped>(f.runner(executor).runUploadCycle(refreshFirst = false, remote = false))
            assertTrue(f.site.snapshots.isEmpty())
        }
    }

    @Test fun platformServiceAccessUsesBearerSchemeWithoutDoublePrefix() {
        assertEquals("Bearer fictional-token", remotePlatformAuthorization("fictional-token"))
        assertEquals("Bearer fictional-token", remotePlatformAuthorization("Bearer fictional-token"))
        assertEquals("Bearer fictional-token", remotePlatformAuthorization("bearer fictional-token"))
        assertFails { remotePlatformAuthorization("fictional\r\nInjected: value") }
    }

    @Test fun overlappingRedeliveryRunsOneQueryAndReturnsEachLease() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var count = 0
            val executor = object : RemoteRefreshExecutor by f.base {
                override suspend fun query(task: RemoteTask, scopes: Set<RemoteScope>): RemoteQueryOutcome {
                    count++
                    entered.complete(Unit)
                    release.await()
                    return RemoteQueryOutcome(RemoteTaskStatus.SUCCEEDED, JsonObject(emptyMap()))
                }
            }
            val runner = f.runner(executor)
            val args = remoteJson.parseToJsonElement("""{"sources":["courses"],"freshness":"latest"}""") as JsonObject
            val task = RemoteTask("req_overlap001", "dev_delivery01", "bind_delivery01", "query", args, 1, 500,
                "running", 1, "lease_overlap01", 220)
            val first = async { runner.handleTask(f.site, task) }
            entered.await()
            val second = async { runner.handleTask(f.site, task.copy(leaseId = "lease_overlap02")) }
            yield()
            release.complete(Unit)
            first.await(); second.await()
            assertEquals(1, count, "重复投递在原任务未完成时也不能重复访问学校")
            assertEquals(setOf("lease_overlap01", "lease_overlap02"), f.site.results.map { it.second.leaseId }.toSet())
        }
    }

    @Test fun largeUtf8ResultReturnsFailureInsteadOfLeavingTheTaskPending() = runBlocking {
        Fixture().use { f ->
            val executor = object : RemoteRefreshExecutor by f.base {
                override suspend fun query(task: RemoteTask, scopes: Set<RemoteScope>) = RemoteQueryOutcome(
                    RemoteTaskStatus.SUCCEEDED, buildJsonObject { put("text", "测".repeat(90_000)) })
            }
            val args = remoteJson.parseToJsonElement("""{"sources":["courses"],"freshness":"local"}""") as JsonObject
            val task = RemoteTask("req_largeText01", "dev_delivery01", "bind_delivery01", "query", args, 1, 500,
                "running", 1, "lease_largeText01", 220)
            f.runner(executor).handleTask(f.site, task)
            val result = f.site.results.single().second
            assertEquals(RemoteTaskStatus.FAILED, result.status)
            assertEquals("result_too_large", result.error?.code)
            assertTrue(result.result.isEmpty())
            assertTrue(f.store.loadResults().single().result.isEmpty(), "本地任务回执也不保存超大副本")
        }
    }

    @Test fun scheduleScopeDoesNotExposeAssignmentCourseNamesInFilterMetadata() {
        Fixture().use { f ->
            val snapshot = loadAgentSnapshot(f.cache, "20990001", LocalDate.parse("2026-10-21")).copy(
                citelTasks = listOf(CitelTask(9265, 1, "未授权课程", "示例题", "https://citel.bjtu.edu.cn/mlsv3/", true)))
            for (scopes in listOf(setOf(RemoteScope.SCHEDULE), RemoteScope.DEFAULT)) {
                val remote = buildRemoteSnapshotContent(snapshot, scopes, emptyMap(), { null }, Instant.parse("2026-10-21T00:00:00Z"))
                assertFalse(remote.data.getValue("schedule").toString().contains("未授权课程"), "每个范围的筛选元数据必须独立")
            }
        }
    }
}

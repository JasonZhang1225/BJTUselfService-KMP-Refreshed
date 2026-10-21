package team.bjtuss.bjtuselfservice.shared.agent.remote

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openDesktopCacheStore
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSourceResult
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

internal class FakeSiteClient : RemoteSiteClient {
    val snapshots = CopyOnWriteArrayList<RemoteSnapshot>()
    val results = CopyOnWriteArrayList<Pair<String, RemoteTaskResult>>()
    val polls = AtomicInteger()
    val scopeUpdates = CopyOnWriteArrayList<List<String>>()
    var snapshotFailures = ArrayDeque<RemoteCall.Failure>()
    var pollResponses = ArrayDeque<RemoteCall<RemotePollResponse>>()
    var pairResponse: RemoteCall<RemotePairResponse> =
        RemoteCall.Ok(RemotePairResponse(1, "dev_testDevice01", "bjtud_testtokentesttokentesttoken", "bind_x", listOf("schedule")))
    var lastPair: RemotePairRequest? = null

    override suspend fun pair(request: RemotePairRequest): RemoteCall<RemotePairResponse> {
        lastPair = request
        return pairResponse
    }

    override suspend fun putSnapshot(snapshot: RemoteSnapshot): RemoteCall<RemoteSnapshotAck> {
        snapshotFailures.removeFirstOrNull()?.let { return it }
        snapshots += snapshot
        return RemoteCall.Ok(RemoteSnapshotAck(snapshot.snapshotId, snapshot.sequence, stored = true, duplicate = false))
    }

    override suspend fun putScopes(scopes: List<String>): RemoteCall<Unit> {
        scopeUpdates += scopes
        return RemoteCall.Ok(Unit)
    }

    override suspend fun poll(): RemoteCall<RemotePollResponse> {
        polls.incrementAndGet()
        return pollResponses.removeFirstOrNull() ?: RemoteCall.Ok(RemotePollResponse(1, 0, 0, null))
    }

    override suspend fun renewLease(requestId: String, leaseId: String, leaseSeconds: Int): RemoteCall<Unit> = RemoteCall.Ok(Unit)

    override suspend fun submitResult(requestId: String, result: RemoteTaskResult): RemoteCall<RemoteTaskResultAck> {
        results += requestId to result
        return RemoteCall.Ok(RemoteTaskResultAck(requestId, accepted = true, duplicate = false))
    }
}

internal class FakeExecutor(var account: String? = "20990001") : RemoteRefreshExecutor {
    val refreshes = AtomicInteger()
    /** 非 null 时刷新挂起在这里，用来制造“刷新进行中又来一次轮询”的交错。 */
    @Volatile var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val contentReads = AtomicInteger()
    val origins = CopyOnWriteArrayList<Boolean>()
    var reportStatus = RefreshStatus.SUCCESS

    override fun currentAccountScope(): String? = account

    override suspend fun refreshAll(remote: Boolean): RefreshReport? {
        if (account == null) return null
        refreshes.incrementAndGet()
        gate?.await()
        origins += remote
        return RefreshReport(account!!, if (remote) RefreshOrigin.REMOTE else RefreshOrigin.SCHEDULED, 10, 20, listOf(
            RefreshSourceResult(RefreshSource.COURSES, RefreshStatus.SUCCESS, fetchedAtEpochSeconds = 20),
            RefreshSourceResult(RefreshSource.HOMEWORK, reportStatus, if (reportStatus == RefreshStatus.SUCCESS) null else "network", fetchedAtEpochSeconds = 5),
        ))
    }

    override suspend fun snapshotContent(scopes: Set<RemoteScope>, lastStates: Map<String, RemoteSourceState>): RemoteSnapshotContent {
        contentReads.incrementAndGet()
        return RemoteSnapshotContent(lastStates, JsonObject(scopes.associate { it.wire to JsonObject(emptyMap()) }))
    }
}

class RemoteAgentRunnerTest {
    private val directory: File = Files.createTempDirectory("remote-runner").toFile()
    private val cache: CacheStore = openDesktopCacheStore(directory, JvmAesCacheValueProtector(ByteArray(32) { it.toByte() })).store
    private val store = RemoteAgentStore(cache)
    private val client = FakeSiteClient()
    private val executor = FakeExecutor()
    private var now = 1_800_000_000L
    private val config = MutableStateFlow(
        RemoteAgentConfig(enabled = true, siteUrl = "https://site.example", deviceId = "dev_testDevice01",
            accountBindingId = "bind_testBinding01", accountScope = "20990001", consentAtEpochSeconds = 1),
    )
    private val runner = RemoteAgentRunner(config, { client }, executor, store, { now }, Random(7), secondMillis = 10)

    @AfterTest
    fun cleanUp() {
        cache.close()
        directory.deleteRecursively()
    }

    @Test
    fun emptyPollNeverTouchesSchoolOrCache() = runBlocking {
        repeat(5) { assertNull(runner.pollOnce(this)) }
        assertEquals(5, client.polls.get())
        assertEquals(0, executor.refreshes.get())
        assertEquals(0, executor.contentReads.get())
    }

    @Test
    fun uploadRefreshesFirstAndSequenceIsMonotonic() = runBlocking {
        val first = assertIs<RemoteUploadOutcome.Uploaded>(runner.runUploadCycle(refreshFirst = true, remote = false))
        now += 1
        val second = assertIs<RemoteUploadOutcome.Uploaded>(runner.runUploadCycle(refreshFirst = true, remote = false))
        assertTrue(second.sequence > first.sequence)
        assertEquals(2, executor.refreshes.get())
        assertEquals(listOf(false, false), executor.origins)
        val snapshot = client.snapshots.last()
        assertEquals("bind_testBinding01", snapshot.accountBindingId)
        assertEquals("success", snapshot.sources["courses"]?.status)
        assertEquals(5, snapshot.sources["homework"]?.fetchedAt)
        assertTrue(snapshot.data.keys.all { it in snapshot.scopes })
        assertNull(store.loadPending())
    }

    @Test
    fun failedUploadRetransmitsSameSnapshotWithoutRefreshingAgain() = runBlocking {
        client.snapshotFailures.addLast(RemoteCall.Failure(RemoteFailureKind.NETWORK))
        assertIs<RemoteUploadOutcome.Failed>(runner.runUploadCycle(refreshFirst = true, remote = false))
        val pending = store.loadPending()!!
        now += 60
        assertIs<RemoteUploadOutcome.Uploaded>(runner.runUploadCycle(refreshFirst = true, remote = false))
        assertEquals(1, executor.refreshes.get(), "重传不重复登录/刷新学校")
        assertEquals(pending.snapshotId, client.snapshots.single().snapshotId)
    }

    @Test
    fun pendingOlderThanOneCycleIsRegenerated() = runBlocking {
        client.snapshotFailures.addLast(RemoteCall.Failure(RemoteFailureKind.NETWORK))
        runner.runUploadCycle(refreshFirst = true, remote = false)
        now += config.value.uploadIntervalSeconds + 1
        runner.runUploadCycle(refreshFirst = true, remote = false)
        assertEquals(2, executor.refreshes.get())
    }

    @Test
    fun staleConflictDropsPendingAndUnauthorizedStops() = runBlocking {
        client.snapshotFailures.addLast(RemoteCall.Failure(RemoteFailureKind.CONFLICT, "stale_snapshot"))
        runner.runUploadCycle(refreshFirst = true, remote = false)
        assertNull(store.loadPending())
        client.snapshotFailures.addLast(RemoteCall.Failure(RemoteFailureKind.UNAUTHORIZED, "unauthorized"))
        runner.runUploadCycle(refreshFirst = true, remote = false)
        assertEquals(RemotePhase.NEEDS_PAIRING, runner.status.value.phase)
        assertTrue(runner.status.value.lastError!!.contains("重新配对"))
    }

    @Test
    fun concurrentPollDuringUploadKeepsEveryPersistedField() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        executor.gate = gate
        val cycle = async(Dispatchers.Default) { runner.runUploadCycle(refreshFirst = true, remote = false) }
        withTimeout(5_000) { while (executor.refreshes.get() == 0) delay(5) }
        now += 120
        assertNull(runner.pollOnce(this))
        gate.complete(Unit)
        assertIs<RemoteUploadOutcome.Uploaded>(cycle.await())
        val state = store.loadState()
        // 以前两边整份覆盖：上传会抹掉轮询写的在线时间，轮询会抹掉上传尝试时间（导致下一轮立即重刷）。
        assertEquals(now, state.lastSeenAt, "轮询写的在线时间被上传覆盖")
        assertEquals(now - 120, state.lastUploadAttemptAt, "上传尝试时间被轮询覆盖")
        assertTrue(state.lastSequence > 0)
    }

    @Test
    fun accountSwitchPausesWithoutContactingSite() = runBlocking {
        executor.account = "20990002"
        val outcome = assertIs<RemoteUploadOutcome.Skipped>(runner.runUploadCycle(refreshFirst = true, remote = false))
        assertEquals("account_mismatch", outcome.reason)
        assertEquals(0, executor.refreshes.get())
        assertTrue(client.snapshots.isEmpty())
        assertEquals(RemotePhase.ACCOUNT_MISMATCH, runner.status.value.phase)
    }

    @Test
    fun retryDelayHonorsRetryAfterAndBacksOffWithJitter() {
        assertEquals(42, runner.retryDelaySeconds(RemoteCall.Failure(RemoteFailureKind.RATE_LIMITED, retryAfterSeconds = 42), 1, 5, 300))
        val first = runner.retryDelaySeconds(RemoteCall.Failure(RemoteFailureKind.NETWORK), 1, 5, 300)
        val fourth = runner.retryDelaySeconds(RemoteCall.Failure(RemoteFailureKind.NETWORK), 4, 5, 300)
        val capped = runner.retryDelaySeconds(RemoteCall.Failure(RemoteFailureKind.NETWORK), 30, 5, 300)
        assertTrue(first in 4..6, "$first")
        assertTrue(fourth in 32..48, "$fourth")
        assertTrue(capped <= 300)
    }

    private fun task(requestId: String = "req_testTask0001", lease: String = "lease_test000001", operation: String = RemoteOperation.REFRESH_ALL,
                     binding: String = "bind_testBinding01") =
        RemoteTask(requestId, "dev_testDevice01", binding, operation, JsonObject(emptyMap()), now, now + 300, "running", 1, lease, now + 120)

    @Test
    fun refreshAllTaskRefreshesOnceUploadsAndReportsSequence() = runBlocking {
        runner.handleTask(client, task())
        assertEquals(1, executor.refreshes.get())
        assertEquals(listOf(true), executor.origins, "来源记为 remote")
        val (requestId, result) = client.results.single()
        assertEquals("req_testTask0001", requestId)
        assertEquals(RemoteTaskStatus.SUCCEEDED, result.status)
        assertEquals(client.snapshots.single().sequence, result.result["snapshotSequence"]!!.jsonPrimitive.long)
    }

    @Test
    fun partialRefreshIsReportedAsPartial() = runBlocking {
        executor.reportStatus = RefreshStatus.NEEDS_USER_ACTION
        runner.handleTask(client, task())
        assertEquals(RemoteTaskStatus.PARTIAL, client.results.single().second.status)
    }

    @Test
    fun redeliveredTaskReusesStoredResultWithNewLease() = runBlocking {
        runner.handleTask(client, task(lease = "lease_test000001"))
        runner.handleTask(client, task(lease = "lease_test000002"))
        assertEquals(1, executor.refreshes.get(), "重新投递不重复刷新")
        assertEquals(listOf("lease_test000001", "lease_test000002"), client.results.map { it.second.leaseId })
    }

    @Test
    fun unsupportedOrForeignTasksFailWithoutTouchingSchool() = runBlocking {
        runner.handleTask(client, task(requestId = "req_testTask0002", operation = RemoteOperation.QUERY))
        runner.handleTask(client, task(requestId = "req_testTask0003", operation = "shell"))
        runner.handleTask(client, task(requestId = "req_testTask0004", binding = "bind_other0000001"))
        assertEquals(0, executor.refreshes.get())
        assertEquals(listOf(RemoteErrorCode.UNSUPPORTED_OPERATION, RemoteErrorCode.UNSUPPORTED_OPERATION, RemoteErrorCode.BINDING_MISMATCH),
            client.results.map { it.second.error?.code })
    }

    @Test
    fun loopsRunIndependentlyAndIntervalChangeDoesNotDuplicateLoops() = runBlocking {
        // 上报周期取上限：慢机器上也只会在启动时到期一次，断言不依赖墙钟速度。
        config.value = config.value.copy(pollIntervalSeconds = 1, uploadIntervalSeconds = RemoteIntervals.UPLOAD_RANGE.last)
        // 用真实时钟驱动（1 秒 = 10 毫秒）；只断言“至少”与“不再增加”，不卡固定时间窗内的次数。
        val start = System.currentTimeMillis()
        val realRunner = RemoteAgentRunner(config, { client }, executor, store, { (System.currentTimeMillis() - start) / 10 + now },
            Random(1), secondMillis = 10)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        realRunner.start(scope)
        realRunner.start(scope)
        withTimeout(20_000) { while (client.polls.get() < 5 || executor.refreshes.get() < 1) delay(10) }
        assertEquals(1, executor.refreshes.get(), "启动时到期只上报一轮，空轮询不刷新：refreshes=${executor.refreshes.get()}")
        assertEquals(1, client.snapshots.size, "snapshots=${client.snapshots.size}")
        // 改成 300 秒（压缩后 3 秒）：等轮询静下来（连续 500 毫秒没有新轮询），再确认之后 1.5 秒内不再增加。
        // 若旧频率的循环还在或多开了循环，轮询会持续增加，永远静不下来（超时失败）。
        config.value = config.value.copy(pollIntervalSeconds = 300)
        withTimeout(20_000) {
            var last = client.polls.get()
            var quietSince = System.currentTimeMillis()
            while (System.currentTimeMillis() - quietSince < 500) {
                delay(20)
                val current = client.polls.get()
                if (current != last) { last = current; quietSince = System.currentTimeMillis() }
            }
        }
        val settled = client.polls.get()
        delay(1_500)
        assertEquals(settled, client.polls.get(), "改间隔后不能保留旧频率的循环：settled=$settled now=${client.polls.get()}")
        assertEquals(1, executor.refreshes.get(), "refreshes=${executor.refreshes.get()}")
        realRunner.stop()
        scope.cancel()
    }

    @Test
    fun disabledRunnerNeitherPollsNorUploads() = runBlocking {
        config.value = config.value.copy(enabled = false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runner.start(scope)
        delay(200)
        assertEquals(0, client.polls.get())
        assertEquals(0, executor.refreshes.get())
        assertEquals(RemotePhase.DISABLED, runner.status.value.phase)
        scope.cancel()
    }

    @Test
    fun configNormalizesIntervalsAndUrls() {
        val normalized = RemoteAgentConfig(uploadIntervalSeconds = 5, pollIntervalSeconds = 9999, scopes = listOf("grades", "evil", "schedule")).normalized()
        assertEquals(60, normalized.uploadIntervalSeconds)
        assertEquals(300, normalized.pollIntervalSeconds)
        assertEquals(listOf("schedule", "grades"), normalized.scopes)
        assertEquals("https://me.example.com/site", normalizeSiteUrl(" https://me.example.com/site/ "))
        assertNull(normalizeSiteUrl("http://me.example.com"))
        assertNull(normalizeSiteUrl("https://user:pw@me.example.com"))
        assertNull(normalizeSiteUrl("https://me.example.com/?x=1"))
        assertFalse(RemoteAgentConfig(enabled = true, siteUrl = "https://a.example", deviceId = "d", accountBindingId = "b", accountScope = "s").runnable,
            "没有同意记录不能运行")
    }
}

package team.bjtuss.bjtuselfservice.shared.agent.remote

import java.io.File
import java.nio.file.Files
import java.util.PriorityQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
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
    var healthResponse: RemoteCall<RemoteHealth> = RemoteCall.Ok(RemoteHealth(1, "1.8.6", "1.8.6-KMP"))

    override suspend fun health(): RemoteCall<RemoteHealth> = healthResponse

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
    fun loopsRunIndependentlyAndIntervalChangeDoesNotDuplicateLoops() {
        val dispatcher = RunnerTestDispatcher()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + dispatcher)
        val refreshGate = CompletableDeferred<Unit>()
        executor.gate = refreshGate
        config.value = config.value.copy(pollIntervalSeconds = 1, uploadIntervalSeconds = RemoteIntervals.UPLOAD_RANGE.last)
        // 时钟和 delay 共用虚拟时间，保持生产的一秒 = 1000 毫秒，不依赖 CI 的运行速度。
        val loopRunner = RemoteAgentRunner(config, { client }, executor, store,
            { now + dispatcher.currentTimeMillis / 1_000 }, Random(1))
        try {
            loopRunner.start(scope)
            loopRunner.start(scope)
            dispatcher.runCurrent()
            assertEquals(1, client.polls.get(), "重复 start 只启动一个轮询循环")
            assertEquals(1, executor.refreshes.get())

            dispatcher.advanceTimeBy(4_000)
            assertEquals(5, client.polls.get(), "刷新挂起时轮询仍独立运行")
            assertEquals(1, executor.refreshes.get())
            // 此时旧等待条件 polls >= 5 && refreshes >= 1 已成立，上传却尚未开始。
            assertTrue(client.snapshots.isEmpty())
            assertNull(loopRunner.status.value.lastUploadAt)
            refreshGate.complete(Unit)
            dispatcher.runCurrent()
            assertEquals(now + 4, loopRunner.status.value.lastUploadAt, "确认上传完整结束后再检查快照")
            assertEquals(1, client.snapshots.size)
            assertNull(store.loadPending())

            config.value = config.value.copy(pollIntervalSeconds = 300)
            dispatcher.runCurrent()
            // 配置变更会唤醒等待中的循环，允许立即轮询一次；随后必须使用新间隔。
            val settled = client.polls.get()
            assertEquals(6, settled)
            loopRunner.start(scope)
            repeat(2) { cycle ->
                dispatcher.advanceTimeBy(299_999)
                assertEquals(settled + cycle, client.polls.get(), "新间隔到期前不能保留旧频率或重复循环")
                dispatcher.advanceTimeBy(1)
                assertEquals(settled + cycle + 1, client.polls.get(), "每个新间隔恰好轮询一次")
            }
            config.value = config.value.copy(pollIntervalSeconds = 1)
            dispatcher.runCurrent()
            val resumed = client.polls.get()
            assertEquals(settled + 3, resumed)
            dispatcher.advanceTimeBy(999)
            assertEquals(resumed, client.polls.get())
            dispatcher.advanceTimeBy(1)
            assertEquals(resumed + 1, client.polls.get(), "缩短间隔也只保留一个循环")
            assertEquals(1, executor.refreshes.get(), "空轮询与间隔变更不触发学校刷新")
            assertEquals(1, client.snapshots.size)
        } finally {
            loopRunner.stop()
            scope.cancel()
            // 处理取消的 continuation，确保关闭数据库前所有循环都已退出，即使断言失败也清理。
            dispatcher.runCurrent()
            assertTrue(job.isCompleted, "测试协程必须在清理后结束")
        }
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

/** 仅供本文件的循环测试：按截止时间、入队顺序执行；时钟与协程计时都由测试推进。 */
@OptIn(InternalCoroutinesApi::class)
private class RunnerTestDispatcher : CoroutineDispatcher(), Delay {
    private class Scheduled(val at: Long, val order: Long, val block: Runnable) {
        var cancelled = false
    }

    private val queue = PriorityQueue(compareBy<Scheduled> { it.at }.thenBy { it.order })
    private var order = 0L
    var currentTimeMillis = 0L
        private set

    private fun schedule(delayMillis: Long, block: Runnable): DisposableHandle {
        val task = Scheduled(currentTimeMillis + delayMillis, order++, block)
        queue.add(task)
        return DisposableHandle { task.cancelled = true }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        schedule(0, block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val handle = schedule(timeMillis, Runnable { continuation.resume(Unit) })
        continuation.invokeOnCancellation { handle.dispose() }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
        schedule(timeMillis, block)

    fun runCurrent() {
        var steps = 0
        while (queue.peek()?.let { it.at <= currentTimeMillis } == true) {
            check(++steps <= 10_000) { "循环在同一虚拟时刻未挂起" }
            val task = queue.remove()
            if (!task.cancelled) task.block.run()
        }
    }

    fun advanceTimeBy(millis: Long) {
        require(millis >= 0)
        val target = currentTimeMillis + millis
        while (queue.peek()?.let { it.at <= target } == true) {
            currentTimeMillis = queue.peek().at
            runCurrent()
        }
        currentTimeMillis = target
    }
}

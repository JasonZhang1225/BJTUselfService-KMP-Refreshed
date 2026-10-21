package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

/** 运行阶段：界面据此说明“为什么没在同步”。 */
enum class RemotePhase {
    DISABLED,
    NOT_PAIRED,
    /** App 未登录或会话未就绪：不上传、不领取任务（轮询仍保持在线）。 */
    WAITING_FOR_LOGIN,
    RUNNING,
    /** 当前登录的学校账号与绑定时不同：暂停，需重新配对。 */
    ACCOUNT_MISMATCH,
    /** Site 返回 401（设备被撤销或令牌失效）：停止，需重新配对。 */
    NEEDS_PAIRING,
    PROTOCOL_MISMATCH,
}

data class RemoteAgentStatus(
    val phase: RemotePhase = RemotePhase.DISABLED,
    val lastUploadAt: Long? = null,
    val lastRefreshAt: Long? = null,
    val lastSeenAt: Long? = null,
    val lastError: String? = null,
    val nextUploadAt: Long? = null,
    val activeTasks: Int = 0,
    val lastSequence: Long = 0,
)

/** 平台侧能力：全量刷新（交给 App 当前会话的公共刷新服务）与读取本机缓存生成快照。 */
interface RemoteRefreshExecutor {
    /** 当前登录账号；未登录返回 null。 */
    fun currentAccountScope(): String?

    /** 执行一次全量刷新；未登录或会话未就绪返回 null。来源记为 scheduled/remote。 */
    suspend fun refreshAll(remote: Boolean): RefreshReport?

    /** 用当前缓存生成快照内容；读取失败返回 null。 */
    suspend fun snapshotContent(scopes: Set<RemoteScope>, lastStates: Map<String, RemoteSourceState>): RemoteSnapshotContent?

    suspend fun query(task: RemoteTask, scopes: Set<RemoteScope>): RemoteQueryOutcome? = null
}

sealed interface RemoteUploadOutcome {
    data class Uploaded(val sequence: Long, val duplicate: Boolean) : RemoteUploadOutcome
    data class Failed(val failure: RemoteCall.Failure) : RemoteUploadOutcome
    data class Skipped(val reason: String) : RemoteUploadOutcome
}

/**
 * Windows 常驻组件的调度核心（计划 PR 4）。
 *
 * - 两个独立计时器：每 [RemoteAgentConfig.uploadIntervalSeconds] 先全量刷新再上报快照；
 *   每 [RemoteAgentConfig.pollIntervalSeconds] 只向 Site 领取任务，空轮询不访问学校、不读快照。
 * - 上传失败只重传同一份快照（不重复登录学校）；序号取 max(上次+1, 当前毫秒)，本地状态丢失也不会倒退。
 * - 失败指数退避加抖动，429 遵守 Retry-After；401 停止并提示重新配对；改间隔时重新计算，不另起循环；
 *   休眠恢复后只补一次，不追补离线期间的所有周期。
 * - 任务至少一次交付：本地记录已完成结果，同一请求被重新投递时直接重传结果。
 */
class RemoteAgentRunner(
    private val configFlow: StateFlow<RemoteAgentConfig>,
    private val client: () -> RemoteSiteClient?,
    private val executor: RemoteRefreshExecutor,
    private val store: RemoteAgentStore,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
    /** 一“秒”对应的毫秒数；测试用较小值压缩时间。 */
    private val secondMillis: Long = 1_000,
) {
    private val statusState = MutableStateFlow(initialStatus())
    val status: StateFlow<RemoteAgentStatus> = statusState.asStateFlow()

    private val uploadMutex = Mutex()
    private val resultMutex = Mutex()
    /** 同一请求的新旧租约共用尚在执行的结果，不能各自访问学校。 */
    private val inFlightTasks = mutableMapOf<String, CompletableDeferred<RemoteStoredResult>>()
    /** 上传、轮询和错误记录并发改同一份持久状态：只能在锁内“读—改—写”各自的字段，不能整份覆盖。 */
    private val stateMutex = Mutex()

    private suspend fun updateState(transform: (RemoteAgentPersistedState) -> RemoteAgentPersistedState): RemoteAgentPersistedState =
        stateMutex.withLock {
            val current = store.loadState()
            val next = transform(current)
            if (next != current) store.saveState(next)
            next
        }
    private var jobs: List<Job> = emptyList()
    @kotlin.concurrent.Volatile private var stopped: RemotePhase? = null

    private fun initialStatus(): RemoteAgentStatus {
        val state = store.loadState()
        return RemoteAgentStatus(
            phase = phaseFor(configFlow.value),
            lastUploadAt = state.lastUploadAt,
            lastRefreshAt = state.lastRefreshAt,
            lastSeenAt = state.lastSeenAt,
            lastError = state.lastError,
            lastSequence = state.lastSequence,
        )
    }

    private fun phaseFor(config: RemoteAgentConfig): RemotePhase = when {
        !config.paired -> RemotePhase.NOT_PAIRED
        !config.runnable -> RemotePhase.DISABLED
        stopped != null -> stopped!!
        executor.currentAccountScope() == null -> RemotePhase.WAITING_FOR_LOGIN
        executor.currentAccountScope() != config.accountScope -> RemotePhase.ACCOUNT_MISMATCH
        else -> RemotePhase.RUNNING
    }

    private fun refreshPhase() = statusState.update { it.copy(phase = phaseFor(configFlow.value)) }

    private fun bindingStillCurrent(expected: RemoteAgentConfig): Boolean {
        val current = configFlow.value
        return current.runnable && current.siteUrl == expected.siteUrl && current.deviceId == expected.deviceId &&
            current.accountBindingId == expected.accountBindingId && current.accountScope == expected.accountScope &&
            current.scopesRevision == expected.scopesRevision && current.scopeSet == expected.scopeSet &&
            executor.currentAccountScope() == expected.accountScope
    }

    /** 启动两个循环；重复调用不会多开。 */
    fun start(scope: CoroutineScope) {
        if (jobs.any { it.isActive }) return
        jobs = listOf(scope.launch { uploadLoop() }, scope.launch { pollLoop() })
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** 重新配对或用户手动恢复后清除“需重新配对/版本不一致”的停止状态。 */
    fun clearStopped() {
        stopped = null
        refreshPhase()
    }

    private suspend fun waitForRunnable(): RemoteAgentConfig {
        while (true) {
            refreshPhase()
            val config = configFlow.value
            if (config.runnable && stopped == null) return config
            // 设置变化即重新判断；登录状态变化没有事件，最多 5 秒复查一次。
            withTimeoutOrNull(5 * secondMillis) { configFlow.first { it != config } }
        }
    }

    /** 等到 [atSeconds] 或设置变化；返回 true 表示时间到了。 */
    private suspend fun waitUntil(atSeconds: Long, config: RemoteAgentConfig): Boolean {
        val waitMillis = (atSeconds - clock()) * secondMillis
        if (waitMillis <= 0) return true
        return withTimeoutOrNull(waitMillis) { configFlow.first { it != config } } == null
    }

    private suspend fun uploadLoop() {
        var failures = 0
        var nextAttempt: Long? = null
        while (currentCoroutineIsActive()) {
            val config = waitForRunnable()
            val state = store.loadState()
            // 启动时：到期就立即刷新上报；否则按上次尝试时间排下一轮（不追补错过的周期）。
            val due = nextAttempt ?: ((state.lastUploadAttemptAt ?: 0) + config.uploadIntervalSeconds)
            statusState.update { it.copy(nextUploadAt = due) }
            if (!waitUntil(due, config)) continue
            if (phaseFor(configFlow.value) != RemotePhase.RUNNING) {
                // 等登录：不计失败，稍后再试。
                nextAttempt = clock() + 30
                continue
            }
            when (val outcome = runUploadCycle(refreshFirst = true, remote = false)) {
                is RemoteUploadOutcome.Uploaded -> { failures = 0; nextAttempt = null }
                is RemoteUploadOutcome.Skipped -> { nextAttempt = clock() + 30 }
                is RemoteUploadOutcome.Failed -> {
                    failures += 1
                    nextAttempt = clock() + retryDelaySeconds(outcome.failure, failures, base = 30, max = config.uploadIntervalSeconds.toLong())
                }
            }
        }
    }

    private suspend fun pollLoop() {
        var failures = 0
        while (currentCoroutineIsActive()) {
            val config = waitForRunnable()
            val delaySeconds = when (val outcome = pollOnce()) {
                null -> { failures = 0; config.pollIntervalSeconds.toLong() }
                else -> {
                    failures += 1
                    retryDelaySeconds(outcome, failures, base = config.pollIntervalSeconds.toLong(), max = 300)
                }
            }
            waitUntil(clock() + delaySeconds, config)
        }
    }

    /** 失败重试间隔：429 用服务器给的值；其余指数退避（加 ±20% 抖动），上限 [max]。 */
    internal fun retryDelaySeconds(failure: RemoteCall.Failure, failures: Int, base: Long, max: Long): Long {
        failure.retryAfterSeconds?.takeIf { failure.kind == RemoteFailureKind.RATE_LIMITED && it > 0 }?.let { return min(it, 3_600) }
        val exponent = (failures - 1).coerceIn(0, 16)
        val raw = min(max, base.coerceAtLeast(1) shl exponent)
        val jitter = 0.8 + random.nextDouble() * 0.4
        return (raw * jitter).toLong().coerceIn(1, max.coerceAtLeast(1))
    }

    private suspend fun handleTerminal(failure: RemoteCall.Failure) {
        when (failure.kind) {
            RemoteFailureKind.UNAUTHORIZED -> stopped = RemotePhase.NEEDS_PAIRING
            RemoteFailureKind.PROTOCOL_MISMATCH -> stopped = RemotePhase.PROTOCOL_MISMATCH
            else -> Unit
        }
        recordError(describe(failure))
    }

    private suspend fun recordError(message: String?) {
        updateState { it.copy(lastError = message) }
        statusState.update { it.copy(lastError = message, phase = phaseFor(configFlow.value)) }
    }

    /**
     * 一轮上报。[refreshFirst] 为 true 时先全量刷新再生成快照；有未送达且仍在本周期内的快照时只重传它。
     * [report] 由即时任务传入（已刷新过），避免重复刷新。
     */
    suspend fun runUploadCycle(refreshFirst: Boolean, remote: Boolean, report: RefreshReport? = null): RemoteUploadOutcome =
        uploadMutex.withLock {
            val config = configFlow.value
            if (!config.runnable) return@withLock RemoteUploadOutcome.Skipped("disabled")
            val account = executor.currentAccountScope() ?: return@withLock RemoteUploadOutcome.Skipped("not_signed_in")
            if (account != config.accountScope) {
                refreshPhase()
                return@withLock RemoteUploadOutcome.Skipped("account_mismatch")
            }
            val api = client() ?: return@withLock RemoteUploadOutcome.Skipped("not_paired")
            val now = clock()
            var lastStates = updateState { it.copy(lastUploadAttemptAt = now) }.lastSourceStates
            val pending = store.loadPending()?.takeIf {
                it.deviceId == config.deviceId && it.accountBindingId == config.accountBindingId &&
                    now - it.generatedAt < config.uploadIntervalSeconds && it.scopes.toSet() == config.scopes.toSet() && it.scopesRevision == config.scopesRevision
            }
            val snapshot = if (pending != null && report == null) pending else {
                val refreshed = report ?: if (refreshFirst) executor.refreshAll(remote) else null
                if (!bindingStillCurrent(config)) return@withLock RemoteUploadOutcome.Skipped("configuration_changed")
                if (refreshed != null) {
                    lastStates = updateState {
                        it.copy(
                            lastRefreshAt = refreshed.finishedAtEpochSeconds,
                            lastSourceStates = refreshed.results.associate { r -> r.source.wire to RemoteSourceState(r.status.wire, r.fetchedAtEpochSeconds, r.reason) },
                        )
                    }.lastSourceStates
                    statusState.update { it.copy(lastRefreshAt = refreshed.finishedAtEpochSeconds) }
                }
                val content = executor.snapshotContent(config.scopeSet, lastStates)
                    ?: return@withLock RemoteUploadOutcome.Skipped("cache_unavailable")
                if (!bindingStillCurrent(config)) return@withLock RemoteUploadOutcome.Skipped("configuration_changed")
                val sequence = updateState { it.copy(lastSequence = maxOf(it.lastSequence + 1, now * 1_000)) }.lastSequence
                RemoteSnapshot(
                    deviceId = config.deviceId!!,
                    accountBindingId = config.accountBindingId!!,
                    snapshotId = "snap_${randomToken(random, 12)}",
                    sequence = sequence,
                    generatedAt = now,
                    uploadedAt = now,
                    scopes = config.scopes,
                    sources = content.sources,
                    data = content.data,
                    scopesRevision = config.scopesRevision,
                ).also {
                    // 先落盘再上传：上传失败或进程退出后重传同一快照。
                    store.savePending(it)
                }
            }
            if (!bindingStillCurrent(config)) return@withLock RemoteUploadOutcome.Skipped("configuration_changed")
            // 明确范围更新成功后才送快照；失败重试不重新登录学校。
            when (val scopes = api.putScopes(config.scopes, config.scopesRevision)) {
                is RemoteCall.Failure -> {
                    handleTerminal(scopes)
                    return@withLock RemoteUploadOutcome.Failed(scopes)
                }
                is RemoteCall.Ok -> Unit
            }
            if (!bindingStillCurrent(config)) return@withLock RemoteUploadOutcome.Skipped("configuration_changed")
            when (val result = api.putSnapshot(snapshot.copy(uploadedAt = clock()))) {
                is RemoteCall.Ok -> {
                    store.savePending(null)
                    val uploadedAt = clock()
                    updateState { it.copy(lastUploadAt = uploadedAt, lastError = null) }
                    statusState.update { it.copy(lastUploadAt = uploadedAt, lastError = null, lastSequence = snapshot.sequence) }
                    RemoteUploadOutcome.Uploaded(snapshot.sequence, result.value.duplicate)
                }
                is RemoteCall.Failure -> {
                    // 旧快照永远传不上去：丢弃，下一轮生成新的。
                    if (result.kind == RemoteFailureKind.CONFLICT) store.savePending(null)
                    handleTerminal(result)
                    RemoteUploadOutcome.Failed(result)
                }
            }
        }

    /** 一次轮询；成功返回 null，失败返回原因。领到的任务在独立协程里执行，不阻塞下一次轮询。 */
    internal suspend fun pollOnce(taskScope: CoroutineScope? = null): RemoteCall.Failure? {
        val api = client() ?: return RemoteCall.Failure(RemoteFailureKind.UNAUTHORIZED, "not_paired")
        return when (val result = api.poll()) {
            is RemoteCall.Ok -> {
                val now = clock()
                // 在线时间本地也只粗粒度记录（约每分钟）。
                updateState { state -> if (state.lastSeenAt == null || now - state.lastSeenAt >= 60) state.copy(lastSeenAt = now) else state }
                statusState.update { it.copy(lastSeenAt = now) }
                result.value.task?.let { task ->
                    val scope = taskScope ?: CoroutineScope(kotlin.coroutines.coroutineContext)
                    scope.launch { handleTask(api, task) }
                }
                null
            }
            is RemoteCall.Failure -> {
                handleTerminal(result)
                result
            }
        }
    }

    /** 执行一条已领取的任务并提交结果；执行期间每分钟续租一次。 */
    internal suspend fun handleTask(api: RemoteSiteClient, task: RemoteTask) {
        val leaseId = task.leaseId ?: return
        statusState.update { it.copy(activeTasks = it.activeTasks + 1) }
        try {
            val config = configFlow.value
            val bindingMatches = config.deviceId == task.deviceId && config.accountBindingId == task.accountBindingId
            if (!config.runnable || !bindingMatches || executor.currentAccountScope() != config.accountScope) {
                submitWithRetry(api, task.requestId, RemoteTaskResult(leaseId, RemoteTaskStatus.FAILED,
                    error = RemoteError(if (!bindingMatches) RemoteErrorCode.BINDING_MISMATCH else RemoteErrorCode.SCOPE_DENIED, "绑定或查询范围已撤销")))
                return
            }
            val previous = resultMutex.withLock { store.loadResults().firstOrNull { it.requestId == task.requestId } }
            if (previous != null && when (task.operation) {
                    RemoteOperation.QUERY -> runCatching { parseRemoteQuery(task.arguments, config.scopeSet) }.isFailure
                    RemoteOperation.CITEL_PROBLEM -> RemoteScope.ASSIGNMENTS !in config.scopeSet
                    else -> false
                }) {
                submitWithRetry(api, task.requestId, RemoteTaskResult(leaseId, RemoteTaskStatus.FAILED,
                    error = RemoteError(RemoteErrorCode.SCOPE_DENIED, "历史结果的查询范围已撤销")))
                return
            }
            val outcome = previous ?: taskOutcome(api, task, leaseId)
            val scopeAllowed = when (task.operation) {
                RemoteOperation.QUERY -> runCatching { parseRemoteQuery(task.arguments, configFlow.value.scopeSet) }.isSuccess
                RemoteOperation.CITEL_PROBLEM -> RemoteScope.ASSIGNMENTS in configFlow.value.scopeSet
                else -> true
            }
            if (!bindingStillCurrent(config) || (!scopeAllowed && outcome.status != RemoteTaskStatus.FAILED)) {
                submitWithRetry(api, task.requestId, RemoteTaskResult(leaseId, RemoteTaskStatus.FAILED,
                    error = RemoteError(RemoteErrorCode.SCOPE_DENIED, "查询期间绑定或数据范围已变化")))
            } else {
                submitWithRetry(api, task.requestId, RemoteTaskResult(leaseId, outcome.status, outcome.result, outcome.error))
            }
        } finally {
            statusState.update { it.copy(activeTasks = (it.activeTasks - 1).coerceAtLeast(0)) }
        }
    }

    private suspend fun taskOutcome(api: RemoteSiteClient, task: RemoteTask, leaseId: String): RemoteStoredResult {
        val key = "${task.accountBindingId}:${task.requestId}"
        val (pending, owner) = resultMutex.withLock {
            store.loadResults().firstOrNull { it.requestId == task.requestId }?.let { return it }
            inFlightTasks[key]?.let { it to false } ?: (CompletableDeferred<RemoteStoredResult>().also { inFlightTasks[key] = it } to true)
        }
        if (!owner) return withLeaseRenewal(api, task.requestId, leaseId) { pending.await() }
        try {
            val outcome = try {
                withTimeoutOrNull(600 * secondMillis) { executeTask(api, task, leaseId) }?.let(::boundedTaskResult)
                    ?: RemoteStoredResult(task.requestId, clock(), RemoteTaskStatus.FAILED, JsonObject(emptyMap()), RemoteError("timeout", "任务执行超时"))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                RemoteStoredResult(task.requestId, clock(), RemoteTaskStatus.FAILED, JsonObject(emptyMap()), RemoteError("execution_failed", "任务执行失败，请稍后重试或在 App 中处理"))
            }
            resultMutex.withLock {
                val cutoff = clock() - 24 * 3600
                store.saveResults((store.loadResults().filter { it.requestId != outcome.requestId && it.storedAt > cutoff } + outcome).takeLast(50))
            }
            pending.complete(outcome)
            return outcome
        } catch (failure: Throwable) {
            pending.completeExceptionally(failure)
            throw failure
        } finally {
            withContext(NonCancellable) {
                resultMutex.withLock { if (inFlightTasks[key] === pending) inFlightTasks.remove(key) }
            }
        }
    }

    private fun boundedTaskResult(outcome: RemoteStoredResult): RemoteStoredResult {
        // 按实际 UTF-8 JSON 字节计数，给协议允许的最长租约 ID 留足空间。
        val envelope = RemoteTaskResult("x".repeat(64), outcome.status, outcome.result, outcome.error)
        val bytes = remoteJson.encodeToString(RemoteTaskResult.serializer(), envelope).encodeToByteArray().size
        return if (bytes <= 256 * 1024) outcome else outcome.copy(
            status = RemoteTaskStatus.FAILED,
            result = JsonObject(emptyMap()),
            error = RemoteError("result_too_large", "查询结果超过远程大小限制，请缩小查询范围，或在电脑端导出题面与用例"),
        )
    }

    private suspend fun executeTask(api: RemoteSiteClient, task: RemoteTask, leaseId: String): RemoteStoredResult {
        val config = configFlow.value
        fun failed(code: String, message: String) =
            RemoteStoredResult(task.requestId, clock(), RemoteTaskStatus.FAILED, JsonObject(emptyMap()), RemoteError(code, message))
        if (task.accountBindingId != config.accountBindingId || task.deviceId != config.deviceId) {
            return failed(RemoteErrorCode.BINDING_MISMATCH, "任务不属于当前绑定")
        }
        if (executor.currentAccountScope() != config.accountScope) {
            return failed("account_mismatch", "电脑上登录的学校账号与绑定时不同")
        }
        return when (task.operation) {
            RemoteOperation.REFRESH_ALL -> withLeaseRenewal(api, task.requestId, leaseId) {
                val report = executor.refreshAll(remote = true)
                    ?: return@withLeaseRenewal failed("not_signed_in", "电脑上的 App 尚未登录")
                val upload = runUploadCycle(refreshFirst = false, remote = true, report = report)
                val uploaded = upload as? RemoteUploadOutcome.Uploaded
                val status = when {
                    report.allSucceeded && uploaded != null -> RemoteTaskStatus.SUCCEEDED
                    else -> RemoteTaskStatus.PARTIAL
                }
                RemoteStoredResult(task.requestId, clock(), status, buildJsonObject {
                    put("refresh", report.toRemoteJson())
                    uploaded?.let { put("snapshotSequence", it.sequence) }
                    if (uploaded == null) put("snapshotUploaded", false)
                })
            }
            RemoteOperation.QUERY, RemoteOperation.CITEL_PROBLEM -> withLeaseRenewal(api, task.requestId, leaseId) {
                val result = executor.query(task, config.scopeSet)
                    ?: return@withLeaseRenewal failed(RemoteErrorCode.UNSUPPORTED_OPERATION, "当前执行者不支持精确查询")
                val current = configFlow.value
                if (!current.runnable || current.accountBindingId != task.accountBindingId ||
                    executor.currentAccountScope() != current.accountScope || !current.scopeSet.containsAll(config.scopeSet)) {
                    return@withLeaseRenewal failed(RemoteErrorCode.SCOPE_DENIED, "查询期间绑定或数据范围已变化，请重新查询")
                }
                RemoteStoredResult(task.requestId, clock(), result.status, result.result, result.error)
            }
            else -> failed(RemoteErrorCode.UNSUPPORTED_OPERATION, "电脑端暂不支持该操作：${task.operation.take(32)}")
        }
    }

    private suspend fun <T> withLeaseRenewal(api: RemoteSiteClient, requestId: String, leaseId: String, block: suspend () -> T): T {
        val parent = CoroutineScope(kotlin.coroutines.coroutineContext)
        val renewal = parent.launch {
            while (isActive) {
                delay(60 * secondMillis)
                api.renewLease(requestId, leaseId, 180)
            }
        }
        return try {
            block()
        } finally {
            renewal.cancel()
        }
    }

    /** 传结果失败只重传结果（最多 3 次），不重做学校刷新；旧租约被拒就放弃（任务已被重新领取或过期）。 */
    private suspend fun submitWithRetry(api: RemoteSiteClient, requestId: String, result: RemoteTaskResult) {
        repeat(3) { attempt ->
            when (val response = api.submitResult(requestId, result)) {
                is RemoteCall.Ok -> return
                is RemoteCall.Failure -> {
                    if (response.kind == RemoteFailureKind.CONFLICT || response.kind == RemoteFailureKind.UNAUTHORIZED ||
                        response.kind == RemoteFailureKind.CLIENT_ERROR) {
                        if (response.kind == RemoteFailureKind.UNAUTHORIZED) handleTerminal(response)
                        return
                    }
                    if (attempt < 2) delay(retryDelaySeconds(response, attempt + 1, base = 2, max = 30) * secondMillis)
                }
            }
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    companion object {
        fun describe(failure: RemoteCall.Failure): String = when (failure.kind) {
            RemoteFailureKind.UNAUTHORIZED -> "Site 拒绝了本机（设备已撤销或令牌失效），请重新配对。"
            RemoteFailureKind.FORBIDDEN -> "Site 拒绝访问（${failure.code ?: "forbidden"}）。若是私人 Site 的平台访问限制，请检查平台访问凭据。"
            RemoteFailureKind.PROTOCOL_MISMATCH -> "电脑端与 Site 模板的协议版本不一致，请更新到同一版本。"
            RemoteFailureKind.CONFLICT -> "Site 拒绝了过期的数据（${failure.code ?: "conflict"}），下一轮会重新生成。"
            RemoteFailureKind.RATE_LIMITED -> "Site 要求降低频率，稍后重试。"
            RemoteFailureKind.CLIENT_ERROR -> "Site 拒绝了请求（${failure.code ?: "bad_request"}）。"
            RemoteFailureKind.SERVER_ERROR -> "Site 暂时不可用，稍后重试。"
            RemoteFailureKind.NETWORK -> "无法连接 Site，稍后重试。"
        }
    }
}

private fun RefreshReport.toRemoteJson(): JsonObject = buildJsonObject {
    put("origin", origin.wire)
    put("startedAt", startedAtEpochSeconds)
    put("finishedAt", finishedAtEpochSeconds)
    put("sources", buildJsonArray {
        results.forEach { result ->
            add(buildJsonObject {
                put("source", result.source.wire)
                put("status", result.status.wire)
                result.reason?.let { put("reason", it) }
                put("fetchedAt", result.fetchedAtEpochSeconds)
            })
        }
    })
    put("needsUserAction", results.any { it.status == RefreshStatus.NEEDS_USER_ACTION })
}

private const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

internal fun randomToken(random: Random, length: Int): String = buildString(length) {
    repeat(length) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
}

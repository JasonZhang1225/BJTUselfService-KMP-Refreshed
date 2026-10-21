package team.bjtuss.bjtuselfservice.shared.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import team.bjtuss.bjtuselfservice.shared.feature.shell.SessionRefreshCoordinator

/**
 * 一个数据源的刷新适配：把现有 ScreenModel/Repository 的刷新动作与结果状态翻译成统一报告。
 * 适配层只读状态，不另写学校协议。
 */
interface RefreshSourceAdapter {
    val source: RefreshSource

    /** 走 MIS 统一会话的来源：刷新前探测会话、过期后自动恢复并重试。独立账号的来源为 false。 */
    val usesSchoolSession: Boolean

    /** 是否参与本次刷新；非 ENABLED 时直接记为跳过，不访问网络。 */
    suspend fun availability(): RefreshAvailability = RefreshAvailability.ENABLED

    suspend fun refresh()

    /** [refresh] 之后会话仍过期（恢复失败）。 */
    fun sessionExpired(): Boolean = false

    /** [refresh] 之后的结果；会话过期已由协调器单独判定。 */
    fun outcome(): RefreshSourceResult

    /** 当前缓存的真实同步时间（Unix 秒）；没有记录返回 null。 */
    fun fetchedAtEpochSeconds(): Long?
}

/**
 * App 按钮、CLI、周期任务和远程任务共用的全量刷新执行者（每个已登录账号一个实例）。
 *
 * - 同一来源只有一个在途刷新：相同请求合并等待；部分重叠的请求复用在途工作，只补齐缺失来源。
 * - 一个来源失败不影响其它来源；会话恢复在同一批内合并，避免并发发起多组验证码请求。
 * - 在途工作跑在 [scope]（所有者的作用域）里：某个等待方被取消不会中断别人共享的刷新；
 *   [close] 后（退出登录、切换账号）取消全部在途工作，之后的请求不再访问学校。
 */
class AppRefreshCoordinator(
    val accountScope: String,
    private val adapters: List<RefreshSourceAdapter>,
    private val scope: CoroutineScope,
    private val reauthenticate: (suspend () -> Boolean)? = null,
    private val probeSession: (suspend () -> Boolean)? = null,
    private val nowEpochSeconds: () -> Long,
    /** 单个来源的总预算（含会话恢复与重试）；模型内部的网络超时通常更短。 */
    private val perSourceTimeoutMillis: Long = DEFAULT_SOURCE_TIMEOUT_MILLIS,
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<RefreshSource, CompletableDeferred<RefreshSourceResult>>()
    @Volatile
    private var closed = false
    private val adapterBySource = adapters.associateBy { it.source }

    private val recoveryState = MutableStateFlow(false)
    private val runningSources = MutableStateFlow<Set<RefreshSource>>(emptySet())

    /** 正在恢复学校会话（用于界面提示）。 */
    val recoveryInProgress: StateFlow<Boolean> = recoveryState.asStateFlow()

    /** 当前正在刷新的来源。 */
    val running: StateFlow<Set<RefreshSource>> = runningSources.asStateFlow()

    private val completedReports = MutableSharedFlow<RefreshReport>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 每次 [refresh] 完成后的报告（只发给正在收听的一方，不回放）；手机端 ChatGPT 插件据此上传快照。 */
    val completed: SharedFlow<RefreshReport> = completedReports.asSharedFlow()

    val supportedSources: Set<RefreshSource> get() = adapterBySource.keys

    /**
     * 刷新 [request] 中的来源并等待结果。不支持的来源记为跳过。
     * 调用方取消只停止等待；共享的在途刷新继续运行，直至完成或 [close]。
     */
    suspend fun refresh(request: RefreshRequest): RefreshReport {
        val startedAt = nowEpochSeconds()
        val requested = RefreshSource.entries.filter { it in request.sources }
        val waits: Map<RefreshSource, Deferred<RefreshSourceResult>> = mutex.withLock {
            if (closed) return@withLock emptyMap()
            val missing = requested.filter { it in adapterBySource && it !in inFlight }
            if (missing.isNotEmpty()) startBatch(missing, request.forceReauthenticate)
            requested.mapNotNull { source -> inFlight[source]?.let { source to it } }.toMap()
        }
        val results = requested.map { source ->
            val adapter = adapterBySource[source]
            val wait = waits[source]
            when {
                adapter == null -> RefreshSourceResult(source, RefreshStatus.SKIPPED, RefreshReason.NOT_CONFIGURED,
                    "当前宿主不支持该来源。")
                wait == null -> cancelledResult(adapter)
                else -> try {
                    wait.await()
                } catch (error: CancellationException) {
                    // 调用方自己被取消：照常抛出；否则是共享工作被 close() 取消。
                    if (!currentCoroutineContext().isActive) throw error
                    cancelledResult(adapter)
                }
            }
        }
        return RefreshReport(accountScope, request.origin, startedAt, nowEpochSeconds(), results)
            .also { if (!closed && waits.isNotEmpty()) completedReports.tryEmit(it) }
    }

    /** 退出登录或切换账号：取消在途刷新，此后请求一律不再执行。 */
    fun close() {
        val pending = synchronizedClose()
        pending.forEach { it.cancel(CancellationException("refresh coordinator closed")) }
        scope.cancel(CancellationException("refresh coordinator closed"))
    }

    val isClosed: Boolean get() = closed

    private fun synchronizedClose(): List<CompletableDeferred<RefreshSourceResult>> {
        // close() 可能在主线程同步调用；inFlight 只在 mutex 下变更，这里尽力取一份快照即可：
        // 之后的 refresh() 会看到 closed 并直接返回，漏掉的在途项随 scope 一起取消。
        closed = true
        return inFlight.values.toList()
    }

    private fun startBatch(sources: List<RefreshSource>, forceReauthenticate: Boolean) {
        val session = SessionRefreshCoordinator(
            reauthenticate = reauthenticate,
            probeSession = probeSession,
            onRecoveryStateChanged = { active -> recoveryState.value = active },
        )
        // 显式“先登录再刷新”：整批共享一次恢复，失败则整批不访问学校（与旧 refreshGlobal 一致）。
        val preflight: Deferred<Boolean>? = if (forceReauthenticate) {
            scope.async { session.recoverBeforeRefresh() }
        } else null
        for (source in sources) {
            val adapter = adapterBySource.getValue(source)
            val result = CompletableDeferred<RefreshSourceResult>()
            inFlight[source] = result
            val job = scope.launch {
                runningSources.update { it + source }
                val outcome = try {
                    if (preflight != null && !preflight.await()) {
                        RefreshSourceResult(source, RefreshStatus.NEEDS_USER_ACTION, RefreshReason.RECOVERY_FAILED,
                            "登录已失效，自动恢复失败，请在 App 里重新登录。", adapter.safeFetchedAt())
                    } else {
                        runSource(adapter, session)
                    }
                } catch (error: CancellationException) {
                    runningSources.update { it - source }
                    mutex.withLock { if (inFlight[source] === result) inFlight.remove(source) }
                    result.cancel(error)
                    throw error
                }
                runningSources.update { it - source }
                mutex.withLock { if (inFlight[source] === result) inFlight.remove(source) }
                result.complete(outcome)
            }
            // close() 与本批启动交错时，协程可能来不及开始就被取消：不让等待方永远挂起。
            job.invokeOnCompletion { cause -> if (!result.isCompleted) result.cancel(CancellationException("refresh cancelled", cause)) }
        }
    }

    private suspend fun runSource(adapter: RefreshSourceAdapter, session: SessionRefreshCoordinator): RefreshSourceResult {
        val availability = try {
            adapter.availability()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            RefreshAvailability.NOT_CONFIGURED
        }
        when (availability) {
            RefreshAvailability.DISABLED -> return RefreshSourceResult(adapter.source, RefreshStatus.SKIPPED,
                RefreshReason.DISABLED, "已在设置中关闭。", adapter.safeFetchedAt())
            RefreshAvailability.NOT_CONFIGURED -> return RefreshSourceResult(adapter.source, RefreshStatus.SKIPPED,
                RefreshReason.NOT_CONFIGURED, "尚未配置账号。", adapter.safeFetchedAt())
            RefreshAvailability.ENABLED -> Unit
        }
        val finished = try {
            withTimeoutOrNull(perSourceTimeoutMillis) {
                if (adapter.usesSchoolSession) session.run(adapter::refresh, adapter::sessionExpired) else adapter.refresh()
                true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return RefreshSourceResult(adapter.source, RefreshStatus.FAILED, RefreshReason.ERROR,
                "刷新失败（${error::class.simpleName}），保留原缓存。", adapter.safeFetchedAt())
        }
        if (finished == null) {
            return RefreshSourceResult(adapter.source, RefreshStatus.FAILED, RefreshReason.TIMEOUT,
                "刷新超时，保留原缓存。", adapter.safeFetchedAt())
        }
        if (adapter.usesSchoolSession && adapter.sessionExpired()) {
            return RefreshSourceResult(adapter.source, RefreshStatus.NEEDS_USER_ACTION, RefreshReason.SESSION_EXPIRED,
                "登录已失效，自动恢复失败，请在 App 里重新登录。", adapter.safeFetchedAt())
        }
        return adapter.outcome().copy(source = adapter.source, fetchedAtEpochSeconds = adapter.safeFetchedAt())
    }

    private fun cancelledResult(adapter: RefreshSourceAdapter) = RefreshSourceResult(adapter.source, RefreshStatus.FAILED,
        RefreshReason.CANCELLED, "刷新已取消（退出登录或切换账号）。", adapter.safeFetchedAt())

    private fun RefreshSourceAdapter.safeFetchedAt(): Long? = runCatching { fetchedAtEpochSeconds() }.getOrNull()

    companion object {
        const val DEFAULT_SOURCE_TIMEOUT_MILLIS: Long = 5 * 60_000L
    }
}

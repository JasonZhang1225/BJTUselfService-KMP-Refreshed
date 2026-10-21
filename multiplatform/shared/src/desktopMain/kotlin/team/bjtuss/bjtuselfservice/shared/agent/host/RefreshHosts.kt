package team.bjtuss.bjtuselfservice.shared.agent.host

import java.io.File
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.agent.AgentAppLock
import team.bjtuss.bjtuselfservice.shared.agent.AgentLoginOutcome
import team.bjtuss.bjtuselfservice.shared.agent.headlessLogin
import team.bjtuss.bjtuselfservice.shared.auth.CaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.files.UnavailableHomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityStore
import team.bjtuss.bjtuselfservice.shared.sync.AppDataGraph
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshRequest
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource

/** App 等待登录完成再执行 IPC 刷新的上限（静默自动登录通常数秒）。 */
private const val APP_SESSION_WAIT_MILLIS = 120_000L
private const val LOCK_POLL_MILLIS = 200L

/**
 * App 窗口进程的刷新宿主：持有执行者锁并提供 IPC，CLI/后台请求交给当前会话的公共刷新服务，
 * 与界面共享同一轮进度、缓存和会话。关闭时先停 IPC 再释放锁。
 */
class AppRefreshHost private constructor(
    private val lock: AgentAppLock,
    private val server: RefreshIpcServer?,
) : AutoCloseable {
    val ipcAvailable: Boolean get() = server != null

    override fun close() {
        runCatching { server?.close() }
        lock.close()
    }

    companion object {
        /**
         * GUI 启动时调用。锁被无窗口宿主占着时先请它交出执行权（它会取消在途刷新并退出），
         * 最多等 [handoffWaitMillis]；仍拿不到（旧版本命令行或进程卡住）就返回 null，App 照常启动。
         */
        fun start(
            cacheDirectory: File,
            sessionProvider: () -> AuthenticatedSession?,
            handoffWaitMillis: Long = 8_000,
        ): AppRefreshHost? {
            if (!cacheDirectory.isDirectory) cacheDirectory.mkdirs()
            val lock = AgentAppLock.tryAcquire(cacheDirectory) ?: run {
                callRefreshIpc(cacheDirectory, RefreshIpcOp.HANDOFF, deadlineMillis = 5_000)
                waitForLock(cacheDirectory, handoffWaitMillis)
            } ?: return null
            val server = RefreshIpcServer.start(cacheDirectory, RefreshExecutor.APP, AppSessionIpcHandler(sessionProvider))
            return AppRefreshHost(lock, server)
        }
    }
}

internal fun waitForLock(cacheDirectory: File, waitMillis: Long): AgentAppLock? {
    val deadline = System.nanoTime() + waitMillis * 1_000_000
    while (true) {
        AgentAppLock.tryAcquire(cacheDirectory)?.let { return it }
        if (System.nanoTime() >= deadline) return null
        Thread.sleep(LOCK_POLL_MILLIS)
    }
}

/** App 进程：等到当前会话登录完成，再交给它的 [team.bjtuss.bjtuselfservice.shared.sync.AppRefreshCoordinator]。 */
internal class AppSessionIpcHandler(private val sessionProvider: () -> AuthenticatedSession?) : RefreshIpcHandler {
    override suspend fun refresh(sources: Set<RefreshSource>, origin: RefreshOrigin, deadlineMillis: Long): RefreshIpcOutcome {
        val session = withTimeoutOrNull(minOf(deadlineMillis, APP_SESSION_WAIT_MILLIS)) { awaitReadySession() } ?: return RefreshIpcOutcome.Rejected(RefreshIpcCode.NOT_SIGNED_IN, "App 正在运行但尚未登录。请在 App 里完成登录后再刷新。")
        val coordinator = session.refreshCoordinator
            ?: return RefreshIpcOutcome.Rejected(RefreshIpcCode.NOT_SIGNED_IN, "App 会话尚未就绪。")
        return RefreshIpcOutcome.Completed(coordinator.refresh(RefreshRequest(sources = sources, origin = origin)))
    }

    private suspend fun awaitReadySession(): AuthenticatedSession {
        while (true) {
            val current = sessionProvider()
            if (current != null && !current.entryLoggingIn && current.refreshCoordinator?.isClosed == false) return current
            delay(500)
        }
    }
}

/** 无窗口执行的依赖：均由调用方从系统安全存储与已有缓存提供，不新建、不清除凭据。 */
class HeadlessRefreshDependencies(
    val cacheDirectory: File,
    val cache: CacheStore,
    val credentials: Credentials,
    val securityStore: AccountSecurityStore,
    val captchaRecognizer: CaptchaRecognizer,
    val transport: SchoolHttpTransport,
    val platformFamily: PlatformFamily,
)

data class HeadlessRunResult(
    val login: AgentLoginOutcome,
    val report: RefreshReport?,
    /** 期间 GUI 启动并接手：在途刷新已取消，调用方应释放锁后改交给 App。 */
    val handedOff: Boolean,
    /** 登录返回的学号与缓存账号不一致。 */
    val accountMismatch: Boolean = false,
)

/**
 * App 未运行时的无窗口执行者：用记住的账号登录，装配与 App 相同的 [AppDataGraph]，
 * 执行一次公共全量刷新。期间开放 IPC：其它 CLI 请求并入同一执行者，GUI 启动可请求交接。
 * 调用方须已持有执行者锁。
 */
class HeadlessRefreshHost(private val deps: HeadlessRefreshDependencies) {
    @Volatile private var handedOff = false
    @Volatile private var runJob: Job? = null
    private val graphReady = CompletableDeferred<AppDataGraph?>()

    private val handler = object : RefreshIpcHandler {
        override suspend fun refresh(sources: Set<RefreshSource>, origin: RefreshOrigin, deadlineMillis: Long): RefreshIpcOutcome {
            if (handedOff) return RefreshIpcOutcome.Rejected(RefreshIpcCode.HANDING_OFF, "正在把执行权交给 App，请稍后重试。")
            val graph = graphReady.await()
                ?: return RefreshIpcOutcome.Rejected(RefreshIpcCode.NOT_SIGNED_IN, "命令行自动登录失败，请打开 App 登录。")
            return RefreshIpcOutcome.Completed(graph.refreshCoordinator.refresh(RefreshRequest(sources = sources, origin = origin)))
        }

        override fun handoff(): Boolean {
            handedOff = true
            runJob?.cancel(CancellationException("handoff to app"))
            graphReady.complete(null)
            return true
        }
    }

    suspend fun run(sources: Set<RefreshSource>, origin: RefreshOrigin): HeadlessRunResult {
        val server = RefreshIpcServer.start(deps.cacheDirectory, RefreshExecutor.HEADLESS, handler)
        var graph: AppDataGraph? = null
        var result = HeadlessRunResult(AgentLoginOutcome.Network, null, handedOff = false)
        try {
            coroutineScope {
                val protocol = SchoolLoginProtocol(deps.transport)
                runJob = launch {
                    val login = headlessLogin(protocol, deps.captchaRecognizer, deps.credentials)
                    val profile = login.profile
                    if (login.outcome != AgentLoginOutcome.Ok || profile == null) {
                        result = HeadlessRunResult(login.outcome, null, handedOff = false)
                        graphReady.complete(null)
                        return@launch
                    }
                    if (profile.studentId != deps.credentials.username) {
                        result = HeadlessRunResult(login.outcome, null, handedOff = false, accountMismatch = true)
                        graphReady.complete(null)
                        return@launch
                    }
                    val built = buildGraph(profile, protocol)
                    graph = built
                    built.loadCachedState()
                    graphReady.complete(built)
                    val report = built.refreshCoordinator.refresh(RefreshRequest(sources = sources, origin = origin))
                    result = HeadlessRunResult(AgentLoginOutcome.Ok, report, handedOff = false)
                }
            }
        } finally {
            graphReady.complete(null)
            runCatching { server?.close() }
            graph?.close()
        }
        return if (handedOff) result.copy(handedOff = true) else result
    }

    private fun buildGraph(profile: team.bjtuss.bjtuselfservice.shared.auth.StudentProfile, protocol: SchoolLoginProtocol): AppDataGraph {
        val preferences = runCatching { deps.cache.preferences() }.getOrDefault(AppPreferences())
        return AppDataGraph(
            profile = profile,
            transport = deps.transport,
            loginProtocol = protocol,
            cacheStore = deps.cache,
            platformFamily = deps.platformFamily,
            appPreferences = preferences,
            // 无窗口宿主不改用户设置。
            onPreferencesChanged = { false },
            securityCoordinator = AccountSecurityCoordinator(deps.securityStore),
            captchaRecognizer = deps.captchaRecognizer,
            credentialsProvider = { deps.credentials },
            homeworkFileGateway = UnavailableHomeworkFileGateway,
            onPurgeLogout = {},
            refreshDispatcher = Dispatchers.Default,
            nowEpochSeconds = { Clock.System.now().epochSeconds },
        )
    }
}

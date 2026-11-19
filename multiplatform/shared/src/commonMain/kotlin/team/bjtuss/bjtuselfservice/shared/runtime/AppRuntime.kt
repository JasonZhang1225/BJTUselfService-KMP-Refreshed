package team.bjtuss.bjtuselfservice.shared.runtime

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.cleanup.*
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityStore
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackRecoveryAccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns account resources independently of the login destination's composition.
 * Creation, binding updates and release are serialized on the host event thread.
 * Cross-thread consumers observe [session] or compare an account's identity.
 *
 * Platform hosts retain this owner across root UI reconstruction and close it
 * when their application lifetime ends. UI bindings never close this owner.
 */
class AppRuntime(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val cleanupPlatform: team.bjtuss.bjtuselfservice.shared.cleanup.CleanupPlatform = team.bjtuss.bjtuselfservice.shared.cleanup.platformCleanup(),
    private val transportFactory: () -> SchoolHttpTransport = ::createSchoolHttpTransport,
) {
    val cleanupCoordinator = team.bjtuss.bjtuselfservice.shared.cleanup.AccountCleanupCoordinator()
    private class CleanupResources(val cache: CacheStore, val security: AccountSecurityCoordinator, val recovery: PlaybackRecoveryAccess?, val cacheAvailable: Boolean)
    private var cleanupResources: CleanupResources? = null
    private val recoveryMutex = Mutex()
    private var cleanupStartupChecked = false
    var cleanupBlocksLogin: Boolean = false
        private set

    internal fun bindCleanupResources(cache: CacheStore, store: AccountSecurityStore, recovery: PlaybackRecoveryAccess?, cacheAvailable: Boolean = true) {
        if (cleanupResources != null) return
        val security = AccountSecurityCoordinator(store)
        cleanupResources = CleanupResources(cache, security, recovery, cacheAvailable)
        cleanupCoordinator.bindJournal(security.cleanupJournal)
    }

    /** Recovery uses storage ports only; it never creates a login, page model, or native window. */
    suspend fun recoverPendingCleanup(cacheAvailable: Boolean = true): List<CleanupReport> = recoveryMutex.withLock {
        val resources = cleanupResources ?: return@withLock emptyList()
        if (currentAccount != null) return@withLock emptyList()
        val records = try { cleanupCoordinator.pending() } catch (_: Exception) {
            cleanupBlocksLogin = true
            return@withLock listOf(cleanupCoordinator.journalFailure())
        }
        val reports = records.map { record ->
            AccountCleanupService(cleanupCoordinator, resources.cache, resources.security, boundCleanupPlatform(),
                null, remoteController, resources.recovery,
                stopWork = { check(currentAccount == null) { "Recovery cannot cross a new account session" } },
                protocol = null, recoveredRecord = record, cacheAvailable = cacheAvailable && resources.cacheAvailable,
            ).execute(record.request.operation)
        }
        cleanupBlocksLogin = try { cleanupCoordinator.pending().isNotEmpty() } catch (_: Exception) { true }
        cleanupStartupChecked = true
        reports
    }

    internal suspend fun ensureCleanupReady(cacheAvailable: Boolean = true): Boolean {
        if (cleanupStartupChecked && !cleanupBlocksLogin) return true
        if (retryCleanup != null && currentAccount == null) retryLastCleanup()
        recoverPendingCleanup(cacheAvailable)
        return !cleanupBlocksLogin
    }

    private fun boundCleanupPlatform(): CleanupPlatform = object : CleanupPlatform by cleanupPlatform {
        override suspend fun clearDeviceSettings(removeAgent: Boolean): Boolean {
            val native = runCatching { cleanupPlatform.clearDeviceSettings(removeAgent) }.getOrDefault(false)
            val device = runCatching { deviceCleanup?.invoke() ?: true }.getOrDefault(false)
            return native && device
        }
    }
    private var deviceCleanup: (suspend () -> Boolean)? = null
    fun bindDeviceCleanup(action: suspend () -> Boolean) { deviceCleanup = action }

    private var remoteController: team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentController? = null
    private var completedCleanup: Pair<Long, CleanupReport>? = null
    private var retryCleanup: Triple<AccountSession, team.bjtuss.bjtuselfservice.shared.cleanup.AccountCleanupService, team.bjtuss.bjtuselfservice.shared.cleanup.CleanupOperation>? = null

    fun bindRemoteAgent(controller: team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentController) {
        remoteController = controller
        controller.cleanupCoordinator = cleanupCoordinator
    }

    internal suspend fun cleanup(account: AccountSession, operation: team.bjtuss.bjtuselfservice.shared.cleanup.CleanupOperation): team.bjtuss.bjtuselfservice.shared.cleanup.CleanupReport {
        val service = cleanupService(account, operation)
        val report = service.execute(operation)
        stageCleanupForLogout(account, report)
        retryCleanup = if (report.succeeded) null else Triple(account, service, operation)
        cleanupBlocksLogin = !report.localDataCleared || runCatching { cleanupCoordinator.pending().isNotEmpty() }.getOrDefault(true)
        return report
    }

    private fun cleanupService(account: AccountSession, operation: CleanupOperation) = AccountCleanupService(
            cleanupCoordinator, account.graph.cleanupCache, account.graph.securityCoordinator,
            boundCleanupPlatform(),
            account.graph, remoteController ?: account.presentation.remoteAgent, services?.playbackRecovery,
            stopWork = {
                check(currentAccount == null || currentAccount === account) { "Cleanup owner is no longer current" }
                account.graph.stopAndJoin()
                if (operation != team.bjtuss.bjtuselfservice.shared.cleanup.CleanupOperation.SIGN_OUT) {
                    retiring.toList().forEach { it.graph.stopAndJoin() }
                }
            },
            protocol = account.graph.loginProtocol,
            cacheAvailable = cleanupResources?.cacheAvailable ?: true,
        )

    private fun stageCleanupForLogout(account: AccountSession, report: CleanupReport) {
        if (currentAccount === account && report.accountWorkStopped &&
            (report.request.operation == CleanupOperation.ALL_LOCAL_DATA ||
                report.request.operation == CleanupOperation.PREPARE_UNINSTALL)
        ) {
            completedCleanup = account.id to report
        }
    }

    internal fun takeCompletedCleanup(account: AccountSession): CleanupReport? {
        val previous = completedCleanup?.takeIf { it.first == account.id } ?: return null
        completedCleanup = null
        return previous.second
    }

    suspend fun retryLastCleanup(): team.bjtuss.bjtuselfservice.shared.cleanup.CleanupReport? {
        val retry = retryCleanup ?: return recoverPendingCleanup().lastOrNull()
        val (account, service, operation) = retry
        if (currentAccount != null && currentAccount !== account) return null
        val report = service.execute(operation)
        if (report.succeeded) retryCleanup = null
        stageCleanupForLogout(account, report)
        cleanupBlocksLogin = !report.localDataCleared || runCatching { cleanupCoordinator.pending().isNotEmpty() }.getOrDefault(true)
        return report
    }

    internal val taskDispatcher get() = dispatcher
    internal val operations = CoroutineScope(SupervisorJob() + dispatcher)
    private val sessionState = MutableStateFlow<AuthenticatedSession?>(null)
    val session = sessionState.asStateFlow()

    @Volatile
    internal var currentAccount: AccountSession? = null
        private set

    @Volatile
    private var closed = false
    private var nextAccountId = 0L
    private var loginContext: LoginRuntimeContext? = null
    private var services: RuntimeHostServices? = null
    private val retiring = mutableSetOf<AccountSession>()
    private var shutdown: Deferred<Unit>? = null
    internal val entryAuthenticationWork: AccountWork? get() = loginContext?.authenticationWork

    internal fun loginContext(store: team.bjtuss.bjtuselfservice.shared.security.AccountSecurityStore): LoginRuntimeContext {
        check(!closed) { "App runtime is closed" }
        return loginContext ?: LoginRuntimeContext(store, operations, transportFactory).also { loginContext = it }
    }

    internal fun hostServices(playbackRecovery: PlaybackRecoveryAccess?): RuntimeHostServices {
        check(!closed) { "App runtime is closed" }
        return services ?: RuntimeHostServices(playbackRecovery).also { services = it }
    }

    internal fun obtainAccount(accountScope: String, create: (Long) -> AccountSession): AccountSession {
        check(!closed) { "App runtime is closed" }
        val scope = accountScope.trim().also { require(it.isNotEmpty()) { "Account scope cannot be blank" } }
        currentAccount?.takeIf { it.accountScope == scope }?.let { return it }
        currentAccount?.let(::endAccount)
        val account = create(++nextAccountId)
        currentAccount = account
        sessionState.value = account.presentation
        account.syncTasks.start()
        account.widgetPublisher.start()
        return account
    }

    internal fun isCurrent(account: AccountSession): Boolean =
        !closed && currentAccount === account && !account.isClosed

    /** An old destination must never end a newer login, even for the same account. */
    internal fun endAccount(account: AccountSession): Boolean {
        if (currentAccount !== account) return false
        currentAccount = null
        sessionState.value = null
        account.close()
        retiring += account
        operations.launch(start = CoroutineStart.UNDISPATCHED) {
            account.graph.stopAndJoin()
            retiring -= account
        }
        return true
    }

    /** Runs a bound transition only when the exact account incarnation was ended. */
    internal fun endAccount(account: AccountSession, afterEnd: () -> Unit): Boolean {
        if (!endAccount(account)) return false
        afterEnd()
        return true
    }

    internal fun requestLogout(account: AccountSession) {
        if (!isCurrent(account) || !account.beginLogout()) return
        operations.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                cleanupService(account, CleanupOperation.SIGN_OUT).prepare(CleanupOperation.SIGN_OUT)
                account.graph.stopAndJoin()
                if (isCurrent(account)) account.notifyLogout()
            } finally {
                account.finishLogout()
            }
        }
    }

    /** Revokes immediately; dependent platform resources are released only after all finalizers. */
    fun close(onStopped: () -> Unit = {}) {
        if (!closed) {
            closed = true
            currentAccount?.let(::endAccount)
            val pending = retiring.toList()
            loginContext?.stopAuthentication()
            operations.cancel()
            services?.close()
            val login = loginContext
            shutdown = CoroutineScope(SupervisorJob() + dispatcher).async(start = CoroutineStart.UNDISPATCHED) {
                pending.forEach { it.graph.stopAndJoin() }
                operations.coroutineContext[Job]?.join()
                login?.close()
                loginContext = null
                retiring.clear()
            }
        }
        shutdown?.invokeOnCompletion { onStopped() }
    }

    suspend fun closeAndJoin() {
        close()
        shutdown?.await()
    }
}

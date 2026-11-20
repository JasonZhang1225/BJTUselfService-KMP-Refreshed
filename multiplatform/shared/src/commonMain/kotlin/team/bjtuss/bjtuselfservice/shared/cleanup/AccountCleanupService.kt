package team.bjtuss.bjtuselfservice.shared.cleanup

import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentController
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentStore
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentConfig
import team.bjtuss.bjtuselfservice.shared.agent.remote.VaultRemoteSecretStore
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackRecoveryAccess
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.sync.AppDataGraph
import team.bjtuss.bjtuselfservice.shared.webview.clearSchoolWebViewData

/** Shared assembly of storage adapters; no navigation or platform deletion implementation. */
internal class AccountCleanupService(
    private val coordinator: AccountCleanupCoordinator,
    private val cache: CacheStore,
    private val security: AccountSecurityCoordinator,
    private val platform: CleanupPlatform,
    private val graph: AppDataGraph?,
    private val remote: RemoteAgentController?,
    private val recovery: PlaybackRecoveryAccess?,
    private val stopWork: suspend () -> Unit,
    private val protocol: SchoolLoginProtocol?,
    private val clearWeb: suspend () -> Boolean = ::clearSchoolWebViewData,
    private val recoveredRecord: CleanupRecord? = null,
    private val cacheAvailable: Boolean = true,
) {
    private fun inventory(owner: String, all: Boolean): CleanupInventory {
        val playback = graph?.playbackTaskManager?.cleanupInventory()
        val remoteConfig = remote?.config?.value ?: RemoteAgentStore(cache).loadConfig()
        return CleanupInventory(accounts = setOf(owner), playbackTaskIds = playback?.first.orEmpty(),
            playbackTemporaryDestinations = if (recovery == null) playback?.second.orEmpty() else emptySet(),
            ownsRemote = remote?.ownsCleanup(owner, all)
                ?: remoteConfig.accountScope.let { all || it == null || it == owner },
            remoteBindingId = remoteConfig.accountBindingId)
    }

    suspend fun prepare(operation: CleanupOperation): Boolean {
        val owner = checkNotNull(graph).accountScope
        return coordinator.admit(CleanupRequest(operation, owner), inventory(owner,
            operation == CleanupOperation.ALL_LOCAL_DATA || operation == CleanupOperation.PREPARE_UNINSTALL))
    }

    suspend fun execute(operation: CleanupOperation): CleanupReport {
        val owner = recoveredRecord?.request?.accountScope ?: checkNotNull(graph).accountScope
        val all = operation == CleanupOperation.ALL_LOCAL_DATA || operation == CleanupOperation.PREPARE_UNINSTALL
        // Capture before deleting any namespace. Include owners whose service configuration is absent.
        val manager = graph?.playbackTaskManager
        val remoteStore = RemoteAgentStore(cache)
        val context = CleanupContext(inventory(owner, all), recovering = recoveredRecord != null)
        fun ownsRemote(): Boolean {
            if (!context.inventory.ownsRemote) return false
            val current = (remote?.config?.value ?: remoteStore.loadConfig()).accountBindingId
            return all || current == null || current == context.inventory.remoteBindingId
        }
        return coordinator.execute(CleanupRequest(operation, owner), mapOf(
            CleanupStep.DISCOVER_OWNERS to {
                check(cacheAvailable)
                context.inventory = context.inventory.copy(accounts = context.inventory.accounts + cache.accountScopes() + recovery?.knownAccountScopes().orEmpty())
                true
            },
            CleanupStep.STOP_WORK to {
                stopWork()
                manager?.stopAccountWorkAndJoin()
                if (manager == null && recovery != null && owner in recovery.knownAccountScopes()) recovery.retainedManager(owner)?.stopAccountWorkAndJoin()
                if (all && recovery != null) for (account in recovery.knownAccountScopes() - owner) recovery.retainedManager(account)?.stopAccountWorkAndJoin()
                if (ownsRemote()) remote?.stopCleanupWork()
                true
            },
            CleanupStep.STALE_TEMP to { manager?.clearStaleTemporaryFiles() ?: true },
            CleanupStep.PLAYBACK to {
                var ok = manager?.clearAccountData(allLocalData = all) ?: true
                if (recovery != null) {
                    val owners = if (all) context.inventory.accounts else setOf(owner)
                    for (account in owners) {
                        if (account == owner && manager != null) continue
                        val retained = if (account in recovery.knownAccountScopes()) recovery.retainedManager(account) else null
                        val cleared = retained?.clearAccountData() ?: clearStoredPlaybackData(recovery.storeFor(account), recovery::restoreDestination)
                        if (!cleared) ok = false
                    }
                    if (all && !recovery.clearUnregisteredAccountData(context.inventory.accounts)) ok = false
                } else if (manager == null) {
                    if (!clearLegacyPlaybackWork(context.inventory, all)) ok = false
                }
                ok
            },
            CleanupStep.MANAGED_FILES to { platform.clearManagedTemporaryFiles() },
            CleanupStep.PRESENTATION to { platform.clearPresentation(all) },
            CleanupStep.WEB_SESSION to clearWeb,
            CleanupStep.PHYSICS_LAB to { security.physicsLabVault(owner)?.clear(); true },
            CleanupStep.PHYSICS_DEMO to { security.physicsDemoVault(owner)?.clear(); true },
            CleanupStep.PHY_VLAB to { security.clearPhyVlabCredentials(owner) },
            CleanupStep.CITEL to { security.citelVault(owner)?.clear(); true },
            CleanupStep.ALL_SERVICE_SECRETS to { security.clearAllServiceCredentials(context.inventory.accounts) },
            CleanupStep.PRIMARY_CREDENTIALS to { if (all) security.purge() else security.clear() },
            CleanupStep.REMOTE_TOKENS to {
                if (ownsRemote()) {
                    if (remote != null) remote.clearCleanupSecrets()
                    else security.remoteVault?.let { VaultRemoteSecretStore(it).clear() }
                }
                true
            },
            CleanupStep.REMOTE_RECORDS to {
                if (ownsRemote()) {
                    if (remote != null) remote.clearCleanupRecords(cache, all)
                    else {
                        val next = if (all) RemoteAgentConfig() else remoteStore.loadConfig().withoutAccountData()
                        remoteStore.resetBinding(); remoteStore.saveConfig(next)
                    }
                }
                true
            },
            CleanupStep.DEVICE_DATA to { platform.clearDeviceSettings(operation == CleanupOperation.PREPARE_UNINSTALL) },
            CleanupStep.DATABASE to {
                check(cacheAvailable)
                when (operation) {
                    CleanupOperation.ACCOUNT_CACHE -> graph?.assignmentCompletionStore?.clearWith { cache.clearAccountCache(owner) }
                        ?: run { cache.clearAccountCache(owner); true }
                    CleanupOperation.SIGN_OUT -> { cache.clearAccountData(owner); true }
                    else -> { cache.clearAll(); true }
                }
            },
            CleanupStep.SCHOOL_SESSION to { protocol?.logout() ?: true },
        ), context, recoveredRecord?.id)
    }

    private suspend fun clearLegacyPlaybackWork(inventory: CleanupInventory, all: Boolean): Boolean {
        var ok = platform.clearPlaybackTemporaryDestinations(inventory.playbackTemporaryDestinations)
        for (id in inventory.playbackTaskIds) {
            if (!id.matches(Regex("t[0-9]+-[0-9]+")) || runCatching {
                team.bjtuss.bjtuselfservice.shared.media.MediaWorkDirectory.forTask(id).deleteAll()
            }.isFailure) ok = false
        }
        val root = team.bjtuss.bjtuselfservice.shared.media.MediaWorkDirectory.root
        if (all) {
            if (runCatching { team.bjtuss.bjtuselfservice.shared.media.MediaWorkDirectory.purgeStale(workRoot = root) }.isFailure) ok = false
        } else if (kotlinx.io.files.SystemFileSystem.exists(root) && kotlinx.io.files.SystemFileSystem.list(root).isNotEmpty()) ok = false
        return ok
    }
}

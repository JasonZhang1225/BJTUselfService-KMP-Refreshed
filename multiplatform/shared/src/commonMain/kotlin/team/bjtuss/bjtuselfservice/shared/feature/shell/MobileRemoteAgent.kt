package team.bjtuss.bjtuselfservice.shared.feature.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.agent.remote.AppSessionRemoteExecutor
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentController
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentStore
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteDeviceKind
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteHostPlatform
import team.bjtuss.bjtuselfservice.shared.agent.remote.VaultRemoteSecretStore
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.sync.AppRefreshCoordinator
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin

/** 控制器先于会话创建，执行者通过它找到会话。 */
internal class MobileSessionHolder {
    var session: AuthenticatedSession? = null
}

internal class MobileRemoteHost(platform: PlatformInfo) : RemoteHostPlatform {
    override val deviceName: String = run {
        val system = if (platform.family == PlatformFamily.IOS) "iOS" else "Android"
        "${platform.deviceModel.ifBlank { system }}（$system）".take(64)
    }
    override val deviceKind: String = RemoteDeviceKind.MOBILE
}

internal class MobileRemoteAgent(val controller: RemoteAgentController, private val scope: CoroutineScope) {
    fun close() {
        controller.close()
        scope.cancel()
    }
}

/**
 * 手机/平板：每当用户在 App 里刷新完成（来源 APP），且“ChatGPT 插件”功能开着、已配对并开启同步，
 * 就用这次刷新的逐源结果上传一次快照。没有定时器，不轮询即时任务，App 进入后台后不再运行。
 */
internal fun createMobileRemoteAgent(
    platform: PlatformInfo,
    cacheStore: CacheStore,
    securityCoordinator: AccountSecurityCoordinator,
    refreshCoordinator: AppRefreshCoordinator?,
    holder: MobileSessionHolder,
): MobileRemoteAgent? {
    if (!platform.isMobile) return null
    val vault = securityCoordinator.remoteVault ?: return null
    val coordinator = refreshCoordinator ?: return null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = RemoteAgentController(
        store = RemoteAgentStore(cacheStore),
        secrets = VaultRemoteSecretStore(vault),
        executor = AppSessionRemoteExecutor(holder::session, cacheStore),
        scope = scope,
        host = MobileRemoteHost(platform),
    )
    scope.launch {
        coordinator.completed.collect { report ->
            val enabled = holder.session?.settingsModel?.state?.value?.preferences?.isAgentPluginEnabled(mobile = true) == true
            if (report.origin == RefreshOrigin.APP && enabled) runCatching { controller.uploadAfterRefresh(report) }
        }
    }
    return MobileRemoteAgent(controller, scope)
}

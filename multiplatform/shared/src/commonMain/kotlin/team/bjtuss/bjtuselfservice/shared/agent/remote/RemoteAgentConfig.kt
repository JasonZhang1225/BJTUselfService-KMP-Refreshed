package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlinx.serialization.Serializable
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore

/** 远程常驻组件的设置范围与默认值（计划 §4.3）。 */
object RemoteIntervals {
    const val DEFAULT_UPLOAD_SECONDS = 1800
    const val DEFAULT_POLL_SECONDS = 5
    val UPLOAD_RANGE = 60..86_400
    val POLL_RANGE = 1..300
}

/**
 * 电脑端远程设置（非机密，存在加密缓存的元数据里）。设备令牌与平台访问材料走系统安全存储
 * （[RemoteSecretStore]），不写在这里，也不进日志和命令参数。
 */
@Serializable
data class RemoteAgentConfig(
    /** 用户开启远程同步；配对成功但关闭时不上传、不轮询。 */
    val enabled: Boolean = false,
    val siteUrl: String? = null,
    val deviceId: String? = null,
    /** 本机生成的不透明绑定标识（不是学号），与 [accountScope] 对应。 */
    val accountBindingId: String? = null,
    /** 绑定时的学校账号（只存本机）；当前登录账号不同则暂停远程同步。 */
    val accountScope: String? = null,
    val scopes: List<String> = RemoteScope.DEFAULT.map { it.wire },
    val uploadIntervalSeconds: Int = RemoteIntervals.DEFAULT_UPLOAD_SECONDS,
    val pollIntervalSeconds: Int = RemoteIntervals.DEFAULT_POLL_SECONDS,
    /** 用户确认“所选数据会上传到自己的 Site，可被 Site 与 ChatGPT 读取”的时间。 */
    val consentAtEpochSeconds: Long? = null,
    val scopesRevision: Long = 1,
    /** Windows 窗口最小化或关闭后保留托盘；不改变远程同步授权。 */
    val minimizeToTray: Boolean = true,
) {
    val paired: Boolean get() = siteUrl != null && deviceId != null && accountBindingId != null && accountScope != null
    val runnable: Boolean get() = enabled && paired && consentAtEpochSeconds != null

    val scopeSet: Set<RemoteScope> get() = scopes.mapNotNull(RemoteScope::fromWire).toSet()

    /** 实际生效值：越界的间隔夹到允许范围，未知范围丢弃。 */
    fun normalized(): RemoteAgentConfig = copy(
        uploadIntervalSeconds = uploadIntervalSeconds.coerceIn(RemoteIntervals.UPLOAD_RANGE),
        pollIntervalSeconds = pollIntervalSeconds.coerceIn(RemoteIntervals.POLL_RANGE),
        scopes = RemoteScope.entries.filter { it.wire in scopes }.map { it.wire },
    )

    /** 解除绑定：保留间隔与范围偏好，清掉 Site 与设备身份。 */
    fun unpaired(): RemoteAgentConfig = copy(enabled = false, siteUrl = null, deviceId = null, accountBindingId = null, accountScope = null)
}

/**
 * 只接受 https 的 Site 地址（不新增明文 HTTP），去掉末尾斜杠；
 * 拒绝带用户信息、查询串或片段的地址。返回 null 表示无效。
 */
fun normalizeSiteUrl(input: String): String? {
    val trimmed = input.trim().trimEnd('/')
    val match = Regex("^https://([A-Za-z0-9.-]+)(:\\d{1,5})?(/[A-Za-z0-9._~/-]*)?$").matchEntire(trimmed) ?: return null
    val host = match.groupValues[1]
    if (host.isEmpty() || host.startsWith('.') || host.endsWith('.') || ".." in host) return null
    return trimmed
}

/** 设备令牌与平台访问材料：Windows 用 DPAPI；测试用内存实现。 */
interface RemoteSecretStore {
    suspend fun loadDeviceToken(): String?
    suspend fun saveDeviceToken(token: String)
    /** 平台机器访问材料（如 Sites 的服务访问凭据）；平台不需要时为 null。 */
    suspend fun loadPlatformAccess(): String?
    suspend fun savePlatformAccess(value: String?)
    /** 预配置访问材料必须绑定精确 Site，空表单不能把另一 Site 的凭据带过去。 */
    suspend fun loadPlatformAccessSiteUrl(): String? = null
    suspend fun savePlatformAccessSiteUrl(siteUrl: String?) {}
    suspend fun clear()
}

private const val REMOTE_METADATA_SCOPE = "remote-agent"
private const val CONFIG_KEY = "config_v1"
private const val STATE_KEY = "state_v1"
private const val PENDING_KEY = "pending_snapshot_v1"
private const val RESULTS_KEY = "task_results_v1"

/** 运行状态（跨重启保留）：序号、最近一次逐源报告、最近上传/连接/错误。 */
@Serializable
data class RemoteAgentPersistedState(
    val lastSequence: Long = 0,
    val lastUploadAt: Long? = null,
    val lastUploadAttemptAt: Long? = null,
    val lastRefreshAt: Long? = null,
    val lastSeenAt: Long? = null,
    val lastError: String? = null,
    /** 最近一次全量刷新的逐源状态（RefreshSource.wire → 状态）。 */
    val lastSourceStates: Map<String, RemoteSourceState> = emptyMap(),
)

/** 已完成任务的本地结果记录：同一请求被重新投递时直接重传结果，不重复访问学校。 */
@Serializable
data class RemoteStoredResult(
    val requestId: String,
    val storedAt: Long,
    val status: String,
    val result: kotlinx.serialization.json.JsonObject,
    val error: RemoteError? = null,
)

/** 设置与状态都存在本机加密缓存里（与业务缓存同一密钥），退出“清除全部离线数据”时一并删除。 */
class RemoteAgentStore(private val cache: CacheStore) {
    private fun read(key: String): String? = runCatching { cache.metadata(REMOTE_METADATA_SCOPE, key) }.getOrNull()?.takeIf(String::isNotBlank)
    private fun write(key: String, value: String) {
        cache.putMetadata(REMOTE_METADATA_SCOPE, key, value)
    }

    fun loadConfig(): RemoteAgentConfig = read(CONFIG_KEY)
        ?.let { runCatching { remoteJson.decodeFromString(RemoteAgentConfig.serializer(), it) }.getOrNull() }
        ?.normalized() ?: RemoteAgentConfig()

    fun saveConfig(config: RemoteAgentConfig) = write(CONFIG_KEY, remoteJson.encodeToString(RemoteAgentConfig.serializer(), config.normalized()))

    fun loadState(): RemoteAgentPersistedState = read(STATE_KEY)
        ?.let { runCatching { remoteJson.decodeFromString(RemoteAgentPersistedState.serializer(), it) }.getOrNull() }
        ?: RemoteAgentPersistedState()

    fun saveState(state: RemoteAgentPersistedState) = write(STATE_KEY, remoteJson.encodeToString(RemoteAgentPersistedState.serializer(), state))

    fun loadPending(): RemoteSnapshot? = read(PENDING_KEY)
        ?.let { runCatching { remoteJson.decodeFromString(RemoteSnapshot.serializer(), it) }.getOrNull() }

    fun savePending(snapshot: RemoteSnapshot?) =
        write(PENDING_KEY, snapshot?.let { remoteJson.encodeToString(RemoteSnapshot.serializer(), it) } ?: "")

    fun loadResults(): List<RemoteStoredResult> = read(RESULTS_KEY)
        ?.let { runCatching { remoteJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RemoteStoredResult.serializer()), it) }.getOrNull() }
        .orEmpty()

    fun saveResults(results: List<RemoteStoredResult>) =
        write(RESULTS_KEY, remoteJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(RemoteStoredResult.serializer()), results))

    /** 解除绑定：清掉待传快照、任务结果和运行状态（新绑定代次从头开始）。 */
    fun resetBinding() {
        savePending(null)
        saveResults(emptyList())
        saveState(RemoteAgentPersistedState())
    }
}

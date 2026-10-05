package team.bjtuss.bjtuselfservice.shared.feature.physicslab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault

data class PhysicsLabState(
    val enabled: Boolean = false,
    val username: String = "",
    val labs: List<PhysicsLab> = emptyList(),
    val refreshing: Boolean = false,
    val fromCache: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
    val synced: Boolean = false,
)

class PhysicsLabModel(
    private val scope: String,
    private val cache: CacheStore,
    private val vault: CredentialVault?,
    private val remote: PhysicsLabRemote,
) {
    private val mutableState = MutableStateFlow(PhysicsLabState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var initialized = false
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock
        val enabled = cache.metadata(scope, "physicslab.enabled") == "true"
        val credentials = try {
            if (cache.metadata(scope, "physicslab.configured") == "true") vault?.load()
            else { vault?.clear(); null }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val labs = runCatching { json.decodeFromString<List<PhysicsLab>>(cache.metadata(scope, "physicslab.results").orEmpty()) }.getOrDefault(emptyList())
        mutableState.value = PhysicsLabState(enabled, credentials?.username.orEmpty(), labs, fromCache = labs.isNotEmpty())
        initialized = true
    }
    suspend fun configure(username: String, password: String, enabled: Boolean) {
        initialize()
        mutex.withLock {
            try {
                if (enabled && (username.isBlank() || password.isBlank())) throw PhysicsLabFailure("请填写实验系统账号和密码。")
                if (username.isNotBlank() && password.isNotBlank()) {
                    val storage = vault ?: throw PhysicsLabFailure("当前平台安全存储不可用。")
                    storage.save(Credentials(username.trim(), password))
                }
                val changedAccount = username.trim() != mutableState.value.username && username.isNotBlank()
                if (changedAccount) cache.putMetadata(scope, "physicslab.results", "[]")
                cache.putMetadata(scope, "physicslab.enabled", enabled.toString())
                cache.putMetadata(scope, "physicslab.configured", "true")
                mutableState.value = mutableState.value.copy(enabled = enabled, username = username.trim(),
                    labs = if (changedAccount) emptyList() else mutableState.value.labs, failed = false,
                    message = if (enabled) null else "已保存，物理实验同步已关闭。")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(message = (e as? PhysicsLabFailure)?.userMessage ?: "安全保存失败，请重试。")
                return
            }
        }
        if (enabled) refresh()
    }
    suspend fun refresh() {
        initialize()
        mutex.withLock {
            if (!mutableState.value.enabled) return@withLock
            mutableState.value = mutableState.value.copy(refreshing = true, message = null, failed = false)
            try {
                val credentials = vault?.load() ?: throw PhysicsLabFailure("请先设置实验系统账号和密码。")
                val labs = withTimeout(25_000) { remote.fetch(credentials) }
                cache.putMetadata(scope, "physicslab.results", json.encodeToString(labs))
                mutableState.value = mutableState.value.copy(labs = labs, username = credentials.username, fromCache = false, failed = false, synced = true, message = "已同步 ${labs.size} 个实验。")
            } catch (e: TimeoutCancellationException) {
                mutableState.value = mutableState.value.copy(fromCache = true, failed = true, message = "连接超时，请连接校园网；继续使用缓存。")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(fromCache = true, failed = true, message = (e as? PhysicsLabFailure)?.userMessage ?: "无法连接实验系统，请连接校园网；继续使用缓存。")
            } finally { mutableState.value = mutableState.value.copy(refreshing = false) }
        }
    }
    suspend fun savedPassword(): String = try {
        if (cache.metadata(scope, "physicslab.configured") == "true") vault?.load()?.password.orEmpty() else ""
    } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
}

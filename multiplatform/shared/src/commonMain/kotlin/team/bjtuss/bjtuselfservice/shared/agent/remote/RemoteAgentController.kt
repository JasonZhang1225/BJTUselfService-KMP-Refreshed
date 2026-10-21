package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.agent.AGENT_TIME_ZONE
import team.bjtuss.bjtuselfservice.shared.agent.loadAgentSnapshot
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshRequest
import team.bjtuss.bjtuselfservice.shared.sync.cachedSourceSyncTime

/** 宿主平台能力：开机启动（默认关闭，由用户选择）与设备显示名。 */
interface RemoteHostPlatform {
    val deviceName: String
    val autostartSupported: Boolean
    val traySupported: Boolean get() = false
    fun isAutostartEnabled(): Boolean
    fun setAutostart(enabled: Boolean): Boolean
}

/**
 * 刷新交给 App 当前会话的公共刷新服务（与界面共享同一轮进度和会话，不另建执行者）；
 * 快照从同一份本机缓存生成。会话未就绪（静默登录中、未登录）时不执行。
 */
class AppSessionRemoteExecutor(
    private val sessionProvider: () -> AuthenticatedSession?,
    private val cache: CacheStore,
    private val now: () -> kotlin.time.Instant = { Clock.System.now() },
) : RemoteRefreshExecutor {
    private fun readySession(): AuthenticatedSession? =
        sessionProvider()?.takeIf { !it.entryLoggingIn && it.refreshCoordinator?.isClosed == false }

    override fun currentAccountScope(): String? = readySession()?.profile?.studentId

    override suspend fun refreshAll(remote: Boolean): RefreshReport? {
        val coordinator = readySession()?.refreshCoordinator ?: return null
        return coordinator.refresh(RefreshRequest(origin = if (remote) RefreshOrigin.REMOTE else RefreshOrigin.SCHEDULED))
    }

    override suspend fun query(task: RemoteTask, scopes: Set<RemoteScope>): RemoteQueryOutcome {
        fun failure(code: String) = RemoteQueryOutcome(RemoteTaskStatus.FAILED, JsonObject(emptyMap()), RemoteError(code, "无法完成查询，请检查授权、账号或在 App 中处理"))
        val session = readySession() ?: return failure("not_signed_in")
        val account = session.profile.studentId
        try {
            if (task.operation == RemoteOperation.CITEL_PROBLEM) {
                if (RemoteScope.ASSIGNMENTS !in scopes) return failure("scope_denied")
                if (task.arguments.keys.any { it !in setOf("taskId", "includeFullText") }) return failure("bad_request")
                val id = (task.arguments["taskId"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 } ?: return failure("bad_request")
                val fetch = task.arguments["includeFullText"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: return failure("bad_request") } ?: false
                val problem = session.citelModel?.remoteProblem(id, fetch) ?: return failure("problem_not_cached")
                if (currentAccountScope() != account) return failure("account_mismatch")
                return RemoteQueryOutcome(RemoteTaskStatus.SUCCEEDED, buildJsonObject {
                    put("taskId", id); put("fetchedAt", problem.fetchedAt)
                    put("problem", remoteJson.encodeToJsonElement(problem.problem))
                })
            }
            val query = parseRemoteQuery(task.arguments, scopes)
            val report = if (query.latest) session.refreshCoordinator?.refresh(RefreshRequest(sources = query.sources, origin = RefreshOrigin.REMOTE)) else null
            if (currentAccountScope() != account) return failure("account_mismatch")
            return withContext(Dispatchers.Default) {
                val instant = now()
                val snapshot = loadAgentSnapshot(cache, account, instant.toLocalDateTime(AGENT_TIME_ZONE).date)
                val states = report?.results?.associate { it.source.wire to RemoteSourceState(it.status.wire, it.fetchedAtEpochSeconds, it.reason) }.orEmpty()
                val content = buildRemoteSnapshotContent(snapshot, scopes, states, { cachedSourceSyncTime(cache, account, it) }, instant, query.date, query.sources)
                RemoteQueryOutcome(if (report?.allSucceeded == false) RemoteTaskStatus.PARTIAL else RemoteTaskStatus.SUCCEEDED, buildJsonObject {
                    put("freshness", if (query.latest) "latest" else "local")
                    put("data", projectRemoteQuery(content.data, query))
                    put("sources", remoteJson.encodeToJsonElement(content.sources.filterKeys { key -> query.sources.any { it.wire == key } }))
                })
            }
        } catch (cancel: CancellationException) { throw cancel
        } catch (error: IllegalArgumentException) { return failure(if (error.message == "scope_denied") "scope_denied" else "bad_request")
        } catch (_: Exception) { return failure("query_failed") }
    }

    override suspend fun snapshotContent(scopes: Set<RemoteScope>, lastStates: Map<String, RemoteSourceState>): RemoteSnapshotContent? {
        val account = currentAccountScope() ?: return null
        return withContext(Dispatchers.Default) {
            runCatching {
                val instant = now()
                val snapshot = loadAgentSnapshot(cache, account, instant.toLocalDateTime(AGENT_TIME_ZONE).date)
                buildRemoteSnapshotContent(snapshot, scopes, lastStates, { cachedSourceSyncTime(cache, account, it) }, instant)
            }.getOrNull()
        }
    }
}

/**
 * 电脑端远程功能的入口：配对、解除绑定、开关、范围、周期与开机启动；持有唯一的 [RemoteAgentRunner]。
 * 功能关闭时不轮询、不上传、不保活；关闭主窗口是否继续由宿主根据 [keepRunningInBackground] 决定。
 */
class RemoteAgentController(
    private val store: RemoteAgentStore,
    private val secrets: RemoteSecretStore,
    private val executor: RemoteRefreshExecutor,
    private val scope: CoroutineScope,
    val host: RemoteHostPlatform? = null,
    private val clock: () -> Long = { Clock.System.now().epochSeconds },
    private val clientFactory: (String) -> RemoteSiteClient = { url ->
        KtorRemoteSiteClient(url, deviceToken = { secrets.loadDeviceToken() }, platformAccess = { secrets.loadPlatformAccess() })
    },
    private val random: Random = Random.Default,
    secondMillis: Long = 1_000,
) {
    private val configState = MutableStateFlow(store.loadConfig())
    val config: StateFlow<RemoteAgentConfig> = configState.asStateFlow()

    private var cachedClient: Pair<String, RemoteSiteClient>? = null

    private fun client(): RemoteSiteClient? {
        val url = configState.value.siteUrl ?: return null
        cachedClient?.takeIf { it.first == url }?.let { return it.second }
        return runCatching { clientFactory(url) }.getOrNull()?.also { cachedClient = url to it }
    }

    val runner = RemoteAgentRunner(configState, ::client, executor, store, clock, random, secondMillis)
    val status get() = runner.status

    /** 开启了远程同步：关闭主窗口时应留在后台继续运行（明确退出才停止）。 */
    val keepRunningInBackground: Boolean get() = configState.value.runnable

    fun start() = runner.start(scope)

    fun close() = runner.stop()

    private fun update(transform: (RemoteAgentConfig) -> RemoteAgentConfig): RemoteAgentConfig {
        val next = transform(configState.value).normalized()
        store.saveConfig(next)
        configState.value = next
        return next
    }

    /**
     * 配对：需要已登录（绑定当前学校账号）、https Site 地址、一次性配对码和用户的上云同意。
     * 成功后设备令牌只存系统安全存储；返回 null 表示成功，否则返回给用户看的原因。
     */
    suspend fun pair(siteUrl: String, pairingCode: String, platformAccess: String?, scopes: Set<RemoteScope>, consent: Boolean): String? {
        val url = normalizeSiteUrl(siteUrl) ?: return "Site 地址必须是 https:// 开头的完整地址。"
        val code = pairingCode.uppercase().filter { it.isLetterOrDigit() }
        if (!Regex("^[A-Z2-9]{8}$").matches(code)) return "配对码是 8 位字母和数字，请在你的 Site 页面重新生成。"
        if (!consent) return "请先确认同意：所选数据会上传到你自己的 Site，可被 Site 与 ChatGPT 读取。"
        if (scopes.isEmpty()) return "至少选择一类允许上云的数据。"
        val account = executor.currentAccountScope() ?: return "请先在 App 里登录学校账号，再配对。"
        val bindingId = "bind_${randomToken(random, 20)}"
        return try {
            val enteredAccess = platformAccess?.trim()?.takeIf(String::isNotEmpty)
            if (enteredAccess != null || secrets.loadPlatformAccessSiteUrl() != url) {
                secrets.savePlatformAccess(enteredAccess)
                secrets.savePlatformAccessSiteUrl(if (enteredAccess != null) url else null)
            }
            val wireScopes = RemoteScope.entries.filter { it in scopes }.map { it.wire }
            val response = clientFactory(url).pair(RemotePairRequest(
                pairingCode = code,
                deviceName = host?.deviceName?.take(64)?.ifBlank { null } ?: "Windows 电脑",
                accountBindingId = bindingId,
                scopes = wireScopes,
            ))
            when (response) {
                is RemoteCall.Ok -> {
                    secrets.saveDeviceToken(response.value.deviceToken)
                    store.resetBinding()
                    cachedClient = null
                    update {
                        it.copy(enabled = true, siteUrl = url, deviceId = response.value.deviceId, accountBindingId = bindingId,
                            accountScope = account, scopes = wireScopes, consentAtEpochSeconds = clock(), scopesRevision = response.value.scopesRevision)
                    }
                    runner.clearStopped()
                    null
                }
                is RemoteCall.Failure -> when (response.code) {
                    "pairing_invalid" -> "配对码无效、已过期或已使用，请在 Site 重新生成。"
                    "pairing_locked" -> "配对失败次数过多，请 10 分钟后在 Site 重新生成配对码。"
                    "platform_auth_required" -> "电脑与私人 Site 的连接授权未就绪，请通过 AI 助手重新连接这台电脑。"
                    else -> if (response.kind == RemoteFailureKind.UNAUTHORIZED)
                        "Site 未通过配对身份验证，请重新生成配对码后重试。"
                    else RemoteAgentRunner.describe(response)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            "无法保存到系统安全存储，配对未完成。"
        }
    }

    /** 解除绑定：只清本机令牌与绑定；云端数据请在 Site 上撤销设备/清除（电脑离线时云端无法即时得知）。 */
    suspend fun unpair(): Boolean {
        update { it.unpaired() }
        store.resetBinding()
        cachedClient = null
        runner.clearStopped()
        return runCatching { secrets.clear() }.isSuccess
    }

    fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled && it.consentAtEpochSeconds != null) }
        if (enabled) runner.clearStopped()
    }

    fun setMinimizeToTray(enabled: Boolean) {
        update { it.copy(minimizeToTray = enabled) }
    }

    /** 改上云范围：本地立即生效，并尽快告诉 Site（离线时等下一次快照替换）。 */
    suspend fun setScopes(scopes: Set<RemoteScope>): String? {
        if (scopes.isEmpty()) return "至少保留一类数据；不想上传请关闭远程同步。"
        val next = update { it.copy(scopes = RemoteScope.entries.filter { s -> s in scopes }.map { s -> s.wire }, scopesRevision = it.scopesRevision + 1) }
        store.savePending(null)
        val api = client() ?: return null
        return when (val result = api.putScopes(next.scopes, next.scopesRevision)) {
            is RemoteCall.Ok -> null
            is RemoteCall.Failure -> "本机已更新范围，但暂时无法通知 Site：${RemoteAgentRunner.describe(result)}"
        }
    }

    /** 返回实际生效的间隔（越界值被夹到允许范围）。 */
    fun setIntervals(uploadSeconds: Int, pollSeconds: Int): RemoteAgentConfig =
        update { it.copy(uploadIntervalSeconds = uploadSeconds, pollIntervalSeconds = pollSeconds) }

    /** 立即刷新并上报一次（用户手动触发）。 */
    suspend fun uploadNow(): String = when (val outcome = runner.runUploadCycle(refreshFirst = true, remote = false)) {
        is RemoteUploadOutcome.Uploaded -> "已上传最新快照。"
        is RemoteUploadOutcome.Failed -> RemoteAgentRunner.describe(outcome.failure)
        is RemoteUploadOutcome.Skipped -> when (outcome.reason) {
            "not_signed_in" -> "App 尚未登录，暂不能上传。"
            "account_mismatch" -> "当前登录账号与绑定时不同，请重新配对。"
            "disabled" -> "远程同步未开启。"
            else -> "暂时无法生成快照。"
        }
    }
}

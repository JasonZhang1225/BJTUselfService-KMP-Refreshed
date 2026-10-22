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
import team.bjtuss.bjtuselfservice.shared.agent.AgentSourceState
import team.bjtuss.bjtuselfservice.shared.agent.AgentWeek
import team.bjtuss.bjtuselfservice.shared.agent.agentMailMessageJson
import team.bjtuss.bjtuselfservice.shared.agent.agentScheduleWindow
import team.bjtuss.bjtuselfservice.shared.agent.buildAgentDataset
import team.bjtuss.bjtuselfservice.shared.agent.loadAgentSnapshot
import team.bjtuss.bjtuselfservice.shared.agent.runAgentQuery
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshRequest
import team.bjtuss.bjtuselfservice.shared.sync.cachedSourceSyncTime

/** 宿主平台能力：设备显示名与托盘（开机启动见 AppStartupHost）。 */
interface RemoteHostPlatform {
    val deviceName: String
    val traySupported: Boolean get() = false
    /** [RemoteDeviceKind]：电脑常驻领取任务；手机只在刷新后上传。 */
    val deviceKind: String get() = RemoteDeviceKind.DESKTOP
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
            if (task.operation == RemoteOperation.MAIL_MESSAGE) {
                val messageId = parseRemoteMailMessage(task.arguments, scopes)
                val message = session.mailboxModel.fetchMessageForAgent(messageId)
                if (currentAccountScope() != account) return failure("account_mismatch")
                return RemoteQueryOutcome(RemoteTaskStatus.SUCCEEDED, agentMailMessageJson(message))
            }
            val live = parseRemoteLiveQuery(task.arguments, scopes)
            val refreshSources = remoteRefreshSources(live.query.command, scopes)
            val report = if (live.latest) session.refreshCoordinator?.refresh(RefreshRequest(sources = refreshSources, origin = RefreshOrigin.REMOTE)) else null
            if (currentAccountScope() != account) return failure("account_mismatch")
            return withContext(Dispatchers.Default) {
                val instant = now()
                val snapshot = loadAgentSnapshot(cache, account, instant.toLocalDateTime(AGENT_TIME_ZONE).date)
                val states = report?.results?.associate { it.source.wire to RemoteSourceState(it.status.wire, it.fetchedAtEpochSeconds, it.reason) }.orEmpty()
                val sources = remoteSourceStates(scopes, states) { cachedSourceSyncTime(cache, account, it) }
                    .filterKeys { key -> refreshSources.any { it.wire == key } }
                    .mapValues { (_, state) -> AgentSourceState(state.fetchedAt, if (report == null) null else state.status, if (report == null) null else state.reason) }
                val weeks = snapshot.academicWeeks.map { AgentWeek(it.week, it.startDate.toString()) }
                val (from, to) = agentScheduleWindow(live.query, weeks, instant.epochSeconds)
                val dataset = buildAgentDataset(snapshot, remoteDatasetParts(scopes), from, to, instant, gradesAllowed = true)
                val result = runAgentQuery(dataset, sources, live.query, instant.epochSeconds, if (live.latest) "live_latest" else "live_local")
                RemoteQueryOutcome(if (report?.allSucceeded == false) RemoteTaskStatus.PARTIAL else RemoteTaskStatus.SUCCEEDED, result)
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
    val keepRunningInBackground: Boolean get() = configState.value.runnable && !mobile

    /** 手机/平板：不常驻、不轮询任务，只在 App 每次刷新完成后上传一次快照。 */
    val mobile: Boolean get() = host?.deviceKind == RemoteDeviceKind.MOBILE

    /** 电脑端启动定时上传与任务轮询；手机端什么也不做（改由 [uploadAfterRefresh] 驱动）。 */
    fun start() {
        if (!mobile) runner.start(scope)
    }

    /** App 完成一次刷新后调用：用这次刷新的逐源结果生成并上传快照（未开启或未配对时直接跳过）。 */
    suspend fun uploadAfterRefresh(report: RefreshReport): RemoteUploadOutcome =
        runner.runUploadCycle(refreshFirst = false, remote = false, report = report)

    fun close() = runner.stop()

    private fun update(transform: (RemoteAgentConfig) -> RemoteAgentConfig): RemoteAgentConfig {
        val next = transform(configState.value).normalized()
        store.saveConfig(next)
        configState.value = next
        return next
    }

    /** 完整配对信息包含地址、一次性码与 Site 授权；敏感材料只存系统安全存储。 */
    suspend fun pair(pairingInfo: String, scopes: Set<RemoteScope>, consent: Boolean): String? {
        val info = runCatching { parseRemotePairingInfo(pairingInfo.trim(), clock()) }.getOrNull()
            ?: return "配对信息无效或已过期，请登录 Site 重新生成并复制完整长码（bjtuss-pair-v1:…），无需填写地址。"
        val url = info.siteUrl
        val code = info.pairingCode
        if (!consent) return "请先确认同意：所选数据会上传到你自己的 Site，可被 Site 与 ChatGPT 读取。"
        if (scopes.isEmpty()) return "至少选择一类允许上云的数据。"
        val account = executor.currentAccountScope() ?: return "请先在 App 里登录学校账号，再配对。"
        val bindingId = "bind_${randomToken(random, 20)}"
        return try {
            secrets.savePlatformAccess(info.platformAccess)
            secrets.savePlatformAccessSiteUrl(url)
            val wireScopes = RemoteScope.entries.filter { it in scopes }.map { it.wire }
            val api = clientFactory(url)
            if (mobile) {
                // 旧模板（0.4.0 之前）一个 Site 只认一台设备：手机配对会把电脑顶掉。先确认 Site 已升级。
                val health = api.health()
                if (health is RemoteCall.Failure) return if (health.code == "platform_auth_required")
                    "本站连接授权无效，请登录 Site 重新生成并复制完整配对信息。"
                else "无法读取 Site 版本，请检查地址和网络后重试：${RemoteAgentRunner.describe(health)}"
                val version = (health as RemoteCall.Ok).value.templateVersion
                if (!remoteTemplateAtLeast(version, REMOTE_MULTI_DEVICE_TEMPLATE)) {
                    return "你的 Site 模板版本是 ${version ?: "未知"}，还不支持电脑和手机同时连接。" +
                        "请先让 ChatGPT 把 Site 升级到 $REMOTE_MULTI_DEVICE_TEMPLATE 或更高版本，再配对手机。"
                }
            }
            val response = api.pair(RemotePairRequest(
                pairingCode = code,
                deviceName = host?.deviceName?.take(64)?.ifBlank { null } ?: "Windows 电脑",
                accountBindingId = bindingId,
                scopes = wireScopes,
                deviceKind = host?.deviceKind ?: RemoteDeviceKind.DESKTOP,
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
                    "pairing_invalid" -> "配对信息无效、已过期或已使用，请在 Site 重新生成。"
                    "pairing_locked" -> "配对失败次数过多，请 10 分钟后在 Site 重新生成完整配对信息。"
                    "platform_auth_required" -> "本站连接授权无效，请登录 Site 重新生成并复制完整配对信息。"
                    else -> if (response.kind == RemoteFailureKind.UNAUTHORIZED)
                        "Site 未通过配对身份验证，请重新生成完整配对信息后重试。"
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

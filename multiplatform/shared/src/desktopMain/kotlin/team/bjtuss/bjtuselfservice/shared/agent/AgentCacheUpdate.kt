package team.bjtuss.bjtuselfservice.shared.agent

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.bjtuss.bjtuselfservice.shared.agent.host.HeadlessRefreshDependencies
import team.bjtuss.bjtuselfservice.shared.agent.host.HeadlessRefreshHost
import team.bjtuss.bjtuselfservice.shared.agent.host.MAX_IPC_DEADLINE_MILLIS
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshExecutor
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshIpcCallResult
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshIpcCode
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshIpcOp
import team.bjtuss.bjtuselfservice.shared.agent.host.callRefreshIpc
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

private const val APP_LOCK_FILE = "app-running.lock"

/**
 * 刷新执行者锁：同一时刻只有持锁进程（App 窗口进程或无窗口命令行）登录学校、写缓存，
 * 避免两个进程各登录一次互相顶掉会话。持锁者通过本机 IPC（见 `agent.host`）接收其它进程的刷新请求；
 * GUI 启动时请无窗口持锁者交出执行权，等不到（旧版本命令行）时仍照常启动，不阻塞界面。
 */
class AgentAppLock private constructor(private val file: RandomAccessFile, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { file.close() }
    }

    companion object {
        fun tryAcquire(cacheDirectory: File): AgentAppLock? = runCatching {
            if (!cacheDirectory.isDirectory) return null
            val file = RandomAccessFile(File(cacheDirectory, APP_LOCK_FILE), "rw")
            val lock = runCatching { file.channel.tryLock() }.getOrNull()
            if (lock == null) {
                file.close()
                null
            } else AgentAppLock(file, lock)
        }.getOrNull()
    }
}

/** App 进程在启动时调用并一直持有（进程退出时系统自动释放）。 */
fun holdAgentAppLock(cacheDirectory: File): AgentAppLock? = AgentAppLock.tryAcquire(cacheDirectory)

private val reportJson = Json { prettyPrint = true }

/** 等 GUI 接手后再把请求交给它的最长时间。 */
private const val HANDOFF_REDELIVER_WAIT_MILLIS = 30_000L

/** 解析来源参数：默认全量；兼容旧的 grades,courses,homework,exams 写法。null 表示含未知来源。 */
internal fun parseRefreshSources(names: List<String>): Set<RefreshSource>? {
    if (names.isEmpty() || names == listOf("all")) return RefreshSource.entries.toSet()
    return names.map { name -> RefreshSource.fromWire(name) ?: return null }.toSet()
}

/**
 * `cache update [来源,...] [--json]`：默认刷新全部已启用且已配置的数据源。
 *
 * 1. 有正在运行的执行者（App 或另一个命令行）就经本机 IPC 交给它，复用其会话与进度；
 * 2. 否则拿执行者锁，用记住的账号在本进程无窗口执行同一套公共刷新服务；
 * 3. 锁被占着却连不上 IPC（旧版本 App 或正在启动）时，保持旧行为：提示在 App 里刷新。
 */
internal fun runCacheUpdate(
    args: List<String>,
    platform: AgentCliPlatform,
    ipcDeadlineMillis: Long = MAX_IPC_DEADLINE_MILLIS,
): AgentOutput {
    val json = "--json" in args
    if (args.any { it.startsWith("-") && it != "--json" }) {
        return AgentOutput("", "cache update 不支持这些选项。", AgentExit.USAGE)
    }
    val requested = args.filter { !it.startsWith("--") }.flatMap { it.split(',') }.map(String::trim).filter(String::isNotEmpty)
    val sources = parseRefreshSources(requested)
        ?: return AgentOutput("", "cache update 只支持：all,${RefreshSource.entries.joinToString(",") { it.wire }}。", AgentExit.USAGE)

    fun fail(reason: String, code: Int, errorCode: String? = null): AgentOutput =
        AgentOutput(if (json) reportJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("schemaVersion", AGENT_SCHEMA_VERSION); put("command", "cache update"); put("error", reason)
            errorCode?.let { put("code", it) }
        }) + "\n" else "", reason, code)

    fun delegate(): AgentOutput? = when (val call = callRefreshIpc(platform.cacheDirectory, RefreshIpcOp.REFRESH, sources,
        RefreshOrigin.CLI, ipcDeadlineMillis)) {
        RefreshIpcCallResult.NoServer -> null
        is RefreshIpcCallResult.Completed -> call.report?.toCacheUpdateOutput(json, call.executor ?: RefreshExecutor.APP.wire, AgentLoginOutcome.Ok)
            ?: fail("执行者没有返回刷新报告。", AgentExit.PARTIAL, RefreshIpcCode.BAD_REQUEST)
        is RefreshIpcCallResult.AcceptedWithoutResult ->
            fail("刷新请求已交给${executorLabel(call.executor)}，但在时限内没有收到结果；刷新可能仍在进行，请稍后用查询命令查看同步时间。",
                AgentExit.REFRESH_PENDING, RefreshIpcCode.TIMEOUT)
        is RefreshIpcCallResult.Rejected -> when (call.code) {
            RefreshIpcCode.PROTOCOL_MISMATCH -> fail(call.message, AgentExit.VERSION_MISMATCH, call.code)
            RefreshIpcCode.NOT_SIGNED_IN -> fail(call.message, AgentExit.LOGIN_FAILED, call.code)
            else -> fail("${executorLabel(call.executor)}拒绝了刷新请求：${call.message}", AgentExit.PARTIAL, call.code)
        }
    }

    delegate()?.let { return it }

    val securityFactory = platform.securityStore ?: return fail("当前平台不支持命令行刷新。", AgentExit.NO_DATA)
    val captchaFactory = platform.captchaRecognizer ?: return fail("当前平台不支持命令行刷新。", AgentExit.NO_DATA)
    val lock = AgentAppLock.tryAcquire(platform.cacheDirectory)
        // 锁和端点之间有短暂窗口（执行者刚启动或刚退出）：再试一次 IPC。
        ?: return delegate()
        ?: fail("交大自由行 App 正在运行，但没有响应刷新请求（可能是旧版本或正在启动）。请直接在 App 里刷新，或更新 App 后重试。",
            AgentExit.APP_RUNNING)
    var handedOff = false
    val output = lock.use {
        val databaseFile = File(platform.cacheDirectory, "bjtuselfservice_cache.db")
        if (!databaseFile.isFile) return@use fail("本机还没有交大自由行的缓存。请先打开 App 登录一次。", AgentExit.NO_DATA)
        val key = try {
            platform.loadCacheKey()
        } catch (_: Exception) {
            null
        } ?: return@use fail("无法读取系统安全存储中的缓存密钥。", AgentExit.NO_DATA)
        val store = try {
            openExistingCacheStore(databaseFile, key, readOnly = false)
        } catch (error: AgentCacheVersionException) {
            return@use fail(error.message.orEmpty(), AgentExit.NO_DATA)
        } catch (_: Exception) {
            return@use fail("无法打开本地缓存数据库，请稍后重试。", AgentExit.NO_DATA)
        } finally {
            key.fill(0)
        }
        try {
            runBlocking {
                val security = securityFactory()
                // 只读：未勾选“记住密码”时不登录，也不清除任何凭据（App 的 restore() 会清，这里不能用）。
                if (!security.preferences.shouldRememberCredentials()) {
                    return@runBlocking fail("App 没有记住密码，命令行无法自动登录。请在 App 登录时勾选“记住密码”，或直接在 App 里刷新。", AgentExit.LOGIN_FAILED)
                }
                val credentials = runCatching { security.credentialVault?.load() }.getOrNull()?.takeIf { it.isValid }
                    ?: return@runBlocking fail("系统安全存储里没有可用的登录信息。请在 App 里登录一次。", AgentExit.LOGIN_FAILED)
                val account = resolveAgentAccount(store, credentials.username)
                if (account != credentials.username) {
                    return@runBlocking fail("记住的账号与本机缓存的账号不一致，请在 App 里重新登录。", AgentExit.LOGIN_FAILED)
                }
                val result = HeadlessRefreshHost(
                    HeadlessRefreshDependencies(
                        cacheDirectory = platform.cacheDirectory,
                        cache = store,
                        credentials = credentials,
                        securityStore = security,
                        captchaRecognizer = captchaFactory(),
                        transport = createSchoolHttpTransport(),
                        platformFamily = team.bjtuss.bjtuselfservice.shared.currentPlatform().family,
                    ),
                ).run(sources, RefreshOrigin.CLI)
                when {
                    result.handedOff -> {
                        handedOff = true
                        null
                    }
                    result.accountMismatch -> fail("学校返回的账号与记住的账号不一致，请在 App 里重新登录。", AgentExit.LOGIN_FAILED)
                    result.report == null -> loginFailureOutput(result.login, json)
                    else -> result.report.toCacheUpdateOutput(json, RefreshExecutor.HEADLESS.wire, result.login)
                }
            }
        } finally {
            runCatching(store::close)
        }
    }
    if (!handedOff) return output ?: fail("刷新没有产生结果。", AgentExit.PARTIAL)
    // GUI 启动接手：锁已释放，等 App 的 IPC 就绪后把同一请求交给它（它登录后执行一次正常刷新）。
    val deadline = System.nanoTime() + HANDOFF_REDELIVER_WAIT_MILLIS * 1_000_000
    while (System.nanoTime() < deadline) {
        delegate()?.let { return it }
        Thread.sleep(500)
    }
    return fail("App 已启动并接手刷新，请在 App 里查看同步结果。", AgentExit.REFRESH_PENDING, RefreshIpcCode.HANDING_OFF)
}

private fun executorLabel(executor: String?) = when (executor) {
    RefreshExecutor.HEADLESS.wire -> "另一个正在运行的命令行刷新"
    else -> "交大自由行 App"
}

private fun loginFailureOutput(login: AgentLoginOutcome, json: Boolean): AgentOutput {
    val (text, reason) = when (login) {
        is AgentLoginOutcome.ManualRequired -> "manual_required" to
            "自动登录失败（${login.reason.name.lowercase()}）：验证码多次识别失败或密码已变更，请打开 App 手动登录。"
        AgentLoginOutcome.Network -> "network" to "无法连接学校系统，请检查网络后重试。"
        AgentLoginOutcome.Ok -> "ok" to "登录成功但没有执行刷新。"
    }
    val stdout = if (json) reportJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("schemaVersion", AGENT_SCHEMA_VERSION)
        put("command", "cache update")
        put("executor", RefreshExecutor.HEADLESS.wire)
        put("login", text)
        put("results", buildJsonObject {})
        put("sources", buildJsonArray {})
    }) + "\n" else "# 刷新结果\n\n- 登录：失败\n"
    return AgentOutput(stdout, reason, AgentExit.LOGIN_FAILED)
}

/**
 * 输出格式：`results` 保留旧版“来源 → ok/失败原因”映射（跳过的来源不出现），
 * 新增 `executor`（app/headless）与逐源 `sources`（状态、原因码、真实同步时间）。
 */
internal fun RefreshReport.toCacheUpdateOutput(json: Boolean, executor: String, login: AgentLoginOutcome): AgentOutput {
    val failed = results.filter { it.status == RefreshStatus.FAILED || it.status == RefreshStatus.NEEDS_USER_ACTION }
    val exit = if (failed.isEmpty()) AgentExit.OK else AgentExit.PARTIAL
    val stderr = when {
        failed.isEmpty() -> ""
        results.any { it.status == RefreshStatus.NEEDS_USER_ACTION } ->
            "部分数据需要在 App 里处理（重新登录或补填账号），失败项保留原缓存。"
        else -> "部分数据刷新失败，失败项保留原缓存。"
    }
    if (json) {
        val body = buildJsonObject {
            put("schemaVersion", AGENT_SCHEMA_VERSION)
            put("command", "cache update")
            put("executor", executor)
            put("login", if (login == AgentLoginOutcome.Ok) "ok" else "network")
            put("results", buildJsonObject {
                results.filter { it.status != RefreshStatus.SKIPPED }.forEach { result ->
                    put(result.source.wire, if (result.status == RefreshStatus.SUCCESS) "ok" else result.reason ?: result.status.wire)
                }
            })
            put("sources", buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("source", result.source.wire)
                        put("status", result.status.wire)
                        result.reason?.let { put("reason", it) }
                        result.message?.let { put("message", it) }
                        put("syncedAt", result.fetchedAtEpochSeconds?.let(::isoTime))
                    })
                }
            })
        }
        return AgentOutput(reportJson.encodeToString(JsonObject.serializer(), body) + "\n", stderr, exit)
    }
    val text = buildString {
        appendLine("# 刷新结果")
        appendLine()
        appendLine("- 执行者：" + if (executor == RefreshExecutor.APP.wire) "交大自由行 App" else "命令行（无窗口）")
        results.forEach { result ->
            val state = when (result.status) {
                RefreshStatus.SUCCESS -> "已更新"
                RefreshStatus.SKIPPED -> "跳过（${if (result.reason == "disabled") "已关闭" else "未配置"}）"
                RefreshStatus.NEEDS_USER_ACTION -> "需要在 App 里处理（${result.message ?: result.reason}）"
                RefreshStatus.FAILED -> "失败（${result.reason}），保留原缓存"
            }
            appendLine("- ${result.source.label}：$state")
        }
    }
    return AgentOutput(text, stderr, exit)
}

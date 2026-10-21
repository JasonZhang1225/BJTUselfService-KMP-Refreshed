package team.bjtuss.bjtuselfservice.shared.agent.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSourceResult
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

/**
 * 本机刷新 IPC：只监听 127.0.0.1 的随机端口，端口与一次性令牌写在用户私有缓存目录的
 * [ENDPOINT_FILE_NAME]（仅本人可读）。每个连接一行请求、先回“已受理”、再回结果；
 * 请求有长度上限、截止时间和 ID，只接受白名单操作，不执行任意命令。
 */
const val REFRESH_IPC_PROTOCOL = 1
internal const val ENDPOINT_FILE_NAME = "agent-ipc.json"
private const val MAX_LINE_BYTES = 64 * 1024
private const val REQUEST_READ_TIMEOUT_MILLIS = 5_000
private const val MAX_CONCURRENT_CONNECTIONS = 4
const val MAX_IPC_DEADLINE_MILLIS = 15 * 60_000L

/** 执行者身份：App 窗口进程，或 CLI 拉起的无窗口宿主。 */
enum class RefreshExecutor(val wire: String) { APP("app"), HEADLESS("headless") }

object RefreshIpcOp {
    const val REFRESH = "refresh"
    /** GUI 启动时请求无窗口宿主交出执行权。 */
    const val HANDOFF = "handoff"
    const val PING = "ping"
    /** 再次启动 App 时请已在运行（可能隐藏在后台）的窗口进程显示出来，避免出现第二个会话。 */
    const val SHOW = "show"
}

/** 可判定的拒绝/失败码（CLI JSON 输出原样带出）。 */
object RefreshIpcCode {
    const val UNAUTHORIZED = "unauthorized"
    const val PROTOCOL_MISMATCH = "protocol_mismatch"
    const val BAD_REQUEST = "bad_request"
    const val BUSY = "busy"
    const val NOT_SIGNED_IN = "not_signed_in"
    const val HANDING_OFF = "handing_off"
    const val TIMEOUT = "timeout"
    const val UNSUPPORTED = "unsupported"
}

@Serializable
internal data class RefreshIpcEndpoint(
    val protocol: Int,
    val port: Int,
    val token: String,
    val executor: String,
    val pid: Long,
)

@Serializable
internal data class RefreshIpcRequest(
    val protocol: Int,
    val token: String,
    val requestId: String,
    val op: String,
    val sources: List<String> = emptyList(),
    val origin: String = RefreshOrigin.CLI.wire,
    val deadlineMillis: Long = MAX_IPC_DEADLINE_MILLIS,
)

@Serializable
internal data class RefreshIpcResponse(
    val protocol: Int,
    val requestId: String,
    /** accepted / result / error */
    val type: String,
    val executor: String? = null,
    val code: String? = null,
    val message: String? = null,
    val report: RefreshIpcReport? = null,
)

@Serializable
data class RefreshIpcReport(
    val origin: String,
    val startedAt: Long,
    val finishedAt: Long,
    val results: List<RefreshIpcSourceResult>,
)

@Serializable
data class RefreshIpcSourceResult(
    val source: String,
    val status: String,
    val reason: String? = null,
    val message: String? = null,
    val fetchedAt: Long? = null,
)

fun RefreshReport.toIpcReport() = RefreshIpcReport(
    origin = origin.wire,
    startedAt = startedAtEpochSeconds,
    finishedAt = finishedAtEpochSeconds,
    results = results.map { RefreshIpcSourceResult(it.source.wire, it.status.wire, it.reason, it.message, it.fetchedAtEpochSeconds) },
)

/** 反序列化后还原为业务报告；未知来源/状态（新版对端）丢弃，不猜含义。 */
fun RefreshIpcReport.toRefreshReport(accountScope: String = ""): RefreshReport = RefreshReport(
    accountScope = accountScope,
    origin = RefreshOrigin.fromWire(origin) ?: RefreshOrigin.CLI,
    startedAtEpochSeconds = startedAt,
    finishedAtEpochSeconds = finishedAt,
    results = results.mapNotNull { result ->
        val source = RefreshSource.fromWire(result.source) ?: return@mapNotNull null
        val status = RefreshStatus.fromWire(result.status) ?: return@mapNotNull null
        RefreshSourceResult(source, status, result.reason, result.message, result.fetchedAt)
    },
)

sealed interface RefreshIpcOutcome {
    data class Completed(val report: RefreshReport) : RefreshIpcOutcome
    data class Rejected(val code: String, val message: String) : RefreshIpcOutcome
}

/** 执行者一侧的处理：App 进程交给当前会话，无窗口宿主交给自己的组合根。 */
interface RefreshIpcHandler {
    suspend fun refresh(sources: Set<RefreshSource>, origin: RefreshOrigin, deadlineMillis: Long): RefreshIpcOutcome

    /** 返回 true 表示同意交出执行权（随后释放进程锁）；App 进程总是拒绝。 */
    fun handoff(): Boolean = false

    /** 返回 true 表示已把窗口显示到前台；无窗口宿主返回 false。 */
    fun show(): Boolean = false
}

internal val ipcJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

private val random = SecureRandom()

internal fun newIpcToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

internal fun newRequestId(): String = ByteArray(12).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

private val requestIdPattern = Regex("^[A-Za-z0-9_-]{8,64}$")

private fun tokensEqual(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))

/** 读一行（不含换行）；超过上限或连接中断返回 null。 */
internal fun readBoundedLine(input: InputStream, limit: Int = MAX_LINE_BYTES): String? {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next == -1) return if (buffer.size() > 0) buffer.toString(Charsets.UTF_8) else null
        if (next == '\n'.code) return buffer.toString(Charsets.UTF_8)
        if (buffer.size() >= limit) return null
        buffer.write(next)
    }
}

private fun Socket.writeLine(response: RefreshIpcResponse) {
    val bytes = (ipcJson.encodeToString(RefreshIpcResponse.serializer(), response) + "\n").toByteArray(Charsets.UTF_8)
    getOutputStream().apply { write(bytes); flush() }
}

/** 写端点文件：先写临时文件再原子替换；POSIX 上限定为仅本人可读写。 */
internal fun writeEndpointFile(directory: File, endpoint: RefreshIpcEndpoint): File {
    val target = File(directory, ENDPOINT_FILE_NAME)
    val temp = File(directory, "$ENDPOINT_FILE_NAME.${ProcessHandle.current().pid()}.tmp")
    temp.delete()
    val path = temp.toPath()
    runCatching {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    }.getOrElse {
        // Windows：%LOCALAPPDATA% 本身只对当前用户开放 ACL。
        Files.createFile(path)
    }
    temp.writeText(ipcJson.encodeToString(RefreshIpcEndpoint.serializer(), endpoint))
    Files.move(path, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    return target
}

internal fun readEndpointFile(directory: File): RefreshIpcEndpoint? = runCatching {
    val file = File(directory, ENDPOINT_FILE_NAME)
    if (!file.isFile || file.length() > MAX_LINE_BYTES) return null
    ipcJson.decodeFromString(RefreshIpcEndpoint.serializer(), file.readText())
}.getOrNull()

/**
 * 执行者进程里的 IPC 服务。调用方须先持有 [team.bjtuss.bjtuselfservice.shared.agent.AgentAppLock]：
 * 锁决定谁是执行者，端点文件只是找到执行者的地址。
 */
class RefreshIpcServer private constructor(
    private val directory: File,
    private val server: ServerSocket,
    private val token: String,
    private val executor: RefreshExecutor,
    private val handler: RefreshIpcHandler,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(MAX_CONCURRENT_CONNECTIONS)
    private val closed = AtomicBoolean(false)
    private val acceptThread = Thread(::acceptLoop, "bjtu-refresh-ipc").apply { isDaemon = true }

    val port: Int get() = server.localPort

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                continue
            }
            if (!slots.tryAcquire()) {
                runCatching {
                    socket.use { it.writeLine(RefreshIpcResponse(REFRESH_IPC_PROTOCOL, "", "error", executor.wire, RefreshIpcCode.BUSY, "执行者繁忙，请稍后重试。")) }
                }
                continue
            }
            scope.launch {
                try {
                    socket.use { handle(it) }
                } finally {
                    slots.release()
                }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = REQUEST_READ_TIMEOUT_MILLIS
        val line = try {
            readBoundedLine(socket.getInputStream())
        } catch (_: SocketTimeoutException) {
            null
        } ?: return socket.writeLine(error("", RefreshIpcCode.BAD_REQUEST, "请求为空或过长。"))
        val request = runCatching { ipcJson.decodeFromString(RefreshIpcRequest.serializer(), line) }.getOrNull()
            ?: return socket.writeLine(error("", RefreshIpcCode.BAD_REQUEST, "请求格式错误。"))
        if (!tokensEqual(token, request.token)) {
            return socket.writeLine(error("", RefreshIpcCode.UNAUTHORIZED, "IPC 令牌不匹配。"))
        }
        if (request.protocol != REFRESH_IPC_PROTOCOL) {
            return socket.writeLine(error(request.requestId, RefreshIpcCode.PROTOCOL_MISMATCH,
                "命令行与 App 的刷新协议版本不一致（${request.protocol} ≠ $REFRESH_IPC_PROTOCOL），请更新到同一版本。"))
        }
        if (!requestIdPattern.matches(request.requestId) || request.deadlineMillis !in 1..MAX_IPC_DEADLINE_MILLIS) {
            return socket.writeLine(error("", RefreshIpcCode.BAD_REQUEST, "请求 ID 或截止时间无效。"))
        }
        when (request.op) {
            RefreshIpcOp.PING -> socket.writeLine(RefreshIpcResponse(REFRESH_IPC_PROTOCOL, request.requestId, "result", executor.wire))
            RefreshIpcOp.SHOW -> socket.writeLine(
                if (handler.show()) RefreshIpcResponse(REFRESH_IPC_PROTOCOL, request.requestId, "result", executor.wire)
                else error(request.requestId, RefreshIpcCode.UNSUPPORTED, "当前执行者没有窗口。"),
            )
            RefreshIpcOp.HANDOFF -> {
                val agreed = handler.handoff()
                socket.writeLine(
                    if (agreed) RefreshIpcResponse(REFRESH_IPC_PROTOCOL, request.requestId, "result", executor.wire, RefreshIpcCode.HANDING_OFF)
                    else error(request.requestId, RefreshIpcCode.UNSUPPORTED, "当前执行者不交出执行权。"),
                )
            }
            RefreshIpcOp.REFRESH -> {
                val sources = request.sources.mapNotNull(RefreshSource::fromWire).toSet()
                val origin = RefreshOrigin.fromWire(request.origin) ?: RefreshOrigin.CLI
                if (sources.isEmpty() || sources.size != request.sources.distinct().size) {
                    return socket.writeLine(error(request.requestId, RefreshIpcCode.BAD_REQUEST, "来源为空或包含未知来源。"))
                }
                socket.writeLine(RefreshIpcResponse(REFRESH_IPC_PROTOCOL, request.requestId, "accepted", executor.wire))
                val outcome = try {
                    withTimeoutOrNull(request.deadlineMillis) { handler.refresh(sources, origin, request.deadlineMillis) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    RefreshIpcOutcome.Rejected(RefreshIpcCode.BAD_REQUEST, "执行失败（${failure::class.simpleName}）。")
                } ?: RefreshIpcOutcome.Rejected(RefreshIpcCode.TIMEOUT, "执行超出截止时间。")
                runCatching {
                    socket.writeLine(
                        when (outcome) {
                            is RefreshIpcOutcome.Completed -> RefreshIpcResponse(REFRESH_IPC_PROTOCOL, request.requestId, "result",
                                executor.wire, report = outcome.report.toIpcReport())
                            is RefreshIpcOutcome.Rejected -> error(request.requestId, outcome.code, outcome.message)
                        },
                    )
                }
            }
            else -> socket.writeLine(error(request.requestId, RefreshIpcCode.UNSUPPORTED, "不支持的操作。"))
        }
    }

    private fun error(requestId: String, code: String, message: String) =
        RefreshIpcResponse(REFRESH_IPC_PROTOCOL, requestId, "error", executor.wire, code, message)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        scope.cancel()
        // 只删自己写的端点：另一个执行者可能已经接手并覆盖了文件。
        val current = readEndpointFile(directory)
        if (current?.token == token) File(directory, ENDPOINT_FILE_NAME).delete()
    }

    companion object {
        fun start(directory: File, executor: RefreshExecutor, handler: RefreshIpcHandler): RefreshIpcServer? = runCatching {
            if (!directory.isDirectory) return null
            val server = ServerSocket()
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8)
            val token = newIpcToken()
            val instance = RefreshIpcServer(directory, server, token, executor, handler)
            try {
                writeEndpointFile(directory, RefreshIpcEndpoint(REFRESH_IPC_PROTOCOL, server.localPort, token, executor.wire,
                    ProcessHandle.current().pid()))
            } catch (error: Exception) {
                server.close()
                throw error
            }
            instance.acceptThread.start()
            instance
        }.getOrNull()
    }
}

sealed interface RefreshIpcCallResult {
    /** 没有端点或连不上：没有正在运行的新版执行者。 */
    data object NoServer : RefreshIpcCallResult
    data class Rejected(val executor: String?, val code: String, val message: String) : RefreshIpcCallResult
    /** 已受理但在等待时限内没有收到结果：底层刷新可能仍在执行，不能当作未执行。 */
    data class AcceptedWithoutResult(val executor: String?) : RefreshIpcCallResult
    data class Completed(val executor: String?, val report: RefreshReport?, val code: String? = null) : RefreshIpcCallResult
}

/** CLI 一侧：把请求交给当前执行者并等待结果。 */
fun callRefreshIpc(
    directory: File,
    op: String,
    sources: Set<RefreshSource> = emptySet(),
    origin: RefreshOrigin = RefreshOrigin.CLI,
    deadlineMillis: Long = MAX_IPC_DEADLINE_MILLIS,
    connectTimeoutMillis: Int = 2_000,
): RefreshIpcCallResult {
    val endpoint = readEndpointFile(directory) ?: return RefreshIpcCallResult.NoServer
    val requestId = newRequestId()
    val socket = Socket()
    return try {
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), connectTimeoutMillis)
        socket.soTimeout = REQUEST_READ_TIMEOUT_MILLIS
        val request = RefreshIpcRequest(REFRESH_IPC_PROTOCOL, endpoint.token, requestId, op,
            RefreshSource.entries.filter { it in sources }.map { it.wire }, origin.wire, deadlineMillis.coerceIn(1, MAX_IPC_DEADLINE_MILLIS))
        socket.getOutputStream().apply {
            write((ipcJson.encodeToString(RefreshIpcRequest.serializer(), request) + "\n").toByteArray(Charsets.UTF_8))
            flush()
        }
        val first = readResponse(socket) ?: return RefreshIpcCallResult.NoServer
        if (first.type != "accepted") return first.toCallResult()
        // 已受理：结果最多等到截止时间再加一点余量。
        socket.soTimeout = (deadlineMillis + 5_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val second = try {
            readResponse(socket)
        } catch (_: SocketTimeoutException) {
            null
        } ?: return RefreshIpcCallResult.AcceptedWithoutResult(first.executor)
        second.toCallResult()
    } catch (_: ConnectException) {
        RefreshIpcCallResult.NoServer
    } catch (_: SocketTimeoutException) {
        RefreshIpcCallResult.Rejected(endpoint.executor, RefreshIpcCode.TIMEOUT, "执行者没有响应。")
    } catch (_: java.io.IOException) {
        RefreshIpcCallResult.NoServer
    } finally {
        runCatching { socket.close() }
    }
}

private fun readResponse(socket: Socket): RefreshIpcResponse? {
    val line = readBoundedLine(socket.getInputStream(), limit = 4 * MAX_LINE_BYTES) ?: return null
    return runCatching { ipcJson.decodeFromString(RefreshIpcResponse.serializer(), line) }.getOrNull()
}

private fun RefreshIpcResponse.toCallResult(): RefreshIpcCallResult = when (type) {
    "result" -> RefreshIpcCallResult.Completed(executor, report?.toRefreshReport(), code)
    else -> RefreshIpcCallResult.Rejected(executor, code ?: RefreshIpcCode.BAD_REQUEST, message.orEmpty())
}

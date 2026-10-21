package team.bjtuss.bjtuselfservice.shared.agent.host

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import team.bjtuss.bjtuselfservice.shared.agent.AgentAppLock
import team.bjtuss.bjtuselfservice.shared.agent.AgentCliPlatform
import team.bjtuss.bjtuselfservice.shared.agent.AgentExit
import team.bjtuss.bjtuselfservice.shared.agent.runCacheUpdate
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSourceResult
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

class RefreshIpcTest {
    private val directory: File = Files.createTempDirectory("refresh-ipc").toFile()
    private val servers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        servers.forEach { runCatching { it.close() } }
        directory.deleteRecursively()
    }

    private class FakeHandler(
        private val outcome: (Set<RefreshSource>, RefreshOrigin) -> RefreshIpcOutcome = { sources, origin ->
            RefreshIpcOutcome.Completed(RefreshReport("20990001", origin, 10, 20,
                sources.sortedBy { it.ordinal }.map { RefreshSourceResult(it, RefreshStatus.SUCCESS, fetchedAtEpochSeconds = 20) }))
        },
        private val delayMillis: Long = 0,
        private val agreeHandoff: Boolean = false,
    ) : RefreshIpcHandler {
        val calls = AtomicInteger()
        val handoffs = AtomicInteger()
        override suspend fun refresh(sources: Set<RefreshSource>, origin: RefreshOrigin, deadlineMillis: Long): RefreshIpcOutcome {
            calls.incrementAndGet()
            delay(delayMillis)
            return outcome(sources, origin)
        }

        override fun handoff(): Boolean {
            handoffs.incrementAndGet()
            return agreeHandoff
        }
    }

    private fun start(handler: RefreshIpcHandler, executor: RefreshExecutor = RefreshExecutor.APP): RefreshIpcServer =
        assertNotNull(RefreshIpcServer.start(directory, executor, handler)).also { servers += it }

    @Test
    fun refreshRoundTripReturnsReceiptAndReport() {
        val handler = FakeHandler()
        start(handler)
        val result = callRefreshIpc(directory, RefreshIpcOp.REFRESH, setOf(RefreshSource.GRADES, RefreshSource.CITEL))
        val completed = assertIs<RefreshIpcCallResult.Completed>(result)
        assertEquals("app", completed.executor)
        val report = assertNotNull(completed.report)
        assertEquals(listOf(RefreshSource.GRADES, RefreshSource.CITEL), report.results.map { it.source })
        assertEquals(RefreshOrigin.CLI, report.origin)
        assertEquals("", report.accountScope, "IPC 报告不传学号")
        assertEquals(1, handler.calls.get())
    }

    @Test
    fun endpointFileIsOwnerOnlyAndRemovedOnClose() {
        val server = start(FakeHandler())
        val file = File(directory, ENDPOINT_FILE_NAME)
        assertTrue(file.isFile)
        val permissions = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
        if (permissions != null) assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(permissions))
        server.close()
        assertFalse(file.exists())
        assertIs<RefreshIpcCallResult.NoServer>(callRefreshIpc(directory, RefreshIpcOp.PING))
    }

    @Test
    fun wrongTokenIsRejectedWithoutRunning() {
        val handler = FakeHandler()
        val server = start(handler)
        val endpoint = assertNotNull(readEndpointFile(directory))
        writeEndpointFile(directory, endpoint.copy(token = newIpcToken()))
        val result = assertIs<RefreshIpcCallResult.Rejected>(callRefreshIpc(directory, RefreshIpcOp.REFRESH, setOf(RefreshSource.GRADES)))
        assertEquals(RefreshIpcCode.UNAUTHORIZED, result.code)
        assertEquals(0, handler.calls.get())
        server.close()
    }

    @Test
    fun oversizedOrMalformedRequestIsRejected() {
        start(FakeHandler())
        val endpoint = assertNotNull(readEndpointFile(directory))
        Socket(InetAddress.getLoopbackAddress(), endpoint.port).use { socket ->
            socket.getOutputStream().write(ByteArray(70 * 1024) { 'a'.code.toByte() })
            socket.getOutputStream().write('\n'.code)
            val reply = readBoundedLine(socket.getInputStream())!!
            assertTrue(RefreshIpcCode.BAD_REQUEST in reply)
        }
        Socket(InetAddress.getLoopbackAddress(), endpoint.port).use { socket ->
            socket.getOutputStream().write("{\"protocol\":1}\n".toByteArray())
            assertTrue(RefreshIpcCode.BAD_REQUEST in readBoundedLine(socket.getInputStream())!!)
        }
    }

    @Test
    fun protocolMismatchAndUnknownSourcesAreRejected() {
        val handler = FakeHandler()
        start(handler)
        val endpoint = assertNotNull(readEndpointFile(directory))
        fun send(request: RefreshIpcRequest): String = Socket(InetAddress.getLoopbackAddress(), endpoint.port).use { socket ->
            socket.getOutputStream().write((ipcJson.encodeToString(RefreshIpcRequest.serializer(), request) + "\n").toByteArray())
            readBoundedLine(socket.getInputStream())!!
        }
        assertTrue(RefreshIpcCode.PROTOCOL_MISMATCH in send(RefreshIpcRequest(99, endpoint.token, newRequestId(), RefreshIpcOp.REFRESH, listOf("grades"))))
        assertTrue(RefreshIpcCode.BAD_REQUEST in send(RefreshIpcRequest(1, endpoint.token, newRequestId(), RefreshIpcOp.REFRESH, listOf("grades", "rm -rf"))))
        assertTrue(RefreshIpcCode.BAD_REQUEST in send(RefreshIpcRequest(1, endpoint.token, "x", RefreshIpcOp.REFRESH, listOf("grades"))))
        assertTrue(RefreshIpcCode.UNSUPPORTED in send(RefreshIpcRequest(1, endpoint.token, newRequestId(), "exec", listOf("grades"))))
        assertEquals(0, handler.calls.get())
    }

    @Test
    fun serverDeadlineProducesTimeoutResult() {
        start(FakeHandler(delayMillis = 5_000))
        val result = callRefreshIpc(directory, RefreshIpcOp.REFRESH, setOf(RefreshSource.GRADES), deadlineMillis = 200)
        assertEquals(RefreshIpcCode.TIMEOUT, assertIs<RefreshIpcCallResult.Rejected>(result).code)
    }

    @Test
    fun handoffIsOnlyGrantedByAgreeingExecutor() {
        val app = FakeHandler(agreeHandoff = false)
        val server = start(app)
        assertIs<RefreshIpcCallResult.Rejected>(callRefreshIpc(directory, RefreshIpcOp.HANDOFF))
        server.close()
        val headless = FakeHandler(agreeHandoff = true)
        start(headless, RefreshExecutor.HEADLESS)
        val granted = assertIs<RefreshIpcCallResult.Completed>(callRefreshIpc(directory, RefreshIpcOp.HANDOFF))
        assertEquals(RefreshIpcCode.HANDING_OFF, granted.code)
        assertEquals(1, headless.handoffs.get())
    }

    @Test
    fun staleEndpointIsTreatedAsNoServer() {
        writeEndpointFile(directory, RefreshIpcEndpoint(REFRESH_IPC_PROTOCOL, port = 1, token = newIpcToken(), executor = "app", pid = 1))
        assertIs<RefreshIpcCallResult.NoServer>(callRefreshIpc(directory, RefreshIpcOp.PING, connectTimeoutMillis = 500))
    }

    @Test
    fun cacheUpdateDelegatesToRunningExecutorWithoutTakingTheLock() {
        val handler = FakeHandler()
        start(handler)
        val holder = assertNotNull(AgentAppLock.tryAcquire(directory))
        holder.use {
            val output = runCacheUpdate(listOf("--json"), platform())
            assertEquals(AgentExit.OK, output.exitCode, output.stderr)
            assertTrue("\"executor\": \"app\"" in output.stdout)
            assertTrue("\"physicsLab\": \"ok\"" in output.stdout, "默认全量，包含独立账号来源")
        }
        assertEquals(1, handler.calls.get())
    }

    @Test
    fun cacheUpdateReportsAppNotSignedIn() {
        start(FakeHandler(outcome = { _, _ -> RefreshIpcOutcome.Rejected(RefreshIpcCode.NOT_SIGNED_IN, "App 正在运行但尚未登录。") }))
        val output = runCacheUpdate(listOf("grades"), platform())
        assertEquals(AgentExit.LOGIN_FAILED, output.exitCode)
        assertTrue("尚未登录" in output.stderr)
    }

    @Test
    fun appHostWaitsForHeadlessHandoffAndTakesOwnership() {
        val headlessLock = assertNotNull(AgentAppLock.tryAcquire(directory))
        val headless = object : RefreshIpcHandler {
            override suspend fun refresh(sources: Set<RefreshSource>, origin: RefreshOrigin, deadlineMillis: Long) =
                RefreshIpcOutcome.Rejected(RefreshIpcCode.HANDING_OFF, "")
            override fun handoff(): Boolean {
                // 无窗口宿主同意后很快释放锁。
                Thread { Thread.sleep(300); headlessLock.close() }.start()
                return true
            }
        }
        start(headless, RefreshExecutor.HEADLESS)
        val host = assertNotNull(AppRefreshHost.start(directory, sessionProvider = { null }, handoffWaitMillis = 5_000))
        servers += host
        assertTrue(host.ipcAvailable)
        assertEquals("app", readEndpointFile(directory)?.executor)
        assertNull(AgentAppLock.tryAcquire(directory), "App 已持有执行者锁")
    }

    @Test
    fun appHostWithoutHandoffReturnsNullAndAppStillStarts() {
        val stuck = assertNotNull(AgentAppLock.tryAcquire(directory))
        stuck.use {
            assertNull(AppRefreshHost.start(directory, sessionProvider = { null }, handoffWaitMillis = 300))
        }
    }

    private fun platform() = AgentCliPlatform(
        cacheDirectory = directory,
        loadCacheKey = { error("委托给执行者时不读缓存密钥") },
        rememberedUsername = { error("委托给执行者时不读账号") },
    )
}

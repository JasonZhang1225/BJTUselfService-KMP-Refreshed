package team.bjtuss.bjtuselfservice.shared.network

import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlin.test.*

class TransportConcurrencyTest {
    @Test fun readsOverlapWithinLimitAndAnotherHostDoesNotWait() = runBlocking {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            val slow = exchange.requestURI.path != "/fast"
            if (slow) {
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            try {
                val name = exchange.requestURI.path.removePrefix("/")
                exchange.responseHeaders.add("Set-Cookie", "$name=fixture; Path=/")
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("ok".encodeToByteArray()) }
            } finally { if (slow) active.decrementAndGet(); exchange.close() }
        }
        server.start()
        val transport = KtorSchoolHttpTransport(schoolHttpEngineFactory())
        val port = server.address.port
        try {
            val reads = (1..3).map { index -> async(start = CoroutineStart.UNDISPATCHED) {
                transport.execute(SchoolHttpRequest(SchoolHttpMethod.GET, "http://127.0.0.1:$port/r$index"))
            } }
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) }, "Two reads must overlap, not serialize")
            assertEquals(2, active.get(), "The third same-host request must wait")
            val other = withTimeout(3_000) {
                transport.execute(SchoolHttpRequest(SchoolHttpMethod.GET, "http://localhost:$port/fast"))
            }
            assertEquals(200, other.statusCode)
            release.countDown()
            withTimeout(5_000) { reads.awaitAll() }
            assertEquals(2, maximum.get())
            assertEquals(setOf("r1", "r2", "r3"), transport.sessionCookiesFor("http://127.0.0.1:$port/").map { it.name }.toSet())
        } finally {
            release.countDown()
            transport.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    @OptIn(io.ktor.utils.io.InternalAPI::class)
    fun clearPhyVlabSessionPreventsInFlightAdoptFromPersistingCookie() = runBlocking {
        val probeStarted = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        val factory = object : HttpClientEngineFactory<HttpClientEngineConfig> {
            override fun create(block: HttpClientEngineConfig.() -> Unit) = object : HttpClientEngineBase("probe-gate") {
                override val config = HttpClientEngineConfig().apply(block)
                override val supportedCapabilities = setOf(HttpTimeoutCapability)
                @io.ktor.utils.io.InternalAPI
                override suspend fun execute(data: HttpRequestData): HttpResponseData {
                    probeStarted.complete(Unit)
                    releaseProbe.await()
                    return HttpResponseData(
                        statusCode = HttpStatusCode.OK,
                        requestTime = GMTDate(),
                        headers = Headers.Empty,
                        version = HttpProtocolVersion.HTTP_1_1,
                        body = ByteReadChannel("ok"),
                        callContext = callContext(),
                    )
                }
            }
        }
        val transport = KtorSchoolHttpTransport(factory)
        try {
            val session = requireNotNull(PhyVlabLoginSession.fromCookie("fresh-session-token"))
            val adoptJob = async(Dispatchers.Default) {
                transport.adoptPhyVlabSession(session) { true }
            }
            withTimeout(5_000) { probeStarted.await() }
            transport.clearPhyVlabSession()
            releaseProbe.complete(Unit)

            assertFalse(withTimeout(5_000) { adoptJob.await() })
            val stored = transport.sessionCookiesFor("${SchoolEndpoints.PHYVLAB_ORIGIN}/my/courses.php")
            assertTrue(stored.none { it.name == "MoodleSession" && it.value.isNotBlank() })
        } finally {
            releaseProbe.complete(Unit)
            transport.close()
        }
    }
}

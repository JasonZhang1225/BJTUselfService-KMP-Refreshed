package team.bjtuss.bjtuselfservice.shared.network

import com.sun.net.httpserver.HttpServer
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
}

package team.bjtuss.bjtuselfservice.shared.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KtorSchoolStreamingTest {
    @Test
    fun receivesFirstBytesBeforeServerFinishesResponse() = runBlocking {
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, 8)
            exchange.responseBody.use { output ->
                output.write(byteArrayOf(1, 2, 3, 4))
                output.flush()
                release.await(10, TimeUnit.SECONDS)
                output.write(byteArrayOf(5, 6, 7, 8))
            }
        }
        server.start()
        val client = createSchoolHttpTransport()
        val first = CompletableDeferred<Unit>()
        val bytes = mutableListOf<Byte>()
        try {
            val job = async(Dispatchers.Default) {
                client.stream(SchoolHttpRequest(SchoolHttpMethod.GET, "http://127.0.0.1:${server.address.port}/file"),
                    { assertEquals(200, it.statusCode) },
                    { chunk -> bytes.addAll(chunk.toList()); first.complete(Unit) })
            }
            withTimeout(5_000) { first.await() }
            assertFalse(job.isCompleted)
            assertTrue(bytes.isNotEmpty())
            release.countDown()
            withTimeout(5_000) { job.await() }
            assertEquals((1..8).map(Int::toByte), bytes)
        } finally {
            release.countDown()
            server.stop(0)
            (client as? KtorSchoolHttpTransport)?.close()
        }
    }
}

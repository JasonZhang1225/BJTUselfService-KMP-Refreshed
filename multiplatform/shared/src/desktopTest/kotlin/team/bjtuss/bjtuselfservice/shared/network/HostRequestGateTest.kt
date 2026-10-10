package team.bjtuss.bjtuselfservice.shared.network

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostRequestGateTest {
    private val get = SchoolHttpRequest(SchoolHttpMethod.GET, "https://aa.bjtu.edu.cn/x")
    private val post = SchoolHttpRequest(SchoolHttpMethod.POST, "https://aa.bjtu.edu.cn/x")
    private fun response(status: Int) = SchoolHttpResponse(status, "https://aa.bjtu.edu.cn/x", emptyMap(), ByteArray(0))

    @Test fun defaultPolicySerializesAaAndWidensSmartPlatform() {
        val aa = defaultHostRequestPolicy(AA_HOST)
        assertEquals(1, aa.maxConcurrent)
        assertTrue(aa.minStartIntervalMillis > 0)
        assertTrue(aa.throttleRetryDelaysMillis.isNotEmpty())
        assertEquals(1, defaultHostRequestPolicy("cas.bjtu.edu.cn").maxConcurrent)
        assertEquals(5, defaultHostRequestPolicy("123.121.147.7").maxConcurrent)
        assertEquals(5, defaultHostRequestPolicy("bksycenter.bjtu.edu.cn").maxConcurrent)
        assertEquals(2, defaultHostRequestPolicy("mail.bjtu.edu.cn").maxConcurrent)
    }

    @Test fun throttledGetIsRetriedWithBackoffUntilSuccess() = runBlocking {
        val gate = HostRequestGate(HostRequestPolicy(1, throttleRetryDelaysMillis = listOf(5, 5, 5)))
        val calls = AtomicInteger()
        val result = gate.run(get) { response(if (calls.incrementAndGet() < 3) 503 else 200) }
        assertEquals(200, result.statusCode)
        assertEquals(3, calls.get())
    }

    @Test fun throttledGetGivesUpAfterConfiguredRetries() = runBlocking {
        val gate = HostRequestGate(HostRequestPolicy(1, throttleRetryDelaysMillis = listOf(5, 5)))
        val calls = AtomicInteger()
        val result = gate.run(get) { calls.incrementAndGet(); response(503) }
        assertEquals(503, result.statusCode)
        assertEquals(3, calls.get())
    }

    @Test fun postIsNeverReplayed() = runBlocking {
        val gate = HostRequestGate(HostRequestPolicy(1, throttleRetryDelaysMillis = listOf(5, 5)))
        val calls = AtomicInteger()
        assertEquals(503, gate.run(post) { calls.incrementAndGet(); response(503) }.statusCode)
        assertEquals(1, calls.get())
    }

    @Test fun startsAreSpacedAndConcurrencyIsBounded() = runBlocking {
        val gate = HostRequestGate(HostRequestPolicy(1, minStartIntervalMillis = 60))
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val starts = java.util.Collections.synchronizedList(mutableListOf<Long>())
        List(4) {
            async {
                gate.run(get) {
                    starts += System.nanoTime()
                    peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
                    delay(5)
                    active.decrementAndGet()
                    response(200)
                }
            }
        }.awaitAll()
        assertEquals(1, peak.get())
        val gaps = starts.sorted().zipWithNext { a, b -> (b - a) / 1_000_000 }
        assertTrue(gaps.all { it >= 55 }, "gaps=$gaps")
    }
}

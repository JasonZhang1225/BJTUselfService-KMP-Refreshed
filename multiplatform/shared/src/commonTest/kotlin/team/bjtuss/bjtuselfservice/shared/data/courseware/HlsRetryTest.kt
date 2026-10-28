package team.bjtuss.bjtuselfservice.shared.data.courseware

import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.network.*

class HlsRetryTest {
    private class FailingTransport(var succeedsAt: Int = Int.MAX_VALUE) : SchoolHttpTransport {
        var calls = 0
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            calls++
            return SchoolHttpResponse(if (calls >= succeedsAt) 200 else 503, request.url, body = byteArrayOf(1))
        }
        override suspend fun executeWithoutRedirects(request: SchoolHttpRequest) = execute(request)
        override fun clearSession() = Unit
    }

    @Test fun initialFailureRetriesThreeTimesWithExponentialBackoff() = runBlocking {
        val transport = FailingTransport()
        val waits = mutableListOf<Long>()
        val retries = mutableListOf<Int>()
        assertFailsWith<HlsDownloadException> {
            fetchHlsBytes(transport, "https://lmt2.bjtu.edu.cn:8880/sample.ts", 2000,
                waitBeforeRetry = { waits += it }, onRetry = { attempt, _ -> retries += attempt })
        }
        assertEquals(4, transport.calls)
        assertEquals(listOf(2000L, 4000L, 8000L), waits)
        assertEquals(listOf(1, 2, 3), retries)
    }

    @Test fun successStopsRetriesAndCancellationDoesNotSendAnotherRequest() = runBlocking {
        val transport = FailingTransport(3)
        val waits = mutableListOf<Long>()
        assertContentEquals(byteArrayOf(1), fetchHlsBytes(transport, "https://lmt2.bjtu.edu.cn:8880/sample.ts", 2000,
            waitBeforeRetry = { waits += it }))
        assertEquals(listOf(2000L, 4000L), waits)
        val cancelled = FailingTransport()
        assertFailsWith<CancellationException> {
            fetchHlsBytes(cancelled, "https://lmt2.bjtu.edu.cn:8880/sample.ts", 2000,
                waitBeforeRetry = { throw CancellationException() })
        }
        assertEquals(1, cancelled.calls)
    }
}

package team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class ClassroomOccupancyRemoteDataSourceTest {
    @Test
    fun publicCalendarTimeoutReturnsEmptyDatesInsteadOfCancellingStartup() = runBlocking {
        val source = SchoolClassroomOccupancyRemoteDataSource(HangingCalendarTransport(), requestDelayMillis = 0)
        val weeks = withTimeout(10_000) { source.fetchWeekDates() }
        assertTrue(weeks.isEmpty())
    }

    @Test
    fun callerTimeoutStillCancelsCalendarRequest() = runBlocking<Unit> {
        val source = SchoolClassroomOccupancyRemoteDataSource(HangingCalendarTransport(), requestDelayMillis = 0)
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(50) { source.fetchWeekDates() }
        }
    }

    @Test
    fun explicitCancellationReachesTransport() = runBlocking {
        val transport = HangingCalendarTransport()
        val source = SchoolClassroomOccupancyRemoteDataSource(transport, requestDelayMillis = 0)
        val request = launch { source.fetchWeekDates() }
        try {
            withTimeout(5_000) { transport.started.await() }
        } finally {
            request.cancelAndJoin()
        }
        assertTrue(transport.cancelled.isCompleted)
    }

    private class HangingCalendarTransport : SchoolHttpTransport {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = error("Calendar must use public transport")
        override suspend fun executePublic(request: SchoolHttpRequest): SchoolHttpResponse {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        override fun clearSession() = Unit
    }
}

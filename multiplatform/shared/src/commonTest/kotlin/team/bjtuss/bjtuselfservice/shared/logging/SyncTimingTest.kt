package team.bjtuss.bjtuselfservice.shared.logging

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class SyncTimingTest {
    @Test fun diagnosticRecordsDurationWithoutExceptionContents() = runBlocking {
        val previous = SyncTiming.sink
        val lines = mutableListOf<String>()
        try {
            SyncTiming.sink = { lines += it }
            assertEquals(17, SyncTiming.measure("fixture") { 17 })
            assertFailsWith<IllegalStateException> {
                SyncTiming.measure("failure") { error("private credential must not appear") }
            }
            assertFailsWith<CancellationException> {
                SyncTiming.measure("cancelled") { throw CancellationException("private body") }
            }
            assertTrue(lines.any { "module-end module=fixture" in it && "elapsed_ms=" in it && "outcome=completed" in it })
            assertTrue(lines.any { "outcome=failed" in it })
            assertTrue(lines.any { "outcome=cancelled" in it })
            assertFalse(lines.any { "private" in it })
        } finally { SyncTiming.sink = previous }
    }
}

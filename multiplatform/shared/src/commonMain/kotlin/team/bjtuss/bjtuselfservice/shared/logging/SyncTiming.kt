package team.bjtuss.bjtuselfservice.shared.logging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** Explicit diagnostic opt-in. Callers supply static labels, never URLs, bodies or credentials. */
object SyncTiming {
    var sink: ((String) -> Unit)? = null
    private val origin = TimeSource.Monotonic.markNow()
    private val ids = Mutex()
    private var sequence = 0L
    suspend fun nextId(): Long = ids.withLock { ++sequence }
    fun record(event: String) { sink?.invoke("[SyncTiming] t_ms=${origin.elapsedNow().inWholeMilliseconds} $event") }
    suspend fun <T> measure(module: String, action: suspend () -> T): T {
        if (sink == null) return action()
        val start = TimeSource.Monotonic.markNow()
        record("module-start module=$module")
        var outcome = "completed"
        try { return withContext(CoroutineName(module)) { action() } }
        catch (error: CancellationException) { outcome = "cancelled"; throw error }
        catch (error: Exception) { outcome = "failed"; throw error }
        finally { record("module-end module=$module elapsed_ms=${start.elapsedNow().inWholeMilliseconds} outcome=$outcome") }
    }
}

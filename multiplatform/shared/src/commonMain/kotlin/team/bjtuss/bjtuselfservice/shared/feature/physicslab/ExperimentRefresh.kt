package team.bjtuss.bjtuselfservice.shared.feature.physicslab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.bjtuss.bjtuselfservice.shared.runtime.AccountMutationLock

/** Shares one list refresh while serializing it with account settings. */
internal class ExperimentRefresh(
    private val scope: CoroutineScope?,
    private val operationMutex: Mutex,
    private val isUpToDate: () -> Boolean = { false },
    private val fetch: suspend () -> Unit,
) {
    private val lock = AccountMutationLock()
    private var pending: Request? = null

    private class Request(var onlyIfUnsynced: Boolean) {
        lateinit var completion: Deferred<Unit>
    }

    /** Returns whether the caller joined an existing request. */
    suspend fun run(onlyIfUnsynced: Boolean = false): Boolean {
        val sharedScope = scope ?: return false.also {
            execute(Request(onlyIfUnsynced), shared = false)
        }
        return coroutineScope {
            val (completion, joined) = lock.withLock {
                val active = pending
                if (active != null && !active.completion.isCompleted) {
                    if (!onlyIfUnsynced) active.onlyIfUnsynced = false
                    active.completion to true
                } else {
                    val next = Request(onlyIfUnsynced)
                    next.completion = sharedScope.async(start = CoroutineStart.LAZY) { execute(next, shared = true) }
                    pending = next
                    next.completion to false
                }
            }
            completion.await()
            joined
        }
    }

    private suspend fun execute(request: Request, shared: Boolean) = operationMutex.withLock {
        val skip = if (shared) {
            lock.withLock {
                if (request.onlyIfUnsynced && isUpToDate()) {
                    // A manual refresh arriving after this decision starts a new request.
                    if (pending === request) pending = null
                    true
                } else {
                    false
                }
            }
        } else {
            request.onlyIfUnsynced && isUpToDate()
        }
        if (!skip) {
            try {
                fetch()
            } finally {
                if (shared) {
                    // Retire the result before an account settings change can acquire the lock.
                    lock.withLock { if (pending === request) pending = null }
                }
            }
        }
    }
}

package team.bjtuss.bjtuselfservice.shared.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares a successful MIS check only while the related account refreshes overlap. */
internal class RefreshSessionProbe(private val probeSession: (suspend () -> Boolean)?) {
    private val lock = AccountMutationLock()
    private var active: Check? = null

    suspend fun <T> use(block: suspend ((suspend () -> Boolean)?) -> T): T {
        val probe = probeSession ?: return block(null)
        val check = lock.withLock {
            val current = active ?: Check(probe).also { active = it }
            current.users++
            current
        }
        try {
            return block(check::verify)
        } finally {
            lock.withLock {
                check.users--
                if (check.users == 0) active = null
            }
        }
    }

    private class Check(private val probe: suspend () -> Boolean) {
        var users = 0
        private val mutex = Mutex()
        private var verified = false

        suspend fun verify(): Boolean = mutex.withLock {
            // A failed check can trigger recovery and change the session before the next check.
            if (!verified) verified = probe()
            verified
        }
    }
}

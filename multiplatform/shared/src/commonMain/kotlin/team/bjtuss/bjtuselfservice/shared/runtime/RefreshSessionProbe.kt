package team.bjtuss.bjtuselfservice.shared.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares a successful MIS check only while the related account refreshes overlap. */
internal class RefreshSessionProbe(private val probeSession: (suspend () -> Boolean)?) {
    private val lock = AccountMutationLock()
    private var active: Check? = null

    interface Lease {
        val verify: (suspend () -> Boolean)?
        fun release()
    }

    fun acquire(): Lease {
        val probe = probeSession ?: return EmptyLease
        val check = lock.withLock {
            val current = active ?: Check(probe).also { active = it }
            current.users++
            current
        }
        return object : Lease {
            private var released = false
            override val verify: (suspend () -> Boolean) = check::verify
            override fun release() {
                lock.withLock {
                    if (released) return@withLock
                    released = true
                    check.users--
                    if (check.users == 0 && active === check) active = null
                }
            }
        }
    }

    suspend fun <T> use(block: suspend ((suspend () -> Boolean)?) -> T): T {
        val lease = acquire()
        return try {
            block(lease.verify)
        } finally {
            lease.release()
        }
    }

    private object EmptyLease : Lease {
        override val verify: (suspend () -> Boolean)? = null
        override fun release() = Unit
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

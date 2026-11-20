package team.bjtuss.bjtuselfservice.shared.runtime

import platform.Foundation.NSRecursiveLock

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual class AccountMutationLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }
}

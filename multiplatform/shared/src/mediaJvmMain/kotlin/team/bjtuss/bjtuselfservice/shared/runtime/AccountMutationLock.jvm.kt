package team.bjtuss.bjtuselfservice.shared.runtime

internal actual class AccountMutationLock actual constructor() {
    private val monitor = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(monitor, block)
}

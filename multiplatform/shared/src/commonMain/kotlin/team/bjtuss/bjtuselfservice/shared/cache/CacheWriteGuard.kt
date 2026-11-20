package team.bjtuss.bjtuselfservice.shared.cache

/** Serializes one complete synchronous mutation against its owner's revocation. */
interface CacheWriteGuard {
    fun <T> mutate(block: () -> T): T
}

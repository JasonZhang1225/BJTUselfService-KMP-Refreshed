package team.bjtuss.bjtuselfservice.shared.cache

/**
 * Protects text before it reaches the SQL driver.
 *
 * Mobile platforms already rely on application-private storage and use the identity
 * implementation. Desktop platforms inject a system-key-backed implementation so the
 * database never contains readable names, student IDs, grades, homework text or settings.
 */
interface CacheValueProtector {
    val isIdentity: Boolean get() = false

    /** Randomized authenticated encryption for ordinary values. */
    fun protect(value: String): String

    /**
     * Deterministic authenticated encryption for equality keys and primary-key columns.
     * It leaks equality but keeps the original value confidential and remains reversible.
     */
    fun protectStable(value: String): String

    fun unprotect(value: String): String

    /** Reversible protection for SQLite INTEGER columns that must remain numeric. */
    fun protectNumber(value: Long): Long

    fun unprotectNumber(value: Long): Long
}

object PlaintextCacheValueProtector : CacheValueProtector {
    override val isIdentity: Boolean = true
    override fun protect(value: String): String = value
    override fun protectStable(value: String): String = value
    override fun unprotect(value: String): String = value
    override fun protectNumber(value: Long): Long = value
    override fun unprotectNumber(value: Long): Long = value
}

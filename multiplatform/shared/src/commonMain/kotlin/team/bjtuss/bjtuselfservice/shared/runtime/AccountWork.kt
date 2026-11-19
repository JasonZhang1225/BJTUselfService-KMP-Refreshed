package team.bjtuss.bjtuselfservice.shared.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import team.bjtuss.bjtuselfservice.shared.cache.CacheWriteGuard

/** One login's admission, synchronous commit boundary and cancellable work tree. */
class AccountWork(parent: Job? = null) : CacheWriteGuard {
    private val lock = AccountMutationLock()
    private var active = true
    private val job = SupervisorJob(parent)
    private class Entry(val owner: AccountWork) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Entry>
    }

    val isActive: Boolean get() = lock.withLock { active }

    fun checkActive() {
        if (!isActive) throw CancellationException("Account session ended")
    }

    override fun <T> mutate(block: () -> T): T = lock.withLock {
        if (!active) throw CancellationException("Account session ended")
        block()
    }

    /** Keeps caller cancellation, while the account also owns and can join the operation. */
    suspend fun <T> run(block: suspend () -> T): T {
        coroutineContext.ensureActive()
        checkActive()
        if (coroutineContext[Entry]?.owner === this) return block()
        val task = CoroutineScope(coroutineContext.minusKey(Job) + job + Entry(this)).async { block() }
        return try {
            task.await().also { coroutineContext.ensureActive(); checkActive() }
        } finally {
            if (!coroutineContext.isActive) withContext(NonCancellable) { task.cancelAndJoin() }
        }
    }

    fun scope(dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob(job) + dispatcher + Entry(this))

    /** Revocation and an admitted synchronous database transaction cannot overlap. */
    fun stop() {
        lock.withLock { active = false }
        job.cancel(CancellationException("Account session ended"))
    }

    suspend fun join() { job.join() }

    internal companion object {
        suspend fun <T> commitCurrent(block: () -> T): T {
            coroutineContext.ensureActive()
            val owner = coroutineContext[Entry]?.owner
            return if (owner == null) block() else owner.mutate(block)
        }
    }

    internal fun <T> state(initial: T): MutableStateFlow<T> {
        val delegate = MutableStateFlow(initial)
        return object : MutableStateFlow<T> by delegate {
            override var value: T
                get() = delegate.value
                set(value) { lock.withLock { if (active) delegate.value = value } }
            // Frozen, retired models discard updates. Returning true lets Flow.update terminate.
            override fun compareAndSet(expect: T, update: T): Boolean = lock.withLock {
                if (active) delegate.compareAndSet(expect, update) else true
            }
            override suspend fun emit(value: T) { this.value = value }
            override fun tryEmit(value: T): Boolean { this.value = value; return true }
        }
    }
}

internal suspend fun <T> AccountWork?.inAccount(block: suspend () -> T): T =
    if (this == null) block() else run(block)

internal fun <T> AccountWork?.accountState(initial: T): MutableStateFlow<T> =
    this?.state(initial) ?: MutableStateFlow(initial)

internal suspend fun <T> accountCommit(block: () -> T): T = AccountWork.commitCurrent(block)

/** Only this platform primitive differs; account policy lives in AccountWork. */
internal expect class AccountMutationLock() {
    fun <T> withLock(block: () -> T): T
}

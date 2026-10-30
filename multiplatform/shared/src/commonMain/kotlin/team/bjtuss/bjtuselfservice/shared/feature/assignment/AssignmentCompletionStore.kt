package team.bjtuss.bjtuselfservice.shared.feature.assignment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTask

/** 本地标记的身份不依赖标题、截止时间或服务器提交状态。 */
@Serializable
data class AssignmentCompletionKey(
    val source: AssignmentSource,
    val courseId: Int,
    val assignmentId: Int,
    val programming: Boolean = false,
)

fun Homework.completionKey() = AssignmentCompletionKey(AssignmentSource.COURSE_PLATFORM, courseId, upId)

fun PhyVlabActivity.completionKey() = AssignmentCompletionKey(AssignmentSource.PHYVLAB, courseId, id)

fun CitelTask.completionKey() = AssignmentCompletionKey(AssignmentSource.CITEL, courseId, id, programming)

enum class AssignmentCompletionFailure { LOAD, SAVE }

data class AssignmentCompletionState(
    val completedKeys: Set<AssignmentCompletionKey> = emptySet(),
    val loaded: Boolean = false,
    val failure: AssignmentCompletionFailure? = null,
)

internal interface AssignmentCompletionPersistence {
    fun load(): Set<AssignmentCompletionKey>
    fun save(keys: Set<AssignmentCompletionKey>)
}

/**
 * 一个账号共用的手动完成状态；只持有本地存储，不修改学校数据或启动网络请求。
 * 写入成功后才发布新状态；读失败时禁止覆盖，调用 [reload] 成功后才能继续操作。
 */
class AssignmentCompletionStore internal constructor(private val persistence: AssignmentCompletionPersistence) {
    constructor(cache: CacheStore, accountScope: String) : this(CacheAssignmentCompletionPersistence(cache, accountScope))

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AssignmentCompletionState())
    val state: StateFlow<AssignmentCompletionState> = mutableState.asStateFlow()

    init {
        loadState()
    }

    suspend fun reload(): Boolean = mutex.withLock { loadState() }

    /** 与标记写入互斥地执行现有清缓存操作，随后重新读取，避免旧勾选重新写回。 */
    fun clearWith(clear: () -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            clear()
            loadState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadState()
            false
        } finally {
            mutex.unlock()
        }
    }

    suspend fun setCompleted(key: AssignmentCompletionKey, completed: Boolean): Boolean = mutex.withLock {
        val current = mutableState.value
        if (!current.loaded) return@withLock false
        val next = if (completed) current.completedKeys + key else current.completedKeys - key
        if (next == current.completedKeys) return@withLock true
        try {
            persistence.save(next)
            mutableState.value = AssignmentCompletionState(next, loaded = true)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = current.copy(failure = AssignmentCompletionFailure.SAVE)
            false
        }
    }

    private fun loadState(): Boolean = try {
        mutableState.value = AssignmentCompletionState(persistence.load().toSet(), loaded = true)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(loaded = false, failure = AssignmentCompletionFailure.LOAD)
        false
    }
}

private class CacheAssignmentCompletionPersistence(
    private val cache: CacheStore,
    accountScope: String,
) : AssignmentCompletionPersistence {
    private val account = accountScope.trim().also { require(it.isNotEmpty()) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): Set<AssignmentCompletionKey> {
        val saved = cache.metadata(account, KEY) ?: return emptySet()
        return json.decodeFromString<Set<AssignmentCompletionKey>>(saved)
    }

    override fun save(keys: Set<AssignmentCompletionKey>) {
        cache.putMetadata(account, KEY, json.encodeToString(keys))
    }

    companion object {
        private const val KEY = "assignment.manualCompletion.v1"
    }
}

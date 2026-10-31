package team.bjtuss.bjtuselfservice.shared.feature.assignment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
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

/**
 * 本地“忽略”标记的身份，不依赖标题、截止时间或服务器提交状态。
 * 类型与存储键沿用最初的“手动完成”命名，已保存的标记升级后直接按“已忽略”显示。
 */
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

/** 列表里刚忽略的一项，供撤销提示使用；[serial] 区分连续两次忽略同一项。 */
data class AssignmentIgnoreUndo(val key: AssignmentCompletionKey, val title: String, val serial: Long)

data class AssignmentCompletionState(
    val completedKeys: Set<AssignmentCompletionKey> = emptySet(),
    val loaded: Boolean = false,
    val failure: AssignmentCompletionFailure? = null,
    val savingKeys: Set<AssignmentCompletionKey> = emptySet(),
)

internal interface AssignmentCompletionPersistence {
    fun load(): Set<AssignmentCompletionKey>
    fun save(keys: Set<AssignmentCompletionKey>)
}

/**
 * 一个账号共用的作业忽略状态；只持有本地存储，不修改学校数据或启动网络请求。
 * 写入成功后才发布新状态；读失败时禁止覆盖，调用 [reload] 成功后才能继续操作。
 */
class AssignmentCompletionStore internal constructor(
    private val persistence: AssignmentCompletionPersistence,
    private val saveScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    constructor(cache: CacheStore, accountScope: String) : this(CacheAssignmentCompletionPersistence(cache, accountScope))

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AssignmentCompletionState())
    val state: StateFlow<AssignmentCompletionState> = mutableState.asStateFlow()
    private val mutableUndo = MutableStateFlow<AssignmentIgnoreUndo?>(null)
    val undo: StateFlow<AssignmentIgnoreUndo?> = mutableUndo.asStateFlow()
    private var undoSerial = 0L

    init {
        loadState()
    }

    suspend fun reload(): Boolean = mutex.withLock { loadState() }

    /**
     * 保存属于账号会话，即使列表项退出组合也继续执行。
     * 传入 [undoTitle] 且忽略成功时发布撤销提示；任何一次新的改动都会先收起旧提示。
     */
    fun requestSetCompleted(key: AssignmentCompletionKey, completed: Boolean, undoTitle: String? = null): Job? {
        while (true) {
            val current = mutableState.value
            if (!current.loaded || key in current.savingKeys) return null
            if (mutableState.compareAndSet(current, current.copy(savingKeys = current.savingKeys + key))) break
        }
        mutableUndo.value = null
        return saveScope.launch {
            try {
                if (setCompleted(key, completed) && completed && undoTitle != null) {
                    mutableUndo.value = AssignmentIgnoreUndo(key, undoTitle, ++undoSerial)
                }
            } finally { mutableState.update { it.copy(savingKeys = it.savingKeys - key) } }
        }
    }

    /** 撤销提示上的“撤销”：取消这次忽略，不再发布新的提示。 */
    fun undoIgnore(undo: AssignmentIgnoreUndo): Job? {
        if (mutableUndo.value?.serial != undo.serial) return null
        return requestSetCompleted(undo.key, false)
    }

    /** 提示到时或被新操作替换时收起；只收起仍是同一次的提示。 */
    fun dismissUndo(undo: AssignmentIgnoreUndo) {
        mutableUndo.compareAndSet(undo, null)
    }

    fun close() { saveScope.cancel() }

    /** 与标记写入互斥地执行现有清缓存操作，随后重新读取，避免旧勾选重新写回。 */
    fun clearWith(clear: () -> Unit): Boolean {
        if (mutableState.value.savingKeys.isNotEmpty()) return false
        if (!mutex.tryLock()) return false
        return try {
            if (mutableState.value.savingKeys.isNotEmpty()) return false
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
            mutableState.update { it.copy(completedKeys = next, loaded = true, failure = null) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(failure = AssignmentCompletionFailure.SAVE) }
            false
        }
    }

    private fun loadState(): Boolean = try {
        val loaded = persistence.load().toSet()
        mutableState.update { it.copy(completedKeys = loaded, loaded = true, failure = null) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        mutableState.update { it.copy(loaded = false, failure = AssignmentCompletionFailure.LOAD) }
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

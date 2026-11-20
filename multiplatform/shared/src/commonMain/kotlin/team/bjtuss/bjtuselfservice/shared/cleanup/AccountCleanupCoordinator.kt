package team.bjtuss.bjtuselfservice.shared.cleanup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable enum class CleanupOperation { ACCOUNT_CACHE, SIGN_OUT, ALL_LOCAL_DATA, REMOTE_UNPAIR, PREPARE_UNINSTALL }
@Serializable enum class CleanupStep(val failureLabel: String) {
    JOURNAL("清理记录"),
    DISCOVER_OWNERS("账号数据目录"), STOP_WORK("账号任务"), PLAYBACK("回放记录和临时文件"), STALE_TEMP("失效临时文件"),
    MANAGED_FILES("应用临时文件"), PRESENTATION("系统展示"), WEB_SESSION("网页会话"),
    PHYSICS_LAB("系统安全存储"), PHYSICS_DEMO("系统安全存储"), PHY_VLAB("系统安全存储"), CITEL("系统安全存储"),
    ALL_SERVICE_SECRETS("系统安全存储"), PRIMARY_CREDENTIALS("系统安全存储"),
    REMOTE_TOKENS("系统安全存储"), REMOTE_RECORDS("本地远程绑定"), DATABASE("本地缓存"),
    DEVICE_DATA("应用设置和文件"), SCHOOL_SESSION("学校服务端会话"),
}
@Serializable enum class CleanupStatus { SUCCEEDED, FAILED, BLOCKED, SKIPPED }
@Serializable data class CleanupItem(val step: CleanupStep, val status: CleanupStatus)

@Serializable class CleanupRequest(val operation: CleanupOperation, val accountScope: String) {
    init { require(accountScope.isNotBlank()) }
    override fun toString() = "CleanupRequest(operation=$operation, account=<redacted>)"
}

data class CleanupReport(val request: CleanupRequest, val items: List<CleanupItem>) {
    val succeeded: Boolean get() = items.all { it.status == CleanupStatus.SUCCEEDED || it.status == CleanupStatus.SKIPPED }
    val accountWorkStopped: Boolean get() = items.any { it.step == CleanupStep.STOP_WORK && it.status == CleanupStatus.SUCCEEDED }
    val localDataCleared: Boolean get() = items.filter { it.step != CleanupStep.SCHOOL_SESSION }.all { it.status == CleanupStatus.SUCCEEDED }
    val failureLabels: List<String> get() = items.filter { it.status == CleanupStatus.FAILED || it.status == CleanupStatus.BLOCKED }.map { it.step.failureLabel }.distinct()
}

/** Policy only: platform deletion code and UI instances never live in this coordinator. */
class AccountCleanupCoordinator(private var journal: CleanupJournal = MemoryCleanupJournal()) {
    private val mutex = Mutex()
    private val journalMutex = Mutex()
    private val reportState = MutableStateFlow<CleanupReport?>(null)
    val lastReport = reportState.asStateFlow()
    internal fun bindJournal(store: CleanupJournal) { journal = store }
    suspend fun pending(): List<CleanupRecord> = journalMutex.withLock { journal.load() }

    internal suspend fun admit(request: CleanupRequest, inventory: CleanupInventory): Boolean = withContext(NonCancellable) {
        journalMutex.withLock {
            try {
                val records = journal.load()
                if (records.none { it.request.operation == request.operation && it.request.accountScope == request.accountScope }) {
                    journal.save(records + CleanupRecord(newId(), request, inventory))
                }
                true
            } catch (_: Exception) { journalFailure(request); false }
        }
    }

    private fun newId() = "${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}"

    suspend fun execute(request: CleanupRequest, actions: Map<CleanupStep, suspend () -> Boolean>): CleanupReport =
        execute(request, actions, CleanupContext())

    internal suspend fun execute(request: CleanupRequest, actions: Map<CleanupStep, suspend () -> Boolean>, context: CleanupContext,
        recordId: String? = null): CleanupReport = mutex.withLock {
        // Admission remains cancellable; an already-started cleanup survives its originating page.
        withContext(NonCancellable) {
            val steps = steps(request.operation)
            val records = try { pending() } catch (_: Exception) {
                return@withContext journalFailure(request, steps.map { CleanupItem(it, CleanupStatus.BLOCKED) })
            }
            val existing = if (recordId != null) records.singleOrNull { it.id == recordId } else
                records.firstOrNull { it.request.operation == request.operation && it.request.accountScope == request.accountScope }
            if (recordId != null && existing == null) return@withContext journalFailure(request, emptyList())
            if (existing != null) context.inventory = existing.inventory
            var record = existing ?: CleanupRecord(newId(), request, context.inventory)
            suspend fun checkpoint(items: List<CleanupItem>): Boolean = try {
                record = record.copy(inventory = context.inventory, items = items.toList())
                journalMutex.withLock {
                    val latest = journal.load().filterNot { it.id == record.id }
                    journal.save(latest + record)
                }
                true
            } catch (_: Exception) { false }
            if (!checkpoint(record.items)) return@withContext journalFailure(request, steps.map { CleanupItem(it, CleanupStatus.BLOCKED) })
            val results = mutableListOf<CleanupItem>()
            var stopped = true
            for (step in steps) {
                val status = if (step == CleanupStep.SCHOOL_SESSION && context.recovering) CleanupStatus.SKIPPED
                else if (!stopped && step != CleanupStep.SCHOOL_SESSION) CleanupStatus.BLOCKED else {
                    // Quiescence must be re-established in this process. Successful deletion steps are not repeated.
                    val completed = step != CleanupStep.STOP_WORK && record.items.any { it.step == step && it.status == CleanupStatus.SUCCEEDED }
                    val ok = completed || try { actions[step]?.invoke() == true } catch (_: Exception) { false }
                    if (ok) CleanupStatus.SUCCEEDED else CleanupStatus.FAILED
                }
                results += CleanupItem(step, status)
                if (step in setOf(CleanupStep.STOP_WORK, CleanupStep.DISCOVER_OWNERS) && status != CleanupStatus.SUCCEEDED) stopped = false
                val remaining = record.items.filter { old -> results.none { it.step == old.step } }
                val progress = results.map { item ->
                    if (item.status == CleanupStatus.BLOCKED) record.items.firstOrNull {
                        it.step == item.step && it.status == CleanupStatus.SUCCEEDED
                    } ?: item else item
                }
                if (!checkpoint(progress + remaining)) {
                    return@withContext journalFailure(request, results + steps.drop(results.size).map { CleanupItem(it, CleanupStatus.BLOCKED) })
                }
            }
            val report = CleanupReport(request, results)
            // A server session belongs to this process; it cannot be replayed against a later login.
            if (report.localDataCleared) {
                try { journalMutex.withLock { journal.save(journal.load().filterNot { it.id == record.id }) } }
                catch (_: Exception) { return@withContext journalFailure(request, results) }
            }
            report.also { reportState.value = it }
        }
    }

    internal fun journalFailure(request: CleanupRequest = CleanupRequest(CleanupOperation.SIGN_OUT, "pending-cleanup"),
        items: List<CleanupItem> = emptyList()): CleanupReport =
        CleanupReport(request, items + CleanupItem(CleanupStep.JOURNAL, CleanupStatus.FAILED)).also { reportState.value = it }

    companion object {
        fun steps(operation: CleanupOperation): List<CleanupStep> = when (operation) {
            CleanupOperation.ACCOUNT_CACHE -> listOf(CleanupStep.DATABASE, CleanupStep.STALE_TEMP)
            CleanupOperation.REMOTE_UNPAIR -> listOf(CleanupStep.STOP_WORK, CleanupStep.REMOTE_TOKENS, CleanupStep.REMOTE_RECORDS)
            else -> buildList {
                add(CleanupStep.STOP_WORK)
                if (operation != CleanupOperation.SIGN_OUT) add(CleanupStep.DISCOVER_OWNERS)
                add(CleanupStep.PLAYBACK)
                add(CleanupStep.MANAGED_FILES)
                add(CleanupStep.PRESENTATION)
                add(CleanupStep.WEB_SESSION)
                if (operation == CleanupOperation.SIGN_OUT) {
                    addAll(listOf(CleanupStep.PHYSICS_LAB, CleanupStep.PHYSICS_DEMO, CleanupStep.PHY_VLAB, CleanupStep.CITEL))
                } else add(CleanupStep.ALL_SERVICE_SECRETS)
                add(CleanupStep.PRIMARY_CREDENTIALS)
                add(CleanupStep.REMOTE_TOKENS)
                add(CleanupStep.REMOTE_RECORDS)
                if (operation != CleanupOperation.SIGN_OUT) add(CleanupStep.DEVICE_DATA)
                add(CleanupStep.DATABASE)
                add(CleanupStep.SCHOOL_SESSION)
            }
        }
    }
}

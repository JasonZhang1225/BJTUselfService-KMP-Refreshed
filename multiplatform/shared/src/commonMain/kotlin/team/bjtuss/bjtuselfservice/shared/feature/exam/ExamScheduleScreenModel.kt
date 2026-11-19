package team.bjtuss.bjtuselfservice.shared.feature.exam

import team.bjtuss.bjtuselfservice.shared.runtime.AccountWork
import team.bjtuss.bjtuselfservice.shared.runtime.inAccount
import team.bjtuss.bjtuselfservice.shared.runtime.accountState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleRepository
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleSnapshot
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeRecorder
import team.bjtuss.bjtuselfservice.shared.domain.change.recordSafely
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule

enum class ExamScheduleContentSource {
    CACHE,
    NETWORK,
}

data class ExamScheduleUiState(
    val exams: List<ExamSchedule> = emptyList(),
    val selectedType: String? = null,
    val selectedExamId: Int? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val source: ExamScheduleContentSource? = null,
    val failure: ExamScheduleSyncFailure? = null,
) {
    val typeOptions: List<String>
        get() = exams.map(ExamSchedule::examType).filter(String::isNotBlank).distinct()

    val visibleExams: List<ExamSchedule>
        get() = selectedType?.let { type -> exams.filter { it.examType == type } } ?: exams

    val selectedExam: ExamSchedule?
        get() = exams.firstOrNull { it.id == selectedExamId }
}

class ExamScheduleScreenModel(
    private val repository: ExamScheduleRepository,
    private val changeRecorder: DataChangeRecorder<ExamSchedule>? = null,
    private val accountWork: AccountWork? = null,
) {
    private val mutableState = accountWork.accountState(ExamScheduleUiState())
    val state: StateFlow<ExamScheduleUiState> = mutableState.asStateFlow()

    private var cacheLoaded = false
    private var networkAutoSyncStarted = false
    private var refreshInFlight = false

    /**
     * @param refreshFromNetwork false 只读缓存；true 在登录成功后由 shell 触发自动同步。
     */
    suspend fun initialize(refreshFromNetwork: Boolean = true) = accountWork.inAccount { initializeInAccount(refreshFromNetwork = refreshFromNetwork) }

    private suspend fun initializeInAccount(refreshFromNetwork: Boolean = true) {
        if (!cacheLoaded) {
            cacheLoaded = true
            val cached = runCatching(repository::load).getOrNull()
            if (cached != null) {
                applySnapshot(
                    cached,
                    if (cached.exams.isEmpty()) null else ExamScheduleContentSource.CACHE,
                    null,
                )
            } else {
                mutableState.value = mutableState.value.copy(
                    isLoading = true,
                    failure = ExamScheduleSyncFailure.CACHE,
                )
            }
            if (!refreshFromNetwork) {
                mutableState.value = mutableState.value.copy(isLoading = false, isRefreshing = false)
            }
        }
        if (refreshFromNetwork && !networkAutoSyncStarted) {
            networkAutoSyncStarted = true
            refresh()
        }
    }

    suspend fun refresh() = accountWork.inAccount { refreshInAccount() }

    private suspend fun refreshInAccount() {
        if (refreshInFlight) return
        refreshInFlight = true
        val before = mutableState.value
        mutableState.value = before.copy(
            isLoading = before.exams.isEmpty(),
            isRefreshing = before.exams.isNotEmpty(),
            failure = null,
        )
        try {
            when (val result = repository.refresh()) {
                is ExamScheduleRefreshResult.Success -> {
                    changeRecorder.recordSafely(before.exams, result.snapshot.exams)
                    applySnapshot(result.snapshot, ExamScheduleContentSource.NETWORK, null)
                }
                is ExamScheduleRefreshResult.Failure -> applySnapshot(
                    result.snapshot,
                    if (result.snapshot.exams.isEmpty()) null else ExamScheduleContentSource.CACHE,
                    result.reason,
                )
            }
        } finally {
            refreshInFlight = false
            val current = mutableState.value
            if (current.isRefreshing || current.isLoading) {
                mutableState.value = current.copy(isRefreshing = false, isLoading = false)
            }
        }
    }

    fun selectType(type: String?) {
        val safeType = type?.takeIf { candidate ->
            mutableState.value.exams.any { it.examType == candidate }
        }
        mutableState.value = mutableState.value.copy(
            selectedType = safeType,
            selectedExamId = null,
        )
    }

    fun showExamDetails(examId: Int) {
        mutableState.value = mutableState.value.copy(selectedExamId = examId)
    }

    fun dismissExamDetails() {
        mutableState.value = mutableState.value.copy(selectedExamId = null)
    }

    fun dismissFailure() {
        mutableState.value = mutableState.value.copy(failure = null)
    }

    private fun applySnapshot(
        snapshot: ExamScheduleSnapshot,
        source: ExamScheduleContentSource?,
        failure: ExamScheduleSyncFailure?,
    ) {
        val current = mutableState.value
        val typeOptions = snapshot.exams.map(ExamSchedule::examType).toSet()
        val ids = snapshot.exams.mapTo(mutableSetOf(), ExamSchedule::id)
        mutableState.value = current.copy(
            exams = snapshot.exams,
            selectedType = current.selectedType?.takeIf(typeOptions::contains),
            selectedExamId = current.selectedExamId?.takeIf(ids::contains),
            isLoading = false,
            isRefreshing = false,
            source = source,
            failure = failure,
        )
    }
}

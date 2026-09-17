package team.bjtuss.bjtuselfservice.shared.feature.homework

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkDetailResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkRepository
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkOperationResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSnapshot
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeRecorder
import team.bjtuss.bjtuselfservice.shared.domain.change.recordSafely
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkDetail
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkSortOrder
import team.bjtuss.bjtuselfservice.shared.domain.homework.SubmittedHomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.domain.homework.dueSoonHomeworkCount
import team.bjtuss.bjtuselfservice.shared.domain.homework.filterHomework
import team.bjtuss.bjtuselfservice.shared.domain.homework.sortHomework
import team.bjtuss.bjtuselfservice.shared.domain.homework.stableKey

enum class HomeworkContentSource {
    CACHE,
    NETWORK,
}

/** 登录后自动同步：首轮 + 失败后再试，避免静默入场后握手抖动就亮红条。 */
internal const val HOMEWORK_AUTO_SYNC_MAX_ATTEMPTS = 3
internal const val HOMEWORK_AUTO_SYNC_RETRY_DELAY_MILLIS = 700L

data class HomeworkUiState(
    val homework: List<Homework> = emptyList(),
    val selectedCourses: Set<String> = emptySet(),
    val hideExpired: Boolean = false,
    val sortOrder: HomeworkSortOrder = HomeworkSortOrder.ORIGINAL,
    val selectedHomeworkKey: String? = null,
    val detail: HomeworkDetail? = null,
    val submittedAttachments: List<SubmittedHomeworkAttachment> = emptyList(),
    val isDetailLoading: Boolean = false,
    val isSubmittedAttachmentsLoading: Boolean = false,
    val detailFailure: HomeworkSyncFailure? = null,
    val fileFailure: HomeworkSyncFailure? = null,
    /** 服务端原文回绝原因，仅在 [fileFailure] 为 `SUBMIT_REJECTED` 时有值。 */
    val fileFailureMessage: String? = null,
    val isFileTransferInProgress: Boolean = false,
    val isSubmitting: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val source: HomeworkContentSource? = null,
    val failure: HomeworkSyncFailure? = null,
    val now: LocalDateTime = LocalDateTime(1970, 1, 1, 0, 0),
    val timeZone: TimeZone = TimeZone.UTC,
) {
    val courseOptions: List<String>
        get() = homework.map(Homework::courseName).filter(String::isNotBlank).distinct().sorted()

    val visibleHomework: List<Homework>
        get() = sortHomework(
            filterHomework(homework, selectedCourses, hideExpired, now),
            sortOrder,
        )

    val dueSoonCount: Int
        get() = dueSoonHomeworkCount(visibleHomework, now, timeZone)

    val selectedHomework: Homework?
        get() = homework.firstOrNull { it.stableKey() == selectedHomeworkKey }
}

class HomeworkScreenModel(
    private val repository: HomeworkRepository,
    private val changeRecorder: DataChangeRecorder<Homework>? = null,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val clock: Clock = Clock.System,
    private val nowProvider: () -> LocalDateTime = {
        clock.now().toLocalDateTime(timeZone)
    },
) {
    private val mutableState = MutableStateFlow(
        HomeworkUiState(timeZone = timeZone, now = nowProvider()),
    )
    val state: StateFlow<HomeworkUiState> = mutableState.asStateFlow()

    private var cacheLoaded = false
    private var networkAutoSyncStarted = false
    private val refreshMutex = Mutex()
    private var detailRequestKey: String? = null

    /**
     * @param refreshFromNetwork false 只读缓存；true 在登录成功后由 shell 触发自动同步。
     */
    suspend fun initialize(refreshFromNetwork: Boolean = true) {
        if (!cacheLoaded) {
            cacheLoaded = true
            val cached = runCatching(repository::load).getOrNull()
            if (cached != null) {
                applySnapshot(
                    snapshot = cached,
                    source = if (cached.homework.isEmpty()) null else HomeworkContentSource.CACHE,
                    failure = null,
                )
            } else {
                mutableState.value = mutableState.value.copy(
                    isLoading = true,
                    failure = HomeworkSyncFailure.CACHE,
                    now = nowProvider(),
                )
            }
            if (!refreshFromNetwork) {
                mutableState.value = mutableState.value.copy(isLoading = false, isRefreshing = false)
            }
        }
        if (refreshFromNetwork && !networkAutoSyncStarted) {
            networkAutoSyncStarted = true
            refreshWithRetry()
        }
    }

    suspend fun refresh() {
        if (!refreshMutex.tryLock()) return
        try {
            performRefresh()
        } finally {
            refreshMutex.unlock()
        }
    }

    private suspend fun performRefresh() {
        val before = mutableState.value
        mutableState.value = before.copy(
            isLoading = before.homework.isEmpty(),
            isRefreshing = before.homework.isNotEmpty(),
            failure = null,
            now = nowProvider(),
        )
        try {
            when (val result = repository.refresh()) {
                is HomeworkRefreshResult.Success -> {
                    changeRecorder.recordSafely(before.homework, result.snapshot.homework)
                    applySnapshot(result.snapshot, HomeworkContentSource.NETWORK, null)
                }
                is HomeworkRefreshResult.Failure -> applySnapshot(
                    result.snapshot,
                    if (result.snapshot.homework.isEmpty()) null else HomeworkContentSource.CACHE,
                    result.reason,
                )
            }
        } finally {
            // 取消/异常时也结束 loading，避免首页「同步中」假死。
            val current = mutableState.value
            if (current.isRefreshing || current.isLoading) {
                mutableState.value = current.copy(isRefreshing = false, isLoading = false)
            }
        }
    }

    /**
     * 连续刷新最多 [maxAttempts] 次；任一次成功即停。
     * 用于登录后自动同步：中间失败不长期停留，最后一次失败才保留 failure 横幅。
     */
    suspend fun refreshWithRetry(
        maxAttempts: Int = HOMEWORK_AUTO_SYNC_MAX_ATTEMPTS,
        delayMillis: Long = HOMEWORK_AUTO_SYNC_RETRY_DELAY_MILLIS,
    ) {
        require(maxAttempts >= 1)
        repeat(maxAttempts) { index ->
            refresh()
            if (mutableState.value.failure == null) return
            if (index < maxAttempts - 1) delay(delayMillis)
        }
    }

    fun toggleCourse(courseName: String) {
        val current = mutableState.value
        if (courseName !in current.courseOptions) return
        val selected = current.selectedCourses.toMutableSet().apply {
            if (!add(courseName)) remove(courseName)
        }
        detailRequestKey = null
        mutableState.value = current.copy(
            selectedCourses = selected,
            selectedHomeworkKey = null,
            detail = null,
            submittedAttachments = emptyList(),
            isDetailLoading = false,
            isSubmittedAttachmentsLoading = false,
            detailFailure = null,
            fileFailure = null,
            now = nowProvider(),
        )
    }

    fun clearCourseFilter() {
        detailRequestKey = null
        mutableState.value = mutableState.value.copy(
            selectedCourses = emptySet(),
            selectedHomeworkKey = null,
            detail = null,
            submittedAttachments = emptyList(),
            isDetailLoading = false,
            isSubmittedAttachmentsLoading = false,
            detailFailure = null,
            fileFailure = null,
            now = nowProvider(),
        )
    }

    fun setHideExpired(hideExpired: Boolean) {
        detailRequestKey = null
        mutableState.value = mutableState.value.copy(
            hideExpired = hideExpired,
            selectedHomeworkKey = null,
            detail = null,
            submittedAttachments = emptyList(),
            isDetailLoading = false,
            isSubmittedAttachmentsLoading = false,
            detailFailure = null,
            fileFailure = null,
            now = nowProvider(),
        )
    }

    fun cycleSortOrder() {
        val next = when (mutableState.value.sortOrder) {
            HomeworkSortOrder.ORIGINAL -> HomeworkSortOrder.ASCENDING
            HomeworkSortOrder.ASCENDING -> HomeworkSortOrder.DESCENDING
            HomeworkSortOrder.DESCENDING -> HomeworkSortOrder.ORIGINAL
        }
        setSortOrder(next)
    }

    fun setSortOrder(order: HomeworkSortOrder) {
        if (mutableState.value.sortOrder == order) return
        mutableState.value = mutableState.value.copy(sortOrder = order, now = nowProvider())
    }

    /**
     * 同步写入选中项与详情占位，不触碰网络。
     * 紧凑端点卡片要先写完选中再 push 详情二级页，不能等详情网络加载。
     */
    fun selectHomework(homeworkKey: String) {
        val item = mutableState.value.homework.firstOrNull { it.stableKey() == homeworkKey } ?: return
        detailRequestKey = homeworkKey
        mutableState.value = mutableState.value.copy(
            selectedHomeworkKey = homeworkKey,
            detail = HomeworkDetail(content = item.content, attachments = emptyList()),
            submittedAttachments = emptyList(),
            isDetailLoading = true,
            isSubmittedAttachmentsLoading = item.hasSubmittedWork(),
            detailFailure = null,
            fileFailure = null,
        )
    }

    suspend fun showDetails(homeworkKey: String) {
        val item = mutableState.value.homework.firstOrNull { it.stableKey() == homeworkKey } ?: return
        selectHomework(homeworkKey)
        when (val result = repository.loadDetail(item)) {
            is HomeworkDetailResult.Success -> if (detailRequestKey == homeworkKey) {
                mutableState.value = mutableState.value.copy(
                    detail = result.detail,
                    isDetailLoading = false,
                    detailFailure = null,
                )
            }
            is HomeworkDetailResult.Failure -> if (detailRequestKey == homeworkKey) {
                mutableState.value = mutableState.value.copy(
                    isDetailLoading = false,
                    detailFailure = result.reason,
                )
            }
        }
        if (detailRequestKey == homeworkKey && item.hasSubmittedWork()) {
            when (val result = repository.loadSubmittedAttachments(item)) {
                is HomeworkOperationResult.Success -> if (detailRequestKey == homeworkKey) {
                    mutableState.value = mutableState.value.copy(
                        submittedAttachments = result.value,
                        isSubmittedAttachmentsLoading = false,
                    )
                }
                is HomeworkOperationResult.Failure -> if (detailRequestKey == homeworkKey) {
                    mutableState.value = mutableState.value.copy(
                        isSubmittedAttachmentsLoading = false,
                        fileFailure = result.reason,
                    )
                }
            }
        } else if (detailRequestKey == homeworkKey) {
            mutableState.value = mutableState.value.copy(isSubmittedAttachmentsLoading = false)
        }
    }

    suspend fun downloadTeacherAttachment(
        attachmentId: Int,
    ): HomeworkOperationResult<HomeworkFileContent> {
        val state = mutableState.value
        val homework = state.selectedHomework
            ?: return HomeworkOperationResult.Failure(HomeworkSyncFailure.MALFORMED_RESPONSE)
        val attachment = state.detail?.attachments?.firstOrNull { it.id == attachmentId }
            ?: return HomeworkOperationResult.Failure(HomeworkSyncFailure.MALFORMED_RESPONSE)
        mutableState.value = state.copy(isFileTransferInProgress = true, fileFailure = null)
        return try {
            repository.downloadTeacherAttachment(homework.upId, attachment).also(::recordFileResult)
        } finally {
            mutableState.value = mutableState.value.copy(isFileTransferInProgress = false)
        }
    }

    suspend fun downloadSubmittedAttachment(
        attachmentId: String,
    ): HomeworkOperationResult<HomeworkFileContent> {
        val state = mutableState.value
        val attachment = state.submittedAttachments.firstOrNull { it.id == attachmentId }
            ?: return HomeworkOperationResult.Failure(HomeworkSyncFailure.MALFORMED_RESPONSE)
        mutableState.value = state.copy(isFileTransferInProgress = true, fileFailure = null)
        return try {
            repository.downloadSubmittedAttachment(attachment).also(::recordFileResult)
        } finally {
            mutableState.value = mutableState.value.copy(isFileTransferInProgress = false)
        }
    }

    suspend fun submitHomework(
        content: String,
        files: List<HomeworkFileContent>,
    ): HomeworkOperationResult<Unit> {
        val selected = mutableState.value.selectedHomework
            ?: return HomeworkOperationResult.Failure(HomeworkSyncFailure.MALFORMED_RESPONSE)
        if (files.isEmpty()) return HomeworkOperationResult.Failure(HomeworkSyncFailure.MALFORMED_RESPONSE)
        val key = selected.stableKey()
        mutableState.value = mutableState.value.copy(isSubmitting = true, fileFailure = null)
        return try {
            when (val result = repository.submitHomework(selected, content, files)) {
                is HomeworkOperationResult.Failure -> {
                    recordFileResult(result)
                    result
                }
                is HomeworkOperationResult.Success -> {
                    refreshMutex.lock()
                    try {
                        performRefresh()
                    } finally {
                        refreshMutex.unlock()
                    }
                    showDetails(key)
                    result
                }
            }
        } finally {
            mutableState.value = mutableState.value.copy(isSubmitting = false)
        }
    }

    fun dismissDetails() {
        detailRequestKey = null
        mutableState.value = mutableState.value.copy(
            selectedHomeworkKey = null,
            detail = null,
            submittedAttachments = emptyList(),
            isDetailLoading = false,
            isSubmittedAttachmentsLoading = false,
            detailFailure = null,
            fileFailure = null,
        )
    }

    fun dismissFailure() {
        mutableState.value = mutableState.value.copy(failure = null)
    }

    fun dismissFileFailure() {
        mutableState.value = mutableState.value.copy(fileFailure = null)
    }

    fun attachmentDownloadUrl(attachmentId: Int): String? = mutableState.value.selectedHomework
        ?.let { repository.attachmentDownloadUrl(it.upId, attachmentId) }

    private fun applySnapshot(
        snapshot: HomeworkSnapshot,
        source: HomeworkContentSource?,
        failure: HomeworkSyncFailure?,
    ) {
        val current = mutableState.value
        val courses = snapshot.homework.mapTo(mutableSetOf(), Homework::courseName)
        val keys = snapshot.homework.mapTo(mutableSetOf(), Homework::stableKey)
        val selectedKey = current.selectedHomeworkKey?.takeIf(keys::contains)
        mutableState.value = current.copy(
            homework = snapshot.homework,
            selectedCourses = current.selectedCourses.filterTo(mutableSetOf(), courses::contains),
            selectedHomeworkKey = selectedKey,
            detail = current.detail.takeIf { selectedKey != null },
            submittedAttachments = current.submittedAttachments.takeIf { selectedKey != null }.orEmpty(),
            isDetailLoading = current.isDetailLoading && selectedKey != null,
            isSubmittedAttachmentsLoading = current.isSubmittedAttachmentsLoading && selectedKey != null,
            detailFailure = current.detailFailure.takeIf { selectedKey != null },
            fileFailure = current.fileFailure.takeIf { selectedKey != null },
            isLoading = false,
            isRefreshing = false,
            source = source,
            failure = failure,
            now = nowProvider(),
        )
    }

    private fun recordFileResult(result: HomeworkOperationResult<*>) {
        if (result is HomeworkOperationResult.Failure) {
            mutableState.value = mutableState.value.copy(
                fileFailure = result.reason,
                fileFailureMessage = result.serverMessage,
            )
        }
    }
}

private fun Homework.hasSubmittedWork(): Boolean = idSnId != null || subStatus == "已提交"

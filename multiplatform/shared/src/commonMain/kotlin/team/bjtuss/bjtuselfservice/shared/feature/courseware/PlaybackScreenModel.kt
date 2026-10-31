package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareOperationResult
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRepository
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway

data class PlaybackUiState(
    val courseId: Int? = null,
    val lessons: List<PlaybackLesson> = emptyList(),
    val lessonsLoading: Boolean = false,
    val lessonsFailure: CoursewareSyncFailure? = null,
    val selectedLessonKey: String? = null,
    val detail: PlaybackDetail? = null,
    val detailLoading: Boolean = false,
    val detailFailure: CoursewareSyncFailure? = null,
    /** 发起下载时的即时提示（如保存面板不可用），由界面展示后清除。 */
    val downloadMessage: String? = null,
) {
    val selectedLesson: PlaybackLesson?
        get() = lessons.firstOrNull { it.stableKey == selectedLessonKey }
}

/**
 * 课程回放：选课程 → 选课节 → 读取各路录像地址 → 播放；桌面端另可入队下载/合成。
 * 课程列表沿用课件页的课程（同一个智慧教学会话）；下载在模型自己的作用域里进行，
 * 离开回放页不会中断，只有用户点“取消”或退出登录时才停止。
 */
class PlaybackScreenModel(
    private val repository: CoursewareRepository,
    val taskManager: PlaybackTaskManager,
    private val scope: CoroutineScope,
    private val reauthenticate: (suspend () -> Boolean)? = null,
) {
    private val mutableState = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = mutableState.asStateFlow()

    private val loadMutex = Mutex()
    private var detailJob: Job? = null
    private val lessonCache = mutableMapOf<Int, List<PlaybackLesson>>()

    /** 进入回放页或切换课程时调用；同一门课已加载过就直接复用（[force] 时重新拉取）。 */
    suspend fun showCourse(course: CoursewareCourse?, force: Boolean = false) {
        if (course == null) {
            mutableState.value = mutableState.value.copy(courseId = null, lessons = emptyList(), selectedLessonKey = null, detail = null)
            return
        }
        val current = mutableState.value
        if (!force && current.courseId == course.id && (current.lessons.isNotEmpty() || current.lessonsLoading)) return
        if (current.courseId != course.id) {
            detailJob?.cancel()
            mutableState.value = current.copy(
                courseId = course.id,
                lessons = lessonCache[course.id].orEmpty(),
                lessonsFailure = null,
                selectedLessonKey = null,
                detail = null,
                detailLoading = false,
                detailFailure = null,
            )
        }
        if (!force && lessonCache.containsKey(course.id)) return
        loadMutex.withLock {
            if (mutableState.value.courseId != course.id) return
            mutableState.value = mutableState.value.copy(lessonsLoading = true, lessonsFailure = null)
            val result = withRecovery { repository.loadPlaybackLessons(course) }
            if (mutableState.value.courseId != course.id) return
            mutableState.value = when (result) {
                is CoursewareOperationResult.Success -> {
                    lessonCache[course.id] = result.value
                    mutableState.value.copy(lessons = result.value, lessonsLoading = false)
                }
                is CoursewareOperationResult.Failure ->
                    mutableState.value.copy(lessonsLoading = false, lessonsFailure = result.reason)
            }
        }
    }

    fun selectLesson(course: CoursewareCourse, lessonKey: String) {
        val lesson = mutableState.value.lessons.firstOrNull { it.stableKey == lessonKey } ?: return
        detailJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedLessonKey = lessonKey,
            detail = null,
            detailFailure = null,
            detailLoading = lesson.hasRecording,
        )
        if (!lesson.hasRecording) return
        detailJob = scope.launch {
            val result = withRecovery { repository.loadPlaybackDetail(course, lesson) }
            if (mutableState.value.selectedLessonKey != lessonKey) return@launch
            mutableState.value = when (result) {
                is CoursewareOperationResult.Success -> mutableState.value.copy(detail = result.value, detailLoading = false)
                is CoursewareOperationResult.Failure ->
                    mutableState.value.copy(detailLoading = false, detailFailure = result.reason)
            }
        }
    }

    fun clearSelection() {
        detailJob?.cancel()
        mutableState.value = mutableState.value.copy(selectedLessonKey = null, detail = null, detailLoading = false, detailFailure = null)
    }

    /** 入队下载（会先弹系统保存面板）；结果说明放进 [PlaybackUiState.downloadMessage]。 */
    suspend fun download(
        detail: PlaybackDetail,
        streams: List<PlaybackStream>,
        gateway: HomeworkFileGateway,
        courseName: String,
        composite: Boolean,
        quality: PlaybackCompositeQuality = PlaybackCompositeQuality.HIGH,
    ): PlaybackEnqueueResult {
        val result = taskManager.enqueue(detail, streams, courseName, composite, gateway, quality)
        mutableState.value = mutableState.value.copy(
            downloadMessage = when (result) {
                is PlaybackEnqueueResult.Started -> null
                PlaybackEnqueueResult.Cancelled -> null
                is PlaybackEnqueueResult.Failed -> result.message
            },
        )
        return result
    }

    fun dismissDownloadMessage() {
        mutableState.value = mutableState.value.copy(downloadMessage = null)
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun <T> withRecovery(
        operation: suspend () -> CoursewareOperationResult<T>,
    ): CoursewareOperationResult<T> {
        val result = operation()
        if (result !is CoursewareOperationResult.Failure || result.reason != CoursewareSyncFailure.SESSION_EXPIRED) {
            return result
        }
        val recovered = try {
            reauthenticate?.invoke() == true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        return if (recovered) operation() else result
    }
}

internal fun playbackFileName(courseName: String, lesson: PlaybackLesson, label: String): String {
    val time = lesson.timeRange.replace(':', '.').replace(' ', '_')
    return listOf(courseName, lesson.name.takeIf(String::isNotBlank), time, label)
        .filterNotNull()
        .filter(String::isNotBlank)
        .joinToString("_") + ".mp4"
}

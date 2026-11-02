package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareOperationResult
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRepository
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNode
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareSnapshot
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class PlaybackScreenModelTest {
    private val course = CoursewareCourse(
        id = 7, name = "大学物理", courseNumber = "M1", groupId = "g", semesterCode = "2026-1",
        teacherId = null, children = emptyList(),
    )
    private val lesson = PlaybackLesson(
        id = 1, courseId = 7, name = "", timeRange = "2026-11-02 08:00-09:50",
        classroom = "SX305", summary = "", hasRecording = true,
    )

    /** 页面在课节返回前被关掉（调用方协程被取消）：再次进入同一门课必须重新加载，而不是一直转圈。 */
    @Test fun cancelledLessonLoadDoesNotLeaveCourseStuckLoading() = runBlocking {
        var calls = 0
        val firstStarted = CompletableDeferred<Unit>()
        val repository = repository {
            calls++
            if (calls == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
            CoursewareOperationResult.Success(listOf(lesson))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = PlaybackScreenModel(repository, taskManager(scope), scope)
            val page = launch(Dispatchers.Default) { model.showCourse(course) }
            firstStarted.await()
            page.cancelAndJoin()
            assertFalse(model.state.value.lessonsLoading)

            model.showCourse(course)
            assertEquals(listOf(lesson), model.state.value.lessons)
            assertFalse(model.state.value.lessonsLoading)
            assertEquals(2, calls)
        } finally {
            scope.cancel()
        }
    }

    /** 界面入口 selectCourse 在模型作用域里加载，不随调用方协程取消。 */
    @Test fun selectCourseLoadsInModelScope() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = PlaybackScreenModel(repository { CoursewareOperationResult.Success(listOf(lesson)) }, taskManager(scope), scope)
            model.selectCourse(course)
            withTimeout(5_000) {
                while (model.state.value.lessons.isEmpty()) delay(10)
            }
            assertEquals(7, model.state.value.courseId)
            assertFalse(model.state.value.lessonsLoading)
        } finally {
            scope.cancel()
        }
    }

    private fun repository(
        lessons: suspend () -> CoursewareOperationResult<List<PlaybackLesson>>,
    ) = object : CoursewareRepository {
        override fun load(): CoursewareSnapshot = error("Not used")
        override suspend fun refresh(): CoursewareRefreshResult = error("Not used")
        override suspend fun downloadResource(node: CoursewareNode): CoursewareOperationResult<HomeworkFileContent> =
            error("Not used")
        override suspend fun downloadTeachingCalendar(course: CoursewareCourse): CoursewareOperationResult<HomeworkFileContent> =
            error("Not used")
        override suspend fun loadPlaybackLessons(course: CoursewareCourse) = lessons()
    }

    private fun taskManager(scope: CoroutineScope) = PlaybackTaskManager(
        scope,
        HlsRecordingDownloader(UnusedTransport, 0),
        { error("Not used") },
        { null },
    )

    private object UnusedTransport : SchoolHttpTransport {
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = error("Not used")
        override fun clearSession() = Unit
    }
}

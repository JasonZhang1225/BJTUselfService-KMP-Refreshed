package team.bjtuss.bjtuselfservice.shared.feature.courseware

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import java.util.Collections
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareOperationResult
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRepository
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNode
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareSnapshot
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePickResult
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class PlaybackScreenModelTest {
    private val physics = course(7, "大学物理")
    private val algorithms = course(8, "数据结构")

    /** 页面在课节返回前被关掉（调用方协程被取消）：再次进入同一门课必须重新加载，而不是一直转圈。 */
    @Test fun cancelledLessonLoadDoesNotLeaveCourseStuckLoading() = runBlocking {
        var calls = 0
        val firstStarted = CompletableDeferred<Unit>()
        val repository = repository { course ->
            calls++
            if (calls == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
            CoursewareOperationResult.Success(listOf(lesson(course)))
        }
        withModel(repository) { model ->
            val page = launch(Dispatchers.Default) { model.showCourse(physics) }
            firstStarted.await()
            page.cancelAndJoin()
            assertFalse(model.state.value.lessonsLoading)

            model.showCourse(physics)
            assertEquals(listOf(lesson(physics)), model.state.value.lessons)
            assertFalse(model.state.value.lessonsLoading)
            assertEquals(2, calls)
        }
    }

    /** 上一门课还在请求时换课：旧请求被取消，新课不必排队等它超时，旧请求收尾也不改动新课状态。 */
    @Test fun switchingCourseReplacesTheStaleLessonRequest() = runBlocking {
        val physicsStarted = CompletableDeferred<Unit>()
        val physicsCancelled = CompletableDeferred<Unit>()
        val repository = repository { course ->
            if (course.id == physics.id) {
                physicsStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    physicsCancelled.complete(Unit)
                }
            }
            CoursewareOperationResult.Success(listOf(lesson(course)))
        }
        withModel(repository) { model ->
            model.selectCourse(physics)
            physicsStarted.await()
            model.selectCourse(algorithms)
            withTimeout(5_000) { physicsCancelled.await() }
            awaitState(model) { it.courseId == algorithms.id && it.lessons.isNotEmpty() }
            assertEquals(listOf(lesson(algorithms)), model.state.value.lessons)
            assertFalse(model.state.value.lessonsLoading)
        }
    }

    /** 新课排在请求锁后面时也显示加载中，不误报“这门课还没有录播课节”。 */
    @Test fun courseWaitingForTheRequestLockShowsLoading() = runBlocking {
        val physicsStarted = CompletableDeferred<Unit>()
        val releasePhysics = CompletableDeferred<Unit>()
        val repository = repository { course ->
            if (course.id == physics.id) {
                physicsStarted.complete(Unit)
                releasePhysics.await()
            }
            CoursewareOperationResult.Success(listOf(lesson(course)))
        }
        withModel(repository) { model ->
            val first = launch(Dispatchers.Default) { model.showCourse(physics) }
            physicsStarted.await()
            val second = launch(Dispatchers.Default) { model.showCourse(algorithms) }
            awaitState(model) { it.courseId == algorithms.id }
            assertTrue(model.state.value.lessonsLoading)
            assertTrue(model.state.value.lessons.isEmpty())

            releasePhysics.complete(Unit)
            first.join()
            second.join()
            assertEquals(listOf(lesson(algorithms)), model.state.value.lessons)
            assertFalse(model.state.value.lessonsLoading)
        }
    }

    /**
     * 真实 PlaybackScreen：默认选课的 effect 以 courseId 为键，选课后会立即重启。
     * 请求必须挂起跨过这次重启，释放后仍能加载完成，不能被 effect 的协程一起取消。
     */
    @Test fun defaultCourseSurvivesEffectRestartOnRealPlaybackScreen() {
        val requestStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = repository { course ->
            requestStarted.complete(Unit)
            release.await()
            CoursewareOperationResult.Success(listOf(lesson(course)))
        }
        val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = PlaybackScreenModel(repository, taskManager(modelScope), modelScope)
        val coursewareModel = CoursewareScreenModel(repository)
        runBlocking { coursewareModel.initialize() }
        var scene: ImageComposeScene? = null
        try {
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene(400, 800, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    PlatformAppTheme(false, false) {
                        Surface(Modifier.fillMaxSize()) {
                            PlaybackScreen(
                                coursewareState = coursewareModel.state.value,
                                coursewareModel = coursewareModel,
                                model = model,
                                fileGateway = NoFileGateway,
                                expanded = false,
                                onRefresh = {},
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            runBlocking { withTimeout(5_000) { requestStarted.await() } }
            // 渲染几帧让 courseId 的变化传到 effect 键上，effect 因此重启。
            repeat(5) {
                SwingUtilities.invokeAndWait { scene!!.render(System.nanoTime()).close() }
                Thread.sleep(20)
            }
            assertEquals(physics.id, model.state.value.courseId)
            assertTrue(model.state.value.lessonsLoading)

            release.complete(Unit)
            runBlocking { awaitState(model) { it.lessons.isNotEmpty() } }
            assertEquals(listOf(lesson(physics)), model.state.value.lessons)
            assertFalse(model.state.value.lessonsLoading)
        } finally {
            SwingUtilities.invokeAndWait { scene?.close() }
            modelScope.cancel()
        }
    }

    private suspend fun awaitState(model: PlaybackScreenModel, predicate: (PlaybackUiState) -> Boolean) {
        withTimeout(5_000) { while (!predicate(model.state.value)) delay(10) }
    }

    private suspend fun withModel(repository: CoursewareRepository, block: suspend CoroutineScope.(PlaybackScreenModel) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            scope.block(PlaybackScreenModel(repository, taskManager(scope), scope))
        } finally {
            scope.cancel()
        }
    }

    private fun repository(
        lessons: suspend (CoursewareCourse) -> CoursewareOperationResult<List<PlaybackLesson>>,
    ) = object : CoursewareRepository {
        private val snapshot = CoursewareSnapshot(listOf(physics, algorithms))
        val requested: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        override fun load(): CoursewareSnapshot = snapshot
        override suspend fun refresh(): CoursewareRefreshResult = CoursewareRefreshResult.Success(snapshot)
        override suspend fun downloadResource(node: CoursewareNode): CoursewareOperationResult<HomeworkFileContent> =
            error("Not used")
        override suspend fun downloadTeachingCalendar(course: CoursewareCourse): CoursewareOperationResult<HomeworkFileContent> =
            error("Not used")
        override suspend fun loadPlaybackLessons(course: CoursewareCourse): CoursewareOperationResult<List<PlaybackLesson>> {
            requested += course.id
            return lessons(course)
        }
    }

    private fun taskManager(scope: CoroutineScope) = PlaybackTaskManager(
        scope,
        HlsRecordingDownloader(UnusedTransport, 0),
        { error("Not used") },
        { null },
    )

    private fun course(id: Int, name: String) = CoursewareCourse(
        id = id, name = name, courseNumber = "M$id", groupId = "g", semesterCode = "2026-1",
        teacherId = null, children = emptyList(),
    )

    private fun lesson(course: CoursewareCourse) = PlaybackLesson(
        id = course.id.toLong(), courseId = course.id, name = "", timeRange = "2026-11-02 08:00-09:50",
        classroom = "SX305", summary = "", hasRecording = true,
    )

    private object UnusedTransport : SchoolHttpTransport {
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = error("Not used")
        override fun clearSession() = Unit
    }

    private object NoFileGateway : HomeworkFileGateway {
        override val isAvailable = false
        override suspend fun pickFiles() = HomeworkFilePickResult.Cancelled
        override suspend fun saveFile(file: HomeworkFileContent) = HomeworkFileSaveResult.Cancelled
    }
}

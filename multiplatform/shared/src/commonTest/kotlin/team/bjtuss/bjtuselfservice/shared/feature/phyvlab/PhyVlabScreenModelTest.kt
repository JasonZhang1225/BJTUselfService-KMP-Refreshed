package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabActivitiesResult
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabAssignmentDetailResult
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabCacheSnapshot
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabCachedAssignmentDetail
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabCoursesResult
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabEventsResult
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabLocalDataSource
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabRepository
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSessionProtocol
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSubmissionResult
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAssignmentDetail
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabCourse
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class PhyVlabScreenModelTest {
    @Test
    fun assignmentDetailShowsCacheImmediatelyAndRefreshesItInBackground() = runBlocking {
        val course = PhyVlabCourse(
            id = 72,
            name = "大学物理I_(2026春)",
            category = "自然科学",
            progressPercent = 8,
            courseUrl = "https://phyvlab.bjtu.edu.cn/course/view.php?id=72",
        )
        val activity = PhyVlabActivity(
            id = 3689,
            courseId = course.id,
            courseName = course.name,
            title = "Chap 25-26",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689",
        )
        val cachedDetail = PhyVlabAssignmentDetail(
            description = "缓存中的作业要求",
            submissionStatus = "未提交",
        )
        val freshDetail = cachedDetail.copy(description = "网络返回的最新作业要求")
        val local = MemoryLocalDataSource(
            PhyVlabCacheSnapshot(
                courses = listOf(course),
                activities = listOf(activity),
                events = emptyList(),
                assignmentDetails = listOf(
                    PhyVlabCachedAssignmentDetail(course.id, activity.id, cachedDetail),
                ),
                savedAtEpochMillis = 123L,
            ),
        )
        val repository = DetailRepository(freshDetail)
        val model = PhyVlabScreenModel(
            repository = repository,
            sessionProtocol = PhyVlabSessionProtocol(AuthenticatedTransport),
            localDataSource = local,
            accountScope = "25531058",
        )

        model.initialize(refreshFromNetwork = false)
        model.showActivityDetails(activity)

        assertEquals(cachedDetail, model.state.value.assignmentDetail)
        assertFalse(model.state.value.isDetailLoading)

        model.loadSelectedActivityDetail()

        assertEquals(1, repository.detailRequestCount)
        assertEquals(freshDetail, model.state.value.assignmentDetail)
        assertFalse(model.state.value.isDetailLoading)
    }

    @Test
    fun loadsCacheBeforeNetworkAndKeepsItWhenTheCampusNetworkIsUnavailable() = runBlocking {
        val course = PhyVlabCourse(
            id = 72,
            name = "大学物理I_(2026春)",
            category = "自然科学",
            progressPercent = 8,
            courseUrl = "https://phyvlab.bjtu.edu.cn/course/view.php?id=72",
        )
        val activity = PhyVlabActivity(
            id = 3689,
            courseId = course.id,
            courseName = course.name,
            title = "Chap 25-26",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689",
            openText = "2026年06月10日 00:00",
            openTimestamp = 1781020800L,
            dueText = "2026年06月16日 00:00",
            dueTimestamp = 1781539200L,
        )
        val local = MemoryLocalDataSource(
            PhyVlabCacheSnapshot(
                courses = listOf(course),
                activities = listOf(activity),
                events = emptyList(),
                savedAtEpochMillis = 123L,
            ),
        )
        val model = PhyVlabScreenModel(
            repository = FailingRepository,
            sessionProtocol = PhyVlabSessionProtocol(UnavailableTransport),
            localDataSource = local,
            accountScope = "25531058",
        )

        model.initialize(refreshFromNetwork = false)

        assertEquals(PhyVlabContentSource.CACHE, model.state.value.contentSource)
        assertEquals(listOf(course), model.state.value.courses)
        assertEquals(listOf(activity), model.state.value.activities)
        assertEquals(2, model.state.value.agendaEvents.size)

        model.refresh()

        assertEquals(PhyVlabContentSource.CACHE, model.state.value.contentSource)
        assertEquals(listOf(course), model.state.value.courses)
        assertEquals(PhyVlabSyncFailure.NETWORK, model.state.value.failure)
        assertFalse(model.state.value.isLoading)
        assertNull(local.replaced)
    }

    @Test
    fun pageInitializationDoesNotRetryAfterHomeRefreshAndDetailNavigationKeepsFailure() = runBlocking {
        val course = PhyVlabCourse(
            id = 72,
            name = "大学物理I_(2026春)",
            category = "自然科学",
            progressPercent = 8,
            courseUrl = "https://phyvlab.bjtu.edu.cn/course/view.php?id=72",
        )
        val activity = PhyVlabActivity(
            id = 3689,
            courseId = course.id,
            courseName = course.name,
            title = "Chap 25-26",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689",
            openText = "2026年06月10日 00:00",
            openTimestamp = 1781020800L,
            dueText = "2026年06月16日 00:00",
            dueTimestamp = 1781539200L,
        )
        val local = MemoryLocalDataSource(
            PhyVlabCacheSnapshot(
                courses = listOf(course),
                activities = listOf(activity),
                events = emptyList(),
                savedAtEpochMillis = 123L,
            ),
        )
        val transport = CountingUnavailableTransport()
        val model = PhyVlabScreenModel(
            repository = FailingRepository,
            sessionProtocol = PhyVlabSessionProtocol(transport),
            localDataSource = local,
            accountScope = "25531058",
        )

        model.initialize(refreshFromNetwork = false)
        model.refresh()
        val requestsAfterHomeRefresh = transport.requestCount

        // 进入物理在线页时的 initialize() 不应把首页刚做过的失败刷新再执行一遍。
        model.initialize()

        assertEquals(requestsAfterHomeRefresh, transport.requestCount)
        assertEquals(PhyVlabSyncFailure.NETWORK, model.state.value.failure)

        // 打开/退出作业详情属于视图导航，不应清掉顶层的同步失败状态。
        model.selectCourse(course)
        model.showActivityDetails(activity)
        model.dismissActivityDetails()
        assertEquals(PhyVlabSyncFailure.NETWORK, model.state.value.failure)
    }

    @Test
    fun assignmentScheduleFollowsSelectedCourseWhileHomeAgendaKeepsBothCourses() = runBlocking {
        val physics = PhyVlabCourse(
            id = 76,
            name = "大学物理II_(2026秋)",
            category = "",
            progressPercent = 0,
            courseUrl = "https://phyvlab.bjtu.edu.cn/course/view.php?id=76",
        )
        val laboratory = PhyVlabCourse(
            id = 78,
            name = "物理实验II_(2026秋)",
            category = "",
            progressPercent = 0,
            courseUrl = "https://phyvlab.bjtu.edu.cn/course/view.php?id=78",
        )
        val local = MemoryLocalDataSource(
            PhyVlabCacheSnapshot(
                courses = listOf(physics, laboratory),
                activities = listOf(activity(physics, 3963), activity(laboratory, 3947)),
                events = emptyList(),
                savedAtEpochMillis = 123L,
            ),
        )
        val model = PhyVlabScreenModel(
            repository = FailingRepository,
            sessionProtocol = PhyVlabSessionProtocol(UnavailableTransport),
            localDataSource = local,
            accountScope = "25531058",
        )

        model.initialize(refreshFromNetwork = false)

        assertEquals(2, model.state.value.events.size)
        assertTrue(model.state.value.events.all { it.title.startsWith("${physics.name} · ") })
        assertEquals(4, model.state.value.agendaEvents.size)

        model.selectCourse(laboratory)

        assertEquals(2, model.state.value.events.size)
        assertTrue(model.state.value.events.all { it.title.startsWith("${laboratory.name} · ") })
        assertEquals(4, model.state.value.agendaEvents.size)
    }

    private fun activity(course: PhyVlabCourse, id: Int) = PhyVlabActivity(
        id = id,
        courseId = course.id,
        courseName = course.name,
        title = "作业 $id",
        activityType = "作业",
        activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=$id",
        openText = "2026年09月23日 00:00",
        openTimestamp = 1790092800L,
        dueText = "2026年09月24日 00:00",
        dueTimestamp = 1790179200L,
    )

    private class MemoryLocalDataSource(
        private val snapshot: PhyVlabCacheSnapshot,
    ) : PhyVlabLocalDataSource {
        var replaced: PhyVlabCacheSnapshot? = null

        override fun load(accountScope: String): PhyVlabCacheSnapshot = snapshot

        override fun replace(accountScope: String, snapshot: PhyVlabCacheSnapshot) {
            replaced = snapshot
        }
    }

    private object FailingRepository : PhyVlabRepository {
        override suspend fun fetchCourses(): PhyVlabCoursesResult = error("not reached")

        override suspend fun fetchCourseActivities(course: PhyVlabCourse): PhyVlabActivitiesResult =
            error("not reached")

        override suspend fun fetchEvents(monthTimestampSeconds: Long): PhyVlabEventsResult = error("not reached")

        override suspend fun fetchAssignmentDetail(activity: PhyVlabActivity): PhyVlabAssignmentDetailResult =
            error("not reached")

        override suspend fun submitAssignment(
            activity: PhyVlabActivity,
            files: List<HomeworkFileContent>,
        ): PhyVlabSubmissionResult = error("not reached")
    }

    private class DetailRepository(
        private val freshDetail: PhyVlabAssignmentDetail,
    ) : PhyVlabRepository {
        var detailRequestCount = 0

        override suspend fun fetchCourses(): PhyVlabCoursesResult = error("not reached")

        override suspend fun fetchCourseActivities(course: PhyVlabCourse): PhyVlabActivitiesResult =
            error("not reached")

        override suspend fun fetchEvents(monthTimestampSeconds: Long): PhyVlabEventsResult =
            error("not reached")

        override suspend fun fetchAssignmentDetail(activity: PhyVlabActivity): PhyVlabAssignmentDetailResult {
            detailRequestCount += 1
            return PhyVlabAssignmentDetailResult.Success(freshDetail)
        }

        override suspend fun submitAssignment(
            activity: PhyVlabActivity,
            files: List<HomeworkFileContent>,
        ): PhyVlabSubmissionResult = error("not reached")
    }

    private object AuthenticatedTransport : SchoolHttpTransport {
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = SchoolHttpResponse(
            statusCode = 200,
            finalUrl = "https://phyvlab.bjtu.edu.cn/login/index.php",
            body = "<a href=\"/login/logout.php\">退出登录</a>".encodeToByteArray(),
        )

        override fun clearSession() = Unit
    }

    private object UnavailableTransport : SchoolHttpTransport {
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse =
            error("campus network unavailable")

        override fun clearSession() = Unit
    }

    private class CountingUnavailableTransport : SchoolHttpTransport {
        var requestCount: Int = 0

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            requestCount += 1
            error("campus network unavailable")
        }

        override fun clearSession() = Unit
    }
}

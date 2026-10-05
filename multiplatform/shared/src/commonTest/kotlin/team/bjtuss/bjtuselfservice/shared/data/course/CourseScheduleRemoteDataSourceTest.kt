package team.bjtuss.bjtuselfservice.shared.data.course

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.data.homework.SmartPlatformEndpoint
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class CourseScheduleRemoteDataSourceTest {
    @Test
    fun requestsCompleteSnapshotInOrder() = runBlocking {
        val transport = QueueTransport(
            mutableListOf(
                response(teacherTable()),
                response(scheduleTable(0, 0, currentCourseChild())),
                response(scheduleTable(1, 1, selectionCourseChild())),
                response("", finalUrl = "https://aa.bjtu.edu.cn/classroom/timeholdresult/room_view/?zc=8"),
            ),
        )

        val snapshot = SchoolCourseScheduleRemoteDataSource(
            transport,
            requestDelayMillis = 0,
        ).fetchSchedule()

        assertEquals(2, snapshot.courses.size)
        assertEquals(8, snapshot.currentWeek)
        assertEquals(4, transport.requests.size)
        assertTrue(transport.requests[0].url.contains("absent_list"))
        assertTrue(transport.requests[1].url.contains("stuschedule"))
        assertTrue(transport.requests[2].url.contains("courseselecttask"))
        assertTrue(transport.requests[3].url.contains("room_view"))
    }

    @Test
    fun anyRedirectAwayFromAcademicSystemExpiresWholeSnapshot() {
        val error = assertFailsWith<CourseScheduleRemoteException> {
            runBlocking {
                SchoolCourseScheduleRemoteDataSource(
                    QueueTransport(
                        mutableListOf(
                            response(teacherTable()).copy(
                                finalUrl = "https://cas.bjtu.edu.cn/auth/login/",
                            ),
                        ),
                    ),
                    requestDelayMillis = 0,
                ).fetchSchedule()
            }
        }

        assertEquals(CourseScheduleRemoteFailure.SESSION_EXPIRED, error.reason)
        assertTrue("程序设计" !in error.toString())
    }

    @Test
    fun prefersTimeListWeekCodeAndSkipsRoomView() = runBlocking {
        val transport = QueueTransport(
            mutableListOf(
                response(teacherTable()),
                response(scheduleTable(0, 0, currentCourseChild())),
                response(scheduleTable(1, 1, selectionCourseChild())),
                response("<html></html>", finalUrl = "https://mis.bjtu.edu.cn/module/module/28/"),
                response(
                    """{"weekCode":"25"}""",
                    finalUrl = "http://123.121.147.7:88/ve/back/coursePlatform/course.shtml?method=getTimeList",
                ),
            ),
        )

        val snapshot = SchoolCourseScheduleRemoteDataSource(
            transport = transport,
            requestDelayMillis = 0,
            endpoint = SmartPlatformEndpoint.LegacyHttp,
        ).fetchSchedule()

        assertEquals(25, snapshot.currentWeek)
        assertEquals(2, snapshot.courses.size)
        assertTrue(transport.requests.any { "getTimeList" in it.url })
        assertFalse(transport.requests.any { it.url.contains("room_view") })
    }

    @Test
    fun fallsBackToRoomViewWhenTimeListIsUnusable() = runBlocking {
        val transport = QueueTransport(
            mutableListOf(
                response(teacherTable()),
                response(scheduleTable(0, 0, currentCourseChild())),
                response(scheduleTable(1, 1, selectionCourseChild())),
                response("<html></html>", finalUrl = "https://mis.bjtu.edu.cn/module/module/28/"),
                response(
                    "<html>login</html>",
                    finalUrl = "http://123.121.147.7:88/ve/back/coursePlatform/course.shtml?method=getTimeList",
                ),
                response("", finalUrl = "https://aa.bjtu.edu.cn/classroom/timeholdresult/room_view/?zc=8"),
            ),
        )

        val snapshot = SchoolCourseScheduleRemoteDataSource(
            transport = transport,
            requestDelayMillis = 0,
            endpoint = SmartPlatformEndpoint.LegacyHttp,
        ).fetchSchedule()

        assertEquals(8, snapshot.currentWeek)
        assertTrue(transport.requests.any { "getTimeList" in it.url })
        assertTrue(transport.requests.any { it.url.contains("room_view") })
    }

    @Test
    fun timeListFailureDoesNotFailTheScheduleSnapshot() = runBlocking {
        val transport = QueueTransport(
            mutableListOf(
                response(teacherTable()),
                response(scheduleTable(0, 0, currentCourseChild())),
                response(scheduleTable(1, 1, selectionCourseChild())),
            ),
        )

        val snapshot = SchoolCourseScheduleRemoteDataSource(
            transport = transport,
            requestDelayMillis = 0,
            endpoint = SmartPlatformEndpoint.LegacyHttp,
        ).fetchSchedule()

        assertEquals(2, snapshot.courses.size)
        assertEquals(0, snapshot.currentWeek)
    }

    private class QueueTransport(
        private val responses: MutableList<SchoolHttpResponse>,
    ) : SchoolHttpTransport {
        val requests = mutableListOf<SchoolHttpRequest>()

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            requests += request
            return responses.removeAt(0)
        }

        override suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse =
            execute(request)

        override fun clearSession() = Unit
    }

    private fun response(
        html: String,
        finalUrl: String = "https://aa.bjtu.edu.cn/course_selection/",
    ) = SchoolHttpResponse(
        statusCode = 200,
        finalUrl = finalUrl,
        body = html.encodeToByteArray(),
    )
}

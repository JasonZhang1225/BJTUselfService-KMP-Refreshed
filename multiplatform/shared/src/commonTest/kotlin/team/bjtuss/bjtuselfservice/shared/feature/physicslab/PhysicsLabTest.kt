package team.bjtuss.bjtuselfservice.shared.feature.physicslab

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.network.*
import team.bjtuss.bjtuselfservice.shared.domain.calendar.*
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.feature.course.*

class PhysicsLabTest {
    @Test fun parsesSelectedResultsAndExpandsTwoWeeks() {
        val labs = parsePhysicsLabs(results("声源定位的GPS模拟"))
        val lab = labs.single()
        assertEquals(listOf(LocalDate(2026, 11, 26), LocalDate(2026, 12, 3)), lab.dates)
        assertEquals("13:00-16:20", lab.timeRange)
        assertEquals("7312A", lab.location)
        assertEquals(1, parsePhysicsLabs(results("光纤特性和光信号传输")).single().weekCount)
        assertEquals(2, physicsLabWeekCount("软磁特性"))
    }
    @Test fun emptyResultsAreSuccessButLoginAndMissingTablesAreFailures() {
        assertTrue(parsePhysicsLabs("<table id='a_ASPxGridViewCourseList_DXMainTable'><tr><th>日期</th></tr></table>").isEmpty())
        assertTrue(parsePhysicsLabs("<table id='a_ASPxGridViewCourseList_DXMainTable'><tr class='dxgvEmptyDataRow'><td colspan='7'>无数据</td></tr></table>").isEmpty())
        assertFailsWith<PhysicsLabFailure> { parsePhysicsLabs("<input name='a_tbPassword'>") }
        assertFailsWith<PhysicsLabFailure> { parsePhysicsLabs("<h1>服务错误</h1>") }
        assertFailsWith<PhysicsLabFailure> { parsePhysicsLabs(results("综合").replace("04", "09")) }
        assertFailsWith<PhysicsLabFailure> { parsePhysicsLabs(results("综合").replace("2026/11/26", "2026/2/30")) }
    }
    @Test fun verifiesProtectedPageAfterLoginAndPreservesAspNetFields() = runBlocking {
        val transport = FakeTransport(listOf(loginForm, "", results("超声专题")))
        val labs = PhysicsLabRemote(transport).fetch(Credentials("fixture-user", "fixture-password"))
        assertEquals(1, labs.size)
        val post = transport.requests[1]
        assertEquals("opaque", post.formFields["__VIEWSTATE"])
        assertEquals("fixture-user", post.formFields["a_tbUserId"])
        assertEquals("fixture-password", post.formFields["a_tbPassword"])
        assertEquals("0", post.formFields["a_ASPxRadioButtonListType"])
        assertEquals("C", post.formFields["a_RB0"])
        assertEquals("U", post.formFields["a_RB1"])
        assertEquals(PHYSICS_LAB_RESULTS, transport.requests.last().url)
        assertFalse(post.toString().contains("fixture-password"))
        assertFalse(post.toString().contains("fixture-user"))
        assertFalse(post.toString().contains("opaque"))
    }
    @Test fun failedLoginIsNotMistakenForEmptyEnrollment() = runBlocking {
        val transport = FakeTransport(listOf(loginForm, loginForm, loginForm))
        assertFailsWith<PhysicsLabFailure> { PhysicsLabRemote(transport).fetch(Credentials("fixture", "fixture")) }
        Unit
    }
    @Test fun rejectsCrossOriginRedirectWithoutFollowingIt() = runBlocking {
        val transport = object : SchoolHttpTransport {
            override suspend fun execute(request: SchoolHttpRequest) = SchoolHttpResponse(302, request.url, mapOf("Location" to listOf("https://example.com/")))
            override fun clearSession() {}
        }
        assertFailsWith<PhysicsLabFailure> { PhysicsLabRemote(transport).fetch(Credentials("fixture", "fixture")) }
        Unit
    }
    @Test fun exactOverlapFragmentsKeepCourseExamAndLabAndExcludeOtherSemester() {
        val weeks = weeks()
        val labs = parsePhysicsLabs(results("超声专题"))
        val exams = listOf(ExamSchedule(examType = "期末", courseName = "测试课程", examTimeAndPlace = "2026-11-26 14:30-16:10 测试楼", examStatus = "", detail = ""))
        val fragments = scheduleEventCourses(exams, labs, weeks)
        assertEquals(4, fragments.count { it.scheduleEventKind == "physicslab" })
        assertEquals(1, fragments.count { it.scheduleEventKind == "exam" })
        val labFirstSlot = fragments.first { it.scheduleEventKind == "physicslab" }
        assertEquals(20, labFirstSlot.courseLocationIndex)
        assertEquals(50f / 110f, labFirstSlot.eventSlotOffset)
        assertEquals(60f / 110f, labFirstSlot.eventSlotHeight)
        val ordinary = Course(1, "regular", "课程", "", 28, "第1周", "", false)
        val state = CourseScheduleUiState(courses = listOf(ordinary), supplementalCourses = fragments, academicWeeks = weeks, selectedWeek = 1)
        assertEquals(3, state.visibleCourses.count { it.courseLocationIndex == 28 })
        assertEquals(2, state.coursesForPage(2, LocalDate(2026, 11, 30)).size)
        assertTrue(scheduleEventCourses(exams, labs, listOf(OccupancyWeekDate(1, "", "", LocalDate(2027, 3, 1)))).isEmpty())
    }
    @Test fun allSixVerifiedPeriodsAreAvailable() {
        assertEquals(6, physicsLabTimeRanges.size)
        assertEquals("10:10-12:40", physicsLabTimeRanges[5])
        assertEquals("17:40-21:00", physicsLabTimeRanges[4])
    }
    @Test fun calendarExportsBothLabDatesWithStableIdsAndHonorsWeekRange() {
        val lab = parsePhysicsLabs(results("超声专题")).single()
        fun export(range: IntRange, labs: List<PhysicsLab>) = generateAcademicCalendarIcs(emptyList(), emptyList(), weeks(), range,
            Instant.parse("2026-10-05T00:00:00Z"), physicsLabs = labs)
        val result = export(1..2, listOf(lab))
        assertEquals(2, result.physicsLabEventCount)
        assertEquals("2026-11-26T13:00:00", result.events.first().startLocal)
        assertEquals("2026-12-03T16:20:00", result.events.last().endLocal)
        assertTrue(result.events.all { it.kind == AcademicCalendarEventKind.PHYSICS_LAB })
        assertTrue(result.events.all { it.stableId.startsWith("course-physicslab-") })
        assertEquals(result.events.map { it.stableId }, export(1..2, listOf(lab.copy(teacher = "换教师", location = "换教室"))).events.map { it.stableId })
        assertEquals(1, export(1..1, listOf(lab)).physicsLabEventCount)
        assertEquals(0, export(1..2, emptyList()).physicsLabEventCount)
        assertTrue(result.ics.contains("DTSTART;TZID=Asia/Shanghai:20261203T130000"))
    }
    @Test fun experimentInNonTeachingWeekStillAppearsAndExportsByItsActualDate() {
        val weeks = listOf(OccupancyWeekDate(1, "", "", LocalDate(2026, 9, 21)), OccupancyWeekDate(2, "", "", LocalDate(2026, 10, 5)))
        val lab = PhysicsLab(LocalDate(2026, 9, 28), 4, "综合实验", "测试实验室", "测试教师", 1)
        val fragments = scheduleEventCourses(emptyList(), listOf(lab), weeks)
        val state = CourseScheduleUiState(supplementalCourses = fragments, academicWeeks = weeks,
            selectedNonTeachingWeekStart = LocalDate(2026, 9, 28))
        assertEquals(2, state.visibleCourses.size)
        assertEquals(1, generateAcademicCalendarIcs(emptyList(), emptyList(), weeks, 1..2,
            Instant.parse("2026-10-05T00:00:00Z"), physicsLabs = listOf(lab)).physicsLabEventCount)
    }
    private fun weeks() = listOf(OccupancyWeekDate(1, "", "", LocalDate(2026, 11, 23)), OccupancyWeekDate(2, "", "", LocalDate(2026, 11, 30)))
}

internal fun results(name: String) = """<table id="a_ASPxGridViewCourseList_DXMainTable"><tr><th>学号</th><th>日期</th><th>时段</th></tr><tr><td>fixture</td><td>2026/11/26</td><td>04</td><td>$name</td><td>7312A</td><td>测试教师</td></tr></table>"""
internal val loginForm = """<form><input name="__VIEWSTATE" value="opaque"><input name="a_tbUserId"><input name="a_tbPassword" type="password"><input name="a_ASPxRadioButtonListType"><input name="a_RB0"><input name="a_RB1"><input name="a_RB2"><input name="a_btnLogin" type="submit" value="登录"></form>"""
private class FakeTransport(private val bodies: List<String>) : SchoolHttpTransport {
    val requests = mutableListOf<SchoolHttpRequest>()
    override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
        requests += request
        return SchoolHttpResponse(200, request.url, body = bodies[requests.lastIndex].encodeToByteArray())
    }
    override fun clearSession() {}
}

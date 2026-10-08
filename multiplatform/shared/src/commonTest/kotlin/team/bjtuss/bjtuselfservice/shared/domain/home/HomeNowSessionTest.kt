package team.bjtuss.bjtuselfservice.shared.domain.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import team.bjtuss.bjtuselfservice.shared.domain.course.Course

class HomeNowSessionTest {
    private fun course(name: String, index: Int, place: String = "海淀西校区,思源西楼,SX501") = Course(
        courseId = name,
        courseName = name,
        courseTeacher = "崔晓龙",
        courseLocationIndex = index,
        courseTime = "第1-8周",
        coursePlace = place,
        isCurrentSemester = false,
    )

    private fun lab(name: String, time: String) = Course(
        courseId = name,
        courseName = name,
        courseTeacher = "实验教师",
        courseLocationIndex = 0,
        courseTime = "第1-8周",
        coursePlace = "实验楼",
        isCurrentSemester = false,
        scheduleEventKind = "physicslab",
        scheduleEventTime = time,
    )

    // 2026-10-08 周四；第一节 index = 0*8+4 = 4，第二节 = 12。
    @Test
    fun ongoingMorningClassIsPicked() {
        val sessions = pickHomeNowSessions(
            listOf(course("工业产品创新设计", 4), course("下午课", 28)),
            emptyList(),
            LocalDateTime(2026, 10, 8, 9, 10),
        )
        assertEquals(1, sessions.size)
        assertEquals("工业产品创新设计", sessions.single().course.courseName)
        assertTrue(sessions.single().ongoing)
        assertEquals("08:00–09:50", sessions.single().timeRange)
    }

    @Test
    fun gapBetweenPeriodsPicksTheNextClass() {
        val sessions = pickHomeNowSessions(
            listOf(course("工业产品创新设计", 4), course("下午课", 28)),
            emptyList(),
            LocalDateTime(2026, 10, 8, 9, 55),
        )
        assertEquals("下午课", sessions.single().course.courseName)
        assertEquals(false, sessions.single().ongoing)
        assertEquals("14:10–16:00", sessions.single().timeRange)
    }

    @Test
    fun afterLastClassReturnsNothing() {
        assertTrue(
            pickHomeNowSessions(
                listOf(course("工业产品创新设计", 4)),
                emptyList(),
                LocalDateTime(2026, 10, 8, 22, 0),
            ).isEmpty(),
        )
    }

    @Test
    fun ongoingLabIsPickedAsInProgress() {
        val sessions = pickHomeNowSessions(
            emptyList(),
            listOf(lab("专题测试实验", "13:00-16:20")),
            LocalDateTime(2026, 10, 8, 14, 0),
        )
        assertEquals("专题测试实验", sessions.single().course.courseName)
        assertTrue(sessions.single().ongoing)
        assertTrue(sessions.single().isLab)
    }

    @Test
    fun overlappingCourseAndLabBothShowWhenOngoing() {
        val sessions = pickHomeNowSessions(
            listOf(course("工业产品创新设计", 4)),
            listOf(lab("专题测试实验", "08:30-10:00")),
            LocalDateTime(2026, 10, 8, 9, 10),
        )
        assertEquals(listOf("工业产品创新设计", "专题测试实验"), sessions.map { it.course.courseName })
        assertTrue(sessions.all { it.ongoing })
    }

    @Test
    fun parseHmRangeAcceptsEnDash() {
        assertEquals(8 * 60 to 9 * 60 + 50, parseHmRange("08:00–09:50"))
        assertNull(parseHmRange("08:00"))
    }
}

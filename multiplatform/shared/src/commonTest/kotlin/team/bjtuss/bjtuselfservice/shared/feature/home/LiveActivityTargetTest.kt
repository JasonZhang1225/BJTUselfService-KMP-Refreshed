package team.bjtuss.bjtuselfservice.shared.feature.home

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.home.pickHomeNowSessions
import team.bjtuss.bjtuselfservice.shared.feature.course.courseColorCategory
import team.bjtuss.bjtuselfservice.shared.feature.widget.buildCourseWidgetSnapshot

class LiveActivityTargetTest {
    private val monday = LocalDate(2026, 10, 12)

    private fun course(name: String, index: Int) = Course(
        courseId = name, courseName = name, courseTeacher = " 教师 ", courseLocationIndex = index,
        courseTime = "第1-5周", coursePlace = "海淀主校区,思源西楼,SX101", isCurrentSemester = false,
    )

    @Test fun idFormatMatchesSwift() {
        assertEquals("2026-10-12|480|590|高等数学", liveActivityOccurrenceId(monday, 480, 590, "高等数学"))
    }

    /** iOS 宿主按 id 在小组件快照里找同一项；卡片与快照的课名、时间规则必须一致。 */
    @Test fun cardTargetMatchesWidgetSnapshotSession() {
        val lab = course("物理实验", 25).copy(
            id = -1, scheduleEventKind = "physicslab",
            scheduleEventDate = monday.toString(), scheduleEventTime = "13:20-15:50",
        )
        val schedule = HomeSchedulePresentation(
            courses = listOf(course("高等数学 [本]", 1)),
            supplementalCourses = listOf(lab),
            academicWeeks = listOf(OccupancyWeekDate(4, "10/12", "10/18", monday)),
            today = monday,
        )
        val snapshotIds = buildCourseWidgetSnapshot(schedule, monday, generatedAtEpochSeconds = 1L, dayCount = 1)
            .days.single().sessions.map { liveActivityOccurrenceId(monday, it.startMinute, it.endMinute, it.name) }

        val morning = pickHomeNowSessions(
            homeCoursesOnDate(schedule, monday), homePhysicsLabsOnDate(schedule, monday), LocalDateTime(2026, 10, 12, 8, 30),
        ).single()
        val afternoon = pickHomeNowSessions(
            homeCoursesOnDate(schedule, monday), homePhysicsLabsOnDate(schedule, monday), LocalDateTime(2026, 10, 12, 13, 30),
        ).single()
        val targets = listOf(morning, afternoon).map {
            it.liveActivityTarget(monday, courseColorCategory(it.course, null))
        }

        assertEquals(snapshotIds, targets.map { it.id })
        assertEquals("高等数学", targets[0].name)
        assertEquals("思源西楼 SX101", targets[0].place)
        assertEquals("教师", targets[0].teacher)
        assertEquals("lab", targets[1].kind)
    }
}

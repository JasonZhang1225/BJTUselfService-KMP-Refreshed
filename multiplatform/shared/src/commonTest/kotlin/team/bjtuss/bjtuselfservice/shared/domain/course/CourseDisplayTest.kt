package team.bjtuss.bjtuselfservice.shared.domain.course

import kotlin.test.Test
import kotlin.test.assertEquals

class CourseDisplayTest {
    @Test fun removesOnlyAnExactTrailingUndergraduateMarker() {
        listOf("[本]", "（本）", "(本)", "【本】").forEach {
            assertEquals("大学物理（A）II", displayScheduleCourseName("大学物理（A）II $it"))
        }
        listOf("本科导论", "课程[本]（实验）", "课程[本科]", "课程[本]附录", "课程[本] ", "课程[选]", "课程[ 本 ]").forEach {
            assertEquals(it, displayScheduleCourseName(it))
        }
    }
    @Test fun teacherMovesIntoTheTitleWithoutChangingStoredData() {
        val course = Course(courseId = "C1", courseName = "大学物理（A）II [本]", courseTeacher = " 测试教师 ",
            courseLocationIndex = 1, courseTime = "第4周", coursePlace = "教室", isCurrentSemester = false)
        assertEquals("大学物理（A）II - 测试教师", course.displayTitleWithTeacher())
        assertEquals("大学物理（A）II [本]", course.courseName)
        assertEquals("大学物理（A）II", course.copy(courseTeacher = "").displayTitleWithTeacher())
    }
}

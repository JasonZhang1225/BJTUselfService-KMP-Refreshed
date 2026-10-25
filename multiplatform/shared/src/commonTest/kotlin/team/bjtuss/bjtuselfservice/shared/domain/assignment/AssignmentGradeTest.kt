package team.bjtuss.bjtuselfservice.shared.domain.assignment

import kotlin.test.Test
import kotlin.test.assertEquals

class AssignmentGradeTest {
    @Test
    fun publishedGradeWinsOverSubmissionState() {
        assertEquals("95", assignmentGradeText("95", submitted = true))
        assertEquals("20", assignmentGradeText("20", submitted = false))
    }

    @Test
    fun submittedWithoutGradeIsPendingGrading() {
        assertEquals("待评分", assignmentGradeText("未公布成绩", submitted = true))
        assertEquals("待评分", assignmentGradeText(null, submitted = true))
        assertEquals("待评分", assignmentGradeText("  ", submitted = true))
    }

    @Test
    fun unsubmittedWithoutGradeIsUnpublished() {
        assertEquals("未公布成绩", assignmentGradeText(null, submitted = false))
        assertEquals("未公布成绩", assignmentGradeText("未公布成绩", submitted = false))
    }
}

package team.bjtuss.bjtuselfservice.shared.domain.assignment

import kotlin.test.Test
import kotlin.test.assertEquals

class AssignmentGradeTest {
    @Test
    fun publishedGradeWinsOverSubmissionAndGradingState() {
        assertEquals("95", assignmentGradeText("95", submitted = true, graded = false))
        assertEquals("20", assignmentGradeText("20", submitted = false, graded = null))
    }

    @Test
    fun placeholderTextsAreNotGrades() {
        assertEquals("待公布", assignmentGradeText("未公布成绩", submitted = true, graded = true))
        assertEquals("待公布", assignmentGradeText("暂未公布", submitted = true, graded = true))
        assertEquals("待公布", assignmentGradeText("  ", submitted = true, graded = true))
    }

    @Test
    fun submittedAndGradedWithoutScoreIsPendingPublish() {
        assertEquals("待公布", assignmentGradeText(null, submitted = true, graded = true))
    }

    @Test
    fun submittedAndNotGradedIsPendingGrading() {
        assertEquals("待评分", assignmentGradeText(null, submitted = true, graded = false))
        assertEquals("待评分", assignmentGradeText("未公布成绩", submitted = true, graded = false))
    }

    @Test
    fun submittedWithoutGradingSignalIsPendingGrading() {
        assertEquals("待评分", assignmentGradeText(null, submitted = true, graded = null))
        assertEquals("待评分", assignmentGradeText("未公布成绩", submitted = true, graded = null))
    }

    @Test
    fun unsubmittedIsNoneRegardlessOfGradingState() {
        assertEquals("暂无", assignmentGradeText(null, submitted = false, graded = null))
        assertEquals("暂无", assignmentGradeText(null, submitted = false, graded = false))
        assertEquals("暂无", assignmentGradeText("未公布成绩", submitted = false, graded = true))
    }
}

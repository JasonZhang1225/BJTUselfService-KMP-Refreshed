package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import kotlin.test.Test
import kotlin.test.assertEquals
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAssignmentDetail

class PhyVlabActivityStatusTest {
    @Test
    fun incompleteActivityBeforeDeadlineIsDueSoon() {
        assertEquals(
            PhyVlabActivityDeadlineState.DUE_SOON,
            phyVlabActivityDeadlineState(activity(dueTimestamp = 2_000L), nowEpochSeconds = 1_999L),
        )
    }

    @Test
    fun deadlineMomentCountsAsOverdue() {
        assertEquals(
            PhyVlabActivityDeadlineState.OVERDUE,
            phyVlabActivityDeadlineState(activity(dueTimestamp = 2_000L), nowEpochSeconds = 2_000L),
        )
    }

    @Test
    fun completedActivityIsSubmittedEvenAfterDeadline() {
        assertEquals(
            PhyVlabActivityDeadlineState.SUBMITTED,
            phyVlabActivityDeadlineState(
                activity(dueTimestamp = 2_000L, completed = true),
                nowEpochSeconds = 3_000L,
            ),
        )
    }

    @Test
    fun detailSubmissionSignalCanOverrideStaleListCompletionFlag() {
        assertEquals(
            PhyVlabActivityDeadlineState.SUBMITTED,
            phyVlabActivityDeadlineState(
                activity(dueTimestamp = 2_000L),
                nowEpochSeconds = 3_000L,
                submitted = true,
            ),
        )
    }

    @Test
    fun missingDeadlineRemainsUnknown() {
        assertEquals(
            PhyVlabActivityDeadlineState.UNKNOWN,
            phyVlabActivityDeadlineState(activity(dueTimestamp = null), nowEpochSeconds = 3_000L),
        )
    }

    @Test
    fun submissionDateAloneDoesNotClaimSubmission() {
        assertEquals(
            false,
            phyVlabAssignmentDetailHasSubmission(
                PhyVlabAssignmentDetail(submissionDateText = "2026年06月15日 22:00"),
            ),
        )
    }

    @Test
    fun explicitSubmittedStatusIsATrustedSubmissionSignal() {
        assertEquals(
            true,
            phyVlabAssignmentDetailHasSubmission(
                PhyVlabAssignmentDetail(
                    submissionStatus = "已提交",
                    submissionDateText = "2026年06月15日 22:00",
                ),
            ),
        )
    }

    @Test
    fun gradingTextDoesNotAppearAsSubmissionStatus() {
        assertEquals(
            "未提交",
            phyVlabSubmissionStatusLabel(
                PhyVlabAssignmentDetail(submissionStatus = "尚未批改"),
            ),
        )
    }

    @Test
    fun submissionTimingUsesDueTimeAndRealSubmissionTime() {
        val activity = activity(dueTimestamp = 2_000L)
        assertEquals(
            "按时提交",
            phyVlabSubmissionTimingLabel(
                activity,
                PhyVlabAssignmentDetail(
                    submissionStatus = "已提交",
                    submissionDateTimestamp = 2_000L,
                ),
            ),
        )
        assertEquals(
            "逾期提交",
            phyVlabSubmissionTimingLabel(
                activity,
                PhyVlabAssignmentDetail(
                    submittedFiles = listOf(team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabSubmissionFile("报告.pdf")),
                    submissionDateTimestamp = 2_001L,
                ),
            ),
        )
    }

    @Test
    fun detailSubmissionAfterDeadlineIsNotShownAsOnTime() {
        assertEquals(
            PhyVlabActivityDeadlineState.LATE_SUBMITTED,
            phyVlabActivityDeadlineState(
                activity(dueTimestamp = 2_000L),
                nowEpochSeconds = 3_000L,
                submitted = true,
                submittedAtEpochSeconds = 2_001L,
            ),
        )
    }

    @Test
    fun detailSubmissionBeforeDeadlineIsGreenState() {
        assertEquals(
            PhyVlabActivityDeadlineState.SUBMITTED,
            phyVlabActivityDeadlineState(
                activity(dueTimestamp = 2_000L),
                nowEpochSeconds = 3_000L,
                submitted = true,
                submittedAtEpochSeconds = 1_999L,
            ),
        )
    }

    private fun activity(
        dueTimestamp: Long?,
        completed: Boolean = false,
    ) = PhyVlabActivity(
        id = 3689,
        courseId = 72,
        courseName = "大学物理I",
        title = "Chap 25-26",
        activityType = "作业",
        activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689",
        dueTimestamp = dueTimestamp,
        completed = completed,
    )
}

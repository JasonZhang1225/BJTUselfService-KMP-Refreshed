package team.bjtuss.bjtuselfservice.shared.data.phyvlab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAssignmentDetail
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabCourse
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabSubmissionFile

class PhyVlabCacheCodecTest {
    @Test
    fun roundTripsScheduleActivitiesAndDetailsWithoutSessionFields() {
        val activity = PhyVlabActivity(
            id = 3689,
            courseId = 72,
            courseName = "大学物理I_(2026春)",
            title = "Chap \"25-26\"",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689",
            openText = "2026年06月10日 00:00",
            openTimestamp = 1781020800L,
            dueText = "2026年06月16日 00:00",
            dueTimestamp = 1781539200L,
            completed = true,
        )
        val snapshot = PhyVlabCacheSnapshot(
            courses = listOf(
                PhyVlabCourse(
                    id = 72,
                    name = "大学物理I_(2026春)",
                    category = "自然科学",
                    progressPercent = 8,
                    courseUrl = "https://phyvlab.bjtu.edu.cn/course/view.php?id=72",
                ),
            ),
            activities = listOf(activity),
            events = listOf(
                PhyVlabEvent(
                    id = "activity-72-3689-start",
                    title = "大学物理I_(2026春) · Chap \"25-26\"",
                    dateText = activity.openText.orEmpty(),
                    dayTimestamp = activity.openTimestamp!!,
                    eventUrl = activity.activityUrl,
                    kind = PhyVlabEventKind.START,
                ),
                PhyVlabEvent(
                    id = "activity-72-3689-due",
                    title = "大学物理I_(2026春) · Chap \"25-26\"",
                    dateText = activity.dueText.orEmpty(),
                    dayTimestamp = activity.dueTimestamp!!,
                    eventUrl = activity.activityUrl,
                    kind = PhyVlabEventKind.DEADLINE,
                ),
            ),
            assignmentDetails = listOf(
                PhyVlabCachedAssignmentDetail(
                    courseId = 72,
                    activityId = 3689,
                    detail = PhyVlabAssignmentDetail(
                        description = "完成实验报告",
                        submissionStatus = "已提交",
                        submissionDateText = "2026年06月14日 18:36",
                        submissionDateTimestamp = 1781433360L,
                        gradingStatus = "未批改",
                        submittedFiles = listOf(PhyVlabSubmissionFile("报告 \"最终\".pdf")),
                        canSubmit = true,
                    ),
                ),
            ),
            savedAtEpochMillis = 1_000L,
        )

        val decoded = decodePhyVlabCache(encodePhyVlabCache(snapshot))
        assertEquals(
            snapshot.copy(
                assignmentDetails = snapshot.assignmentDetails.map { cached ->
                    cached.copy(detail = cached.detail.copy(canSubmit = false))
                },
            ),
            decoded,
        )
    }

    @Test
    fun rejectsUnknownVersionAndDuplicateActivities() {
        val base = PhyVlabCacheSnapshot(
            courses = listOf(
                PhyVlabCourse(1, "课程", "", 0, "https://phyvlab.bjtu.edu.cn/course/view.php?id=1"),
            ),
            activities = emptyList(),
            events = emptyList(),
        )
        val unknownVersion = encodePhyVlabCache(base).replace("\"version\":1", "\"version\":99")
        assertNull(decodePhyVlabCache(unknownVersion))

        val activity = PhyVlabActivity(
            id = 2,
            courseId = 1,
            courseName = "课程",
            title = "作业",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=2",
        )
        val duplicate = base.copy(activities = listOf(activity, activity))
        assertNull(decodePhyVlabCache(encodePhyVlabCache(duplicate)))
    }

    @Test
    fun dropsUntrustedCachedSubmissionDate() {
        val snapshot = PhyVlabCacheSnapshot(
            courses = emptyList(),
            activities = emptyList(),
            events = emptyList(),
            assignmentDetails = listOf(
                PhyVlabCachedAssignmentDetail(
                    courseId = 72,
                    activityId = 3689,
                    detail = PhyVlabAssignmentDetail(
                        submissionStatus = "尚未批改",
                        submissionDateText = "2026年09月16日 00:00",
                        submissionDateTimestamp = 1789488000L,
                    ),
                ),
            ),
        )

        val decoded = decodePhyVlabCache(encodePhyVlabCache(snapshot))
        val detail = decoded?.assignmentDetails?.single()?.detail
        assertNull(detail?.submissionDateText)
        assertNull(detail?.submissionDateTimestamp)
    }
}

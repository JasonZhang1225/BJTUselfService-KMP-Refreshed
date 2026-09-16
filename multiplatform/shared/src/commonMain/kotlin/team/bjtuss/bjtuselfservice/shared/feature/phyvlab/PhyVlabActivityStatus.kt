package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAssignmentDetail

/** 作业列表中唯一可直接展示的截止状态；未知时不猜测颜色。 */
internal enum class PhyVlabActivityDeadlineState {
    SUBMITTED,
    LATE_SUBMITTED,
    DUE_SOON,
    OVERDUE,
    UNKNOWN,
}

/**
 * Moodle 课程页的完成标记是列表层可用的用户完成信号；详情页若拿到更准确的提交信息，
 * 可通过 [submitted] 覆盖它。截止时刻本身视为已到期，因此使用 >= 判断逾期。
 */
internal fun phyVlabActivityDeadlineState(
    activity: PhyVlabActivity,
    nowEpochSeconds: Long,
    submitted: Boolean = activity.completed,
    submittedAtEpochSeconds: Long? = null,
): PhyVlabActivityDeadlineState = when {
    submitted -> if (
        activity.dueTimestamp != null &&
        submittedAtEpochSeconds != null &&
        submittedAtEpochSeconds > activity.dueTimestamp
    ) {
        PhyVlabActivityDeadlineState.LATE_SUBMITTED
    } else {
        PhyVlabActivityDeadlineState.SUBMITTED
    }
    activity.dueTimestamp == null -> PhyVlabActivityDeadlineState.UNKNOWN
    nowEpochSeconds < activity.dueTimestamp -> PhyVlabActivityDeadlineState.DUE_SOON
    else -> PhyVlabActivityDeadlineState.OVERDUE
}

/** 详情页只接受明确的提交状态或已提交文件，不把孤立日期当成提交信号。 */
internal fun phyVlabAssignmentDetailHasSubmission(detail: PhyVlabAssignmentDetail): Boolean {
    val status = detail.submissionStatus.trim().lowercase()
    return detail.submittedFiles.isNotEmpty() ||
        status.contains("已提交") ||
        (status.contains("submitted") && !status.contains("not submitted"))
}

/** 详情页展示用的提交状态；批改文案（如“尚未批改”）不能冒充提交状态。 */
internal fun phyVlabSubmissionStatusLabel(detail: PhyVlabAssignmentDetail): String {
    if (!phyVlabAssignmentDetailHasSubmission(detail)) return "未提交"
    val status = detail.submissionStatus.trim()
    return if (
        status.contains("已提交") ||
            (status.lowercase().contains("submitted") && !status.lowercase().contains("not submitted"))
    ) {
        status
    } else {
        "已提交"
    }
}

/** 只有同时存在截止时间和真实提交时间时，才标注按时/逾期。 */
internal fun phyVlabSubmissionTimingLabel(
    activity: PhyVlabActivity,
    detail: PhyVlabAssignmentDetail?,
): String? {
    if (detail == null || !phyVlabAssignmentDetailHasSubmission(detail)) return null
    val submittedAt = detail.submissionDateTimestamp ?: return null
    val dueAt = activity.dueTimestamp ?: return null
    return if (submittedAt > dueAt) "逾期提交" else "按时提交"
}

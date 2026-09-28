package team.bjtuss.bjtuselfservice.shared.domain.phyvlab

/** 物理在线平台的一门已选课程。 */
data class PhyVlabCourse(
    val id: Int,
    val name: String,
    val category: String,
    val progressPercent: Int,
    val courseUrl: String,
)

/** 课程内的一类学习活动。当前只展示作业。 */
data class PhyVlabActivity(
    val id: Int,
    val courseId: Int,
    val courseName: String,
    val title: String,
    val activityType: String,
    val activityUrl: String,
    val openText: String? = null,
    val openTimestamp: Long? = null,
    val dueText: String? = null,
    val dueTimestamp: Long? = null,
    val completed: Boolean = false,
)

/** 单项作业的提交附件摘要。路径/下载地址只在数据层内部使用，不暴露给 UI。 */
data class PhyVlabSubmissionFile(
    val fileName: String,
)

/** 物理在线单项作业详情；不包含 sesskey、draft item 等短期会话参数。 */
data class PhyVlabAssignmentDetail(
    val description: String = "",
    val submissionStatus: String = "",
    val submissionDateText: String? = null,
    val submissionDateTimestamp: Long? = null,
    val gradingStatus: String? = null,
    val gradeText: String? = null,
    val feedbackText: String? = null,
    val submittedFiles: List<PhyVlabSubmissionFile> = emptyList(),
    val canSubmit: Boolean = false,
)

/** 平台日历中的一个事件（安排）。 */
enum class PhyVlabEventKind {
    /** 作业开放/启动时间。 */
    START,

    /** 作业截止时间；00:00 在首页议程中归到前一天。 */
    DEADLINE,
}

data class PhyVlabEvent(
    val id: String,
    val title: String,
    val dateText: String,
    val dayTimestamp: Long,
    val eventUrl: String? = null,
    // 旧缓存和旧调用方没有类型信息时，按物理在线的日历截止事件处理。
    val kind: PhyVlabEventKind = PhyVlabEventKind.DEADLINE,
    /**
     * 是否已做：课程页完成标记或详情提交信息折叠后的信号，供首页标绿使用。
     * 未知时为 false（红色提醒），不猜测。
     */
    val submitted: Boolean = false,
)

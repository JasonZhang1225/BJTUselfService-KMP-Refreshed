package team.bjtuss.bjtuselfservice.shared.domain.assignment

private const val UNPUBLISHED_GRADE = "未公布成绩"

/**
 * 作业卡片的“评分”行，三个平台统一：有成绩显示成绩；已提交但未出分为“待评分”；未提交为“未公布成绩”。
 */
fun assignmentGradeText(grade: String?, submitted: Boolean): String {
    val value = grade?.trim()?.takeIf { it.isNotEmpty() && it != UNPUBLISHED_GRADE }
    return value ?: if (submitted) "待评分" else UNPUBLISHED_GRADE
}

package team.bjtuss.bjtuselfservice.shared.domain.assignment

/** 学校页面对“尚未出分”的占位文案，都视为没有成绩。 */
private val NO_GRADE_TEXTS = setOf("未公布成绩", "暂未公布", "-")

/**
 * 列表卡片“评分”行的文案，三个平台统一：
 * - 有成绩显示成绩；
 * - 未提交显示“暂无”（实际可能被记 0 分，视情况而定）；
 * - 已提交且已批改（[graded] 为 true）但未出分显示“待公布”；
 * - 已提交且未批改（[graded] 为 false），或平台没有批改信号（[graded] 为 null）显示“待评分”。
 */
fun assignmentGradeText(grade: String?, submitted: Boolean, graded: Boolean?): String {
    val value = grade?.trim()?.takeIf { it.isNotEmpty() && it !in NO_GRADE_TEXTS }
    return when {
        value != null -> value
        !submitted -> "暂无"
        graded == true -> "待公布"
        else -> "待评分"
    }
}

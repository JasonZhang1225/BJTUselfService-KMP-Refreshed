package team.bjtuss.bjtuselfservice.shared.domain.home

import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeKind
import team.bjtuss.bjtuselfservice.shared.feature.citel.isCitelAccepted

enum class HomeChangeDomain(val title: String) {
    GRADES("成绩"),
    COURSES("课程表"),
    EXAMS("考试安排"),
    HOMEWORK("作业"),
    PHYVLAB("物理在线"),
    CITEL("CITEL"),
    PHYSICS_LAB("物理实验"),
    PHYSICS_DEMO("演示实验"),
}

data class HomeChangeField(
    val label: String,
    val before: String,
    val after: String,
)

data class HomeChangeRecord(
    val domain: HomeChangeDomain,
    val kind: DataChangeKind,
    val title: String,
    val beforeDetail: String = "",
    val afterDetail: String = "",
    val fields: List<HomeChangeField> = emptyList(),
) {
    init {
        require(title.isNotBlank())
    }

    val stableKey: String
        get() = listOf(domain.name, kind.name, title, beforeDetail, afterDetail).joinToString("\u0000")

    fun displayFields(): List<HomeChangeField> {
        val resolved = fields.ifEmpty { reconstructLegacyFields(domain, beforeDetail, afterDetail) }
        return resolved.filter { it.before.isNotBlank() || it.after.isNotBlank() }
    }

    /** 变更只列出真的变了的字段；新增/删除仍列出全部有值的字段。 */
    fun visibleFields(): List<HomeChangeField> {
        val resolved = displayFields()
        return when (kind) {
            DataChangeKind.MODIFIED -> resolved.filter { it.before != it.after }
            DataChangeKind.ADDED, DataChangeKind.DELETED -> resolved
        }
    }
}

internal fun reconstructLegacyFields(
    domain: HomeChangeDomain,
    beforeDetail: String,
    afterDetail: String,
): List<HomeChangeField> {
    val labels = legacyFieldLabels(domain)
    val beforeParts = splitLegacyDetail(domain, beforeDetail)
    val afterParts = splitLegacyDetail(domain, afterDetail)
    if (beforeParts == null && afterParts == null) {
        if (beforeDetail.isBlank() && afterDetail.isBlank()) return emptyList()
        return listOf(HomeChangeField("详情", beforeDetail, afterDetail))
    }
    val before = beforeParts ?: List(labels.size) { "" }
    val after = afterParts ?: List(labels.size) { "" }
    return labels.mapIndexed { index, label ->
        HomeChangeField(label, before.getOrElse(index) { "" }, after.getOrElse(index) { "" })
    }
}

private fun legacyFieldLabels(domain: HomeChangeDomain): List<String> = when (domain) {
    HomeChangeDomain.HOMEWORK -> listOf("课程", "截止时间", "状态")
    HomeChangeDomain.COURSES -> listOf("教师", "时间", "地点")
    HomeChangeDomain.EXAMS -> listOf("类型", "时间地点", "状态")
    HomeChangeDomain.GRADES -> listOf("成绩", "学分", "学期")
    HomeChangeDomain.PHYVLAB -> listOf("课程", "截止时间", "状态")
    HomeChangeDomain.CITEL, HomeChangeDomain.PHYSICS_LAB, HomeChangeDomain.PHYSICS_DEMO -> listOf("详情")
}

private fun splitLegacyDetail(domain: HomeChangeDomain, detail: String): List<String>? {
    if (detail.isBlank()) return null
    return when (domain) {
        HomeChangeDomain.PHYVLAB -> splitPhyVlabDetail(detail)
        else -> splitTrailingParts(detail, 3)
    }
}

/** `课程 · 截止时间 · 状态`，课程名里若还有 ` · ` 会留在第一段。 */
private fun splitTrailingParts(detail: String, count: Int): List<String>? {
    val parts = detail.split(" · ").map(String::trim).filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    if (parts.size <= count) return parts + List(count - parts.size) { "" }
    val head = parts.dropLast(count - 1).joinToString(" · ")
    return listOf(head) + parts.takeLast(count - 1)
}

private fun splitPhyVlabDetail(detail: String): List<String>? {
    val parts = detail.split(" · ").map(String::trim).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val status = parts.last()
    val rest = parts.dropLast(1)
    return if (rest.lastOrNull()?.startsWith("截止") == true) {
        val due = rest.last().removePrefix("截止").trim()
        val course = rest.dropLast(1).joinToString(" · ")
        listOf(course, due, status)
    } else {
        listOf(rest.joinToString(" · "), "", status)
    }
}

fun HomeChangeRecord.fieldValue(label: String): String? {
    val field = displayFields().firstOrNull { it.label == label } ?: return null
    return field.after.ifBlank { field.before }.takeIf { it.isNotBlank() }
}

fun matchHomeworkChange(
    record: HomeChangeRecord,
    homework: List<team.bjtuss.bjtuselfservice.shared.domain.homework.Homework>,
): team.bjtuss.bjtuselfservice.shared.domain.homework.Homework? {
    if (record.domain != HomeChangeDomain.HOMEWORK) return null
    val course = record.fieldValue("课程")
    val deadline = record.fieldValue("截止时间")
    val named = homework.filter { it.title == record.title }
    val byCourse = if (course == null) named else named.filter { it.courseName == course }
    // 变化记录里的截止时间是显示文字（零点截止写成前一天 24:00），旧记录仍是原文，两种都要认。
    val byDeadline = if (deadline == null) byCourse else byCourse.filter {
        it.endTime == deadline || team.bjtuss.bjtuselfservice.shared.domain.time.formatSchoolDeadline(it.endTime) == deadline
    }
    return byDeadline.firstOrNull() ?: byCourse.firstOrNull() ?: named.firstOrNull()
}

fun matchExamChange(
    record: HomeChangeRecord,
    exams: List<team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule>,
): team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule? {
    if (record.domain != HomeChangeDomain.EXAMS) return null
    val type = record.fieldValue("类型")
    val named = exams.filter { it.courseName == record.title }
    val byType = if (type == null) named else named.filter { it.examType == type }
    return byType.firstOrNull() ?: named.firstOrNull()
}

/**
 * 对学生有利的变更：作业由未做（未提交、未填写、No attempt）或评测错误变为已提交/已完成/AC，或截止时间、
 * CITEL 折扣时间（过了这个时刻得分打折）延后。
 * 界面把这一行标成绿色。只看单个字段，新增/删除记录由调用方排除。
 */
fun HomeChangeField.isFavorableChange(): Boolean {
    if (before == after) return false
    return when {
        label == "状态" || label == "完成情况" -> !isDoneStatus(before) && isDoneStatus(after)
        label.contains("截止") || label.contains("折扣") -> {
            val old = deadlineSortKey(before) ?: return false
            val new = deadlineSortKey(after) ?: return false
            new > old
        }
        else -> false
    }
}

/** 已提交、已完成，或 CITEL 编程题 AC；与 CITEL 解析端的判断口径一致。空白、“未填写”、No attempt 都不算。 */
private fun isDoneStatus(text: String): Boolean {
    val value = text.trim()
    return isCitelAccepted(value) ||
        value.equals("Submitted for grading", ignoreCase = true) ||
        value.contains("已提交") ||
        value.contains("已完成")
}

// 兼容 `2026-10-11 24:00`、`2026年10月11日 24:00 · 周日` 等显示文字；24:00 仍算当天，排序不受影响。
private val deadlineKeyPattern = Regex("""(\d{4})\D{1,2}(\d{1,2})\D{1,2}(\d{1,2})\D+?(\d{1,2}):(\d{2})""")

private fun deadlineSortKey(text: String): Long? {
    val match = deadlineKeyPattern.find(text) ?: return null
    val (year, month, day, hour, minute) = match.destructured.toList().map(String::toLong)
    return (((year * 100 + month) * 100 + day) * 100 + hour) * 100 + minute
}

data class HomeChangeFeedSnapshot(
    val baselineDomains: Set<HomeChangeDomain> = emptySet(),
    val records: List<HomeChangeRecord> = emptyList(),
)

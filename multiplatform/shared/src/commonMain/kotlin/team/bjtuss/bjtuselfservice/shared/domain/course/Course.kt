package team.bjtuss.bjtuselfservice.shared.domain.course

data class Course(
    val id: Int = 0,
    val courseId: String,
    val courseName: String,
    val courseTeacher: String,
    val courseLocationIndex: Int,
    val courseTime: String,
    val coursePlace: String,
    val isCurrentSemester: Boolean,
    val scheduleEventKind: String? = null,
    val scheduleEventDate: String? = null,
    val scheduleEventTime: String? = null,
    val eventSlotOffset: Float = 0f,
    val eventSlotHeight: Float = 1f,
)

/**
 * 学校课表地点按“大地点 → 楼宇 → 教室”返回，大地点也可能是研究院而非“××校区”。
 * 展示和系统日历统一把所有逗号分隔层级倒序；无层级分隔的自由文本保持不变。
 * 原始 Course 始终保存服务器顺序，因此该函数只在展示/导出边界调用一次。
 */
fun displayCoursePlace(value: String): String {
    val parts = value.split(Regex("[,，]"))
        .map(String::trim)
        .filter(String::isNotBlank)
    if (parts.size < 2) return value.trim()
    return parts.reversed().joinToString("-")
}

/**
 * 解析 Android v1.7.0 使用的周次文本，例如“第1-3周,第5周”。
 * 任意一段格式不合法时整条记录返回空集合，与原实现一致。
 */
fun parseCourseWeeks(courseTime: String): List<Int> = try {
    val normalized = courseTime.replace("第", "").replace("周", "")
    buildList {
        normalized.split(",").forEach { segment ->
            if ('-' in segment) {
                val range = segment.split("-")
                for (week in range[0].toInt()..range[1].toInt()) add(week)
            } else {
                add(segment.toInt())
            }
        }
    }
} catch (_: Exception) {
    emptyList()
}

/** 周次 0 表示“全部”，否则只保留包含该教学周的课程。 */
fun coursesForWeek(courses: List<Course>, week: Int): List<Course> = if (week == 0) {
    courses
} else {
    courses.filter { week in parseCourseWeeks(it.courseTime) }
}

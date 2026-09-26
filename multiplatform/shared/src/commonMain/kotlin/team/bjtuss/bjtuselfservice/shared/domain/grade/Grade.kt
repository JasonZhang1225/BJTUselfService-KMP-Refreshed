package team.bjtuss.bjtuselfservice.shared.domain.grade

/**
 * 不依赖 Room 或任何平台 API 的成绩领域模型。
 *
 * 字符串字段暂时保留 Android v1.7.0 的原始数据形态，避免在尚未迁移解析层时
 * 擅自改变服务端数据的兼容语义。
 */
data class Grade(
    val id: Int = 0,
    val courseName: String,
    val courseTeacher: String,
    val courseScore: String,
    val courseCredits: String,
    val courseYear: String,
    val semester: String,
    val detail: String = "",
)

data class GradeSelectionRecord(
    val courseName: String,
    val courseTeacher: String,
    val courseYear: String,
    val semester: String,
    val lastKnownScore: String,
    val lastKnownCredits: String,
    val occurrence: Int,
)

/**
 * 成绩列表排序。
 *
 * 「正序」严格保留教务网页 ln+lr 的行序；「逆序」将整张列表倒排。
 * - [DESCENDING] / [ASCENDING]：按分数（从高到低 / 从低到高）
 */
enum class GradeSortOrder {
    /** 教务网页原序，UI 称正序 */
    ORIGINAL,
    /** 教务网页原序倒排，UI 称逆序 */
    ORIGINAL_REVERSED,
    /** 分数从低到高 */
    ASCENDING,
    /** 分数从高到低 */
    DESCENDING,
}

/**
 * 课程性质来自培养方案页。数据库缓存存中文原文，枚举与原文的转换集中在 data 层；
 * 映射查不到的课程一律按 UNKNOWN 处理。
 * PHYSICAL_EDUCATION：学校在培养方案 PDF/教务系统/成绩单对体育课口径不一致
 * （专项课记“任选”、体育Ⅰ记“必修”），用户拍板体育课在 App 内独立为一类。
 */
enum class CourseType {
    REQUIRED,
    LIMITED,
    ELECTIVE,
    PHYSICAL_EDUCATION,
    UNKNOWN,
}

sealed interface GradeInfoResult {
    data object NoGrades : GradeInfoResult

    data class Calculated(
        val averageScore: Double,
        val formattedMessage: String,
    ) : GradeInfoResult
}

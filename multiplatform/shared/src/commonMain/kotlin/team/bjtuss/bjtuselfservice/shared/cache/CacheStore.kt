package team.bjtuss.bjtuselfservice.shared.cache

import app.cash.sqldelight.db.SqlDriver
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.domain.grade.Grade
import team.bjtuss.bjtuselfservice.shared.domain.grade.GradeSelectionRecord
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework

data class AppPreferences(
    val autoSyncGrades: Boolean = true,
    val autoSyncHomework: Boolean = true,
    val autoSyncSchedule: Boolean = true,
    val autoSyncExams: Boolean = true,
    val autoSyncPhyVlab: Boolean = true,
    val currentWeek: Int = 0,
    val checkUpdate: Boolean = true,
    val dynamicColor: Boolean = true,
    val theme: String = "System",
    val showPhyVlabInBottomNav: Boolean = true,
) {
    /** 物理在线只有一个总开关：关闭时不自动同步，也不显示入口。 */
    val isPhyVlabEnabled: Boolean
        get() = autoSyncPhyVlab && showPhyVlabInBottomNav
}

enum class CacheOpenState {
    OPENED,
    RECOVERED_AFTER_RESET,
}

data class CacheStoreHandle(
    val store: CacheStore,
    val state: CacheOpenState,
)

class CacheDatabaseOpenException(cause: Throwable) :
    IllegalStateException("无法打开本地缓存数据库。", cause)

/**
 * 普通业务缓存。不得向这里写入密码、Cookie、CSRF、CAPTCHA 或可复用会话。
 */
class CacheStore(
    private val driver: SqlDriver,
) {
    private val database = CacheDatabaseSql(driver)
    private val queries = database.cacheQueries

    fun rowCount(): Long = queries.countAllRows().executeAsOne()

    fun grades(accountScope: String): List<Grade> = queries.selectGradesByAccount(
        account_scope = requireAccountScope(accountScope),
    ) { id, courseName, teacher, score, credits, year, semester, detail ->
        Grade(
            id = id.toIntChecked(),
            courseName = courseName,
            courseTeacher = teacher,
            courseScore = score,
            courseCredits = credits,
            courseYear = year,
            semester = semester,
            detail = detail,
        )
    }.executeAsList()

    fun replaceGrades(accountScope: String, grades: List<Grade>) {
        val scope = requireAccountScope(accountScope)
        queries.transaction {
            replaceGradesInTransaction(scope, grades)
        }
    }

    /**
     * 成绩与自选记录属于同一个可见快照，必须在同一事务内替换。
     * 课程性质映射来自培养方案页：方案抓取成功时随快照整体替换，
     * 抓取失败（null）时保留上一次成功的旧映射不动。
     */
    fun replaceGradeSnapshot(
        accountScope: String,
        grades: List<Grade>,
        selections: List<GradeSelectionRecord>,
        courseTypes: Map<String, String>? = null,
    ) {
        val scope = requireAccountScope(accountScope)
        queries.transaction {
            replaceGradesInTransaction(scope, grades)
            replaceGradeSelectionsInTransaction(scope, selections)
            if (courseTypes != null) {
                replaceProgramCourseTypesInTransaction(scope, courseTypes)
            }
        }
    }

    /** 课程号 → 课程性质中文原文（必修/限选/任选），枚举转换在 data 层完成。 */
    fun programCourseTypes(accountScope: String): Map<String, String> =
        queries.selectProgramCourseTypesByAccount(requireAccountScope(accountScope))
            .executeAsList()
            .associate { it.course_id to it.course_type }

    fun replaceProgramCourseTypes(accountScope: String, courseTypes: Map<String, String>) {
        val scope = requireAccountScope(accountScope)
        queries.transaction {
            replaceProgramCourseTypesInTransaction(scope, courseTypes)
        }
    }

    fun courses(accountScope: String): List<Course> = queries.selectCoursesByAccount(
        account_scope = requireAccountScope(accountScope),
    ) { id, courseId, courseName, teacher, locationIndex, time, place, currentSemester ->
        Course(
            id = id.toIntChecked(),
            courseId = courseId,
            courseName = courseName,
            courseTeacher = teacher,
            courseLocationIndex = locationIndex.toIntChecked(),
            courseTime = time,
            coursePlace = place,
            isCurrentSemester = currentSemester != 0L,
        )
    }.executeAsList()

    fun replaceCourses(accountScope: String, courses: List<Course>) {
        val scope = requireAccountScope(accountScope)
        queries.transaction {
            replaceCoursesInTransaction(scope, courses)
        }
    }

    fun courseCurrentWeek(accountScope: String): Int =
        metadata(accountScope, COURSE_CURRENT_WEEK_KEY)
            ?.toIntOrNull()
            ?.takeIf { it in 0..COURSE_MAX_WEEK }
            ?: 0

    /** 课程行与它们对应的当前周提示属于同一个账号快照。 */
    fun replaceCourseSnapshot(accountScope: String, courses: List<Course>, currentWeek: Int) {
        val scope = requireAccountScope(accountScope)
        val safeWeek = currentWeek.takeIf { it in 1..COURSE_MAX_WEEK } ?: 0
        queries.transaction {
            replaceCoursesInTransaction(scope, courses)
            queries.putMetadata(scope, COURSE_CURRENT_WEEK_KEY, safeWeek.toString())
        }
    }

    fun exams(accountScope: String): List<ExamSchedule> = queries.selectExamsByAccount(
        account_scope = requireAccountScope(accountScope),
    ) { id, examType, courseName, timeAndPlace, status, detail ->
        ExamSchedule(
            id = id.toIntChecked(),
            examType = examType,
            courseName = courseName,
            examTimeAndPlace = timeAndPlace,
            examStatus = status,
            detail = detail,
        )
    }.executeAsList()

    fun replaceExams(accountScope: String, exams: List<ExamSchedule>) {
        val scope = requireAccountScope(accountScope)
        queries.transaction {
            queries.deleteExamsByAccount(scope)
            exams.forEach { exam ->
                queries.insertExam(
                    scope,
                    exam.examType,
                    exam.courseName,
                    exam.examTimeAndPlace,
                    exam.examStatus,
                    exam.detail,
                )
            }
        }
    }

    fun homework(accountScope: String): List<Homework> = queries.selectHomeworkByAccount(
        account_scope = requireAccountScope(accountScope),
    ) { id, upId, idSnId, score, userId, courseId, courseName, title, content,
        createDate, endTime, openDate, status, submitCount, allCount, subStatus,
        scoreId, homeworkType ->
        Homework(
            id = id.toIntChecked(),
            upId = upId.toIntChecked(),
            idSnId = idSnId?.toIntChecked(),
            score = score,
            userId = userId.toIntChecked(),
            courseId = courseId.toIntChecked(),
            courseName = courseName,
            title = title,
            content = content,
            createDate = createDate,
            endTime = endTime,
            openDate = openDate,
            status = status.toIntChecked(),
            submitCount = submitCount.toIntChecked(),
            allCount = allCount.toIntChecked(),
            subStatus = subStatus,
            scoreId = scoreId.toIntChecked(),
            homeworkType = homeworkType.toIntChecked(),
        )
    }.executeAsList()

    fun replaceHomework(accountScope: String, homework: List<Homework>) {
        val scope = requireAccountScope(accountScope)
        queries.transaction {
            queries.deleteHomeworkByAccount(scope)
            homework.forEach { item ->
                queries.insertHomework(
                    scope,
                    item.upId.toLong(),
                    item.idSnId?.toLong(),
                    item.score,
                    item.userId.toLong(),
                    item.courseId.toLong(),
                    item.courseName,
                    item.title,
                    item.content,
                    item.createDate,
                    item.endTime,
                    item.openDate,
                    item.status.toLong(),
                    item.submitCount.toLong(),
                    item.allCount.toLong(),
                    item.subStatus,
                    item.scoreId.toLong(),
                    item.homeworkType.toLong(),
                )
            }
        }
    }

    fun gradeSelections(accountScope: String): List<GradeSelectionRecord> =
        queries.selectGradeSelectionsByAccount(
            account_scope = requireAccountScope(accountScope),
        ) { courseName, teacher, year, semester, score, credits, occurrence ->
            GradeSelectionRecord(
                courseName = courseName,
                courseTeacher = teacher,
                courseYear = year,
                semester = semester,
                lastKnownScore = score,
                lastKnownCredits = credits,
                occurrence = occurrence.toIntChecked(),
            )
        }.executeAsList()

    fun replaceGradeSelections(accountScope: String, records: List<GradeSelectionRecord>) {
        val scope = requireAccountScope(accountScope)
        queries.transaction {
            replaceGradeSelectionsInTransaction(scope, records)
        }
    }

    fun metadata(accountScope: String, key: String): String? =
        queries.selectMetadata(requireAccountScope(accountScope), requireKey(key))
            .executeAsOneOrNull()

    fun putMetadata(accountScope: String, key: String, value: String) {
        queries.putMetadata(requireAccountScope(accountScope), requireKey(key), value)
    }

    /**
     * 登录成功后缓存的最小档案快照：下次冷启动时，自动登录完成前先用它渲染主界面。
     */
    fun cachedProfile(accountScope: String): StudentProfile? {
        val scope = requireAccountScope(accountScope)
        val name = metadata(scope, PROFILE_NAME_KEY)?.takeIf(String::isNotBlank) ?: return null
        return StudentProfile(
            name = name,
            studentId = scope,
            identity = metadata(scope, PROFILE_IDENTITY_KEY).orEmpty(),
            department = metadata(scope, PROFILE_DEPARTMENT_KEY).orEmpty(),
        )
    }

    fun saveCachedProfile(profile: StudentProfile) {
        val scope = requireAccountScope(profile.studentId)
        queries.transaction {
            queries.putMetadata(scope, PROFILE_NAME_KEY, profile.name)
            queries.putMetadata(scope, PROFILE_IDENTITY_KEY, profile.identity)
            queries.putMetadata(scope, PROFILE_DEPARTMENT_KEY, profile.department)
        }
    }

    fun setting(key: String): String? = queries.selectSetting(requireKey(key)).executeAsOneOrNull()

    fun putSetting(key: String, value: String) {
        queries.putSetting(requireKey(key), value)
    }

    fun deleteSetting(key: String) {
        queries.deleteSetting(requireKey(key))
    }

    fun preferences(): AppPreferences = AppPreferences(
        autoSyncGrades = booleanSetting(SettingKey.AUTO_SYNC_GRADES, true),
        autoSyncHomework = booleanSetting(SettingKey.AUTO_SYNC_HOMEWORK, true),
        autoSyncSchedule = booleanSetting(SettingKey.AUTO_SYNC_SCHEDULE, true),
        autoSyncExams = booleanSetting(SettingKey.AUTO_SYNC_EXAMS, true),
        autoSyncPhyVlab = booleanSetting(SettingKey.AUTO_SYNC_PHYVLAB, true),
        currentWeek = setting(SettingKey.CURRENT_WEEK)?.toIntOrNull()?.coerceIn(0, 56) ?: 0,
        checkUpdate = booleanSetting(SettingKey.CHECK_UPDATE, true),
        dynamicColor = booleanSetting(SettingKey.DYNAMIC_COLOR, true),
        theme = setting(SettingKey.THEME)?.takeIf(String::isNotBlank) ?: "System",
        showPhyVlabInBottomNav = booleanSetting(SettingKey.SHOW_PHYVLAB_IN_BOTTOM_NAV, true),
    )

    fun savePreferences(preferences: AppPreferences) {
        queries.transaction {
            putSetting(SettingKey.AUTO_SYNC_GRADES, preferences.autoSyncGrades.toString())
            putSetting(SettingKey.AUTO_SYNC_HOMEWORK, preferences.autoSyncHomework.toString())
            putSetting(SettingKey.AUTO_SYNC_SCHEDULE, preferences.autoSyncSchedule.toString())
            putSetting(SettingKey.AUTO_SYNC_EXAMS, preferences.autoSyncExams.toString())
            putSetting(SettingKey.AUTO_SYNC_PHYVLAB, preferences.autoSyncPhyVlab.toString())
            putSetting(SettingKey.CURRENT_WEEK, preferences.currentWeek.coerceIn(0, 56).toString())
            putSetting(SettingKey.CHECK_UPDATE, preferences.checkUpdate.toString())
            putSetting(SettingKey.DYNAMIC_COLOR, preferences.dynamicColor.toString())
            putSetting(SettingKey.THEME, preferences.theme.ifBlank { "System" })
            putSetting(SettingKey.SHOW_PHYVLAB_IN_BOTTOM_NAV, preferences.showPhyVlabInBottomNav.toString())
        }
    }

    fun claimLegacyAccountData(accountScope: String) {
        val scope = requireAccountScope(accountScope)
        queries.transaction {
            queries.claimLegacyGrades(scope)
            queries.claimLegacyCourses(scope)
            queries.claimLegacyExams(scope)
            queries.claimLegacyHomework(scope)
        }
    }

    fun clearAccount(accountScope: String) {
        val scope = requireAccountScope(accountScope)
        queries.transaction {
            queries.deleteGradesByAccount(scope)
            queries.deleteCoursesByAccount(scope)
            queries.deleteExamsByAccount(scope)
            queries.deleteHomeworkByAccount(scope)
            queries.deleteGradeSelectionsByAccount(scope)
            queries.deleteProgramCourseTypesByAccount(scope)
            queries.deleteMetadataByAccount(scope)
        }
    }

    fun clearAll() {
        queries.transaction {
            queries.deleteAllGrades()
            queries.deleteAllCourses()
            queries.deleteAllExams()
            queries.deleteAllHomework()
            queries.deleteAllGradeSelections()
            queries.deleteAllProgramCourseTypes()
            queries.deleteAllMetadata()
            queries.deleteAllSettings()
        }
    }

    fun close() {
        driver.close()
    }

    private fun replaceGradesInTransaction(scope: String, grades: List<Grade>) {
        queries.deleteGradesByAccount(scope)
        grades.forEach { grade ->
            queries.insertGrade(
                scope,
                grade.courseName,
                grade.courseTeacher,
                grade.courseScore,
                grade.courseCredits,
                grade.courseYear,
                grade.semester,
                grade.detail,
            )
        }
    }

    private fun replaceGradeSelectionsInTransaction(
        scope: String,
        records: List<GradeSelectionRecord>,
    ) {
        queries.deleteGradeSelectionsByAccount(scope)
        records.forEach { record ->
            queries.insertGradeSelection(
                scope,
                record.courseName,
                record.courseTeacher,
                record.courseYear,
                record.semester,
                record.lastKnownScore,
                record.lastKnownCredits,
                record.occurrence.toLong(),
            )
        }
    }

    private fun replaceProgramCourseTypesInTransaction(
        scope: String,
        courseTypes: Map<String, String>,
    ) {
        queries.deleteProgramCourseTypesByAccount(scope)
        courseTypes.forEach { (courseId, courseType) ->
            if (courseId.isBlank() || courseType.isBlank()) return@forEach
            queries.insertProgramCourseType(scope, courseId, courseType)
        }
    }

    private fun replaceCoursesInTransaction(scope: String, courses: List<Course>) {
        queries.deleteCoursesByAccount(scope)
        courses.forEach { course ->
            queries.insertCourse(
                scope,
                course.courseId,
                course.courseName,
                course.courseTeacher,
                course.courseLocationIndex.toLong(),
                course.courseTime,
                course.coursePlace,
                if (course.isCurrentSemester) 1L else 0L,
            )
        }
    }

    private fun booleanSetting(key: String, default: Boolean): Boolean = when (setting(key)) {
        "true" -> true
        "false" -> false
        else -> default
    }
}

fun openCacheStoreWithRecovery(
    openDriver: () -> SqlDriver,
    deleteStorage: () -> Unit,
): CacheStoreHandle {
    fun openAndProbe(): CacheStore {
        val driver = openDriver()
        return try {
            CacheStore(driver).also(CacheStore::rowCount)
        } catch (error: Exception) {
            runCatching(driver::close)
            throw error
        }
    }

    return try {
        CacheStoreHandle(openAndProbe(), CacheOpenState.OPENED)
    } catch (_: Exception) {
        try {
            deleteStorage()
            CacheStoreHandle(openAndProbe(), CacheOpenState.RECOVERED_AFTER_RESET)
        } catch (error: Exception) {
            throw CacheDatabaseOpenException(error)
        }
    }
}

private object SettingKey {
    const val AUTO_SYNC_GRADES = "auto_sync_grades"
    const val AUTO_SYNC_HOMEWORK = "auto_sync_homework"
    const val AUTO_SYNC_SCHEDULE = "auto_sync_schedule"
    const val AUTO_SYNC_EXAMS = "auto_sync_exams"
    const val AUTO_SYNC_PHYVLAB = "auto_sync_phyvlab"
    const val CURRENT_WEEK = "current_week"
    const val CHECK_UPDATE = "check_update"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val THEME = "theme"
    const val SHOW_PHYVLAB_IN_BOTTOM_NAV = "show_phyvlab_in_bottom_nav"
}

private const val COURSE_CURRENT_WEEK_KEY = "course_current_week"
private const val COURSE_MAX_WEEK = 30
private const val PROFILE_NAME_KEY = "profile_name"
private const val PROFILE_IDENTITY_KEY = "profile_identity"
private const val PROFILE_DEPARTMENT_KEY = "profile_department"

private fun requireAccountScope(value: String): String = value.trim().also {
    require(it.isNotEmpty()) { "accountScope 不能为空。" }
    require(it.length <= 128) { "accountScope 过长。" }
}

private fun requireKey(value: String): String = value.trim().also {
    require(it.isNotEmpty()) { "缓存键不能为空。" }
    require(it.length <= 128) { "缓存键过长。" }
}

private fun Long.toIntChecked(): Int {
    require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "数据库整数越界。" }
    return toInt()
}

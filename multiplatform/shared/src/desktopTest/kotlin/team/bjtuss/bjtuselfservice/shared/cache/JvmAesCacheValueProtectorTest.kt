package team.bjtuss.bjtuselfservice.shared.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.domain.grade.Grade
import team.bjtuss.bjtuselfservice.shared.domain.grade.GradeSelectionRecord
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework

class JvmAesCacheValueProtectorTest {
    private val key = ByteArray(32) { index -> (index + 1).toByte() }

    @Test
    fun randomizedAndStableValuesRoundTripWithAuthentication() {
        val protector = JvmAesCacheValueProtector(key)
        val first = protector.protect("敏感字段")
        val second = protector.protect("敏感字段")
        val stableFirst = protector.protectStable("20260001")
        val stableSecond = protector.protectStable("20260001")

        assertNotEquals(first, second)
        assertEquals(stableFirst, stableSecond)
        assertEquals("敏感字段", protector.unprotect(first))
        assertEquals("20260001", protector.unprotect(stableFirst))
        listOf(Long.MIN_VALUE, -1L, 0L, 1L, 3L, Long.MAX_VALUE).forEach { number ->
            assertEquals(number, protector.unprotectNumber(protector.protectNumber(number)))
        }
        assertNotEquals(3L, protector.protectNumber(3L))

        val tamperIndex = first.lastIndexOf(':') + 2
        val tampered = first.toCharArray().also { chars ->
            chars[tamperIndex] = if (chars[tamperIndex] == 'A') 'B' else 'A'
        }.concatToString()
        assertFails { protector.unprotect(tampered) }
    }

    @Test
    fun cacheRoundTripsButDatabaseContainsNoReadablePersonalText() {
        val directory = Files.createTempDirectory("bjtu-encrypted-cache-test-").toFile()
        val database = directory.resolve("cache.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
        CacheDatabaseSql.Schema.create(driver).value
        val store = CacheStore(driver, JvmAesCacheValueProtector(key))
        try {
            store.saveCachedProfile(
                StudentProfile(
                    name = "张三安全测试",
                    studentId = "202600019999",
                    identity = "本科生",
                    department = "计算机学院",
                ),
            )
            store.replaceGrades(
                "202600019999",
                listOf(
                    Grade(
                        courseName = "安全测试课程",
                        courseTeacher = "测试教师",
                        courseScore = "99",
                        courseCredits = "2.0",
                        courseYear = "2026-2027",
                        semester = "1",
                        detail = "仅供加密测试",
                    ),
                ),
            )
            store.replaceCourses(
                "202600019999",
                listOf(
                    Course(
                        courseId = "COURSE-SECRET-001",
                        courseName = "加密课表课程",
                        courseTeacher = "课表教师",
                        courseLocationIndex = 1,
                        courseTime = "第1-16周",
                        coursePlace = "思源楼-秘密教室",
                        isCurrentSemester = true,
                    ),
                ),
            )
            store.replaceExams(
                "202600019999",
                listOf(
                    ExamSchedule(
                        examType = "期末考试",
                        courseName = "加密考试课程",
                        examTimeAndPlace = "秘密考试时间地点",
                        examStatus = "待考试",
                        detail = "考试秘密详情",
                    ),
                ),
            )
            store.replaceHomework(
                "202600019999",
                listOf(
                    Homework(
                        upId = 1,
                        idSnId = 2,
                        score = "作业秘密分数",
                        userId = 3,
                        courseId = 4,
                        courseName = "加密作业课程",
                        title = "作业秘密标题",
                        content = "作业秘密正文",
                        createDate = "秘密创建时间",
                        endTime = "秘密截止时间",
                        openDate = "秘密开放时间",
                        status = 0,
                        submitCount = 0,
                        allCount = 1,
                        subStatus = "秘密提交状态",
                        scoreId = 5,
                        homeworkType = 6,
                    ),
                ),
            )
            store.replaceGradeSnapshot(
                accountScope = "202600019999",
                grades = store.grades("202600019999"),
                selections = listOf(
                    GradeSelectionRecord(
                        courseName = "成绩自选秘密课程",
                        courseTeacher = "自选秘密教师",
                        courseYear = "2026-2027",
                        semester = "2",
                        lastKnownScore = "自选秘密成绩",
                        lastKnownCredits = "3.0",
                        occurrence = 1,
                    ),
                ),
                courseTypes = mapOf("COURSE-TYPE-SECRET" to "秘密课程性质"),
            )
            store.savePreferences(AppPreferences(theme = "SecretTheme"))

            assertEquals("张三安全测试", store.cachedProfile("202600019999")?.name)
            assertEquals("安全测试课程", store.grades("202600019999").single().courseName)
            assertEquals("加密课表课程", store.courses("202600019999").single().courseName)
            assertEquals("考试秘密详情", store.exams("202600019999").single().detail)
            assertEquals("作业秘密正文", store.homework("202600019999").single().content)
            assertEquals(
                "成绩自选秘密课程",
                store.gradeSelections("202600019999").single().courseName,
            )
            assertEquals(
                "秘密课程性质",
                store.programCourseTypes("202600019999")["COURSE-TYPE-SECRET"],
            )
            assertEquals("SecretTheme", store.preferences().theme)
            val storedUserId = driver.executeQuery(
                identifier = null,
                sql = "SELECT user_id FROM homework_cache",
                mapper = { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(cursor.getLong(0)!!)
                },
                parameters = 0,
                binders = null,
            ).value
            assertNotEquals(3L, storedUserId)
        } finally {
            store.close()
        }

        val bytes = database.readBytes()
        listOf(
            "202600019999",
            "张三安全测试",
            "计算机学院",
            "安全测试课程",
            "测试教师",
            "仅供加密测试",
            "COURSE-SECRET-001",
            "加密课表课程",
            "思源楼-秘密教室",
            "加密考试课程",
            "考试秘密详情",
            "作业秘密标题",
            "作业秘密正文",
            "成绩自选秘密课程",
            "秘密课程性质",
            "SecretTheme",
        ).forEach { plaintext ->
            assertTrue(
                !bytes.containsSubsequence(plaintext.encodeToByteArray()),
                "database leaked plaintext: $plaintext",
            )
        }
        directory.deleteRecursively()
    }
}

private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (needle.size > size) return false
    return (0..size - needle.size).any { start ->
        needle.indices.all { offset -> this[start + offset] == needle[offset] }
    }
}

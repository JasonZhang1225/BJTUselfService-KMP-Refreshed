package team.bjtuss.bjtuselfservice.shared.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.data.grade.CacheStoreGradeLocalDataSource
import team.bjtuss.bjtuselfservice.shared.data.grade.DefaultGradeRepository
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.grade.TrainingProgramRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.domain.grade.CourseType
import team.bjtuss.bjtuselfservice.shared.domain.grade.Grade
import team.bjtuss.bjtuselfservice.shared.domain.grade.GradeSelectionRecord
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework

class AccountCacheCleanupTest {
    @Test
    fun cacheClearRemovesRefetchableDataAndPreservesLocalRecordsAndOtherAccounts() {
        verifyCacheOnlyClear(PlaintextCacheValueProtector)
    }

    @Test
    fun cacheClearClassifiesEncryptedMetadataWithoutDeletingRetainedData() {
        verifyCacheOnlyClear(JvmAesCacheValueProtector(ByteArray(32) { (it + 1).toByte() }))
    }

    private fun verifyCacheOnlyClear(protector: CacheValueProtector) = withCache(protector) { cache, _ ->
        seedAccount(cache, "fixture-a")
        seedAccount(cache, "fixture-b")
        cache.savePreferences(AppPreferences(dynamicColor = false))
        val preferences = cache.preferences()
        val otherGrades = cache.grades("fixture-b")

        cache.clearAccountCache("fixture-a")
        cache.clearAccountCache("fixture-a") // Repeating a cache clear must not remove retained records.

        assertTrue(cache.grades("fixture-a").isEmpty())
        assertTrue(cache.courses("fixture-a").isEmpty())
        assertTrue(cache.exams("fixture-a").isEmpty())
        assertTrue(cache.homework("fixture-a").isEmpty())
        assertTrue(cache.programCourseTypes("fixture-a").isEmpty())
        assertEquals(listOf(selection("fixture-a")), cache.gradeSelections("fixture-a"))
        cacheKeys.forEach { key -> assertNull(cache.metadata("fixture-a", key), key) }
        retainedKeys.forEach { key ->
            assertEquals("fixture-a:$key", cache.metadata("fixture-a", key), key)
        }

        assertEquals(otherGrades, cache.grades("fixture-b"))
        assertEquals(1, cache.courses("fixture-b").size)
        assertEquals(1, cache.exams("fixture-b").size)
        assertEquals(1, cache.homework("fixture-b").size)
        assertEquals(listOf(selection("fixture-b")), cache.gradeSelections("fixture-b"))
        assertEquals(mapOf("fixture-course" to "必修"), cache.programCourseTypes("fixture-b"))
        (cacheKeys + retainedKeys).forEach { key ->
            assertEquals("fixture-b:$key", cache.metadata("fixture-b", key), key)
        }
        assertEquals(preferences, cache.preferences())
    }

    @Test
    fun metadataDeletionFailureRollsBackBusinessRowsAndCacheMetadataTogether() = withCache { cache, driver ->
        seedAccount(cache, "fixture-a")
        val beforeGrades = cache.grades("fixture-a")
        val beforeCourses = cache.courses("fixture-a")
        val beforeExams = cache.exams("fixture-a")
        val beforeHomework = cache.homework("fixture-a")
        driver.execute(null, """
            CREATE TRIGGER reject_fixture_cache_delete BEFORE DELETE ON cache_metadata
            WHEN OLD.cache_key = 'home_status_v1'
            BEGIN SELECT RAISE(ABORT, 'fixture cache deletion failure'); END;
        """.trimIndent(), 0).value

        assertFails { cache.clearAccountCache("fixture-a") }

        assertEquals(beforeGrades, cache.grades("fixture-a"))
        assertEquals(beforeCourses, cache.courses("fixture-a"))
        assertEquals(beforeExams, cache.exams("fixture-a"))
        assertEquals(beforeHomework, cache.homework("fixture-a"))
        assertEquals(mapOf("fixture-course" to "必修"), cache.programCourseTypes("fixture-a"))
        assertEquals(listOf(selection("fixture-a")), cache.gradeSelections("fixture-a"))
        (cacheKeys + retainedKeys).forEach { key ->
            assertEquals("fixture-a:$key", cache.metadata("fixture-a", key), key)
        }
    }

    @Test
    fun blankAccountCannotClearAnyData() = withCache { cache, _ ->
        seedAccount(cache, "fixture-a")
        val before = cache.grades("fixture-a")
        assertFailsWith<IllegalArgumentException> { cache.clearAccountCache("  ") }
        assertEquals(before, cache.grades("fixture-a"))
    }

    @Test
    fun gradeSelectionIsRestoredAfterCacheClearAndRefetch() = runBlocking {
        withCache { cache, _ ->
            val account = "fixture-a"
            cache.replaceGrades(account, listOf(grade(account)))
            val repository = DefaultGradeRepository(
                accountScope = account,
                local = CacheStoreGradeLocalDataSource(cache),
                remote = object : GradeRemoteDataSource {
                    override suspend fun fetchGrades(): List<Grade> = listOf(grade(account))
                },
                programRemote = object : TrainingProgramRemoteDataSource {
                    override suspend fun fetchCourseTypes(): Map<String, CourseType> = emptyMap()
                },
            )
            val before = repository.load()
            repository.persistSelected(before.grades, setOf(before.grades.single().id))
            val storedSelection = cache.gradeSelections(account)

            cache.clearAccountCache(account)

            assertTrue(repository.load().grades.isEmpty())
            assertEquals(storedSelection, cache.gradeSelections(account))
            val refreshed = assertIs<GradeRefreshResult.Success>(repository.refresh()).snapshot
            assertEquals(setOf(refreshed.grades.single().id), refreshed.selectedGradeIds)
        }
    }

    private fun seedAccount(cache: CacheStore, account: String) {
        cache.replaceGrades(account, listOf(grade(account)))
        cache.replaceCourses(account, listOf(Course(
            courseId = "fixture-course", courseName = "课程-$account", courseTeacher = "测试教师",
            courseLocationIndex = 7, courseTime = "第1-16周", coursePlace = "测试教室", isCurrentSemester = true,
        )))
        cache.replaceExams(account, listOf(ExamSchedule(
            examType = "期末", courseName = "课程-$account", examTimeAndPlace = "第18周 测试教室",
            examStatus = "正常", detail = "测试安排",
        )))
        cache.replaceHomework(account, listOf(Homework(
            upId = 1, idSnId = 2, score = "未评分", userId = 3, courseId = 4,
            courseName = "课程-$account", title = "测试作业", content = "测试内容",
            createDate = "2026-11-01 08:00", endTime = "2026-11-30 08:00", openDate = "2026-11-01 08:00",
            status = 1, submitCount = 0, allCount = 1, subStatus = "未提交", scoreId = 5, homeworkType = 0,
        )))
        cache.replaceGradeSelections(account, listOf(selection(account)))
        cache.replaceProgramCourseTypes(account, mapOf("fixture-course" to "必修"))
        (cacheKeys + retainedKeys).forEach { key -> cache.putMetadata(account, key, "$account:$key") }
    }

    @Test
    fun fullAccountDeletionRemovesServiceConfigurationAndLocalRecordsEvenWithEncryptedOwners() =
        withCache(JvmAesCacheValueProtector(ByteArray(32) { (it + 1).toByte() })) { cache, _ ->
            seedAccount(cache, "fixture-a")
            seedAccount(cache, "fixture-b")
            cache.putMetadata("metadata-only-owner", "physicslab.enabled", "true")
            cache.savePreferences(AppPreferences(dynamicColor = false))
            val preferences = cache.preferences()
            assertEquals(setOf("fixture-a", "fixture-b", "metadata-only-owner"), cache.accountScopes())
            cache.clearAccountData("fixture-a")
            cache.clearAccountData("fixture-a")
            assertTrue(cache.grades("fixture-a").isEmpty())
            assertTrue(cache.gradeSelections("fixture-a").isEmpty())
            (cacheKeys + retainedKeys).forEach { assertNull(cache.metadata("fixture-a", it), it) }
            assertEquals(setOf("fixture-b", "metadata-only-owner"), cache.accountScopes())
            assertEquals(listOf(selection("fixture-b")), cache.gradeSelections("fixture-b"))
            assertEquals(preferences, cache.preferences())
        }

    private fun grade(account: String) = Grade(
        courseName = "课程-$account", courseTeacher = "测试教师", courseScore = "90", courseCredits = "2.0",
        courseYear = "2026-2027", semester = "1", detail = "测试成绩",
    )

    private fun selection(account: String) = GradeSelectionRecord(
        courseName = "课程-$account", courseTeacher = "测试教师", courseYear = "2026-2027", semester = "1",
        lastKnownScore = "90", lastKnownCredits = "2.0", occurrence = 0,
    )

    private inline fun withCache(
        protector: CacheValueProtector = PlaintextCacheValueProtector,
        block: (CacheStore, JdbcSqliteDriver) -> Unit,
    ) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver).value
        val cache = CacheStore(driver, protector)
        try { block(cache, driver) } finally { cache.close() }
    }

    private val cacheKeys = listOf(
        "profile_name", "profile_identity", "profile_department", "course_current_week", "academic_weeks_v1",
        "home_status_v1", "courseware_tree_v1", "phyvlab_snapshot_v1", "physicslab.results", "phydemo.results",
        "phydemo.lastSync", "citel.tasks", "citel.lastSync", "citel.problem.123", "synced_at_v1.grades",
        "refresh_synced_at_v1.home", "agent.mail.inbox", "agent.assignment_details.v1",
        "agent.assignment_details.retry_after.v1",
    )

    private val retainedKeys = listOf(
        "assignment.manualCompletion.v1", "assignment.aggregate.filters", "citel.filters", "phyvlab.filters",
        "homework_filter_courses", "homework_filter_hide_expired", "homework_filter_hide_submitted",
        "homework_filter_sort", "homework_filter_until_date", "homework_filter_until_epoch", "home_change_feed_v1",
        "physicslab.enabled", "physicslab.configured", "physicslab.username", "physicslab.twoWeekOverrides",
        "physicslab.changeBaselinePending", "phydemo.enabled", "phydemo.configured", "phydemo.username",
        "phyvlab.configured", "phyvlab.username", "citel.enabled", "citel.remember", "citel.username",
        "fixture.unclassified.metadata",
    )
}

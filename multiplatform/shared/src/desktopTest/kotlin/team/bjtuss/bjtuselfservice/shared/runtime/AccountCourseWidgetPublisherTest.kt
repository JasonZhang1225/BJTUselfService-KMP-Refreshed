package team.bjtuss.bjtuselfservice.shared.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.TimeSource
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.grade.CourseType
import team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeUiState
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSchedulePresentation
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkUiState
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLab
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabState
import team.bjtuss.bjtuselfservice.shared.feature.presentation.*
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsUiState
import team.bjtuss.bjtuselfservice.shared.feature.widget.*

class AccountCourseWidgetPublisherTest {
    @Test fun coalescesChangesWithoutUiAndIgnoresUnrelatedProgressDuringDebounce() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.publisher.start()
            f.publisher.start()
            delay(100)
            f.courses.value = f.courses.value.copy(courses = listOf(course("中间课程")))
            delay(100)
            val lastChange = TimeSource.Monotonic.markNow()
            f.courses.value = f.courses.value.copy(courses = listOf(course("最终课程")))
            val progress = launch {
                repeat(24) {
                    f.grades.value = f.grades.value.copy(isRefreshing = !f.grades.value.isRefreshing)
                    delay(100)
                }
            }
            delay(1000)
            assertTrue(f.writes.isEmpty)
            val snapshot = f.next()
            assertTrue(lastChange.elapsedNow().inWholeMilliseconds >= COURSE_WIDGET_WRITE_DEBOUNCE_MILLIS)
            assertTrue(snapshot.days.flatMap { it.sessions }.any { it.name == "最终课程" })
            assertTrue(snapshot.days.flatMap { it.sessions }.any { it.isLab && it.name.startsWith("实验") })
            assertFalse(snapshot.days.flatMap { it.sessions }.any { it.name == "中间课程" })
            f.assertOriginalContent(snapshot)
            progress.join()
            assertEquals(1, f.writeCount.get())
        } finally { f.close() }
    }

    @Test fun retainsDedupAcrossReaderRecreationAndIgnoresGeneratedTime() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.publisher.start()
            val first = f.next()
            delay(50) // Let the completed publication store its dedup state.
            var reader = launch { f.model.state.collect() }
            yield()
            reader.cancelAndJoin()
            // A changed map key is relevant, but an unused course type does not change days.
            f.grades.value = f.grades.value.copy(courseTypesByCode = mapOf("unused" to CourseType.REQUIRED))
            f.publisher.start()
            reader = launch { f.model.state.collect() }
            delay(COURSE_WIDGET_WRITE_DEBOUNCE_MILLIS + 250)
            reader.cancelAndJoin()
            assertEquals(1, f.writeCount.get())
            assertTrue(f.writes.isEmpty)
            f.courses.value = f.courses.value.copy(courses = listOf(course("地点更新").copy(coursePlace = "新教室")))
            val second = f.next()
            assertTrue(second.generatedAt > first.generatedAt)
            assertTrue(second.days.flatMap { it.sessions }.any { it.place == "新教室" })
            assertEquals(2, f.writeCount.get())
        } finally { f.close() }
    }

    @Test fun skipsUnavailableInputsAndCancelsPendingWriteWhenAccountEnds() = runBlocking<Unit> {
        val f = Fixture(empty = true)
        try {
            f.publisher.start()
            delay(COURSE_WIDGET_WRITE_DEBOUNCE_MILLIS + 100)
            assertEquals(0, f.writeCount.get())
            f.courses.value = f.courses.value.copy(courses = listOf(course("课程")), homeAcademicWeeks = emptyList(), currentWeek = 0)
            delay(COURSE_WIDGET_WRITE_DEBOUNCE_MILLIS + 100)
            assertEquals(0, f.writeCount.get())
            f.courses.value = f.courses.value.copy(homeAcademicWeeks = weeks, currentWeek = 4)
            withTimeout(5000) { f.model.state.first { it.inputs.courseState.homeAcademicWeeks == weeks } }
            delay(200)
            f.close()
            assertEquals(0, f.courses.subscriptionCount.value)
            f.courses.value = f.courses.value.copy(courses = listOf(course("退出后的迟到数据")))
            delay(COURSE_WIDGET_WRITE_DEBOUNCE_MILLIS + 100)
            assertEquals(0, f.writeCount.get())
        } finally { f.close() }
    }

    @Test fun nextAccountPublishesItsOwnSnapshotAndRetiredAccountCannotPublishAgain() = runBlocking<Unit> {
        val first = Fixture()
        val next = Fixture()
        try {
            first.publisher.start()
            val old = first.next()
            first.close()
            next.publisher.start()
            first.courses.value = first.courses.value.copy(courses = listOf(course("旧账号迟到数据")))
            val fresh = next.next()
            assertEquals(old.days, fresh.days)
            assertEquals(1, first.writeCount.get())
            assertEquals(1, next.writeCount.get())
            assertTrue(first.writes.isEmpty)
        } finally { first.close(); next.close() }
    }

    @Test fun followsBeijingMidnightFromInstantEvenWhenLocalWallClockStaysOnSunday() = runBlocking<Unit> {
        val f = Fixture(instant = Instant.parse("2026-10-11T15:59:00Z"))
        try {
            f.publisher.start()
            val sunday = f.next()
            assertEquals("2026-10-05", sunday.days.first().date)
            f.assertOriginalContent(sunday)
            // New York is still Sunday, while the school date has become Monday.
            f.homework.value = f.homework.value.copy(nowInstant = Instant.parse("2026-10-11T16:01:00Z"))
            val monday = f.next()
            assertEquals("2026-10-12", monday.days.first().date)
            f.assertOriginalContent(monday)
            assertEquals(2, f.writeCount.get())
        } finally { f.close() }
    }

    private class Fixture(empty: Boolean = false, instant: Instant = Instant.parse("2026-10-12T00:00:00Z")) {
        val owner = AccountWork()
        val courses = MutableStateFlow(CourseScheduleUiState(
            courses = if (empty) emptyList() else listOf(course("课程")),
            homeAcademicWeeks = weeks, currentWeek = 4,
        ))
        val grades = MutableStateFlow(GradeUiState())
        val homework = MutableStateFlow(HomeworkUiState(nowInstant = instant,
            now = LocalDateTime(2026, 10, 11, 11, 59), timeZone = TimeZone.of("America/New_York")))
        private val settings = MutableStateFlow(SettingsUiState(AppPreferences(physicsLabEnabled = true)))
        private val labs = MutableStateFlow(PhysicsLabState(labs = if (empty) emptyList() else
            listOf(PhysicsLab(LocalDate(2026, 10, 12), 1, "实验", "实验室", "教师", 1))))
        val model = AccountPageStateModel(PresentationSources(courses = courses, grades = grades,
            homework = homework, settings = settings, physicsLab = labs), owner.scope(Dispatchers.Default), owner)
        val writes = Channel<CourseWidgetSnapshot>(Channel.UNLIMITED)
        val writeCount = AtomicInteger()
        val publisher = AccountCourseWidgetPublisher({ model.state }, owner, owner.scope(Dispatchers.Unconfined)) { json ->
            val snapshot = assertNotNull(decodeCourseWidgetSnapshot(json))
            writeCount.incrementAndGet()
            check(writes.trySend(snapshot).isSuccess)
        }
        suspend fun next(): CourseWidgetSnapshot = withTimeout(5000) { writes.receive() }
        suspend fun close() { owner.stop(); owner.join() }
        fun assertOriginalContent(snapshot: CourseWidgetSnapshot) {
            val state = model.state.value
            val input = state.inputs
            val today = input.homeworkState.nowInstant.toLocalDateTime(SchoolTimeZone).date
            val (start, count) = courseWidgetRange(today)
            val expected = buildCourseWidgetSnapshot(HomeSchedulePresentation(
                courses = input.courseState.courses, supplementalCourses = state.schedule.homeEvents,
                academicWeeks = input.courseState.homeAcademicWeeks, currentWeek = input.courseState.currentWeek,
                today = today, staggeredClassTimes = input.settingsState.preferences.staggeredSecondSlot,
            ), start, snapshot.generatedAt, count, input.gradeState.courseTypesByCode)
            assertEquals(expected, snapshot)
        }
    }

    private companion object {
        val weeks = listOf(
            OccupancyWeekDate(3, "10/5", "10/11", LocalDate(2026, 10, 5)),
            OccupancyWeekDate(4, "10/12", "10/18", LocalDate(2026, 10, 12)),
            OccupancyWeekDate(5, "10/19", "10/25", LocalDate(2026, 10, 19)),
        )
        fun course(name: String) = Course(courseId = "fixture-course", courseName = name, courseTeacher = "教师",
            courseLocationIndex = 1, courseTime = "第1-5周", coursePlace = "教室", isCurrentSemester = false)
    }
}

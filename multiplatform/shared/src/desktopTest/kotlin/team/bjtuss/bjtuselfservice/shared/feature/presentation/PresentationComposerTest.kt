package team.bjtuss.bjtuselfservice.shared.feature.presentation

import kotlinx.datetime.LocalDate
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSource
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelState
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTask
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeUiState
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItemState
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkContentSource
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkUiState
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLab
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabState
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabUiState
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsUiState

class PresentationComposerTest {
    @Test fun scheduleAndHomeUseTheirOwnCalendarsAndPreserveTheUsersSelectedWeek() {
        val input = PresentationInputs(
            settingsState = SettingsUiState(AppPreferences(physicsLabEnabled = true)),
            courseState = CourseScheduleUiState(academicWeeks = listOf(week(1, "2026-09-21")),
                homeAcademicWeeks = listOf(week(3, "2026-10-05")), selectedWeek = 5, followCurrentWeek = false),
            physicsLabState = PhysicsLabState(labs = listOf(PhysicsLab(LocalDate.parse("2026-09-22"), 1, "实验", "测试室", "教师", 1))),
        )
        val composer = PresentationComposer()
        val first = composer.compose(input)
        assertTrue(first.schedule.events.isNotEmpty())
        assertTrue(first.schedule.homeEvents.isEmpty())
        assertEquals(5, first.schedule.course.selectedWeek)
        assertFalse(first.schedule.course.followCurrentWeek)
        assertTrue(input.courseState.supplementalCourses.isEmpty())
        val second = composer.compose(input.copy(courseState = input.courseState.copy(homeAcademicWeeks = listOf(week(1, "2026-09-21")))))
        assertTrue(second.schedule.homeEvents.isNotEmpty())
        assertSame(first.schedule.events, second.schedule.events)
        val hidden = composer.compose(input.copy(settingsState = SettingsUiState(AppPreferences(physicsLabEnabled = false))))
        assertTrue(hidden.schedule.events.isEmpty())
        assertTrue(hidden.schedule.course.physicsLabs.isEmpty())
        assertEquals(1, input.physicsLabState.labs.size)
    }

    @Test fun assignmentsIncludeEveryPhysicsCourseAndIgnorePageSelection() {
        val one = activity(11, 1)
        val two = activity(12, 2)
        val input = PresentationInputs(
            settingsState = SettingsUiState(AppPreferences(citelEnabled = true)),
            homeworkState = HomeworkUiState(homework = listOf(homework()), isLoading = false),
            phyVlabState = PhyVlabUiState(activities = listOf(one), allActivities = listOf(one, two)),
            citelState = CitelState(tasks = listOf(CitelTask(21, 3, "第三门课", "CITEL作业", "https://fixture.invalid", false, dueTime = 1_790_000_000))),
        )
        val composer = PresentationComposer()
        val first = composer.compose(input)
        assertEquals(4, first.assignments.items.size)
        assertEquals(2, first.assignments.items.count { it.source == AssignmentSource.PHYVLAB })
        assertEquals(AssignmentSource.entries.toSet(), first.assignments.items.map { it.source }.toSet())
        val selected = composer.compose(input.copy(phyVlabState = input.phyVlabState.copy(activities = listOf(two), isDetailLoading = true)))
        assertSame(first.assignments.items, selected.assignments.items)
        val hidden = composer.compose(input.copy(settingsState = SettingsUiState(AppPreferences(autoSyncPhyVlab = false, citelEnabled = false))))
        assertEquals(listOf(AssignmentSource.COURSE_PLATFORM), hidden.assignments.items.map { it.source })
        assertEquals(listOf(AssignmentSource.COURSE_PLATFORM), hidden.assignments.sync.map { it.source })
    }

    @Test fun refreshFailurePreservesCachedAssignmentsAndReportsTheFailure() {
        val input = PresentationInputs(homeworkState = HomeworkUiState(homework = listOf(homework()),
            isLoading = false, source = HomeworkContentSource.CACHE))
        val composer = PresentationComposer()
        val first = composer.compose(input)
        val failed = composer.compose(input.copy(homeworkState = input.homeworkState.copy(failure = HomeworkSyncFailure.NETWORK)))
        assertSame(first.assignments.items, failed.assignments.items)
        assertTrue("课程平台作业" in failed.home.failureItems)
        assertEquals(HomeSyncItemState.FAILED, failed.home.syncItems.single { it.title == "课程平台作业" }.state)
        assertEquals(first.assignments.items, failed.assignments.items)
    }

    @Test fun progressChangesReuseUnchangedDerivedListsAndIdenticalInputsReuseTheWholeSnapshot() {
        val input = PresentationInputs(homeworkState = HomeworkUiState(homework = listOf(homework())))
        val composer = PresentationComposer()
        val first = composer.compose(input)
        assertSame(first, composer.compose(input.copy()))
        val progress = composer.compose(input.copy(gradeState = input.gradeState.copy(isRefreshing = true)))
        assertSame(first.assignments.items, progress.assignments.items)
        assertSame(first.schedule, progress.schedule)
        val refreshed = composer.compose(input.copy(homeworkState = input.homeworkState.copy(homework = input.homeworkState.homework.map { it.copy(title = "更新后的作业") })))
        assertNotSame(first.assignments.items, refreshed.assignments.items)
        assertEquals("更新后的作业", refreshed.assignments.items.single().homework?.title)
    }

    @Test fun loginAndRecoveryPhasesAreIndependentOfSourceLoading() {
        val input = PresentationInputs(gradeState = GradeUiState(isLoading = false),
            courseState = CourseScheduleUiState(isLoading = false), examState = ExamScheduleUiState(isLoading = false),
            homeworkState = HomeworkUiState(isLoading = false))
        val composer = PresentationComposer()
        assertFalse(composer.compose(input).home.syncInProgress)
        val login = composer.compose(input.copy(entryLoggingIn = true))
        assertTrue(login.home.syncInProgress)
        assertEquals(HomeSyncItemState.SYNCING, login.home.syncItems.first().state)
        val recovery = composer.compose(input.copy(recoveryInProgress = true))
        assertTrue(recovery.home.syncInProgress)
        assertEquals(HomeSyncItemState.SUCCESS, recovery.home.syncItems.first().state)
        assertFalse(composer.compose(input).home.syncInProgress)
    }

    private fun week(number: Int, date: String) = OccupancyWeekDate(number, "", "", LocalDate.parse(date))
    private fun activity(id: Int, course: Int) = PhyVlabActivity(id, course, "课程$course", "物理作业$id", "作业",
        "https://fixture.invalid/$id", dueTimestamp = 1_790_000_000)
    private fun homework() = Homework(id = 1, upId = 1, idSnId = null, score = "", userId = 1, courseId = 1,
        courseName = "第一门课", title = "课程作业", content = "", createDate = "2026-09-21 08:00:00",
        endTime = "2026-09-23 18:00:00", openDate = "2026-09-21 08:00:00", status = 1,
        submitCount = 0, allCount = 1, subStatus = "未提交", scoreId = 0, homeworkType = 0)
}

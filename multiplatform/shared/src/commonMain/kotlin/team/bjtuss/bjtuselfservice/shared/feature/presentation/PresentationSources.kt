package team.bjtuss.bjtuselfservice.shared.feature.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelState
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeUiState
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeUiState
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkUiState
import team.bjtuss.bjtuselfservice.shared.feature.library.LibraryUiState
import team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoState
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabState
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabUiState
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsUiState

/** Only observable data needed by cross-feature display rules. No repositories, navigation or platform hosts. */
internal class PresentationSources(
    val settings: StateFlow<SettingsUiState> = MutableStateFlow(SettingsUiState(AppPreferences())),
    val grades: StateFlow<GradeUiState> = MutableStateFlow(GradeUiState()),
    val courses: StateFlow<CourseScheduleUiState> = MutableStateFlow(CourseScheduleUiState()),
    val exams: StateFlow<ExamScheduleUiState> = MutableStateFlow(ExamScheduleUiState()),
    val homework: StateFlow<HomeworkUiState> = MutableStateFlow(HomeworkUiState()),
    val phyVlab: StateFlow<PhyVlabUiState> = MutableStateFlow(PhyVlabUiState()),
    val citel: StateFlow<CitelState> = MutableStateFlow(CitelState()),
    val physicsLab: StateFlow<PhysicsLabState> = MutableStateFlow(PhysicsLabState()),
    val physicsDemo: StateFlow<PhysicsDemoState> = MutableStateFlow(PhysicsDemoState()),
    val home: StateFlow<HomeUiState> = MutableStateFlow(HomeUiState()),
    val library: StateFlow<LibraryUiState> = MutableStateFlow(LibraryUiState()),
    val entryLoggingIn: StateFlow<Boolean> = MutableStateFlow(false),
    val recoveryInProgress: StateFlow<Boolean> = MutableStateFlow(false),
) {
    fun snapshot() = PresentationInputs(settings.value, grades.value, courses.value, exams.value, homework.value,
        phyVlab.value, citel.value, physicsLab.value, physicsDemo.value, home.value, library.value,
        entryLoggingIn.value, recoveryInProgress.value)

    val changes: List<StateFlow<*>> = listOf(settings, grades, courses, exams, homework, phyVlab, citel,
        physicsLab, physicsDemo, home, library, entryLoggingIn, recoveryInProgress)
}

internal data class PresentationInputs(
    val settingsState: SettingsUiState = SettingsUiState(AppPreferences()),
    val gradeState: GradeUiState = GradeUiState(),
    val courseState: CourseScheduleUiState = CourseScheduleUiState(),
    val examState: ExamScheduleUiState = ExamScheduleUiState(),
    val homeworkState: HomeworkUiState = HomeworkUiState(),
    val phyVlabState: PhyVlabUiState = PhyVlabUiState(),
    val citelState: CitelState = CitelState(),
    val physicsLabState: PhysicsLabState = PhysicsLabState(),
    val physicsDemoState: PhysicsDemoState = PhysicsDemoState(),
    val homeState: HomeUiState = HomeUiState(),
    val libraryState: LibraryUiState = LibraryUiState(),
    val entryLoggingIn: Boolean = false,
    val recoveryInProgress: Boolean = false,
)

package team.bjtuss.bjtuselfservice.shared.feature.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryReminder
import team.bjtuss.bjtuselfservice.shared.data.library.libraryReminderNow
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AggregatedAssignment
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSourceSync
import team.bjtuss.bjtuselfservice.shared.feature.citel.citelAgendaEvents
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItem
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItemState
import team.bjtuss.bjtuselfservice.shared.runtime.AccountWork
import team.bjtuss.bjtuselfservice.shared.runtime.accountState

internal data class ScheduleDisplayState(
    val course: CourseScheduleUiState,
    val events: List<Course>,
    val homeEvents: List<Course>,
    val citelEvents: List<PhyVlabEvent>,
)

internal data class AssignmentDisplayState(val items: List<AggregatedAssignment>, val sync: List<AssignmentSourceSync>)
internal data class HomeDisplayState(
    val syncItems: List<HomeSyncItem>, val failureItems: List<String>, val syncInProgress: Boolean,
    val libraryReminder: LibraryReminder?,
)
internal data class AccountPageState(
    val inputs: PresentationInputs, val schedule: ScheduleDisplayState,
    val assignments: AssignmentDisplayState, val home: HomeDisplayState,
)

/** One account owns one producer; every shared page/native destination observes its read-only output. */
internal class AccountPageStateModel(
    sources: PresentationSources,
    scope: CoroutineScope,
    accountWork: AccountWork? = null,
) {
    private val composer = PresentationComposer()
    private val mutableState = accountWork.accountState(composer.compose(sources.snapshot()))
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            // Read current values after each signal: an update before collection starts must not be lost.
            merge(*sources.changes.map { it.map { Unit } }.toTypedArray()).conflate().collect {
                mutableState.value = composer.compose(sources.snapshot())
            }
        }
    }
}

/** Preserves existing display rules and reuses derived values whose inputs have not changed. */
internal class PresentationComposer {
    private var previous: AccountPageState? = null
    private var scheduleKey: List<Any?>? = null
    private var homeScheduleKey: List<Any?>? = null
    private var assignmentKey: List<Any?>? = null
    private var citelKey: List<Any?>? = null
    private var reminderLoans: Any? = null
    private var reminder = LibraryReminder.Empty

    fun compose(input: PresentationInputs): AccountPageState {
        val before = previous
        if (before?.inputs == input) return before
        val courseState = input.courseState
        val examState = input.examState
        val homeworkState = input.homeworkState
        val phyVlabState = input.phyVlabState
        val citelState = input.citelState
        val physicsLabState = input.physicsLabState
        val physicsDemoState = input.physicsDemoState
        val gradeState = input.gradeState
        val homeState = input.homeState
        val libraryState = input.libraryState
        val physicsLabEnabled = input.settingsState.preferences.isPhysicsLabEnabled
        val phyVlabEnabled = input.settingsState.preferences.isPhyVlabEnabled
        val citelEnabled = input.settingsState.preferences.isCitelEnabled
        val labs = if (physicsLabEnabled) physicsLabState.labs else emptyList()
        val bookings = if (physicsDemoState.enabled) physicsDemoState.bookings else emptyList()
        val nextScheduleKey = listOf(courseState.academicWeeks, examState.exams, labs, bookings)
        val events = if (before != null && scheduleKey == nextScheduleKey) before.schedule.events else
            composeScheduleEvents(courseState, examState, physicsLabState, physicsDemoState, physicsLabEnabled)
        val nextHomeScheduleKey = listOf(courseState.homeAcademicWeeks, examState.exams, labs, bookings)
        val homeEvents = if (before != null && homeScheduleKey == nextHomeScheduleKey) before.schedule.homeEvents else
            composeHomeScheduleEvents(courseState, examState, physicsLabState, physicsDemoState, physicsLabEnabled)
        val courseWithEvents = if (before != null && before.inputs.courseState === courseState &&
            before.schedule.events === events && before.schedule.course.physicsLabs == labs &&
            before.schedule.course.physicsDemoBookings == bookings) before.schedule.course else
            composeScheduleStateWithEvents(courseState, events, physicsLabState, physicsDemoState, physicsLabEnabled)
        val nextCitelKey = listOf(citelState.tasks, citelEnabled)
        val citelEvents = if (before != null && citelKey == nextCitelKey) before.schedule.citelEvents else
            if (citelEnabled) citelAgendaEvents(citelState.tasks) else emptyList()
        val nextAssignmentKey = listOf(homeworkState.homework,
            if (phyVlabEnabled) phyVlabState.allActivities else emptyList(),
            if (citelEnabled) citelState.tasks else emptyList())
        val assignments = if (before != null && assignmentKey == nextAssignmentKey) before.assignments.items else
            composeAggregatedAssignments(homeworkState, phyVlabState.allActivities, citelState, phyVlabEnabled, citelEnabled)
        val assignmentSync = retain(composeAssignmentSync(homeworkState, phyVlabState, citelState, phyVlabEnabled, citelEnabled), before?.assignments?.sync)
        val isLoggingIn = input.entryLoggingIn
        val sessionRecoveryInProgress = input.recoveryInProgress
        val homeSyncItems = composeHomeSyncItems(input)
        val failures = retain(homeSyncItems.filter { it.state == HomeSyncItemState.FAILED }.map(HomeSyncItem::title), before?.home?.failureItems)
        val busy = isLoggingIn || sessionRecoveryInProgress || homeSyncItems.any { it.state == HomeSyncItemState.SYNCING }
        if (reminderLoans != libraryState.loans) {
            reminder = libraryReminderNow(libraryState.loans)
            reminderLoans = libraryState.loans
        }
        val result = AccountPageState(input,
            retain(ScheduleDisplayState(courseWithEvents, events, homeEvents, citelEvents), before?.schedule),
            retain(AssignmentDisplayState(assignments, assignmentSync), before?.assignments),
            retain(HomeDisplayState(retain(homeSyncItems, before?.home?.syncItems), failures, busy, reminder.takeIf { libraryState.user != null }), before?.home))
        scheduleKey = nextScheduleKey; homeScheduleKey = nextHomeScheduleKey
        assignmentKey = nextAssignmentKey; citelKey = nextCitelKey
        previous = result
        return result
    }

    private fun <T> retain(next: T, old: T?): T = if (old != null && old == next) old else next
}

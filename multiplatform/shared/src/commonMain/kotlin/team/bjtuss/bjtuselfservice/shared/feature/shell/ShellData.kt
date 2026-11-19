package team.bjtuss.bjtuselfservice.shared.feature.shell

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.distinctUntilChanged
import team.bjtuss.bjtuselfservice.shared.feature.presentation.AccountPageState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryReminder
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeRecord
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AggregatedAssignment
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSourceSync
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelState
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomUiState
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyUiState
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareUiState
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeUiState
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItem
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItemState
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeUiState
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkUiState
import team.bjtuss.bjtuselfservice.shared.feature.library.LibraryUiState
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUiState
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUnreadSummary
import team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoState
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabState
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabAccountState
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabUiState
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsUiState

/**
 * 页面组装所需的只读状态快照。
 *
 * 应用壳不再自己订阅业务状态，也不在文件里推导首页/课表/作业的聚合结果；这些由账号拥有的展示模型完成；[rememberShellData] 只订阅和转交结果。壳层只把 [ShellData] 交给页面，页面只读取自己需要的字段。
 */
@Immutable
internal data class ShellData(
    val settingsState: SettingsUiState,
    val gradeState: GradeUiState,
    val courseState: CourseScheduleUiState,
    val examState: ExamScheduleUiState,
    val homeworkState: HomeworkUiState,
    val coursewareState: CoursewareUiState,
    val classroomState: ClassroomUiState,
    val classroomOccupancyState: ClassroomOccupancyUiState,
    val mailboxState: MailboxUiState,
    val mailboxUnread: MailboxUnreadSummary?,
    val phyVlabState: PhyVlabUiState,
    val phyVlabAccountState: PhyVlabAccountState,
    val citelState: CitelState,
    val homeState: HomeUiState,
    val libraryState: LibraryUiState,
    val physicsLabState: PhysicsLabState,
    val physicsDemoState: PhysicsDemoState,
    val homeChanges: List<HomeChangeRecord>,
    val scheduleStateWithEvents: CourseScheduleUiState,
    val homeScheduleEvents: List<Course>,
    val citelEvents: List<PhyVlabEvent>,
    val aggregatedAssignments: List<AggregatedAssignment>,
    val assignmentSync: List<AssignmentSourceSync>,
    val homeSyncItems: List<HomeSyncItem>,
    val homeSyncFailureItems: List<String>,
    val homeSyncInProgress: Boolean,
    val libraryReminder: LibraryReminder?,
) {
    val mailboxReadyState: MailboxUiState.Ready? get() = mailboxState as? MailboxUiState.Ready
    val mailboxMessageLoading: Boolean get() = mailboxReadyState?.isMessageLoading == true
    val phyVlabEnabled: Boolean get() = settingsState.preferences.isPhyVlabEnabled
    val physicsLabEnabled: Boolean get() = settingsState.preferences.isPhysicsLabEnabled
    val citelEnabled: Boolean get() = settingsState.preferences.isCitelEnabled
    val staggeredClassTimes: Boolean get() = settingsState.preferences.staggeredSecondSlot
}

@Composable
internal fun rememberShellData(
    session: AuthenticatedSession,
    sessionRecoveryInProgress: Boolean,
    route: AppRoute? = null,
    overlays: Boolean = false,
): ShellData {
    val scope = rememberCoroutineScope()
    val model = session.pageStateModel ?: remember(session, scope) { session.createPageStateModel(scope) }
    val initialPresentation = remember(model, route, overlays) { model.state.value }
    val selected = remember(model, route, overlays) {
        model.state.runningFold(initialPresentation) { previous, next ->
            if (shellPresentationKey(previous, route, overlays) == shellPresentationKey(next, route, overlays)) previous else next
        }.distinctUntilChanged()
    }
    val presentation by selected.collectAsState(initial = initialPresentation)
    val input = presentation.inputs
    val coursewareState = collectShellState(session.coursewareModel.state, (route == null && !overlays) || route in setOf(AppSection.COURSEWARE, AppSection.PLAYBACK, PlaybackDetailRoute))
    val classroomState = collectShellState(session.classroomModel.state, (route == null && !overlays) || route in setOf(AppSection.CLASSROOMS, ClassroomDetailRoute))
    val classroomOccupancyState = collectShellState(session.classroomOccupancyModel.state, (route == null && !overlays) || route in setOf(AppSection.CLASSROOM_OCCUPANCY, ClassroomOccupancyDetailRoute))
    val mailboxState = collectShellState(session.mailboxModel.state, (route == null && !overlays) || route in setOf(AppSection.MAILBOX, MailboxDetailRoute, MailboxComposeRoute))
    val mailboxUnread = collectShellState(session.mailboxModel.unreadSummary, (route == null && !overlays) || route in setOf(AppSection.HOME))
    val phyVlabAccountState = collectShellState((session.phyVlabAccountModel?.state ?: remember(session) {
        kotlinx.coroutines.flow.MutableStateFlow(PhyVlabAccountState())
    }), (route == null && !overlays) || route in setOf(AppSection.PHYVLAB, PhyVlabDetailRoute, PhyVlabSettingsRoute, AppSection.SETTINGS))
    val homeChanges = collectShellState(session.homeChangeFeed.records, (route == null && !overlays) || route in setOf(AppSection.HOME))
    return ShellData(
        settingsState = input.settingsState,
        gradeState = input.gradeState,
        courseState = input.courseState,
        examState = input.examState,
        homeworkState = input.homeworkState,
        phyVlabState = input.phyVlabState,
        citelState = input.citelState,
        homeState = input.homeState,
        libraryState = input.libraryState,
        physicsLabState = input.physicsLabState,
        physicsDemoState = input.physicsDemoState,
        coursewareState = coursewareState,
        classroomState = classroomState,
        classroomOccupancyState = classroomOccupancyState,
        mailboxState = mailboxState,
        mailboxUnread = mailboxUnread,
        phyVlabAccountState = phyVlabAccountState,
        homeChanges = homeChanges,
        scheduleStateWithEvents = presentation.schedule.course,
        homeScheduleEvents = presentation.schedule.homeEvents,
        citelEvents = presentation.schedule.citelEvents,
        aggregatedAssignments = presentation.assignments.items,
        assignmentSync = presentation.assignments.sync,
        homeSyncItems = presentation.home.syncItems,
        homeSyncFailureItems = presentation.home.failureItems,
        homeSyncInProgress = presentation.home.syncInProgress || sessionRecoveryInProgress,
        libraryReminder = presentation.home.libraryReminder,
    )
}

/** Subscribe only where a consumer needs this source; unused fields are plain snapshots. */
@Composable
private fun <T> collectShellState(state: StateFlow<T>, enabled: Boolean): T =
    if (enabled) { val value by state.collectAsState(); value } else state.value

internal fun shellPresentationKey(state: AccountPageState, route: AppRoute?, overlays: Boolean = false): Any {
    val input = state.inputs
    if (overlays) return listOf(input.settingsState, input.gradeState.pendingChangeNotice, state.home,
        input.citelState.message, input.citelState.fromCache)
    val common = listOf(input.settingsState.preferences, input.entryLoggingIn, input.recoveryInProgress)
    val selected: Any = when (route) {
        AppSection.SETTINGS -> listOf(input.settingsState, input.citelState, input.physicsLabState, input.physicsDemoState)
        AppSection.MORE -> input.physicsDemoState
        AppSection.GRADES -> input.gradeState
        AppSection.SCHEDULE -> listOf(state.schedule.course, input.gradeState.courseTypesByCode,
            input.examState, input.physicsLabState, input.physicsDemoState)
        AppSection.EXAMS, ExamDetailRoute -> input.examState
        AppSection.HOMEWORK, HomeworkDetailRoute -> input.homeworkState
        AppSection.ASSIGNMENTS -> listOf(input.homeworkState, input.phyVlabState, input.citelState, state.assignments)
        AppSection.PHYVLAB, PhyVlabDetailRoute, PhyVlabSettingsRoute -> input.phyVlabState
        AppSection.CITEL, CitelDetailRoute, CitelSettingsRoute -> input.citelState
        AppSection.PHYSICS_LAB, PhysicsLabSettingsRoute -> input.physicsLabState
        PhysicsDemoRoute, PhysicsDemoSettingsRoute -> input.physicsDemoState
        AppSection.LIBRARY -> listOf(input.libraryState, state.home.libraryReminder)
        AppSection.COURSEWARE, AppSection.PLAYBACK, PlaybackDetailRoute,
        AppSection.CLASSROOMS, ClassroomDetailRoute, AppSection.CLASSROOM_OCCUPANCY, ClassroomOccupancyDetailRoute,
        AppSection.MAILBOX, MailboxDetailRoute, MailboxComposeRoute, AppSection.CALENDAR,
        AppSection.REPORT_CARD_DOWNLOAD, AppSection.CHANGELOG, AiAssistantSettingsRoute -> Unit
        else -> state
    }
    return common + selected
}

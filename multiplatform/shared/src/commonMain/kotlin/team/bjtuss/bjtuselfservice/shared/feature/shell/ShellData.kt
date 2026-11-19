package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabAccountState
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryReminder
import team.bjtuss.bjtuselfservice.shared.data.library.libraryReminderNow
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeRecord
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AggregatedAssignment
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSourceSync
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelState
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomUiState
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyUiState
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.course.scheduleEventCourses
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareUiState
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeUiState
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItem
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItemState
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeUiState
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkContentSource
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkUiState
import team.bjtuss.bjtuselfservice.shared.feature.library.LibraryUiState
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUiState
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUnreadSummary
import team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoState
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabState
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PHYVLAB_ACCOUNT_REJECTED_MESSAGE
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PHYVLAB_CAPTCHA_REQUIRED_MESSAGE
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabContentSource
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabUiState
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsUiState

/** 目的地、布局和弹层共用的显示状态，由现有会话提供。 */
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
): ShellData {
    val entryLoggingIn = session.entryLoggingIn
    val gradeModel = session.gradeModel
    val courseScheduleModel = session.courseScheduleModel
    val examScheduleModel = session.examScheduleModel
    val homeworkModel = session.homeworkModel
    val coursewareModel = session.coursewareModel
    val classroomModel = session.classroomModel
    val classroomOccupancyModel = session.classroomOccupancyModel
    val settingsModel = session.settingsModel
    val mailboxModel = session.mailboxModel
    val phyVlabModel = session.phyVlabModel
    val homeModel = session.homeModel
    val homeChangeFeed = session.homeChangeFeed
    val gradeState by gradeModel.state.collectAsState()
    val courseState by courseScheduleModel.state.collectAsState()
    val examState by examScheduleModel.state.collectAsState()
    val homeworkState by homeworkModel.state.collectAsState()
    val coursewareState by coursewareModel.state.collectAsState()
    val classroomState by classroomModel.state.collectAsState()
    val classroomOccupancyState by classroomOccupancyModel.state.collectAsState()
    val mailboxState by mailboxModel.state.collectAsState()
    val mailboxUnread by mailboxModel.unreadSummary.collectAsState()
    val phyVlabState by phyVlabModel.state.collectAsState()
    val phyVlabAccountFlow = remember(session) {
        session.phyVlabAccountModel?.state ?: MutableStateFlow(PhyVlabAccountState())
    }
    val phyVlabAccountState by phyVlabAccountFlow.collectAsState()
    val settingsState by settingsModel.state.collectAsState()
    val physicsLabEnabled = settingsState.preferences.isPhysicsLabEnabled
    val citelEnabled = settingsState.preferences.isCitelEnabled
    val physicsLabFlow = remember(session) { session.physicsLabModel?.state ?: MutableStateFlow(PhysicsLabState()) }
    val physicsLabState by physicsLabFlow.collectAsState()
    val physicsDemoFlow = remember(session) { session.physicsDemoModel?.state ?: MutableStateFlow(team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoState()) }
    val physicsDemoState by physicsDemoFlow.collectAsState()
    val citelFlow = remember(session) { session.citelModel?.state ?: MutableStateFlow(team.bjtuss.bjtuselfservice.shared.feature.citel.CitelState()) }
    val citelState by citelFlow.collectAsState()
    val citelEvents = remember(citelState.tasks, citelEnabled) {
        if (citelEnabled) team.bjtuss.bjtuselfservice.shared.feature.citel.citelAgendaEvents(citelState.tasks) else emptyList()
    }
    val scheduleEvents = remember(courseState.academicWeeks, examState.exams, physicsLabState.labs, physicsLabEnabled, physicsDemoState.bookings, physicsDemoState.enabled) {
        scheduleEventCourses(examState.exams, if (physicsLabEnabled) physicsLabState.labs else emptyList(), courseState.academicWeeks, if (physicsDemoState.enabled) physicsDemoState.bookings else emptyList())
    }
    val scheduleStateWithEvents = courseState.copy(
        supplementalCourses = scheduleEvents,
        physicsLabs = if (physicsLabEnabled) physicsLabState.labs else emptyList(),
        physicsDemoBookings = if (physicsDemoState.enabled) physicsDemoState.bookings else emptyList(),
    )
    val homeScheduleEvents = remember(courseState.homeAcademicWeeks, examState.exams, physicsLabState.labs,
        physicsLabEnabled, physicsDemoState.bookings, physicsDemoState.enabled) {
        scheduleEventCourses(examState.exams, if (physicsLabEnabled) physicsLabState.labs else emptyList(),
            courseState.homeAcademicWeeks, if (physicsDemoState.enabled) physicsDemoState.bookings else emptyList())
    }
    val homeState by homeModel.state.collectAsState()
    // 图书馆借阅数据只存在会话内存；首页提醒由当前借阅派生，历史记录不参与。
    val libraryModel = session.libraryModel
    val libraryState by libraryModel.state.collectAsState()
    val libraryReminder = remember(libraryState.loans) { libraryReminderNow(libraryState.loans) }
    val phyVlabEnabled = settingsState.preferences.isPhyVlabEnabled
    // 同步期间壳层随任一状态流重组；只在来源数据变化时重新聚合，作业页才能跳过无关重组。
    val aggregatedAssignments = remember(homeworkState.homework, phyVlabState, phyVlabEnabled,
        citelState.tasks, citelEnabled) {
        // 不传手机时区：聚合按学校的北京时间换算，与日期筛选一致。
        aggregateAssignments(homeworkState.homework,
            if (phyVlabEnabled) phyVlabModel.allActivities() else emptyList(),
            if (citelEnabled) citelState.tasks else emptyList())
    }
    val phyVlabLoginMessage = when {
        phyVlabState.captchaRequired -> PHYVLAB_CAPTCHA_REQUIRED_MESSAGE
        phyVlabState.accountRejected -> PHYVLAB_ACCOUNT_REJECTED_MESSAGE
        phyVlabState.accountLoginRequired -> "请在设置中配置或检查物理在线账号。"
        else -> null
    }
    val assignmentSync = buildList {
        add(AssignmentSourceSync(AssignmentSource.COURSE_PLATFORM, homeworkState.isLoading || homeworkState.isRefreshing,
            homeworkState.failure != null, homeworkState.source == HomeworkContentSource.NETWORK,
            homeworkState.source == HomeworkContentSource.CACHE,
            if (homeworkState.failure != null) "课程平台作业同步失败，请检查网络或登录状态。" else null))
        if (phyVlabEnabled) add(AssignmentSourceSync(AssignmentSource.PHYVLAB, phyVlabState.isLoading,
            phyVlabState.failure != null || phyVlabState.accountLoginRequired, phyVlabState.contentSource == PhyVlabContentSource.NETWORK,
            phyVlabState.contentSource == PhyVlabContentSource.CACHE,
            phyVlabLoginMessage ?: "请检查校园网或重新登录物理在线。".takeIf { phyVlabState.failure != null },
            requiresCaptcha = phyVlabState.captchaRequired))
        if (citelEnabled) add(AssignmentSourceSync(AssignmentSource.CITEL, citelState.refreshing, citelState.failed,
            citelState.lastSync != null && !citelState.fromCache, citelState.fromCache, citelState.message))
    }
    val homeChanges by homeChangeFeed.records.collectAsState()
    val homeSyncItems = buildHomeSyncItems(
        isLoggingIn = entryLoggingIn,
        homeBusy = homeState.isRefreshing,
        homeFailed = homeState.failure != null,
        homeReady = homeState.status != null,
        gradeBusy = gradeState.isLoading || gradeState.isRefreshing,
        gradeFailed = gradeState.failure != null,
        gradeReady = gradeState.source != null,
        homeworkBusy = homeworkState.isLoading || homeworkState.isRefreshing,
        homeworkFailed = homeworkState.failure != null,
        homeworkReady = homeworkState.source != null,
        examBusy = examState.isLoading || examState.isRefreshing,
        examFailed = examState.failure != null,
        examReady = examState.source != null,
        // 课表来源只说明课程列表到了。教学周未确认时不能显示「已完成」，
        // 否则秋假空档或校历失败时，面板已完成、首页仍停在「日程加载中」。
        courseBusy = courseState.isLoading || courseState.isRefreshing || courseState.isCalendarLoading,
        courseFailed = courseState.failure != null || courseState.calendarFailed,
        courseReady = courseState.source != null && courseState.weekResolved,
        physicsLabEnabled = physicsLabEnabled,
        physicsLabBusy = physicsLabState.refreshing,
        physicsLabFailed = physicsLabState.failed,
        physicsLabReady = physicsLabState.synced,
        physicsDemoEnabled = physicsDemoState.enabled,
        physicsDemoBusy = physicsDemoState.refreshing,
        physicsDemoFailed = physicsDemoState.failed,
        physicsDemoReady = physicsDemoState.synced,
        citelEnabled = citelEnabled,
        citelBusy = citelState.refreshing,
        citelFailed = citelState.failed,
        citelReady = citelState.lastSync != null && !citelState.fromCache,
        phyVlabEnabled = phyVlabEnabled,
        phyVlabBusy = phyVlabEnabled && phyVlabState.isLoading,
        phyVlabRequiresCaptcha = phyVlabState.captchaRequired,
        phyVlabFailureMessage = phyVlabLoginMessage,
        phyVlabFailed = phyVlabEnabled && (phyVlabState.failure != null || phyVlabState.accountLoginRequired),
        phyVlabReady = phyVlabEnabled &&
            (phyVlabState.contentSource == PhyVlabContentSource.NETWORK ||
                phyVlabState.courses.isNotEmpty() || phyVlabState.agendaEvents.isNotEmpty()),
    )
    val homeSyncFailureItems = homeSyncItems
        .filter { it.state == HomeSyncItemState.FAILED }
        .map(HomeSyncItem::title)
    val homeSyncInProgress = entryLoggingIn || sessionRecoveryInProgress || homeSyncItems.any {
        it.state == HomeSyncItemState.SYNCING
    }
    return ShellData(
        settingsState = settingsState,
        gradeState = gradeState,
        courseState = courseState,
        examState = examState,
        homeworkState = homeworkState,
        coursewareState = coursewareState,
        classroomState = classroomState,
        classroomOccupancyState = classroomOccupancyState,
        mailboxState = mailboxState,
        mailboxUnread = mailboxUnread,
        phyVlabState = phyVlabState,
        phyVlabAccountState = phyVlabAccountState,
        citelState = citelState,
        homeState = homeState,
        libraryState = libraryState,
        physicsLabState = physicsLabState,
        physicsDemoState = physicsDemoState,
        homeChanges = homeChanges,
        scheduleStateWithEvents = scheduleStateWithEvents,
        homeScheduleEvents = homeScheduleEvents,
        citelEvents = citelEvents,
        aggregatedAssignments = aggregatedAssignments,
        assignmentSync = assignmentSync,
        homeSyncItems = homeSyncItems,
        homeSyncFailureItems = homeSyncFailureItems,
        homeSyncInProgress = homeSyncInProgress,
        libraryReminder = libraryReminder,
    )
}

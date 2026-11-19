package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeDomain
import team.bjtuss.bjtuselfservice.shared.domain.homework.stableKey
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelDetailWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomBuildingWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyBuildingWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseCompactViewMode
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleContentSource
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareContentSource
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackScreen
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleContentSource
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleDetailWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeContentSource
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItemState
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.home.homeIdleStatusText
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkContentSource
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkDetailWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.library.LibraryWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxComposeScreen
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUiState
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.ReportCardDownloadWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.SCHOOL_CALENDAR_ARTICLE_URL
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.SchoolCalendarArticleWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabSettings
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabContentSource
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabDetailWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsWorkspace
import team.bjtuss.bjtuselfservice.shared.logging.SyncTiming
import team.bjtuss.bjtuselfservice.shared.usesLegacySmartTransportFor

@Composable
internal fun ShellDestination(
    ui: ShellUi,
    route: AppRoute,
    expanded: Boolean,
    modifier: Modifier,
    usesLegacySmartTransport: Boolean,
) {
    val session = ui.session
    val data = rememberShellData(session, ui.sessionRecoveryInProgress, route = route)
    val platform = ui.platform
    val profile = session.profile
    val scope = ui.scope
    val navigator = ui.navigator
    val backStack = navigator.backStack
    val useNativeSecondaryRoutes = navigator.useNativeSecondaryRoutes
    val isPushedHostDestination = navigator.isPushedHostDestination
    val popBackStack: () -> Unit = navigator::pop
    val nativeNavigationEnabled = ui.chrome.nativeNavigationEnabled
    val entryLoggingIn = ui.entryLoggingIn
    val navigateToSection: (AppSection) -> Unit = ui.actions::navigateToSection
    val refresh: () -> Unit = ui.actions::refresh
    val refreshAssignmentSource: (AssignmentSource) -> Unit = ui.actions::refreshAssignmentSource
    val retryPhyVlabDetail: () -> Unit = ui.actions::retryPhyVlabDetail
    val openPhyVlabSettings: () -> Unit = ui.actions::openPhyVlabSettings
    val openExamDetail: (Int) -> Unit = ui.actions::openExamDetail
    val latestNavigateToSection = rememberUpdatedState<(AppSection) -> Unit> { navigateToSection(it) }
    val openScheduleFromHome = remember { { latestNavigateToSection.value(AppSection.SCHEDULE) } }
    val startMailboxComposeFromTopBar: () -> Unit = ui.actions::startMailboxCompose
    val onOpenNativeRoute: (String) -> Unit = ui.actions::openNativeRoute
    val onOpenExternalUrl = ui.chrome.onOpenExternalUrl
    val onLogout: () -> Unit = ui.actions::logout
    val reauthenticateSession = session.reauthenticateSession
    val fileGateway = ui.chrome.fileGateway
    val directoryGateway = ui.chrome.directoryGateway
    val systemCalendarGateway = ui.chrome.systemCalendarGateway
    val compactBottomNavSections = remember(data.settingsState.preferences) {
        bottomNavSections(data.settingsState.preferences)
    }
    val gradeModel = session.gradeModel
    val gradeState = data.gradeState
    val courseScheduleModel = session.courseScheduleModel
    val courseState = data.courseState
    val scheduleStateWithEvents = data.scheduleStateWithEvents
    val examScheduleModel = session.examScheduleModel
    val examState = data.examState
    val homeworkModel = session.homeworkModel
    val homeworkState = data.homeworkState
    val coursewareModel = session.coursewareModel
    val coursewareState = data.coursewareState
    val classroomModel = session.classroomModel
    val classroomState = data.classroomState
    val classroomOccupancyModel = session.classroomOccupancyModel
    val classroomOccupancyState = data.classroomOccupancyState
    val mailboxModel = session.mailboxModel
    val mailboxState = data.mailboxState
    val mailboxReadyState = data.mailboxReadyState
    val mailboxMessageLoading = data.mailboxMessageLoading
    val mailboxUnread = data.mailboxUnread
    val mailboxInlineDetail = mailboxBackTarget(
        useNativeSecondaryRoutes = useNativeSecondaryRoutes,
        hasSelectedMessage = mailboxReadyState?.selectedMessage != null,
        isMessageLoading = mailboxReadyState?.isMessageLoading == true,
    ) == MailboxBackTarget.LIST
    val mailboxInlineCompose = !useNativeSecondaryRoutes &&
        (mailboxReadyState?.compose?.isLoading == true ||
            mailboxReadyState?.compose?.draft != null ||
            mailboxReadyState?.compose?.failure != null)
    val mailboxBack: () -> Unit = when {
        mailboxInlineCompose -> {
            { scope.launch { mailboxModel.cancelCompose() } }
        }
        mailboxInlineDetail -> mailboxModel::clearSelectedMessage
        else -> popBackStack
    }
    val phyVlabModel = session.phyVlabModel
    val phyVlabState = data.phyVlabState
    val phyVlabAccountState = data.phyVlabAccountState
    val phyVlabEnabled = data.phyVlabEnabled
    val physicsLabState = data.physicsLabState
    val physicsDemoState = data.physicsDemoState
    val physicsLabEnabled = data.physicsLabEnabled
    val citelEnabled = data.citelEnabled
    val citelState = data.citelState
    val citelEvents = data.citelEvents
    val settingsModel = session.settingsModel
    val settingsState = data.settingsState
    val homeModel = session.homeModel
    val homeState = data.homeState
    val homeChangeFeed = session.homeChangeFeed
    val homeScheduleEvents = data.homeScheduleEvents
    val homeChanges = data.homeChanges
    val aggregatedAssignments = data.aggregatedAssignments
    val assignmentSync = data.assignmentSync
    val homeSyncItems = data.homeSyncItems
    val homeSyncFailureItems = data.homeSyncFailureItems
    val homeSyncInProgress = data.homeSyncInProgress
    val otherFunctionModel = session.otherFunctionModel
    val libraryModel = session.libraryModel
    val libraryState = data.libraryState
    val libraryReminder = data.libraryReminder
    val staggeredClassTimes = data.staggeredClassTimes
    val remoteAgent = team.bjtuss.bjtuselfservice.shared.feature.settings.LocalRemoteAgentController.current
        ?: session.remoteAgent
    var homeTodayRequest by ui.view.homeTodayRequest
    var homeTodaySelected by ui.view.homeTodaySelected
    var homeSyncDialogVisible by ui.view.homeSyncDialogVisible
    var citelSyncDialogVisible by ui.view.citelSyncDialogVisible
    var aggregateSyncDialogVisible by ui.view.aggregateSyncDialogVisible
    var showCourseCalendarExport by ui.view.showCourseCalendarExport
    var legacyWarningDismissed by ui.view.legacyWarningDismissed
    var classroomIntroBannerDismissed by ui.view.classroomIntroBannerDismissed
    val dismissLegacyWarning: () -> Unit = {
        session.legacyHttpWarningDismissed = true
        legacyWarningDismissed = true
    }
    val dismissClassroomIntroBanner: () -> Unit = {
        session.classroomIntroBannerDismissed = true
        classroomIntroBannerDismissed = true
    }
    val assignmentSearch = ui.view.assignmentSearch
    val openPhyVlabCaptcha: (Boolean) -> Unit = ui.actions::openPhyVlabCaptcha
    val refreshPhyVlabAfterLogin: () -> Unit = ui.actions::refreshPhyVlabAfterLogin
    val renderAssignmentMarkdownActions: @Composable (AssignmentSource) -> Unit = { source ->
        val selected = when (source) {
            AssignmentSource.COURSE_PLATFORM -> aggregateAssignments(listOfNotNull(homeworkState.selectedHomework), emptyList(), emptyList())
            AssignmentSource.PHYVLAB -> aggregateAssignments(emptyList(), listOfNotNull(phyVlabState.selectedActivity), emptyList())
            AssignmentSource.CITEL -> aggregateAssignments(emptyList(), emptyList(), listOfNotNull(citelState.selectedTask))
        }.singleOrNull()
        selected?.let { item ->
            // 紧凑端详情可能由原生容器另起组合，壳层的 CompositionLocal 不一定传得过去，这里显式提供。
            CompositionLocalProvider(
                team.bjtuss.bjtuselfservice.shared.feature.assignment.LocalAssignmentCompletionStore provides session.assignmentCompletionStore,
            ) {
                AssignmentMarkdownActions(item, fileGateway,
                    aggregateAssignmentMarkdown(item, homeworkState, phyVlabState, citelState))
            }
        }
    }

    when (route) {
        AppSection.HOME -> DestinationPage(
            title = AppSection.HOME.title,
            topBarActionLabel = "今".takeUnless { homeTodaySelected },
            onTopBarActionClick = { homeTodayRequest += 1 },
            expanded = expanded,
            refreshable = true,
            refreshAction = {
                homeSyncDialogVisible = true
                refresh()
            },
            isRefreshing = homeSyncInProgress,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            // 与成绩/课表一致：同步态并入顶栏右上胶囊，勿只留孤图标。
            // 首页聚合邮件/校园卡自身失败才写「同步失败」；作业等切片失败写「部分同步失败」，
            // 避免作业红条把整个首页说成全挂。
            idleStatusText = homeIdleStatusText(
                homeFailed = homeState.failure != null,
                hasWaitingSource = homeSyncItems.any { it.state == HomeSyncItemState.WAITING },
                homeworkFailed = homeworkState.failure != null,
                examFailed = examState.failure != null,
                courseFailed = courseState.failure != null,
                phyVlabFailed = (phyVlabEnabled &&
                    (phyVlabState.failure != null || phyVlabState.accountLoginRequired)) ||
                    (physicsLabEnabled && physicsLabState.failed) || (physicsDemoState.enabled && physicsDemoState.failed) || (citelState.enabled && citelState.failed),
                hasAnySource = homeworkState.source != null ||
                    examState.source != null ||
                    courseState.source != null ||
                    homeState.status != null ||
                    (phyVlabEnabled && (
                        phyVlabState.courses.isNotEmpty() ||
                            phyVlabState.agendaEvents.isNotEmpty()
                        )),
            ),
            onStatusClick = { homeSyncDialogVisible = true },
            syncFailureItems = homeSyncFailureItems,
            scrollUnderTopBar = true,
        ) {
            val homeToday = homeworkState.nowInstant.toLocalDateTime(team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone).date
            val homeSchedulePresentation = remember(courseState.courses, homeScheduleEvents,
                courseState.homeAcademicWeeks, courseState.currentWeek, homeToday, gradeState.courseTypesByCode, staggeredClassTimes) {
                team.bjtuss.bjtuselfservice.shared.feature.home.HomeSchedulePresentation(
                    courses = courseState.courses,
                    supplementalCourses = homeScheduleEvents,
                    academicWeeks = courseState.homeAcademicWeeks,
                    currentWeek = courseState.currentWeek,
                    today = homeToday,
                    courseTypesByCode = gradeState.courseTypesByCode,
                    staggeredClassTimes = staggeredClassTimes,
                    onOpenSchedule = openScheduleFromHome,
                )
            }
            val homeAgendaEvents = remember(phyVlabEnabled, phyVlabState.agendaEvents, citelEvents) {
                (if (phyVlabEnabled) phyVlabState.agendaEvents else emptyList()) + citelEvents
            }
            CompositionLocalProvider(
                team.bjtuss.bjtuselfservice.shared.feature.home.LocalHomeSchedule provides homeSchedulePresentation,
            ) {
                HomeWorkspace(
                    todayRequest = homeTodayRequest,
                    onTodaySelectedChanged = { homeTodaySelected = it },
                    model = homeModel,
                    platform = platform,
                    expanded = expanded,
                    mailboxUnread = mailboxUnread,
                    libraryReminder = libraryReminder.takeIf { libraryState.user != null },
                    onOpenLibrary = { navigateToSection(AppSection.LIBRARY) },
                    holdNetwork = entryLoggingIn,
                    homework = homeworkState.homework,
                    exams = examState.exams,
                    phyVlabEvents = homeAgendaEvents,
                    reminderAssignments = aggregatedAssignments,
                    currentWeek = courseState.currentWeek,
                    academicWeeks = courseState.homeAcademicWeeks,
                    // 周数只在被校历确认后显示：确认前统一「日程加载中」，
                    // 拿到确切结果后一次显示最终值，不再出现中间值弹跳。
                    isWeekResolved = courseState.weekResolved,
                    now = homeworkState.now,
                    nowInstant = homeworkState.nowInstant,
                    timeZone = homeworkState.timeZone,
                    isAgendaLoading = homeworkState.isLoading || examState.isLoading ||
                        courseState.isLoading || (phyVlabEnabled && phyVlabState.isLoading) || citelState.refreshing,
                    onOpenMailbox = { navigateToSection(AppSection.MAILBOX) },
                    onOpenHomework = { navigateToSection(AppSection.HOMEWORK) },
                    onOpenHomeworkDetail = { homework ->
                        val key = homework.stableKey()
                        homeworkModel.selectHomework(key)
                        scope.launch { team.bjtuss.bjtuselfservice.shared.feature.homework.loadHomeworkDetailsWithRecovery(homeworkModel, key, reauthenticateSession) }
                        if (useNativeSecondaryRoutes) {
                            onOpenNativeRoute(HOMEWORK_DETAIL_ROUTE_ID)
                        } else if (backStack.lastOrNull() != HomeworkDetailRoute) {
                            backStack.add(HomeworkDetailRoute)
                        }
                    },
                    onOpenExams = { exam -> openExamDetail(exam.id) },
                    onOpenPhyVlab = { event ->
                        if (event.id.startsWith("citel-")) {
                            // 与作业/考试一致：直接进入该 CITEL 作业的二级详情页。
                            val taskId = event.id.substringAfterLast('-').toIntOrNull()
                            val task = citelState.tasks.firstOrNull { it.id == taskId }
                            val model = session.citelModel
                            if (task != null && model != null) {
                                model.showTask(task)
                                if (useNativeSecondaryRoutes) {
                                    onOpenNativeRoute(CITEL_DETAIL_ROUTE_ID)
                                } else if (backStack.lastOrNull() != CitelDetailRoute) {
                                    backStack.add(CitelDetailRoute)
                                }
                            } else {
                                navigateToSection(AppSection.CITEL)
                            }
                        } else {
                            // 首页事件可能来自任一物理在线课程；当前页 activities 只含所选课程。
                            val activity = phyVlabModel.findActivityForEvent(event)
                            if (activity != null) {
                                phyVlabModel.showActivityDetails(activity)
                                if (useNativeSecondaryRoutes) {
                                    onOpenNativeRoute(PHYVLAB_DETAIL_ROUTE_ID)
                                } else if (backStack.lastOrNull() != PhyVlabDetailRoute) {
                                    backStack.add(PhyVlabDetailRoute)
                                }
                            } else {
                                event.eventUrl?.let { onOpenExternalUrl(upgradePhyVlabUrlToHttps(it)) }
                            }
                        }
                    },
                    changes = homeChanges,
                    onClearAllChanges = { scope.launch { homeChangeFeed.clear() } },
                    onClearChangeDomain = { domain -> scope.launch { homeChangeFeed.clear(domain) } },
                    onOpenChangeDomain = { domain ->
                        if (domain == HomeChangeDomain.PHYSICS_DEMO) {
                            if (useNativeSecondaryRoutes) onOpenNativeRoute(PHYSICS_DEMO_ROUTE_ID)
                            else backStack.add(PhysicsDemoRoute)
                        } else navigateToSection(domain.toAppSection())
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        AppSection.GRADES -> DestinationPage(
            title = AppSection.GRADES.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = gradeState.isRefreshing,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
            // 与课表一致：同步态在顶栏右上，banner 内只放成绩摘要与筛选入口。
            idleStatusText = when {
                gradeState.failure != null -> "同步失败"
                gradeState.source == GradeContentSource.NETWORK -> "已同步"
                gradeState.source == GradeContentSource.CACHE -> "已同步"
                else -> "未同步"
            },
        ) {
            GradeWorkspace(
                state = gradeState,
                expanded = expanded,
                model = gradeModel,
                onRefresh = refresh,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AppSection.SCHEDULE -> DestinationPage(
            title = AppSection.SCHEDULE.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = courseState.isRefreshing || examState.isRefreshing || physicsLabState.refreshing || physicsDemoState.refreshing,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            // 同步状态放在顶栏右上；有失败横幅时不要仍显示「已同步」。
            idleStatusText = when {
                courseState.failure != null -> "同步失败"
                courseState.source == CourseScheduleContentSource.NETWORK -> "已同步"
                courseState.source == CourseScheduleContentSource.CACHE -> "已同步"
                else -> "未同步"
            },
            // 「添加到日历」声明成文字 + 回调，好让系统导航栏能直接画它。
            topBarActionLabel =
                if (systemCalendarGateway.isAvailable) "添加到日历" else "导出",
            onTopBarActionClick = { showCourseCalendarExport = true },
            // 固定周表仅缩小表格内部；按日列表通过滚动内容末尾避让胶囊。
            // 顶栏 underlap 暂不启用：按日列表视口被固定的摘要/模式/日期三段头挡在栏下，
            // 内容到不了栏后；硬上只会把三段头顶进状态栏。等表头随滚 redesign 再议。
            // 且关闭滚动过渡：栏后永远是纯色，过渡只会凭空闪一下。
            staticTopBar = true,
        ) {
            CourseScheduleWorkspace(
                state = scheduleStateWithEvents,
                courseTypesByCode = gradeState.courseTypesByCode,
                expanded = expanded,
                model = courseScheduleModel,
                fileGateway = fileGateway,
                systemCalendarGateway = systemCalendarGateway,
                showCalendarExportSheet = showCourseCalendarExport,
                onDismissCalendarExport = { showCourseCalendarExport = false },
                onRefresh = refresh,
                modifier = Modifier.fillMaxSize().then(
                    if (courseState.compactViewMode == CourseCompactViewMode.WEEK)
                        Modifier.padding(bottom = LocalBottomBarClearance.current)
                    else Modifier,
                ),
            )
        }
        AppSection.EXAMS -> DestinationPage(
            title = AppSection.EXAMS.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = examState.isRefreshing,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
            // 与成绩/作业一致：同步态顶栏右上，banner 内放类型筛选入口。
            idleStatusText = when {
                examState.failure != null -> "同步失败"
                examState.source == ExamScheduleContentSource.NETWORK -> "已同步"
                examState.source == ExamScheduleContentSource.CACHE -> "已同步"
                else -> "未同步"
            },
        ) {
            ExamScheduleWorkspace(
                state = examState,
                expanded = expanded,
                model = examScheduleModel,
                fileGateway = fileGateway,
                systemCalendarGateway = systemCalendarGateway,
                onRefresh = refresh,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ExamDetailRoute -> DestinationPage(
            title = "考试详情",
            expanded = expanded,
            refreshable = false,
            isRefreshing = false,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            ExamScheduleDetailWorkspace(
                state = examState,
                model = examScheduleModel,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AppSection.ASSIGNMENTS -> DestinationPage(
            title = "作业", expanded = expanded, refreshable = true,
            refreshAction = { aggregateSyncDialogVisible = true; refresh() },
            isRefreshing = assignmentSync.any { it.busy }, idleStatusText = aggregateAssignmentSyncStatus(assignmentSync),
            onStatusClick = { aggregateSyncDialogVisible = true },
            search = assignmentSearch, searchPrompt = "搜索课程、作业或来源",
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier, scrollUnderTopBar = true,
        ) {
            AggregatedAssignmentWorkspace(aggregatedAssignments, assignmentSync, session.aggregateFilterStore,
                onInitialize = {
                    homeworkModel.initialize(refreshFromNetwork = false)
                    if (phyVlabEnabled) phyVlabModel.initialize(refreshFromNetwork = false)
                    if (citelEnabled) session.citelModel?.let { model ->
                        model.setEnabled(true)
                        SyncTiming.measure("feature.citel") { model.refreshForAppEntry(0) }
                    }
                }, onOpen = { item ->
                    val detailRoute = when (item.source) {
                        AssignmentSource.COURSE_PLATFORM -> {
                            item.homework?.let { homework ->
                                homeworkModel.selectHomework(homework.stableKey())
                                scope.launch { team.bjtuss.bjtuselfservice.shared.feature.homework.loadHomeworkDetailsWithRecovery(homeworkModel, homework.stableKey(), reauthenticateSession) }
                            }
                            HomeworkDetailRoute
                        }
                        AssignmentSource.PHYVLAB -> { item.physical?.let(phyVlabModel::showActivityDetails); PhyVlabDetailRoute }
                        AssignmentSource.CITEL -> { item.citel?.let { session.citelModel?.showTask(it) }; CitelDetailRoute }
                    }
                    val routeId = when (detailRoute) {
                        HomeworkDetailRoute -> HOMEWORK_DETAIL_ROUTE_ID
                        PhyVlabDetailRoute -> PHYVLAB_DETAIL_ROUTE_ID
                        else -> CITEL_DETAIL_ROUTE_ID
                    }
                    if (!expanded) {
                        if (useNativeSecondaryRoutes) onOpenNativeRoute(routeId) else backStack.add(detailRoute)
                    }
                }, onRefreshSource = { source -> refreshAssignmentSource(source) },
                showSyncDetails = aggregateSyncDialogVisible, onDismissSyncDetails = { aggregateSyncDialogVisible = false },
                modifier = Modifier.fillMaxSize(), expanded = expanded, fileGateway = fileGateway,
                markdown = { item -> aggregateAssignmentMarkdown(item, homeworkState, phyVlabState, citelState) },
                search = assignmentSearch,
                onEnterCaptcha = { openPhyVlabCaptcha(true) },
                detailContent = { item ->
                    when (item.source) {
                        AssignmentSource.COURSE_PLATFORM -> HomeworkDetailWorkspace(homeworkModel, fileGateway,
                            reauthenticateSession, Modifier.fillMaxSize())
                        AssignmentSource.PHYVLAB -> PhyVlabDetailWorkspace(phyVlabModel, fileGateway,
                            onOpenExternalUrl, modifier = Modifier.fillMaxSize())
                        AssignmentSource.CITEL -> session.citelModel?.let { model ->
                            CitelDetailWorkspace(model, fileGateway, onOpenExternalUrl, modifier = Modifier.fillMaxSize(), directoryGateway = directoryGateway)
                        }
                    }
                })
        }
        AppSection.HOMEWORK -> DestinationPage(
            title = AppSection.HOMEWORK.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = homeworkState.isRefreshing,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
            search = assignmentSearch,
            searchPrompt = "搜索课程或作业",
            // 与课表/成绩一致：同步态在顶栏右上，banner 内只放摘要与筛选入口。
            idleStatusText = when {
                homeworkState.failure != null -> "同步失败"
                homeworkState.source == HomeworkContentSource.NETWORK -> "已同步"
                homeworkState.source == HomeworkContentSource.CACHE -> "已同步"
                else -> "未同步"
            },
        ) {
            HomeworkWorkspace(
                state = homeworkState,
                expanded = expanded,
                usesLegacySmartTransport = usesLegacySmartTransport,
                legacyWarningVisible = usesLegacySmartTransportFor(platform.family) && !legacyWarningDismissed,
                onDismissLegacyWarning = dismissLegacyWarning,
                model = homeworkModel,
                fileGateway = fileGateway,
                onReauthenticate = reauthenticateSession,
                onRefresh = refresh,
                onOpenDetail = {
                    // 先写完选中再 push（onOpen 里已 selectHomework），避免详情页打开时为空。
                    if (useNativeSecondaryRoutes) {
                        onOpenNativeRoute(HOMEWORK_DETAIL_ROUTE_ID)
                    } else if (backStack.lastOrNull() != HomeworkDetailRoute) {
                        backStack.add(HomeworkDetailRoute)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                search = assignmentSearch,
            )
        }
        AppSection.COURSEWARE -> DestinationPage(
            title = AppSection.COURSEWARE.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = coursewareState.isRefreshing,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            // 课程选择卡片与目录标题都是列表项，内容穿到玻璃顶栏后。
            scrollUnderTopBar = true,
            // 与成绩/作业一致：右上角「已同步」+ sync 同一胶囊，勿只留孤图标。
            idleStatusText = when {
                coursewareState.failure != null -> "同步失败"
                coursewareState.source == CoursewareContentSource.NETWORK -> "已同步"
                coursewareState.source == CoursewareContentSource.CACHE -> "已同步"
                else -> "未同步"
            },
        ) {
            CoursewareWorkspace(
                state = coursewareState,
                expanded = expanded,
                usesLegacySmartTransport = usesLegacySmartTransport,
                legacyWarningVisible = usesLegacySmartTransportFor(platform.family) && !legacyWarningDismissed,
                onDismissLegacyWarning = dismissLegacyWarning,
                model = coursewareModel,
                fileGateway = fileGateway,
                directoryGateway = directoryGateway,
                onRefresh = refresh,
                modifier = Modifier.fillMaxSize(),
            )
        }
        PlaybackDetailRoute -> DestinationPage(
            title = "回放播放与下载", expanded = expanded, refreshable = false,
            isRefreshing = false, showBack = true, modifier = modifier, scrollUnderTopBar = true,
        ) {
            session.playbackModel?.let { model ->
                team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackDetailWorkspace(
                    model, coursewareState.courses, fileGateway, Modifier.fillMaxSize())
            }
        }
        AppSection.PLAYBACK -> DestinationPage(
            title = AppSection.PLAYBACK.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = coursewareState.isRefreshing,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
            idleStatusText = when {
                coursewareState.failure != null -> "同步失败"
                coursewareState.source != null -> "已同步"
                else -> "未同步"
            },
        ) {
            val playbackModel = session.playbackModel
            if (playbackModel == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("当前会话不支持课程回放。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                PlaybackScreen(
                    coursewareState = coursewareState,
                    coursewareModel = coursewareModel,
                    model = playbackModel,
                    fileGateway = fileGateway,
                    expanded = expanded,
                    onRefresh = refresh,
                    modifier = Modifier.fillMaxSize(),
                    onOpenDetail = if (platform.family == PlatformFamily.IOS) {
                        {
                            if (nativeNavigationEnabled) onOpenNativeRoute(PLAYBACK_DETAIL_ROUTE_ID)
                            else if (backStack.lastOrNull() != PlaybackDetailRoute) backStack.add(PlaybackDetailRoute)
                        }
                    } else null,
                )
            }
        }
        AppSection.LIBRARY -> DestinationPage(
            title = AppSection.LIBRARY.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = libraryState.loading || libraryState.refreshing,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            // 账户摘要与 tab 随列表滚动，内容滚进原生玻璃顶栏后面（与考试/作业页一致）。
            scrollUnderTopBar = true,
            idleStatusText = when {
                libraryState.failure != null -> "同步失败"
                libraryState.lastSyncEpochSeconds != null -> "已同步"
                else -> "未同步"
            },
        ) {
            LibraryWorkspace(
                model = libraryModel,
                expanded = expanded,
                holdNetwork = entryLoggingIn,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AppSection.CALENDAR -> DestinationPage(
            title = AppSection.CALENDAR.title,
            expanded = expanded,
            refreshable = false,
            isRefreshing = false,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            SchoolCalendarArticleWorkspace(
                expanded = expanded,
                onOpenArticle = { onOpenExternalUrl(SCHOOL_CALENDAR_ARTICLE_URL) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        AppSection.REPORT_CARD_DOWNLOAD -> DestinationPage(
            title = AppSection.REPORT_CARD_DOWNLOAD.title,
            expanded = expanded,
            refreshable = false,
            isRefreshing = false,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            ReportCardDownloadWorkspace(
                model = otherFunctionModel,
                expanded = expanded,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AppSection.CLASSROOMS -> DestinationPage(
            title = AppSection.CLASSROOMS.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = classroomState.isLoading,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            idleStatusText = classroomIdleStatusText(classroomState),
            scrollUnderTopBar = true,
        ) {
            ClassroomWorkspace(
                model = classroomModel,
                expanded = expanded,
                introBannerVisible = !classroomIntroBannerDismissed,
                onDismissIntroBanner = dismissClassroomIntroBanner,
                onOpenBuilding = {
                    // 先写完选中再 push，避免详情页打开时 selectedBuilding 仍为空。
                    if (useNativeSecondaryRoutes) {
                        onOpenNativeRoute(CLASSROOM_DETAIL_ROUTE_ID)
                    } else if (backStack.lastOrNull() != ClassroomDetailRoute) {
                        backStack.add(ClassroomDetailRoute)
                    }
                },
                onRefresh = refresh,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AppSection.CLASSROOM_OCCUPANCY -> DestinationPage(
            title = AppSection.CLASSROOM_OCCUPANCY.title,
            expanded = expanded,
            refreshable = false,
            isRefreshing = false,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            ClassroomOccupancyWorkspace(
                model = classroomOccupancyModel,
                expanded = expanded,
                onOpenBuilding = {
                    // 先写完选中再 push，避免详情页打开时 selectedBuilding 仍为空。
                    if (useNativeSecondaryRoutes) {
                        onOpenNativeRoute(CLASSROOM_OCCUPANCY_DETAIL_ROUTE_ID)
                    } else if (backStack.lastOrNull() != ClassroomOccupancyDetailRoute) {
                        backStack.add(ClassroomOccupancyDetailRoute)
                    }
                },
                onRefresh = refresh,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ClassroomDetailRoute -> DestinationPage(
            // 二级页标题用教学楼名；顶栏返回即原生层级返回，页内不再放「返回教学楼」。
            title = classroomState.selectedBuilding ?: AppSection.CLASSROOMS.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = classroomState.isLoading,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            idleStatusText = classroomIdleStatusText(classroomState),
            // 顶栏 underlap 暂不启用：搜索框与筛选芯片固定在列表上方，视口到不了栏后。
            // 且关闭滚动过渡：栏后永远是纯色，过渡只会凭空闪一下。
            staticTopBar = true,
        ) {
            ClassroomBuildingWorkspace(
                model = classroomModel,
                onRefresh = refresh,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ClassroomOccupancyDetailRoute -> DestinationPage(
            // 二级页标题用教学楼名；顶栏返回即原生层级返回，页内不再放「返回教学楼」。
            title = classroomOccupancyState.selectedBuilding?.name ?: AppSection.CLASSROOM_OCCUPANCY.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = classroomOccupancyState.isLoading,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            // 顶栏 underlap 暂不启用：周/图例/星期三段筛选头固定在列表上方，视口到不了栏后。
            // 且关闭滚动过渡：栏后永远是纯色，过渡只会凭空闪一下。
            staticTopBar = true,
            denseTopBar = true,
        ) {
            ClassroomOccupancyBuildingWorkspace(
                model = classroomOccupancyModel,
                onRefresh = refresh,
                modifier = Modifier.fillMaxSize(),
            )
        }
        HomeworkDetailRoute -> DestinationPage(
            // 二级页顶栏固定显示「作业详情」，页内不再重复标题；返回即原生层级返回。
            title = "作业详情",
            expanded = expanded,
            refreshable = false,
            isRefreshing = false,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            HomeworkDetailWorkspace(
                model = homeworkModel,
                fileGateway = fileGateway,
                onReauthenticate = reauthenticateSession,
                modifier = Modifier.fillMaxSize(),
                headerContent = { renderAssignmentMarkdownActions(AssignmentSource.COURSE_PLATFORM) },
            )
        }
        AppSection.CHANGELOG -> DestinationPage(
            title = AppSection.CHANGELOG.title,
            expanded = expanded,
            refreshable = false,
            isRefreshing = false,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            team.bjtuss.bjtuselfservice.shared.feature.changelog.ChangelogWorkspace(Modifier.fillMaxSize())
        }
        AppSection.SETTINGS -> DestinationPage(
            title = AppSection.SETTINGS.title,
            expanded = expanded,
            refreshable = false,
            isRefreshing = false,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            SettingsWorkspace(
                model = settingsModel,
                accountName = "${profile.name} · ${profile.studentId}",
                platform = platform,
                expanded = expanded,
                onLogout = onLogout,
                modifier = Modifier.fillMaxSize(),
                physicsLabConfigured = physicsLabState.configured,
                physicsDemoConfigured = physicsDemoState.configured,
                phyVlabConfigured = phyVlabAccountState.configured,
                onPhyVlabSettings = openPhyVlabSettings,
                onAgentPluginEnabled = { enabled ->
                    settingsModel.setAgentPluginEnabled(enabled)
                    // 关闭功能同时暂停上云同步，不在入口隐藏后继续悄悄上传。
                    if (!enabled) remoteAgent?.setEnabled(false)
                },
                physicsDemoEnabled = physicsDemoState.enabled,
                onPhysicsDemoEnabled = { enabled -> scope.launch { session.physicsDemoModel?.setEnabled(enabled) } },
                onPhysicsDemoSettings = {
                    if (useNativeSecondaryRoutes) onOpenNativeRoute(PHYSICS_DEMO_SETTINGS_ROUTE_ID)
                    else backStack.add(PhysicsDemoSettingsRoute)
                },
                citelConfigured = citelState.configured,
                onPhysicsLabSettings = {
                    if (useNativeSecondaryRoutes) onOpenNativeRoute(PHYSICS_LAB_SETTINGS_ROUTE_ID)
                    else backStack.add(PhysicsLabSettingsRoute)
                },
                onCitelSettings = {
                    if (useNativeSecondaryRoutes) onOpenNativeRoute(CITEL_SETTINGS_ROUTE_ID)
                    else backStack.add(CitelSettingsRoute)
                },
            )
        }
        AppSection.MAILBOX -> DestinationPage(
            title = AppSection.MAILBOX.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = mailboxState == MailboxUiState.Preparing ||
                mailboxReadyState?.isListLoading == true,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            onBack = mailboxBack,
            // 邮箱右上角只有一个可执行的列表刷新圆钮：不要再传 idleStatusText，
            // 否则状态圆圈也会画一个刷新 glyph，和刷新圆钮重复成两个。
            // 同步中时顶栏显示 KMP 风格「同步中」胶囊。
            topBarActionLabel = if (mailboxReadyState != null) "写信" else null,
            onTopBarActionClick = startMailboxComposeFromTopBar,
            modifier = modifier,
            // 文件夹选单与邮件同在一个列表里，内容穿到玻璃顶栏后。
            scrollUnderTopBar = true,
        ) {
            MailboxWorkspace(
                model = mailboxModel,
                expanded = expanded,
                onRefresh = refresh,
                onReauthenticate = reauthenticateSession,
                fileGateway = fileGateway,
                onOpenNativeDetail = if (useNativeSecondaryRoutes) {
                    { onOpenNativeRoute(MAILBOX_DETAIL_ROUTE_ID) }
                } else {
                    null
                },
                onOpenNativeCompose = if (useNativeSecondaryRoutes) {
                    { onOpenNativeRoute(MAILBOX_COMPOSE_ROUTE_ID) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        MailboxDetailRoute -> DestinationPage(
            title = "邮件详情",
            expanded = expanded,
            refreshable = false,
            isRefreshing = mailboxMessageLoading,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            MailboxWorkspace(
                model = mailboxModel,
                expanded = false,
                nativeDetail = true,
                onRefresh = refresh,
                onReauthenticate = reauthenticateSession,
                fileGateway = fileGateway,
                onOpenNativeCompose = { onOpenNativeRoute(MAILBOX_COMPOSE_ROUTE_ID) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        MailboxComposeRoute -> DestinationPage(
            title = if (mailboxReadyState?.compose?.draft?.isReply == true) "回复邮件" else "写信",
            expanded = expanded,
            refreshable = false,
            isRefreshing = false,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            onBack = {
                scope.launch {
                    mailboxModel.cancelCompose()
                    popBackStack()
                }
            },
            ownsBackAction = true,
            modifier = modifier,
        ) {
            MailboxComposeScreen(
                model = mailboxModel,
                onSent = popBackStack,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AppSection.PHYVLAB -> DestinationPage(
            title = AppSection.PHYVLAB.title,
            expanded = expanded,
            refreshable = true,
            isRefreshing = phyVlabState.isLoading,
            // 物理在线与首页/课表/作业平级，是完整底栏里的一级页；只有真正被
            // 非底栏路由 push 进来时才显示系统返回按钮。
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
            idleStatusText = when {
                (phyVlabState.failure != null || phyVlabState.accountLoginRequired) &&
                    phyVlabState.contentSource == PhyVlabContentSource.CACHE -> "同步失败·正显示缓存"
                phyVlabState.failure != null || phyVlabState.accountLoginRequired -> "同步失败"
                phyVlabState.contentSource == PhyVlabContentSource.CACHE -> "未同步·缓存"
                phyVlabState.contentSource == PhyVlabContentSource.NETWORK &&
                    phyVlabState.failure == null -> "已同步"
                else -> "未同步"
            },
        ) {
            PhyVlabWorkspace(
                model = phyVlabModel,
                holdNetwork = entryLoggingIn,
                fileGateway = fileGateway,
                onRefresh = refresh,
                showDetailSheet = !useNativeSecondaryRoutes,
                onOpenAccountSettings = openPhyVlabSettings,
                onOpenCourse = { url -> onOpenExternalUrl(upgradePhyVlabUrlToHttps(url)) },
                onOpenActivity = { url -> onOpenExternalUrl(upgradePhyVlabUrlToHttps(url)) },
                onOpenActivityDetail = { activity ->
                    // 先写入选中作业再 push，详情页会基于同一 session model 读取并加载详情。
                    phyVlabModel.showActivityDetails(activity)
                    if (useNativeSecondaryRoutes) {
                        onOpenNativeRoute(PHYVLAB_DETAIL_ROUTE_ID)
                    }
                },
                onLogout = onLogout,
                onRetryDetail = retryPhyVlabDetail,
                modifier = Modifier.fillMaxSize(),
            )
        }
        PhyVlabDetailRoute -> DestinationPage(
            title = "物理作业详情",
            expanded = expanded,
            refreshable = true,
            isRefreshing = phyVlabState.isDetailLoading,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            PhyVlabDetailWorkspace(
                model = phyVlabModel,
                fileGateway = fileGateway,
                onRetry = retryPhyVlabDetail,
                onOpenActivity = { url -> onOpenExternalUrl(upgradePhyVlabUrlToHttps(url)) },
                modifier = Modifier.fillMaxSize(),
                headerContent = { renderAssignmentMarkdownActions(AssignmentSource.PHYVLAB) },
            )
        }
        AiAssistantSettingsRoute -> DestinationPage(
            title = team.bjtuss.bjtuselfservice.shared.feature.settings.agentPluginTitle(platform), expanded = expanded, refreshable = false,
            isRefreshing = false, showBack = true, modifier = modifier, scrollUnderTopBar = true,
        ) {
            CompositionLocalProvider(team.bjtuss.bjtuselfservice.shared.feature.settings.LocalRemoteAgentController provides remoteAgent) {
                team.bjtuss.bjtuselfservice.shared.feature.settings.AiAssistantSettingsScreen(
                    settingsModel, platform, Modifier.fillMaxSize())
            }
        }
        PhysicsDemoSettingsRoute -> DestinationPage(
            title = "演示实验账号设置", expanded = expanded, refreshable = false,
            isRefreshing = physicsDemoState.refreshing, showBack = true,
            modifier = modifier, scrollUnderTopBar = true,
        ) {
            session.physicsDemoModel?.let {
                team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoSettingsScreen(
                    it, Modifier.fillMaxSize(), onAccountSaved = popBackStack)
            }
        }
        PhyVlabSettingsRoute -> DestinationPage(
            title = "物理在线账号设置", expanded = expanded, refreshable = false,
            isRefreshing = phyVlabAccountState.saving, showBack = true,
            modifier = modifier, scrollUnderTopBar = true,
        ) {
            session.phyVlabAccountModel?.let {
                team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabAccountSettingsScreen(
                    it, phyVlabModel::prepareManualLogin, Modifier.fillMaxSize(),
                    onAccountSaved = {
                        settingsModel.setPhyVlabEnabled(true)
                        refreshPhyVlabAfterLogin()
                        popBackStack()
                    },
                    onTestSessionCleared = { refreshPhyVlabAfterLogin() },
                )
            }
        }
        PhysicsDemoRoute -> DestinationPage(
            title = "演示实验查询", expanded = expanded, refreshable = false,
            isRefreshing = physicsDemoState.refreshing, showBack = true,
            modifier = modifier, scrollUnderTopBar = true,
        ) {
            session.physicsDemoModel?.let {
                team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoScreen(it, Modifier.fillMaxSize())
            }
        }
        AppSection.PHYSICS_LAB, PhysicsLabSettingsRoute -> DestinationPage(
            title = if (route == PhysicsLabSettingsRoute) "物理实验账号设置" else "物理实验同步",
            expanded = expanded,
            refreshable = route == AppSection.PHYSICS_LAB && physicsLabEnabled,
            isRefreshing = route == AppSection.PHYSICS_LAB && physicsLabState.refreshing,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            session.physicsLabModel?.let { labModel ->
                PhysicsLabSettings(
                    model = labModel,
                    modifier = Modifier.fillMaxSize(),
                    showTitle = false,
                    showAccountFields = route == PhysicsLabSettingsRoute,
                    showLabPreferences = route == AppSection.PHYSICS_LAB,
                    onAccountSaved = {
                        settingsModel.setPhysicsLabEnabled(true)
                        popBackStack()
                    },
                    onAccountCleared = { settingsModel.setPhysicsLabEnabled(false) },
                )
            }
        }
        AppSection.CITEL -> DestinationPage(
            title = AppSection.CITEL.title,
            expanded = expanded,
            refreshable = citelState.enabled,
            isRefreshing = citelState.refreshing,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
            onStatusClick = { citelSyncDialogVisible = true },
            idleStatusText = when {
                citelState.failed -> "同步失败" + if (citelState.fromCache) "·正显示缓存" else ""
                citelState.fromCache -> "等待同步·正显示缓存"
                citelState.lastSync != null -> "已同步"
                else -> "未同步"
            },
        ) {
            session.citelModel?.let { model ->
                team.bjtuss.bjtuselfservice.shared.feature.citel.CitelWorkspace(
                    model, entryLoggingIn, onOpenExternalUrl, Modifier.fillMaxSize(), fileGateway = fileGateway,
                    showDetailSheet = !useNativeSecondaryRoutes, directoryGateway = directoryGateway,
                    onOpenTask = {
                        if (useNativeSecondaryRoutes) onOpenNativeRoute(CITEL_DETAIL_ROUTE_ID)
                    })
            }
        }
        CitelDetailRoute -> DestinationPage(title = "作业详情", expanded = expanded, refreshable = true,
            isRefreshing = citelState.submissionBusy, showBack = true, modifier = modifier, scrollUnderTopBar = true) {
            session.citelModel?.let { model ->
                team.bjtuss.bjtuselfservice.shared.feature.citel.CitelDetailWorkspace(model, fileGateway,
                    onOpenExternalUrl, entryLoggingIn, Modifier.fillMaxSize(), directoryGateway = directoryGateway,
                    headerContent = { renderAssignmentMarkdownActions(AssignmentSource.CITEL) })
            }
        }
        CitelSettingsRoute -> DestinationPage(title = "CITEL 账号设置", expanded = expanded, refreshable = false,
            isRefreshing = false, showBack = true, modifier = modifier, scrollUnderTopBar = true) {
            session.citelModel?.let { model ->
                team.bjtuss.bjtuselfservice.shared.feature.citel.CitelSettingsWorkspace(model, Modifier.fillMaxSize(), entryLoggingIn,
                    onSaved = {
                        settingsModel.setCitelEnabled(true)
                        popBackStack()
                    }, onCleared = { settingsModel.setCitelEnabled(false) })
            }
        }
        AppSection.MORE -> DestinationPage(
            title = AppSection.MORE.title,
            expanded = expanded,
            refreshable = false,
            isRefreshing = false,
            showBack = isPushedHostDestination || route !in compactBottomNavSections,
            modifier = modifier,
            scrollUnderTopBar = true,
        ) {
            MoreWorkspace(
                agentPluginTitle = team.bjtuss.bjtuselfservice.shared.feature.settings.agentPluginTitle(platform),
                onOpenAgentPlugins = if (settingsState.preferences.isAgentPluginEnabled &&
                    (platform.family == PlatformFamily.MacOS || remoteAgent != null)) ({
                    if (useNativeSecondaryRoutes) onOpenNativeRoute(AI_ASSISTANT_SETTINGS_ROUTE_ID)
                    else backStack.add(AiAssistantSettingsRoute)
                }) else null,
                preferences = settingsState.preferences,
                physicsLabModel = session.physicsLabModel,
                onOpenPhysicsDemo = session.physicsDemoModel?.takeIf { physicsDemoState.enabled }?.let { {
                    if (useNativeSecondaryRoutes) onOpenNativeRoute(PHYSICS_DEMO_ROUTE_ID)
                    else backStack.add(PhysicsDemoRoute)
                } },
                onOpenSection = { target -> navigateToSection(target) },
                modifier = Modifier.fillMaxSize(),
                platformFamily = platform.family,
            )
        }
    }
}

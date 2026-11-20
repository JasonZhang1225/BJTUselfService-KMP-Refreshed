package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancySyncFailure
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusFailure
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSyncFailure
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyQueryState
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxFailure
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUiState
import team.bjtuss.bjtuselfservice.shared.feature.shell.SessionRefreshCoordinator
import team.bjtuss.bjtuselfservice.shared.logging.SyncTiming

@Composable
internal fun ShellAccountTasks(
    session: AuthenticatedSession,
    data: ShellData,
    forcedRouteId: String?,
    nativeTabBarEnabled: Boolean,
    refresher: ShellRefresher,
    currentRoute: AppRoute,
    onRecoveryStateChanged: (Boolean) -> Unit,
) {
    val entryLoggingIn = session.entryLoggingIn
    val gradeModel = session.gradeModel
    val courseScheduleModel = session.courseScheduleModel
    val examScheduleModel = session.examScheduleModel
    val homeworkModel = session.homeworkModel
    val coursewareModel = session.coursewareModel
    val classroomOccupancyModel = session.classroomOccupancyModel
    val mailboxModel = session.mailboxModel
    val phyVlabModel = session.phyVlabModel
    val libraryModel = session.libraryModel
    val homeModel = session.homeModel
    val settingsModel = session.settingsModel
    val settingsState = data.settingsState
    val physicsLabEnabled = data.physicsLabEnabled
    val phyVlabEnabled = data.phyVlabEnabled
    val citelEnabled = data.citelEnabled
    val refreshCoordinator = refresher.refreshCoordinator
    val refreshGlobal: suspend (Boolean) -> Unit = refresher::refreshGlobal

    LaunchedEffect(session.phyVlabAccountModel) { session.phyVlabAccountModel?.initialize() }

    // 仅主宿主持有时钟；切时区或跨零点后，即使没有网络刷新也更新作业和首页时间。
    LaunchedEffect(homeworkModel, forcedRouteId, nativeTabBarEnabled) {
        if (!shouldStartPhyVlabAutoSync(forcedRouteId, nativeTabBarEnabled)) return@LaunchedEffect
        while (true) {
            homeworkModel.updateTime()
            delay(30_000L)
        }
    }

    LaunchedEffect(refreshCoordinator) {
        refreshCoordinator.recoveryInProgress.collect { onRecoveryStateChanged(it) }
    }

    val latestGlobalRefresh = rememberUpdatedState(refreshGlobal)
    LaunchedEffect(session, forcedRouteId, nativeTabBarEnabled) {
        if (!shouldStartPhyVlabAutoSync(forcedRouteId, nativeTabBarEnabled)) return@LaunchedEffect
        session.appForegroundGeneration.collect { generation ->
            if (!session.entryLoggingIn && session.claimAppForegroundRefresh(generation)) latestGlobalRefresh.value(false)
        }
    }
    LaunchedEffect(session, forcedRouteId, nativeTabBarEnabled) {
        if (!shouldStartPhyVlabAutoSync(forcedRouteId, nativeTabBarEnabled)) return@LaunchedEffect
        session.appIdleClickGeneration.collect { generation ->
            if (!session.entryLoggingIn && session.claimAppIdleClickRefresh(generation)) latestGlobalRefresh.value(true)
        }
    }

    // 回到前台时，教学周仍未确认就补拉校历：长时间挂起会取消进行中的校历请求，
    // 只恢复 SESSION_EXPIRED 的业务请求修不好「日程加载中」。
    // 已经明确处于 SESSION_EXPIRED 的当前页面再自动重试一次；
    // 失败后保留当前错误页，不弹回登录页，把再次恢复的主动权交给右上角刷新。
    val latestRoute = rememberUpdatedState(currentRoute)
    LaunchedEffect(session) {
        suspend fun retryIfExpired(
            operation: suspend () -> Unit,
            sessionExpired: () -> Boolean,
        ) {
            if (sessionExpired()) operation()
        }

        session.appResumeGeneration.collect { generation ->
            if (!session.claimAppResume(generation) || entryLoggingIn) return@collect
            if (!courseScheduleModel.state.value.weekResolved) {
                courseScheduleModel.ensureCalendarLoaded()
            }
            when (latestRoute.value) {
                PhyVlabDetailRoute -> retryIfExpired(
                    operation = { phyVlabModel.loadSelectedActivityDetail(force = true) },
                    sessionExpired = {
                        phyVlabModel.state.value.detailFailure == PhyVlabSyncFailure.SESSION_EXPIRED
                    },
                )
                AppSection.HOME -> coroutineScope {
                    launch {
                        retryIfExpired(
                            operation = homeModel::refresh,
                            sessionExpired = { homeModel.state.value.failure == HomeStatusFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        retryIfExpired(
                            operation = homeworkModel::refresh,
                            sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        retryIfExpired(
                            operation = examScheduleModel::refresh,
                            sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        retryIfExpired(
                            operation = courseScheduleModel::refresh,
                            sessionExpired = { courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch { session.physicsLabModel?.refresh() }
                    launch { session.physicsDemoModel?.refresh() }
                    if (phyVlabEnabled) {
                        launch {
                            retryIfExpired(
                                operation = phyVlabModel::refresh,
                                sessionExpired = {
                                    val current = phyVlabModel.state.value
                                    current.failure == PhyVlabSyncFailure.SESSION_EXPIRED || current.accountLoginRequired
                                },
                            )
                        }
                    }
                    launch {
                        retryIfExpired(
                            operation = gradeModel::refresh,
                            sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
                        )
                    }
                }
                AppSection.GRADES -> retryIfExpired(
                    operation = gradeModel::refresh,
                    sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
                )
                AppSection.SCHEDULE -> retryIfExpired(
                    operation = courseScheduleModel::refresh,
                    sessionExpired = { courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED },
                )
                AppSection.EXAMS -> retryIfExpired(
                    operation = examScheduleModel::refresh,
                    sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                )
                AppSection.HOMEWORK -> retryIfExpired(
                    operation = homeworkModel::refresh,
                    sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                )
                AppSection.COURSEWARE, AppSection.PLAYBACK -> retryIfExpired(
                    operation = coursewareModel::refresh,
                    sessionExpired = { coursewareModel.state.value.failure == CoursewareSyncFailure.SESSION_EXPIRED },
                )
                AppSection.CLASSROOMS -> Unit
                AppSection.CLASSROOM_OCCUPANCY -> retryIfExpired(
                    operation = classroomOccupancyModel::refresh,
                    sessionExpired = {
                        (classroomOccupancyModel.state.value.queryState as? ClassroomOccupancyQueryState.Failed)
                            ?.reason == ClassroomOccupancySyncFailure.SESSION_EXPIRED
                    },
                )
                AppSection.MAILBOX -> retryIfExpired(
                    operation = mailboxModel::refresh,
                    sessionExpired = {
                        when (val current = mailboxModel.state.value) {
                            MailboxUiState.SessionUnavailable -> true
                            is MailboxUiState.Ready -> current.failure == MailboxFailure.SESSION_EXPIRED
                            else -> false
                        }
                    },
                )
                AppSection.PHYVLAB -> if (phyVlabEnabled) {
                    retryIfExpired(
                        operation = phyVlabModel::refresh,
                        sessionExpired = {
                            val current = phyVlabModel.state.value
                            current.failure == PhyVlabSyncFailure.SESSION_EXPIRED || current.accountLoginRequired
                        },
                    )
                }
                AppSection.CALENDAR,
                AppSection.REPORT_CARD_DOWNLOAD,
                AppSection.SETTINGS,
                AppSection.MORE,
                -> Unit
                else -> Unit
            }
        }
    }


    // 登录未完成（静默自动登录中）不触发任何网络自动同步。
    // 各 Workspace 只会 initialize(refreshFromNetwork=false) 灌缓存；真正的自动同步只在这里启动。
    LaunchedEffect(gradeModel, entryLoggingIn) {
        if (entryLoggingIn) return@LaunchedEffect
        val sessionRefresh = SessionRefreshCoordinator(
            reauthenticate = session.reauthenticateSession,
            probeSession = session.probeSession,
            // The entry login has just verified this session. Start module work
            // immediately; expired-session recovery still runs after a failure.
            sessionVerifiedAtStart = true,
            onRecoveryStateChanged = onRecoveryStateChanged,
        )
        sessionRefresh.run(
            operation = { SyncTiming.measure("startup.grades") { gradeModel.initialize(refreshFromNetwork = true) } },
            sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
        )
        // 网络失败的重试已在 GradeScreenModel.initialize 内（最多 3 次）。
        if (gradeModel.state.value.courseTypesByCode == null) {
            gradeModel.ensureProgramCourseTypes()
        }
    }
    LaunchedEffect(
        homeworkModel,
        examScheduleModel,
        courseScheduleModel,
        entryLoggingIn,
    ) {
        if (entryLoggingIn) return@LaunchedEffect
        val sessionRefresh = SessionRefreshCoordinator(
            reauthenticate = session.reauthenticateSession,
            probeSession = session.probeSession,
            // The entry login has just verified this session. Start module work
            // immediately; expired-session recovery still runs after a failure.
            sessionVerifiedAtStart = true,
            onRecoveryStateChanged = onRecoveryStateChanged,
        )
        coroutineScope {
            launch {
                // 作业自动同步的失败重试在 ScreenModel 内（最多 3 次），与课表一致。
                sessionRefresh.run(
                    operation = { SyncTiming.measure("startup.homework") { homeworkModel.initialize(refreshFromNetwork = true) } },
                    sessionExpired = {
                        homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED
                    },
                )
            }
            launch {
                sessionRefresh.run(
                    operation = { SyncTiming.measure("startup.exams") { examScheduleModel.initialize(refreshFromNetwork = true) } },
                    sessionExpired = {
                        examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED
                    },
                )
                if (examScheduleModel.state.value.failure != null) {
                    delay(LOGIN_SYNC_RETRY_DELAY_MILLIS)
                    sessionRefresh.run(
                        operation = { examScheduleModel.refresh() },
                        sessionExpired = {
                            examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED
                        },
                    )
                }
            }
            launch {
                // 课表：登录成功后才网络同步；失败重试在 ScreenModel 内（最多 3 次）。
                // 先灌入缓存，课表与校历随后由模型并行同步；周数仍只由校历确认。
                courseScheduleModel.initialize(refreshFromNetwork = false)
                sessionRefresh.run(
                    operation = { SyncTiming.measure("startup.schedule") { courseScheduleModel.initialize(refreshFromNetwork = true) } },
                    sessionExpired = {
                        courseScheduleModel.state.value.failure ==
                            CourseScheduleSyncFailure.SESSION_EXPIRED
                    },
                )
            }
        }
    }

    // 首页「新邮件」徽标：登录完成后探测一次收件箱实际未读数（Coremail 列表），
    // 之后由首页刷新和读信动作维护；探测失败时首页回退 MIS 聚合值。
    LaunchedEffect(mailboxModel, entryLoggingIn) {
        if (entryLoggingIn) return@LaunchedEffect
        SyncTiming.measure("startup.mail-unread") { mailboxModel.refreshUnreadInboxCount() }
    }

    // 独立校园网数据源由稳定首页宿主启动，原生二级页面仅共享状态。
    LaunchedEffect(session.citelModel, citelEnabled, entryLoggingIn, forcedRouteId, nativeTabBarEnabled) {
        val model = session.citelModel ?: return@LaunchedEffect
        model.setEnabled(citelEnabled)
        if (citelEnabled && !model.state.value.configured) settingsModel.setCitelEnabled(false)
        if (citelEnabled && !entryLoggingIn && shouldStartPhyVlabAutoSync(forcedRouteId, nativeTabBarEnabled)) {
            SyncTiming.measure("startup.citel") { model.refreshForAppEntry(0) }
        }
        // Only initial App entry triggers this; foreground/focus changes are not keys.
        // Reads/submissions recover the session only after confirmed expiry.
    }
    // 与物理实验/CITEL 同一门控：iOS 原生 Tab 下首页宿主带 forcedRouteId=HOME，仍需自动同步。
    LaunchedEffect(session.physicsDemoModel, entryLoggingIn, forcedRouteId, nativeTabBarEnabled) {
        val demo = session.physicsDemoModel ?: return@LaunchedEffect
        demo.initialize()
        if (!entryLoggingIn && shouldStartPhyVlabAutoSync(forcedRouteId, nativeTabBarEnabled)) {
            SyncTiming.measure("startup.physics-demo") { demo.refresh(onlyIfUnsynced = true) }
        }
    }
    LaunchedEffect(session.physicsLabModel, physicsLabEnabled, entryLoggingIn, forcedRouteId, nativeTabBarEnabled) {
        val labModel = session.physicsLabModel ?: return@LaunchedEffect
        labModel.setEnabled(physicsLabEnabled)
        if (physicsLabEnabled && !labModel.state.value.configured) settingsModel.setPhysicsLabEnabled(false)
        if (physicsLabEnabled && !entryLoggingIn && shouldStartPhyVlabAutoSync(forcedRouteId, nativeTabBarEnabled)) {
            SyncTiming.measure("startup.physics-lab") { labModel.refresh() }
        }
    }
    // 图书馆：登录后静默同步一次，用于首页「借阅到期」提醒；失败不打扰用户，
    // 进入页面或手动刷新时再报错。原生二级页宿主不重复同步。
    LaunchedEffect(libraryModel, entryLoggingIn, forcedRouteId, nativeTabBarEnabled) {
        if (entryLoggingIn) return@LaunchedEffect
        if (!shouldStartPhyVlabAutoSync(forcedRouteId, nativeTabBarEnabled)) return@LaunchedEffect
        SyncTiming.measure("startup.library") { libraryModel.refreshIfNeeded() }
    }

    // 物理在线总开关同时控制同步与底栏入口。
    LaunchedEffect(phyVlabModel, phyVlabEnabled, entryLoggingIn, forcedRouteId, nativeTabBarEnabled) {
        // 原生作业详情页复用同一个模型，但不能在这里重新刷新整份课程数据；
        // refresh 成功会清空 selectedActivity，导致详情页退化成“未选择物理在线作业”。
        if (
            entryLoggingIn ||
            !phyVlabEnabled ||
            !shouldStartPhyVlabAutoSync(
                forcedRouteId = forcedRouteId,
                nativeTabBarEnabled = nativeTabBarEnabled,
            )
        ) return@LaunchedEffect
        phyVlabModel.initialize(refreshFromNetwork = false)
        SyncTiming.measure("startup.physical-online") { phyVlabModel.refresh() }
    }

    // 进入主界面后静默检查一次更新（对齐原安卓启动时自动检测）：
    // 仅发现新版本才弹「前往下载」；失败或无更新不打扰用户。
    // settingsModel 与 login 同生命周期、按 studentId remember，每次登录各弹一次。
    LaunchedEffect(settingsModel, entryLoggingIn) {
        if (entryLoggingIn) return@LaunchedEffect
        settingsModel.checkForUpdate(silentOnMiss = true)
    }

}

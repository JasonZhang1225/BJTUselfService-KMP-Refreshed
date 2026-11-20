package team.bjtuss.bjtuselfservice.shared.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import team.bjtuss.bjtuselfservice.shared.feature.shell.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusFailure
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyQueryState
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxFailure
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUiState
import team.bjtuss.bjtuselfservice.shared.logging.SyncTiming

/** Account-owned automatic work. UI hosts supply route data, never own these jobs. */
internal class AccountSyncTasks(
    private val session: AuthenticatedSession,
    private val scope: CoroutineScope,
    private val refresher: AccountRefresher,
) {
    private val settingsModel = session.settingsModel
    val recoveryInProgress get() = refresher.recoveryInProgress
    private val onRecoveryStateChanged: (Boolean) -> Unit = refresher::setRecoveryState
    private val login = session.entryLoginState
    private val physicsLab = settingsModel.state.map { it.preferences.isPhysicsLabEnabled }.distinctUntilChanged()
    private val citel = settingsModel.state.map { it.preferences.isCitelEnabled }.distinctUntilChanged()
    private val phyVlab = settingsModel.state.map { it.preferences.isPhyVlabEnabled }.distinctUntilChanged()
    private val physicsLabEnabled get() = settingsModel.state.value.preferences.isPhysicsLabEnabled
    private val citelEnabled get() = settingsModel.state.value.preferences.isCitelEnabled
    private val phyVlabEnabled get() = settingsModel.state.value.preferences.isPhyVlabEnabled
    private val entryLoggingIn get() = login.value
    private var started = false

    /** Idempotent across root reconstruction and native page creation. */
    fun start() {
        if (started) return
        started = true
        val gradeModel = session.gradeModel
        val courseScheduleModel = session.courseScheduleModel
        val examScheduleModel = session.examScheduleModel
        val homeworkModel = session.homeworkModel
        val mailboxModel = session.mailboxModel
        val phyVlabModel = session.phyVlabModel

        observe { session.phyVlabAccountModel?.initialize() }

        // 仅当前账号持有时钟；切时区或跨零点后，即使没有网络刷新也更新作业和首页时间。
        observe {
            while (true) {
                homeworkModel.updateTime()
                delay(30_000L)
            }
        }

        observe {
            refresher.refreshCoordinator.recoveryInProgress.collect { onRecoveryStateChanged(it) }
        }
        observe {
            session.appForegroundGeneration.collect { generation ->
                if (!session.entryLoggingIn && session.claimAppForegroundRefresh(generation)) {
                    refresher.refreshGlobal(false)
                }
            }
        }
        observe {
            session.appIdleClickGeneration.collect { generation ->
                if (!session.entryLoggingIn && session.claimAppIdleClickRefresh(generation)) {
                    refresher.refreshGlobal(true)
                }
            }
        }

        // 回到前台时，教学周仍未确认就补拉校历；已确认过期的当前页再自动重试一次。
        observe {
            session.appResumeGeneration.collect { generation ->
                if (!session.claimAppResume(generation) || session.entryLoggingIn) return@collect
                if (!courseScheduleModel.state.value.weekResolved) {
                    courseScheduleModel.ensureCalendarLoaded()
                }
                when (session.activeSyncRoute) {
                    PhyVlabDetailRoute -> refresher.retryIfExpired(
                        operation = { phyVlabModel?.loadSelectedActivityDetail(force = true) },
                        sessionExpired = { refresher.phyVlabDetailExpired },
                    )
                    AppSection.HOME -> coroutineScope {
                        launch {
                            refresher.retryIfExpired(
                                operation = session.homeModel::refresh,
                                sessionExpired = { session.homeModel.state.value.failure == HomeStatusFailure.SESSION_EXPIRED },
                            )
                        }
                        launch {
                            refresher.retryIfExpired(
                                operation = homeworkModel::refresh,
                                sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                            )
                        }
                        launch {
                            refresher.retryIfExpired(
                                operation = examScheduleModel::refresh,
                                sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                            )
                        }
                        launch {
                            refresher.retryIfExpired(
                                operation = courseScheduleModel::refresh,
                                sessionExpired = { courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED },
                            )
                        }
                        launch { session.physicsLabModel?.refresh() }
                        launch { session.physicsDemoModel?.refresh() }
                        if (phyVlabEnabled) {
                            launch {
                                refresher.retryIfExpired(
                                    operation = { phyVlabModel?.refresh() },
                                    sessionExpired = { refresher.phyVlabExpired },
                                )
                            }
                        }
                        launch {
                            refresher.retryIfExpired(
                                operation = gradeModel::refresh,
                                sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
                            )
                        }
                    }
                    AppSection.GRADES -> refresher.retryIfExpired(
                        operation = gradeModel::refresh,
                        sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
                    )
                    AppSection.SCHEDULE -> refresher.retryIfExpired(
                        operation = courseScheduleModel::refresh,
                        sessionExpired = { courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED },
                    )
                    AppSection.EXAMS -> refresher.retryIfExpired(
                        operation = examScheduleModel::refresh,
                        sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                    )
                    AppSection.HOMEWORK -> refresher.retryIfExpired(
                        operation = homeworkModel::refresh,
                        sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                    )
                    AppSection.COURSEWARE, AppSection.PLAYBACK -> refresher.retryIfExpired(
                        operation = session.coursewareModel::refresh,
                        sessionExpired = {
                            session.coursewareModel.state.value.failure ==
                                team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure.SESSION_EXPIRED
                        },
                    )
                    AppSection.CLASSROOMS -> Unit
                    AppSection.CLASSROOM_OCCUPANCY -> refresher.retryIfExpired(
                        operation = session.classroomOccupancyModel::refresh,
                        sessionExpired = {
                            (session.classroomOccupancyModel.state.value.queryState as? ClassroomOccupancyQueryState.Failed)
                                ?.reason ==
                                team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancySyncFailure.SESSION_EXPIRED
                        },
                    )
                    AppSection.MAILBOX -> refresher.retryIfExpired(
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
                        refresher.retryIfExpired(
                            operation = { phyVlabModel?.refresh() },
                            sessionExpired = { refresher.phyVlabExpired },
                        )
                    }
                    else -> Unit
                }
            }
        }

        // 登录未完成（静默自动登录中）不触发任何网络自动同步。
        observe(login) {
            if (entryLoggingIn) return@observe
            SyncTiming.measure("startup.home-status") { session.homeModel.initialize() }
        }
        observe(login) {
            if (entryLoggingIn) return@observe
            val sessionRefresh = SessionRefreshCoordinator(
                reauthenticate = session.reauthenticateSession,
                probeSession = session.probeSession,
                sessionVerifiedAtStart = true,
                onRecoveryStateChanged = onRecoveryStateChanged,
            )
            sessionRefresh.run(
                operation = { SyncTiming.measure("startup.grades") { gradeModel.initialize(refreshFromNetwork = true) } },
                sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
            )
            if (gradeModel.state.value.courseTypesByCode == null) {
                gradeModel.ensureProgramCourseTypes()
            }
        }
        observe(login) {
            if (entryLoggingIn) return@observe
            val sessionRefresh = SessionRefreshCoordinator(
                reauthenticate = session.reauthenticateSession,
                probeSession = session.probeSession,
                sessionVerifiedAtStart = true,
                onRecoveryStateChanged = onRecoveryStateChanged,
            )
            coroutineScope {
                launch {
                    sessionRefresh.run(
                        operation = { SyncTiming.measure("startup.homework") { homeworkModel.initialize(refreshFromNetwork = true) } },
                        sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                    )
                }
                launch {
                    sessionRefresh.run(
                        operation = { SyncTiming.measure("startup.exams") { examScheduleModel.initialize(refreshFromNetwork = true) } },
                        sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                    )
                    if (examScheduleModel.state.value.failure != null) {
                        delay(LOGIN_SYNC_RETRY_DELAY_MILLIS)
                        sessionRefresh.run(
                            operation = { examScheduleModel.refresh() },
                            sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                }
                launch {
                    courseScheduleModel.initialize(refreshFromNetwork = false)
                    sessionRefresh.run(
                        operation = { SyncTiming.measure("startup.schedule") { courseScheduleModel.initialize(refreshFromNetwork = true) } },
                        sessionExpired = {
                            courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED
                        },
                    )
                }
            }
        }

        // 首页「新邮件」徽标：登录完成后探测一次收件箱实际未读数。
        observe(login) {
            if (entryLoggingIn) return@observe
            SyncTiming.measure("startup.mail-unread") { mailboxModel.refreshUnreadInboxCount() }
        }

        // 独立校园网数据源随账号启动，原生二级页面仅共享状态。
        observe(login, citel) {
            val model = session.citelModel ?: return@observe
            model.setEnabled(citelEnabled)
            if (citelEnabled && !model.state.value.configured) settingsModel.setCitelEnabled(false)
            if (citelEnabled && !entryLoggingIn) {
                SyncTiming.measure("startup.citel") { model.refreshForAppEntry(0) }
            }
        }
        observe(login) {
            val demo = session.physicsDemoModel ?: return@observe
            demo.initialize()
            if (!entryLoggingIn) {
                SyncTiming.measure("startup.physics-demo") { demo.refresh(onlyIfUnsynced = true) }
            }
        }
        observe(login, physicsLab) {
            val labModel = session.physicsLabModel ?: return@observe
            labModel.setEnabled(physicsLabEnabled)
            if (physicsLabEnabled && !labModel.state.value.configured) settingsModel.setPhysicsLabEnabled(false)
            if (physicsLabEnabled && !entryLoggingIn) {
                SyncTiming.measure("startup.physics-lab") { labModel.refresh() }
            }
        }

        // 图书馆：登录后静默同步一次，用于首页借阅到期提醒；原生二级页宿主不重复同步。
        observe(login) {
            if (entryLoggingIn) return@observe
            SyncTiming.measure("startup.library") { session.libraryModel.refreshIfNeeded() }
        }

        observe(login, phyVlab) {
            if (
                entryLoggingIn ||
                !phyVlabEnabled
            ) return@observe
            phyVlabModel?.initialize(refreshFromNetwork = false)
            SyncTiming.measure("startup.physical-online") { phyVlabModel?.refresh() }
        }

        observe(login) {
            if (entryLoggingIn) return@observe
            settingsModel.checkForUpdate(silentOnMiss = true)
        }
    }

    /** Key changes still cancel/restart the affected job, just as the old effect did. */
    private fun observe(vararg inputs: Flow<*>, block: suspend CoroutineScope.() -> Unit) {
        scope.launch {
            if (inputs.isEmpty()) coroutineScope { block() }
            else combine(inputs.asList()) { it.toList() }.distinctUntilChanged().collectLatest {
                coroutineScope { block() }
            }
        }
    }
}

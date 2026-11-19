package team.bjtuss.bjtuselfservice.shared.feature.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancySyncFailure
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSource
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyQueryState
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxFailure
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUiState
import team.bjtuss.bjtuselfservice.shared.feature.shell.SessionRefreshCoordinator
import team.bjtuss.bjtuselfservice.shared.sync.AppRefreshCoordinator
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshRequest
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource

internal class ShellRefresher(
    private val session: AuthenticatedSession,
    private val scope: CoroutineScope,
    private val onRecoveryStateChanged: (Boolean) -> Unit,
) {
    private val gradeModel get() = session.gradeModel
    private val courseScheduleModel get() = session.courseScheduleModel
    private val examScheduleModel get() = session.examScheduleModel
    private val homeworkModel get() = session.homeworkModel
    private val coursewareModel get() = session.coursewareModel
    private val classroomModel get() = session.classroomModel
    private val classroomOccupancyModel get() = session.classroomOccupancyModel
    private val mailboxModel get() = session.mailboxModel
    private val libraryModel get() = session.libraryModel
    private val phyVlabModel get() = session.phyVlabModel
    private val entryLoggingIn get() = session.entryLoggingIn
    private val reauthenticateSession get() = session.reauthenticateSession
    private val physicsLabEnabled get() = session.settingsModel.state.value.preferences.isPhysicsLabEnabled
    private val phyVlabEnabled get() = session.settingsModel.state.value.preferences.isPhyVlabEnabled
    private val citelEnabled get() = session.settingsModel.state.value.preferences.isCitelEnabled

    /** 全局刷新统一交给公共刷新服务；无协调器的探针会话使用空适配器，不访问网络。 */
    val refreshCoordinator: AppRefreshCoordinator = session.refreshCoordinator ?: AppRefreshCoordinator(
        accountScope = session.profile.studentId,
        adapters = emptyList(),
        scope = scope,
        nowEpochSeconds = { kotlin.time.Clock.System.now().epochSeconds },
    )

    /** 首页/前台/空闲恢复共用：全局来源 + 独立内存中的图书馆借阅。 */
    suspend fun refreshGlobal(forceReauthenticate: Boolean) {
        coroutineScope {
            launch {
                refreshCoordinator.refresh(
                    RefreshRequest(origin = RefreshOrigin.APP, forceReauthenticate = forceReauthenticate),
                )
            }
            // 图书馆与学校业务会话独立、不在公共刷新协调器里：全局刷新时一并重取借阅（失败保留旧数据）。
            launch { session.libraryModel.refresh(silent = true) }
        }
    }

    fun refresh(section: AppSection, currentRoute: AppRoute, data: ShellData) {
        scope.launch {
            // 静默自动登录期间会话未就绪，忽略刷新；登录完成后各模块会按自动同步设置初始化。
            if (entryLoggingIn) return@launch
            if (currentRoute == PhyVlabDetailRoute) {
                // 物理在线详情模型自身已在幂等读取失败时恢复一次 CAS；不要在这里重复
                // 套一层全量刷新，否则会清空当前作业选择并重复发起验证码。
                phyVlabModel.loadSelectedActivityDetail(force = true)
                return@launch
            }
            val sessionRefresh = SessionRefreshCoordinator(
                reauthenticate = reauthenticateSession,
                probeSession = session.probeSession,
                onRecoveryStateChanged = onRecoveryStateChanged,
            )
            suspend fun refreshModule(
                operation: suspend () -> Unit,
                sessionExpired: () -> Boolean,
            ) {
                sessionRefresh.run(operation, sessionExpired)
            }
            when (section) {
                AppSection.HOME -> refreshGlobal(false)
                AppSection.GRADES -> refreshModule(
                    operation = gradeModel::refresh,
                    sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
                )
                AppSection.SCHEDULE -> coroutineScope {
                    launch { session.physicsLabModel?.refresh() }
                    launch { session.physicsDemoModel?.refresh() }
                    launch {
                        refreshModule(
                            operation = examScheduleModel::refresh,
                            sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        refreshModule(
                            operation = courseScheduleModel::refresh,
                            sessionExpired = { courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        if (gradeModel.state.value.courseTypesByCode == null) gradeModel.ensureProgramCourseTypes()
                    }
                }
                AppSection.EXAMS -> refreshModule(
                    operation = examScheduleModel::refresh,
                    sessionExpired = { examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                )
                AppSection.HOMEWORK -> refreshModule(
                    operation = homeworkModel::refresh,
                    sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                )
                AppSection.COURSEWARE, AppSection.PLAYBACK -> refreshModule(
                    operation = coursewareModel::refresh,
                    sessionExpired = { coursewareModel.state.value.failure == CoursewareSyncFailure.SESSION_EXPIRED },
                )
                AppSection.CLASSROOMS -> classroomModel.refresh()
                AppSection.CLASSROOM_OCCUPANCY -> refreshModule(
                    operation = classroomOccupancyModel::refresh,
                    sessionExpired = {
                        (classroomOccupancyModel.state.value.queryState as? ClassroomOccupancyQueryState.Failed)
                            ?.reason == ClassroomOccupancySyncFailure.SESSION_EXPIRED
                    },
                )
                AppSection.MAILBOX -> refreshModule(
                    operation = mailboxModel::refresh,
                    sessionExpired = {
                        when (val current = mailboxModel.state.value) {
                            MailboxUiState.SessionUnavailable -> true
                            is MailboxUiState.Ready -> current.failure == MailboxFailure.SESSION_EXPIRED
                            else -> false
                        }
                    },
                )
                AppSection.LIBRARY -> libraryModel.refresh()
                AppSection.PHYVLAB -> if (phyVlabEnabled) phyVlabModel.refresh()
                AppSection.PHYSICS_LAB -> if (physicsLabEnabled) session.physicsLabModel?.refresh()
                AppSection.CITEL -> if (currentRoute == CitelDetailRoute) data.citelState.selectedTask?.let { session.citelModel?.selectTask(it) } else if (currentRoute != CitelSettingsRoute && citelEnabled) session.citelModel?.refresh()
                AppSection.ASSIGNMENTS -> coroutineScope { data.assignmentSync.forEach { status -> launch { refreshAssignmentSource(status.source) } } }
                AppSection.CALENDAR -> Unit
                AppSection.REPORT_CARD_DOWNLOAD -> Unit
                AppSection.SETTINGS, AppSection.CHANGELOG -> Unit
                AppSection.MORE -> Unit
            }
        }
    }
    fun retryPhyVlabDetail() {
        scope.launch {
            phyVlabModel.loadSelectedActivityDetail(force = true)
        }
    }

    suspend fun refreshAssignmentSource(source: AssignmentSource) {
        when (source) {
            AssignmentSource.COURSE_PLATFORM -> SessionRefreshCoordinator(reauthenticate = reauthenticateSession,
                probeSession = session.probeSession, onRecoveryStateChanged = onRecoveryStateChanged)
                .run(homeworkModel::refresh, { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED })
            AssignmentSource.PHYVLAB -> if (phyVlabEnabled) phyVlabModel.refresh()
            AssignmentSource.CITEL -> if (citelEnabled) session.citelModel?.refresh()
        }
    }
    fun refreshPhyVlabAfterLogin() {
        val coordinator = session.refreshCoordinator
        if (coordinator != null) {
            coordinator.enqueueFresh(RefreshRequest(setOf(RefreshSource.PHYVLAB), RefreshOrigin.APP))
        } else {
            scope.launch { phyVlabModel.refreshWaiting() }
        }
    }
}

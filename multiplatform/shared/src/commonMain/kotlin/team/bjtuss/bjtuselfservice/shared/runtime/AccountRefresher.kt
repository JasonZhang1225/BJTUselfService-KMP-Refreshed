package team.bjtuss.bjtuselfservice.shared.runtime

import team.bjtuss.bjtuselfservice.shared.feature.shell.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSyncFailure
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSource
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabContentSource
import team.bjtuss.bjtuselfservice.shared.sync.AppRefreshCoordinator
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshRequest
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource

/**
 * 页面刷新与账号工作共用的执行入口。
 *
 * 只回答“这次刷新要做哪些来源、失败是否需要恢复会话”，不决定页面何时调用它；
 * 页面/启动任务各自在合适的生命周期里调用 [refresh]、[refreshGlobal]。
 */
internal class AccountRefresher(
    private val session: AuthenticatedSession,
    private val scope: CoroutineScope,
) {
    private val recoveryState = MutableStateFlow(false)
    val recoveryInProgress = recoveryState.asStateFlow()
    internal fun setRecoveryState(active: Boolean) { recoveryState.value = active }
    private val onRecoveryStateChanged: (Boolean) -> Unit = ::setRecoveryState
    private val homeAndGradesProbe = RefreshSessionProbe(session.probeSession)
    private val assignmentProbe = RefreshSessionProbe(session.probeSession)

    private fun pageSession(probeSession: (suspend () -> Boolean)? = session.probeSession) =
        SessionRefreshCoordinator(
            reauthenticate = session.reauthenticateSession,
            probeSession = probeSession,
            onRecoveryStateChanged = onRecoveryStateChanged,
        )

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
                val request = RefreshRequest(origin = RefreshOrigin.APP, forceReauthenticate = forceReauthenticate)
                if (forceReauthenticate) {
                    refreshCoordinator.refresh(request)
                } else {
                    refreshCoordinator.refresh(request, homeAndGradesProbe)
                }
            }
            // 图书馆与学校业务会话独立、不在公共刷新协调器里：全局刷新时一并重取借阅（失败保留旧数据）。
            launch { session.libraryModel.refresh(silent = true) }
        }
    }

    /** 当前页刷新。静默自动登录期间会话未就绪，忽略。 */
    fun refresh(section: AppSection, route: AppRoute) {
        scope.launch {
            if (session.entryLoggingIn) return@launch
            if (route == PhyVlabDetailRoute) {
                // 物理在线详情模型自身已在幂等读取失败时恢复一次 CAS；不要重复套全量刷新，
                // 否则会清空当前作业选择并重复发起验证码。
                session.phyVlabModel?.loadSelectedActivityDetail(force = true)
                return@launch
            }
            val sessionRefresh by lazy { pageSession() }
            suspend fun refreshModule(
                operation: suspend () -> Unit,
                sessionExpired: () -> Boolean,
            ) {
                sessionRefresh.run(operation, sessionExpired)
            }
            val gradeModel = session.gradeModel
            val courseModel = session.courseScheduleModel
            val examModel = session.examScheduleModel
            val homeworkModel = session.homeworkModel
            val coursewareModel = session.coursewareModel
            when (section) {
                AppSection.HOME -> refreshGlobal(false)
                AppSection.GRADES -> homeAndGradesProbe.use { probe ->
                    pageSession(probe).run(
                        operation = gradeModel::refresh,
                        sessionExpired = { gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED },
                    )
                }
                AppSection.SCHEDULE -> coroutineScope {
                    launch { session.physicsLabModel?.refresh() }
                    launch { session.physicsDemoModel?.refresh() }
                    launch {
                        refreshModule(
                            operation = examModel::refresh,
                            sessionExpired = { examModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        refreshModule(
                            operation = courseModel::refresh,
                            sessionExpired = { courseModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED },
                        )
                    }
                    launch {
                        if (gradeModel.state.value.courseTypesByCode == null) gradeModel.ensureProgramCourseTypes()
                    }
                }
                AppSection.EXAMS -> refreshModule(
                    operation = examModel::refresh,
                    sessionExpired = { examModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED },
                )
                AppSection.HOMEWORK -> refreshModule(
                    operation = homeworkModel::refresh,
                    sessionExpired = { homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
                )
                AppSection.COURSEWARE, AppSection.PLAYBACK -> coursewareModel.refreshWithSession(sessionRefresh)
                AppSection.CLASSROOMS -> session.classroomModel.refresh()
                AppSection.CLASSROOM_OCCUPANCY -> session.classroomOccupancyModel.refreshWithSession(sessionRefresh)
                AppSection.MAILBOX -> session.mailboxModel.refreshWithSession(sessionRefresh)
                AppSection.LIBRARY -> session.libraryModel.refresh()
                AppSection.PHYVLAB -> if (session.settingsModel.state.value.preferences.isPhyVlabEnabled) {
                    session.phyVlabModel?.refresh()
                }
                AppSection.PHYSICS_LAB -> if (session.settingsModel.state.value.preferences.isPhysicsLabEnabled) {
                    session.physicsLabModel?.refresh()
                }
                AppSection.CITEL -> {
                    val citelEnabled = session.settingsModel.state.value.preferences.isCitelEnabled
                    when {
                        route == CitelDetailRoute -> session.citelModel?.state?.value?.selectedTask?.let {
                            session.citelModel?.selectTask(it)
                        }
                        route != CitelSettingsRoute && citelEnabled -> session.citelModel?.refresh()
                    }
                }
                AppSection.ASSIGNMENTS -> {
                    val phyVlabEnabled = session.settingsModel.state.value.preferences.isPhyVlabEnabled
                    val citelEnabled = session.settingsModel.state.value.preferences.isCitelEnabled
                    coroutineScope {
                        launch { refreshCoursePlatformAssignments() }
                        if (phyVlabEnabled) launch { session.phyVlabModel?.refresh() }
                        if (citelEnabled) launch { session.citelModel?.refresh() }
                    }
                }
                AppSection.CALENDAR,
                AppSection.REPORT_CARD_DOWNLOAD,
                AppSection.SETTINGS,
                AppSection.CHANGELOG,
                AppSection.MORE,
                -> Unit
            }
        }
    }

    fun refreshPhyVlabAfterAccountSaved() {
        val model = session.phyVlabModel ?: return
        if (session.refreshCoordinator != null) {
            refreshCoordinator.enqueueFresh(RefreshRequest(setOf(RefreshSource.PHYVLAB), RefreshOrigin.APP))
        } else {
            scope.launch { model.refreshWaiting() }
        }
    }

    fun requestAssignmentSource(source: AssignmentSource) {
        scope.launch { refreshAssignmentSourceInAccount(source) }
    }

    suspend fun refreshAssignmentSource(source: AssignmentSource) {
        // Cancelling a page stops waiting; the admitted account operation continues.
        scope.async { refreshAssignmentSourceInAccount(source) }.await()
    }

    private suspend fun refreshAssignmentSourceInAccount(source: AssignmentSource) {
        when (source) {
            AssignmentSource.COURSE_PLATFORM -> refreshCoursePlatformAssignments()
            AssignmentSource.PHYVLAB -> if (session.settingsModel.state.value.preferences.isPhyVlabEnabled) {
                session.phyVlabModel?.refresh()
            }
            AssignmentSource.CITEL -> if (session.settingsModel.state.value.preferences.isCitelEnabled) {
                session.citelModel?.refresh()
            }
        }
    }

    private suspend fun refreshCoursePlatformAssignments() = assignmentProbe.use { probe ->
        pageSession(probe).run(
            operation = session.homeworkModel::refresh,
            sessionExpired = { session.homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED },
        )
    }

    fun retryPhyVlabDetail() {
        scope.launch { session.phyVlabModel?.loadSelectedActivityDetail(force = true) }
    }

    /** 前台恢复时，已确认过期的当前页只补一次请求；失败仍保留错误页。 */
    suspend fun retryIfExpired(operation: suspend () -> Unit, sessionExpired: () -> Boolean) {
        if (sessionExpired()) operation()
    }

    val phyVlabExpired: Boolean
        get() = session.phyVlabModel?.state?.value?.let {
            it.failure == PhyVlabSyncFailure.SESSION_EXPIRED || it.accountLoginRequired
        } == true

    val phyVlabDetailExpired: Boolean
        get() = session.phyVlabModel?.state?.value?.detailFailure == PhyVlabSyncFailure.SESSION_EXPIRED

    /** 物理在线详情页当前内容是否来自缓存（刷新状态文案用）。 */
    val phyVlabContentFromCache: Boolean
        get() = session.phyVlabModel?.state?.value?.contentSource == PhyVlabContentSource.CACHE
}

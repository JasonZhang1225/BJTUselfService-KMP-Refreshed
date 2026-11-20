package team.bjtuss.bjtuselfservice.shared.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.auth.CaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.auth.SchoolSessionRecovery
import team.bjtuss.bjtuselfservice.shared.auth.SessionProbeResult
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.SyncSource
import team.bjtuss.bjtuselfservice.shared.cache.markSynced
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarGateway
import team.bjtuss.bjtuselfservice.shared.data.classroom.DefaultClassroomRepository
import team.bjtuss.bjtuselfservice.shared.data.classroom.SchoolClassroomRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.DefaultClassroomOccupancyRepository
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.SchoolClassroomOccupancyRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.course.CacheStoreCourseScheduleLocalDataSource
import team.bjtuss.bjtuselfservice.shared.data.course.DefaultCourseScheduleRepository
import team.bjtuss.bjtuselfservice.shared.data.course.SchoolCourseScheduleRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.courseware.CacheStoreCoursewareLocalDataSource
import team.bjtuss.bjtuselfservice.shared.data.courseware.DefaultCoursewareRepository
import team.bjtuss.bjtuselfservice.shared.data.courseware.SchoolCoursewareRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.exam.CacheStoreExamScheduleLocalDataSource
import team.bjtuss.bjtuselfservice.shared.feature.citel.completed
import team.bjtuss.bjtuselfservice.shared.data.exam.DefaultExamScheduleRepository
import team.bjtuss.bjtuselfservice.shared.data.exam.SchoolExamScheduleRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.grade.CacheStoreGradeLocalDataSource
import team.bjtuss.bjtuselfservice.shared.data.grade.DefaultGradeRepository
import team.bjtuss.bjtuselfservice.shared.data.grade.SchoolGradeRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.grade.SchoolTrainingProgramRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.home.CacheStoreHomeChangeFeedRepository
import team.bjtuss.bjtuselfservice.shared.data.home.CacheStoreHomeStatusLocalDataSource
import team.bjtuss.bjtuselfservice.shared.data.home.DefaultHomeStatusRepository
import team.bjtuss.bjtuselfservice.shared.data.home.SchoolHomeStatusRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.home.citelChangeRecorder
import team.bjtuss.bjtuselfservice.shared.data.home.courseChangeRecorder
import team.bjtuss.bjtuselfservice.shared.data.home.examChangeRecorder
import team.bjtuss.bjtuselfservice.shared.data.home.gradeChangeRecorder
import team.bjtuss.bjtuselfservice.shared.data.home.homeworkChangeRecorder
import team.bjtuss.bjtuselfservice.shared.data.home.physicsDemoChangeRecorder
import team.bjtuss.bjtuselfservice.shared.data.home.physicsLabChangeRecorder
import team.bjtuss.bjtuselfservice.shared.data.home.phyvlabChangeRecorder
import team.bjtuss.bjtuselfservice.shared.data.homework.CacheStoreHomeworkLocalDataSource
import team.bjtuss.bjtuselfservice.shared.data.homework.DefaultHomeworkRepository
import team.bjtuss.bjtuselfservice.shared.data.homework.SchoolHomeworkRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.otherfunction.DefaultOtherFunctionRepository
import team.bjtuss.bjtuselfservice.shared.data.otherfunction.SchoolOtherFunctionRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.CacheStorePhyVlabLocalDataSource
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.DefaultPhyVlabRepository
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSessionProtocol
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.SchoolPhyVlabRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFilterPreferences
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFilterPreferencesStore
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.course.saveAcademicWeeksIfChanged
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.OtherFunctionScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsScreenModel
import team.bjtuss.bjtuselfservice.shared.files.DirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.FileGateway
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.smartPlatformEndpointFor
import team.bjtuss.bjtuselfservice.shared.update.AppUpdateChecker

/**
 * 已登录账号的业务组合根（普通 Kotlin，不依赖 Compose）。
 *
 * App 会话（[team.bjtuss.bjtuselfservice.shared.runtime.bindAuthenticatedSession]）与无窗口宿主
 * 都用它装配同一套 Repository/ScreenModel 和 [refreshCoordinator]，不复制第二套同步协议。
 * 构造本身不访问网络；[close] 取消在途刷新（退出登录、切换账号、宿主结束）。
 */
class AppDataGraph(
    val profile: StudentProfile,
    transport: SchoolHttpTransport,
    val loginProtocol: SchoolLoginProtocol,
    cacheStore: CacheStore,
    platformFamily: PlatformFamily,
    appPreferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Boolean,
    val securityCoordinator: AccountSecurityCoordinator,
    captchaRecognizer: CaptchaRecognizer,
    /** 当前内存中的登录凭据；会话过期时用于在 App 内自动恢复。 */
    credentialsProvider: () -> Credentials?,
    fileGateway: FileGateway,
    onPurgeLogout: (String) -> Unit,
    /** 刷新协调器的调度器：App 用主线程（与旧壳层刷新一致），无窗口宿主用后台线程。 */
    refreshDispatcher: CoroutineDispatcher,
    nowEpochSeconds: () -> Long = { kotlin.time.Clock.System.now().epochSeconds },
    val accountWork: team.bjtuss.bjtuselfservice.shared.runtime.AccountWork = team.bjtuss.bjtuselfservice.shared.runtime.AccountWork(),
    private val entryWork: team.bjtuss.bjtuselfservice.shared.runtime.AccountWork? = null,
    private val onCleanup: (suspend (team.bjtuss.bjtuselfservice.shared.cleanup.CleanupOperation) -> team.bjtuss.bjtuselfservice.shared.cleanup.CleanupReport)? = null,
    private val playbackRecovery: team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackRecoveryAccess? = null,
) {
    private val appCacheStore = cacheStore
    internal val cleanupCache get() = appCacheStore
    private val fallbackCleanupCoordinator = team.bjtuss.bjtuselfservice.shared.cleanup.AccountCleanupCoordinator()
    val cacheStore = appCacheStore.guardedBy(accountWork)
    val transport: SchoolHttpTransport = team.bjtuss.bjtuselfservice.shared.runtime.AccountSchoolTransport(transport, accountWork)
    val accountScope: String = profile.studentId
    val assignmentCompletionStore = team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentCompletionStore(this.cacheStore, accountScope, accountWork.scope(kotlinx.coroutines.Dispatchers.Default), accountWork)

    private val smartPlatformEndpoint = smartPlatformEndpointFor(platformFamily)
    val homeChangeFeed = CacheStoreHomeChangeFeedRepository(accountScope, this.cacheStore)
    val gradeRepository = DefaultGradeRepository(
        accountScope = accountScope,
        local = CacheStoreGradeLocalDataSource(this.cacheStore),
        remote = SchoolGradeRemoteDataSource(this.transport),
        programRemote = SchoolTrainingProgramRemoteDataSource(this.transport),
        onSynced = { this.cacheStore.markSynced(accountScope, SyncSource.GRADES, kotlin.time.Clock.System.now().epochSeconds) },
    )
    val gradeModel = GradeScreenModel(gradeRepository, gradeChangeRecorder(homeChangeFeed),
        accountWork = accountWork,
    )
    // M11/M12 共用同一校历与学期来源：避免课表日期跳转再建一套 bksy 请求。
    val classroomOccupancyRepository = DefaultClassroomOccupancyRepository(
        remote = SchoolClassroomOccupancyRemoteDataSource(this.transport),
    )
    val courseScheduleRepository = DefaultCourseScheduleRepository(
        accountScope = accountScope,
        local = CacheStoreCourseScheduleLocalDataSource(this.cacheStore),
        remote = SchoolCourseScheduleRemoteDataSource(
            transport = this.transport,
            endpoint = smartPlatformEndpoint,
        ),
        onSynced = { this.cacheStore.markSynced(accountScope, SyncSource.COURSES, kotlin.time.Clock.System.now().epochSeconds) },
    )
    val courseScheduleModel = CourseScheduleScreenModel(
        repository = courseScheduleRepository,
        changeRecorder = courseChangeRecorder(homeChangeFeed),
        calendarRepository = classroomOccupancyRepository,
        onCurrentAcademicWeeks = { weeks ->
            this.cacheStore.saveAcademicWeeksIfChanged(accountScope, weeks)
        },
        accountWork = accountWork,
    )
    val examScheduleRepository = DefaultExamScheduleRepository(
        accountScope = accountScope,
        local = CacheStoreExamScheduleLocalDataSource(this.cacheStore),
        remote = SchoolExamScheduleRemoteDataSource(this.transport),
        onSynced = { this.cacheStore.markSynced(accountScope, SyncSource.EXAMS, kotlin.time.Clock.System.now().epochSeconds) },
    )
    val examScheduleModel = ExamScheduleScreenModel(examScheduleRepository, examChangeRecorder(homeChangeFeed),
        accountWork = accountWork,
    )
    val homeworkRepository = DefaultHomeworkRepository(
        accountScope = accountScope,
        local = CacheStoreHomeworkLocalDataSource(this.cacheStore),
        remote = SchoolHomeworkRemoteDataSource(
            transport = this.transport,
            endpoint = smartPlatformEndpoint,
        ),
        onSynced = { this.cacheStore.markSynced(accountScope, SyncSource.HOMEWORK, kotlin.time.Clock.System.now().epochSeconds) },
    )
    val homeworkModel = HomeworkScreenModel(
        homeworkRepository,
        homeworkChangeRecorder(homeChangeFeed),
        filterStore = object : HomeworkFilterPreferencesStore {
            override fun load(): HomeworkFilterPreferences =
                this@AppDataGraph.cacheStore.homeworkFilter(accountScope)

            override fun save(preferences: HomeworkFilterPreferences) {
                this@AppDataGraph.cacheStore.saveHomeworkFilter(accountScope, preferences)
            }
        },
        accountWork = accountWork,
    )
    val coursewareRepository = DefaultCoursewareRepository(
        accountScope = accountScope,
        local = CacheStoreCoursewareLocalDataSource(this.cacheStore),
        remote = SchoolCoursewareRemoteDataSource(
            transport = this.transport,
            endpoint = smartPlatformEndpoint,
        ),
    )
    val otherFunctionRepository = DefaultOtherFunctionRepository(
        remote = SchoolOtherFunctionRemoteDataSource(this.transport),
    )
    val otherFunctionModel = OtherFunctionScreenModel(otherFunctionRepository, fileGateway,
        accountWork = accountWork,
    )
    private val classroomTransport = createSchoolHttpTransport()
    val classroomRepository = DefaultClassroomRepository(
        remote = SchoolClassroomRemoteDataSource(team.bjtuss.bjtuselfservice.shared.runtime.AccountSchoolTransport(classroomTransport, accountWork)),
    )
    val classroomModel = ClassroomScreenModel(classroomRepository,
        accountWork = accountWork,
    )
    val classroomOccupancyModel = ClassroomOccupancyScreenModel(
        repository = classroomOccupancyRepository,
        peopleRepository = classroomRepository,
        // 默认周跟随课表切片的学校当前教学周，课表未同步时回退第 1 周。
        currentWeekProvider = {
            courseScheduleModel.state.value.currentWeek.takeIf { it > 0 } ?: 1
        },
        accountWork = accountWork,
    )
    val settingsModel = SettingsScreenModel(
        initialPreferences = appPreferences.copy(
            physicsLabEnabled = appPreferences.physicsLabEnabled
                ?: (this.cacheStore.metadata(accountScope, "physicslab.enabled") == "true"),
            citelEnabled = appPreferences.citelEnabled ?: (this.cacheStore.metadata(accountScope, "citel.enabled") == "true"),
        ),
        persistPreferences = onPreferencesChanged,
        clearAccountCache = {
            performCleanup(team.bjtuss.bjtuselfservice.shared.cleanup.CleanupOperation.ACCOUNT_CACHE).succeeded
        },
        prepareUninstall = {
            val report = performCleanup(team.bjtuss.bjtuselfservice.shared.cleanup.CleanupOperation.PREPARE_UNINSTALL)
            if (report.accountWorkStopped) onPurgeLogout(accountScope)
            report.succeeded
        },
        wipeAllLocalData = {
            val report = performCleanup(team.bjtuss.bjtuselfservice.shared.cleanup.CleanupOperation.ALL_LOCAL_DATA)
            if (report.accountWorkStopped) onPurgeLogout(accountScope)
            report.succeeded
        },
        checkLatestRelease = { AppUpdateChecker.fetchLatest(this.transport) },
        checkReleaseFromSource = { source -> AppUpdateChecker.fetchLatest(this.transport, source) },
        accountWork = accountWork,
    )
    val mailboxModel = MailboxScreenModel(this.transport,
        accountWork = accountWork,
    )
    // 各学校业务会话的 Cookie 有不同生命周期。保留当前登录凭据的内存引用，
    // 让刷新发现会话过期时可以在 App 内自动恢复 CAS/教务链路，
    // 不把用户推到无法回传 Cookie 的系统浏览器。
    val sessionRecovery = SchoolSessionRecovery(
        protocol = loginProtocol,
        captchaRecognizer = captchaRecognizer,
        credentialsProvider = {
            credentialsProvider()?.takeIf(Credentials::isValid)
        },
        accountWork = accountWork,
    )
    val coursewareModel = CoursewareScreenModel(
        repository = coursewareRepository,
        reauthenticate = sessionRecovery::attempt,
        accountWork = accountWork,
    )
    /** 页面模型的作用域；Android 下载执行器另外由应用按账号持有，不随 [close] 销毁。 */
    private val playbackScope = accountWork.scope(kotlinx.coroutines.Dispatchers.Main)
    val playbackTaskManager = playbackRecovery?.retainedManager(accountScope) ?: team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTaskManager(
        scope = playbackScope,
        downloader = team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader(
            transport = this.transport,
            segmentSource = team.bjtuss.bjtuselfservice.shared.data.courseware.platformBackgroundSegmentSource(this.transport)
                ?: team.bjtuss.bjtuselfservice.shared.data.courseware.DirectHlsSegmentSource(this.transport),
        ),
        compositor = { team.bjtuss.bjtuselfservice.shared.media.PlaybackPlatformHooks.effectiveCompositor },
        backgroundHost = { team.bjtuss.bjtuselfservice.shared.media.PlaybackPlatformHooks.backgroundHost },
        taskStore = playbackRecovery?.storeFor(accountScope),
        restoreDestination = playbackRecovery?.let { recovery -> { token, name -> recovery.restoreDestination(token, name) } },
    )
    /** 页面请求随会话释放；下载任务由独立执行器管理，明确退出账号时才清理。 */
    val playbackModel = team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackScreenModel(
        repository = coursewareRepository,
        taskManager = playbackTaskManager,
        scope = playbackScope,
        reauthenticate = sessionRecovery::attempt,
        accountWork = accountWork,
    )
    val phyVlabRepository = DefaultPhyVlabRepository(SchoolPhyVlabRemoteDataSource(this.transport))
    // 物理在线不再走学校统一认证：MoodleSession 缺失时用独立账号登录，账号由设置页保存。
    // 协议与账号模型互相引用（协议读账号、账号保存前用协议校验），用单元素持有者打破初始化环。
    private val phyVlabAccountHolder = arrayOfNulls<team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabAccountModel>(1)
    val phyVlabSessionProtocol = PhyVlabSessionProtocol(
        this.transport,
        sessionVault = securityCoordinator.phyVlabSessionVault(accountScope),
        credentials = { phyVlabAccountHolder[0]?.credentials() },
    )
    val phyVlabAccountModel = team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabAccountModel(
        accountScope, this.cacheStore, securityCoordinator.phyVlabVault(accountScope),
        signIn = phyVlabSessionProtocol::signInWith,
        rememberSession = phyVlabSessionProtocol::rememberCurrentSession,
        clearSession = phyVlabSessionProtocol::clearRememberedSession,
        accountWork = accountWork,
    ).also { phyVlabAccountHolder[0] = it }
    val phyVlabLocalDataSource = CacheStorePhyVlabLocalDataSource(this.cacheStore)
    val phyVlabModel = PhyVlabScreenModel(
        repository = phyVlabRepository,
        sessionProtocol = phyVlabSessionProtocol,
        localDataSource = phyVlabLocalDataSource,
        changeRecorder = phyvlabChangeRecorder(homeChangeFeed),
        accountScope = accountScope,
        filterStore = team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentFilterStore(this.cacheStore, accountScope, "phyvlab"),
        accountWork = accountWork,
    )
    val physicsLabModel = team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabModel(
        accountScope, this.cacheStore, securityCoordinator.physicsLabVault(accountScope),
        team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabRemote(this.transport),
        changeRecorder = physicsLabChangeRecorder(homeChangeFeed),
        accountWork = accountWork,
    )
    val physicsDemoModel = team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoModel(
        accountScope, this.cacheStore, securityCoordinator.physicsDemoVault(accountScope),
        team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoRemote(this.transport),
        changeRecorder = physicsDemoChangeRecorder(homeChangeFeed),
        accountWork = accountWork,
    )
    val homeStatusRepository = DefaultHomeStatusRepository(
        accountScope = accountScope,
        local = CacheStoreHomeStatusLocalDataSource(this.cacheStore),
        remote = SchoolHomeStatusRemoteDataSource(this.transport),
    )
    val homeModel = HomeScreenModel(homeStatusRepository,
        accountWork = accountWork,
    )
    val citelModel = team.bjtuss.bjtuselfservice.shared.feature.citel.CitelModel(accountScope, this.cacheStore,
        securityCoordinator.citelVault(accountScope), team.bjtuss.bjtuselfservice.shared.feature.citel.CitelRemote(this.transport),
        changeRecorder = citelChangeRecorder(homeChangeFeed),
        accountWork = accountWork,
    )

    /** 本机 CLI 与远程同步共用的作业详情入口。 */
    val assignmentDetails = team.bjtuss.bjtuselfservice.shared.agent.AgentAssignmentDetailService(
        accountScope, this.cacheStore, homeworkRepository,
        team.bjtuss.bjtuselfservice.shared.agent.AgentDetailBackends(
            phyVlabActivities = phyVlabModel::cachedActivitiesForAgent,
            phyVlabDetail = phyVlabModel::fetchDetailForAgent,
            phyVlabDownload = phyVlabModel::downloadAttachmentForAgent,
            citelProblem = { id, live ->
                val task = citelModel.state.value.tasks.firstOrNull { it.id == id && it.programming }
                task?.let { citelModel.remoteProblem(id, live)?.let { cached -> team.bjtuss.bjtuselfservice.shared.feature.citel.citelProblemMarkdown(it, cached) } }
            },
            citelSummary = { id ->
                citelModel.state.value.tasks.firstOrNull { it.id == id }
                    ?.let { team.bjtuss.bjtuselfservice.shared.agent.AgentAssignmentSummary(it.courseName, it.title, it.dueTime, it.completed) }
            },
            beginDetailBatch = phyVlabModel::beginAgentDetailBatch,
            endDetailBatch = phyVlabModel::endAgentDetailBatch,
        ),
        accountWork = accountWork,
    )

    private val refreshScope = accountWork.scope(refreshDispatcher)

    /** 全局刷新的唯一执行者：App 按钮、前台恢复、CLI 与远程任务都经由它。 */
    val refreshCoordinator: AppRefreshCoordinator = AppRefreshCoordinator(
        accountScope = accountScope,
        adapters = appRefreshAdapters(
            AppRefreshModels(
                accountScope = accountScope,
                cache = this.cacheStore,
                homeModel = homeModel,
                mailboxModel = mailboxModel,
                homeworkModel = homeworkModel,
                examScheduleModel = examScheduleModel,
                courseScheduleModel = courseScheduleModel,
                gradeModel = gradeModel,
                physicsLabModel = physicsLabModel,
                physicsDemoModel = physicsDemoModel,
                citelModel = citelModel,
                phyVlabModel = phyVlabModel,
                phyVlabAccountModel = phyVlabAccountModel,
                preferences = { settingsModel.state.value.preferences },
                nowEpochSeconds = nowEpochSeconds,
            ),
        ),
        scope = refreshScope,
        reauthenticate = sessionRecovery::attempt,
        probeSession = { loginProtocol.checkSession() is SessionProbeResult.Active },
        nowEpochSeconds = nowEpochSeconds,
    )

    /**
     * 无窗口宿主在刷新前先载入缓存（不访问网络），让变动记录以本机缓存为基线，
     * 与 App 先显示缓存再同步的顺序一致。App 由各页面自己初始化，不调用这里。
     */
    suspend fun loadCachedState() {
        gradeModel.initialize(refreshFromNetwork = false)
        courseScheduleModel.initialize(refreshFromNetwork = false)
        homeworkModel.initialize(refreshFromNetwork = false)
        examScheduleModel.initialize(refreshFromNetwork = false)
        citelModel.initialize()
    }

    private suspend fun performCleanup(operation: team.bjtuss.bjtuselfservice.shared.cleanup.CleanupOperation): team.bjtuss.bjtuselfservice.shared.cleanup.CleanupReport {
        onCleanup?.let { return it(operation) }
        return team.bjtuss.bjtuselfservice.shared.cleanup.AccountCleanupService(
            fallbackCleanupCoordinator, appCacheStore, securityCoordinator,
            team.bjtuss.bjtuselfservice.shared.cleanup.platformCleanup(), this, null, playbackRecovery,
            stopWork = { stopAndJoin() }, protocol = loginProtocol,
        ).execute(operation)
    }

    suspend fun stopAndJoin(discardPlayback: Boolean = false) {
        close()
        accountWork.join()
        entryWork?.join()
        classroomTransport.close()
        if (discardPlayback) playbackTaskManager.cancelAllAndJoin()
    }

    fun close() {
        entryWork?.stop()
        accountWork.stop()
        assignmentCompletionStore.close()
        refreshCoordinator.close()
        playbackModel.close()
    }
}

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
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.smartPlatformEndpointFor
import team.bjtuss.bjtuselfservice.shared.update.AppUpdateChecker

/**
 * 已登录账号的业务组合根（普通 Kotlin，不依赖 Compose）。
 *
 * App 会话（[team.bjtuss.bjtuselfservice.shared.feature.shell.rememberAuthenticatedSession]）与无窗口宿主
 * 都用它装配同一套 Repository/ScreenModel 和 [refreshCoordinator]，不复制第二套同步协议。
 * 构造本身不访问网络；[close] 取消在途刷新（退出登录、切换账号、宿主结束）。
 */
class AppDataGraph(
    val profile: StudentProfile,
    val transport: SchoolHttpTransport,
    val loginProtocol: SchoolLoginProtocol,
    val cacheStore: CacheStore,
    platformFamily: PlatformFamily,
    appPreferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Boolean,
    val securityCoordinator: AccountSecurityCoordinator,
    captchaRecognizer: CaptchaRecognizer,
    /** 当前内存中的登录凭据；会话过期时用于在 App 内自动恢复。 */
    credentialsProvider: () -> Credentials?,
    homeworkFileGateway: HomeworkFileGateway,
    onPurgeLogout: (String) -> Unit,
    /** 刷新协调器的调度器：App 用主线程（与旧壳层刷新一致），无窗口宿主用后台线程。 */
    refreshDispatcher: CoroutineDispatcher,
    nowEpochSeconds: () -> Long = { kotlin.time.Clock.System.now().epochSeconds },
) {
    val accountScope: String = profile.studentId
    val assignmentCompletionStore = team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentCompletionStore(cacheStore, accountScope)

    private val smartPlatformEndpoint = smartPlatformEndpointFor(platformFamily)
    val homeChangeFeed = CacheStoreHomeChangeFeedRepository(accountScope, cacheStore)
    val gradeRepository = DefaultGradeRepository(
        accountScope = accountScope,
        local = CacheStoreGradeLocalDataSource(cacheStore),
        remote = SchoolGradeRemoteDataSource(transport),
        programRemote = SchoolTrainingProgramRemoteDataSource(transport),
        onSynced = { cacheStore.markSynced(accountScope, SyncSource.GRADES, kotlin.time.Clock.System.now().epochSeconds) },
    )
    val gradeModel = GradeScreenModel(gradeRepository, gradeChangeRecorder(homeChangeFeed))
    // M11/M12 共用同一校历与学期来源：避免课表日期跳转再建一套 bksy 请求。
    val classroomOccupancyRepository = DefaultClassroomOccupancyRepository(
        remote = SchoolClassroomOccupancyRemoteDataSource(transport),
    )
    val courseScheduleRepository = DefaultCourseScheduleRepository(
        accountScope = accountScope,
        local = CacheStoreCourseScheduleLocalDataSource(cacheStore),
        remote = SchoolCourseScheduleRemoteDataSource(
            transport = transport,
            endpoint = smartPlatformEndpoint,
        ),
        onSynced = { cacheStore.markSynced(accountScope, SyncSource.COURSES, kotlin.time.Clock.System.now().epochSeconds) },
    )
    val courseScheduleModel = CourseScheduleScreenModel(
        repository = courseScheduleRepository,
        changeRecorder = courseChangeRecorder(homeChangeFeed),
        calendarRepository = classroomOccupancyRepository,
        onCurrentAcademicWeeks = { weeks ->
            cacheStore.saveAcademicWeeksIfChanged(accountScope, weeks)
        },
    )
    val examScheduleRepository = DefaultExamScheduleRepository(
        accountScope = accountScope,
        local = CacheStoreExamScheduleLocalDataSource(cacheStore),
        remote = SchoolExamScheduleRemoteDataSource(transport),
        onSynced = { cacheStore.markSynced(accountScope, SyncSource.EXAMS, kotlin.time.Clock.System.now().epochSeconds) },
    )
    val examScheduleModel = ExamScheduleScreenModel(examScheduleRepository, examChangeRecorder(homeChangeFeed))
    val homeworkRepository = DefaultHomeworkRepository(
        accountScope = accountScope,
        local = CacheStoreHomeworkLocalDataSource(cacheStore),
        remote = SchoolHomeworkRemoteDataSource(
            transport = transport,
            endpoint = smartPlatformEndpoint,
        ),
        onSynced = { cacheStore.markSynced(accountScope, SyncSource.HOMEWORK, kotlin.time.Clock.System.now().epochSeconds) },
    )
    val homeworkModel = HomeworkScreenModel(
        homeworkRepository,
        homeworkChangeRecorder(homeChangeFeed),
        filterStore = object : HomeworkFilterPreferencesStore {
            override fun load(): HomeworkFilterPreferences =
                cacheStore.homeworkFilter(accountScope)

            override fun save(preferences: HomeworkFilterPreferences) {
                cacheStore.saveHomeworkFilter(accountScope, preferences)
            }
        },
    )
    val coursewareRepository = DefaultCoursewareRepository(
        accountScope = accountScope,
        local = CacheStoreCoursewareLocalDataSource(cacheStore),
        remote = SchoolCoursewareRemoteDataSource(
            transport = transport,
            endpoint = smartPlatformEndpoint,
        ),
    )
    val otherFunctionRepository = DefaultOtherFunctionRepository(
        remote = SchoolOtherFunctionRemoteDataSource(transport),
    )
    val otherFunctionModel = OtherFunctionScreenModel(otherFunctionRepository, homeworkFileGateway)
    val classroomRepository = DefaultClassroomRepository(
        remote = SchoolClassroomRemoteDataSource(createSchoolHttpTransport()),
    )
    val classroomModel = ClassroomScreenModel(classroomRepository)
    val classroomOccupancyModel = ClassroomOccupancyScreenModel(
        repository = classroomOccupancyRepository,
        peopleRepository = classroomRepository,
        // 默认周跟随课表切片的学校当前教学周，课表未同步时回退第 1 周。
        currentWeekProvider = {
            courseScheduleModel.state.value.currentWeek.takeIf { it > 0 } ?: 1
        },
    )
    val settingsModel = SettingsScreenModel(
        initialPreferences = appPreferences.copy(
            physicsLabEnabled = appPreferences.physicsLabEnabled
                ?: (cacheStore.metadata(accountScope, "physicslab.enabled") == "true"),
            citelEnabled = appPreferences.citelEnabled ?: (cacheStore.metadata(accountScope, "citel.enabled") == "true"),
        ),
        persistPreferences = onPreferencesChanged,
        clearAccountCache = {
            assignmentCompletionStore.clearWith { cacheStore.clearAccount(accountScope) }
        },
        prepareUninstall = {
            playbackTaskManager.cancelAllAndJoin()
            val cleaner = team.bjtuss.bjtuselfservice.shared.feature.settings.platformLocalDataCleaner()
            val wiped = cleaner?.clearLocalData(cacheStore, removeAgent = true) == true
            if (wiped) onPurgeLogout(accountScope)
            wiped
        },
        wipeAllLocalData = {
            playbackTaskManager.cancelAllAndJoin()
            val cleaner = team.bjtuss.bjtuselfservice.shared.feature.settings.platformLocalDataCleaner()
            if (cleaner != null) {
                val wiped = cleaner.clearLocalData(cacheStore)
                if (wiped) onPurgeLogout(accountScope)
                wiped
            } else {
                val labAccounts = (cacheStore.metadataAccountScopes("physicslab.configured") + accountScope).distinct()
                val labCredentialsPurged = runCatching {
                    labAccounts.forEach { securityCoordinator.physicsLabVault(it)?.clear() }
                    (cacheStore.metadataAccountScopes("phydemo.configured") + accountScope).distinct()
                        .forEach { securityCoordinator.physicsDemoVault(it)?.clear() }
                    (cacheStore.metadataAccountScopes("phyvlab.configured") + accountScope).distinct()
                        .forEach { securityCoordinator.phyVlabVault(it)?.clear() }
                    (cacheStore.metadataAccountScopes("citel.username") + accountScope).distinct()
                        .forEach { securityCoordinator.citelVault(it)?.clear() }
                }.isSuccess
                val cacheCleared = labCredentialsPurged && runCatching { cacheStore.clearAll() }.isSuccess
                val credentialsPurged = securityCoordinator.purge()
                val wiped = cacheCleared && credentialsPurged && labCredentialsPurged
                // Stay signed out after a full wipe, or background sync could
                // immediately recreate personal cache rows from this live session.
                if (wiped) onPurgeLogout(accountScope)
                wiped
            }
        },
        checkLatestRelease = { AppUpdateChecker.fetchLatest(transport) },
        checkReleaseFromSource = { source -> AppUpdateChecker.fetchLatest(transport, source) },
    )
    val mailboxModel = MailboxScreenModel(transport)
    // 各学校业务会话的 Cookie 有不同生命周期。保留当前登录凭据的内存引用，
    // 让刷新发现会话过期时可以在 App 内自动恢复 CAS/教务链路，
    // 不把用户推到无法回传 Cookie 的系统浏览器。
    val sessionRecovery = SchoolSessionRecovery(
        protocol = loginProtocol,
        captchaRecognizer = captchaRecognizer,
        credentialsProvider = {
            credentialsProvider()?.takeIf(Credentials::isValid)
        },
    )
    val coursewareModel = CoursewareScreenModel(
        repository = coursewareRepository,
        reauthenticate = sessionRecovery::attempt,
    )
    /** 页面模型的作用域；Android 下载执行器另外由应用按账号持有，不随 [close] 销毁。 */
    private val playbackScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private val playbackRecovery = (homeworkFileGateway as? team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackRecoveryProvider)?.playbackRecovery
    val playbackTaskManager = playbackRecovery?.retainedManager(accountScope) ?: team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTaskManager(
        scope = playbackScope,
        downloader = team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader(
            transport = transport,
            segmentSource = team.bjtuss.bjtuselfservice.shared.data.courseware.platformBackgroundSegmentSource(transport)
                ?: team.bjtuss.bjtuselfservice.shared.data.courseware.DirectHlsSegmentSource(transport),
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
    )
    val phyVlabRepository = DefaultPhyVlabRepository(SchoolPhyVlabRemoteDataSource(transport))
    // 物理在线不再走学校统一认证：MoodleSession 缺失时用独立账号登录，账号由设置页保存。
    // 协议与账号模型互相引用（协议读账号、账号保存前用协议校验），用单元素持有者打破初始化环。
    private val phyVlabAccountHolder = arrayOfNulls<team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabAccountModel>(1)
    val phyVlabSessionProtocol = PhyVlabSessionProtocol(transport, credentials = { phyVlabAccountHolder[0]?.credentials() })
    val phyVlabAccountModel = team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabAccountModel(
        accountScope, cacheStore, securityCoordinator.phyVlabVault(accountScope),
        signIn = phyVlabSessionProtocol::signInWith,
    ).also { phyVlabAccountHolder[0] = it }
    val phyVlabLocalDataSource = CacheStorePhyVlabLocalDataSource(cacheStore)
    val phyVlabModel = PhyVlabScreenModel(
        repository = phyVlabRepository,
        sessionProtocol = phyVlabSessionProtocol,
        localDataSource = phyVlabLocalDataSource,
        changeRecorder = phyvlabChangeRecorder(homeChangeFeed),
        accountScope = accountScope,
        filterStore = team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentFilterStore(cacheStore, accountScope, "phyvlab"),
    )
    val physicsLabModel = team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabModel(
        accountScope, cacheStore, securityCoordinator.physicsLabVault(accountScope),
        team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabRemote(transport),
        changeRecorder = physicsLabChangeRecorder(homeChangeFeed),
    )
    val physicsDemoModel = team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoModel(
        accountScope, cacheStore, securityCoordinator.physicsDemoVault(accountScope),
        team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoRemote(transport),
        changeRecorder = physicsDemoChangeRecorder(homeChangeFeed),
    )
    val homeStatusRepository = DefaultHomeStatusRepository(
        accountScope = accountScope,
        local = CacheStoreHomeStatusLocalDataSource(cacheStore),
        remote = SchoolHomeStatusRemoteDataSource(transport),
    )
    val homeModel = HomeScreenModel(homeStatusRepository)
    val citelModel = team.bjtuss.bjtuselfservice.shared.feature.citel.CitelModel(accountScope, cacheStore,
        securityCoordinator.citelVault(accountScope), team.bjtuss.bjtuselfservice.shared.feature.citel.CitelRemote(transport),
        changeRecorder = citelChangeRecorder(homeChangeFeed))

    private val refreshScope = CoroutineScope(SupervisorJob() + refreshDispatcher)

    /** 全局刷新的唯一执行者：App 按钮、前台恢复、CLI 与远程任务都经由它。 */
    val refreshCoordinator: AppRefreshCoordinator = AppRefreshCoordinator(
        accountScope = accountScope,
        adapters = appRefreshAdapters(
            AppRefreshModels(
                accountScope = accountScope,
                cache = cacheStore,
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
    }

    fun close() {
        assignmentCompletionStore.close()
        refreshCoordinator.close()
        playbackModel.close()
    }
}

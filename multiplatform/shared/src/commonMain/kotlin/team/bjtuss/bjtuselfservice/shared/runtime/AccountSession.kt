package team.bjtuss.bjtuselfservice.shared.runtime

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.auth.CaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.auth.SessionProbeResult
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarGateway
import team.bjtuss.bjtuselfservice.shared.data.library.DefaultLibraryRepository
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryLoginProtocol
import team.bjtuss.bjtuselfservice.shared.data.library.SchoolLibraryRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AggregateAssignmentFilterStore
import team.bjtuss.bjtuselfservice.shared.feature.library.LibraryModel
import team.bjtuss.bjtuselfservice.shared.feature.shell.MobileSessionHolder
import team.bjtuss.bjtuselfservice.shared.feature.shell.createMobileRemoteAgent
import team.bjtuss.bjtuselfservice.shared.files.DirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.FileGateway
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackRecoveryAccess
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.sync.AppDataGraph

/** One login incarnation. The presentation facade stays stable during account recovery. */
internal class AccountSession(
    val id: Long,
    val accountScope: String,
    val graph: AppDataGraph,
    val presentation: AuthenticatedSession,
    private val bindings: AccountSessionBindings,
    syncDispatcher: CoroutineDispatcher,
    private val releaseRemote: () -> Unit,
) {
    val refreshService = AccountRefresher(presentation, graph.accountWork.scope(syncDispatcher)).also {
        presentation.refreshService = it
    }
    val syncTasks = AccountSyncTasks(presentation, graph.accountWork.scope(syncDispatcher), refreshService).also {
        presentation.syncTasks = it
    }

    val widgetPublisher = AccountCourseWidgetPublisher(presentation, graph.accountWork.scope(syncDispatcher))

    @Volatile
    var isClosed: Boolean = false
        private set
    private var logoutRequested = false
    private var workStopped = false

    fun update(
        profile: StudentProfile,
        entryLoggingIn: Boolean,
        credentials: Credentials,
        preferences: AppPreferences,
        onPreferencesChanged: (AppPreferences) -> Boolean,
        onLogout: (String) -> Unit,
        onPurgeLogout: (String) -> Unit,
    ) {
        check(!isClosed) { "Account session is closed" }
        require(profile.studentId.trim() == accountScope) { "Account binding mismatch" }
        bindings.credentials = credentials
        bindings.onPreferencesChanged = onPreferencesChanged
        bindings.onLogout = onLogout
        bindings.onPurgeLogout = onPurgeLogout
        presentation.profile = profile
        presentation.entryLoggingIn = entryLoggingIn
        presentation.loginSyncPreferences = preferences
    }

    /** A rebuilt host replaces outward adapters without publishing a login result again. */
    fun rebindCallbacks(
        onPreferencesChanged: (AppPreferences) -> Boolean,
        onLogout: (String) -> Unit,
        onPurgeLogout: (String) -> Unit,
    ) {
        check(!isClosed) { "Account session is closed" }
        bindings.onPreferencesChanged = onPreferencesChanged
        bindings.onLogout = onLogout
        bindings.onPurgeLogout = onPurgeLogout
    }

    fun beginLogout(): Boolean {
        if (logoutRequested || isClosed) return false
        logoutRequested = true
        stopWork()
        return true
    }

    fun finishLogout() { logoutRequested = false }
    fun notifyLogout() = bindings.onLogout(accountScope)

    fun close() {
        if (isClosed) return
        isClosed = true
        bindings.clear()
        stopWork()
    }

    private fun stopWork() {
        if (workStopped) return
        workStopped = true
        graph.close()
        releaseRemote()
    }
}

/** Mutable inputs replace Compose rememberUpdatedState without rebuilding the graph. */
internal class AccountSessionBindings(
    @Volatile var credentials: Credentials?,
    var onPreferencesChanged: (AppPreferences) -> Boolean,
    var onLogout: (String) -> Unit,
    var onPurgeLogout: (String) -> Unit,
) {
    fun clear() {
        credentials = null
        onPreferencesChanged = { false }
        onLogout = {}
        onPurgeLogout = {}
    }
}

internal fun AppRuntime.obtainAccountSession(
    profile: StudentProfile,
    entryLoggingIn: Boolean,
    transport: SchoolHttpTransport,
    loginProtocol: SchoolLoginProtocol,
    cacheStore: CacheStore,
    platform: PlatformInfo,
    preferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Boolean,
    securityCoordinator: AccountSecurityCoordinator,
    captchaRecognizer: CaptchaRecognizer,
    credentials: Credentials,
    fileGateway: FileGateway,
    directoryGateway: DirectoryGateway,
    systemCalendarGateway: SystemCalendarGateway,
    onLogout: (String) -> Unit,
    onPurgeLogout: (String) -> Unit,
    refreshDispatcher: CoroutineDispatcher = Dispatchers.Main,
    playbackRecovery: PlaybackRecoveryAccess? = null,
): AccountSession = obtainAccount(profile.studentId) { id ->
    val bindings = AccountSessionBindings(credentials, onPreferencesChanged, onLogout, onPurgeLogout)
    val graphWork = AccountWork()
    lateinit var account: AccountSession
    val graph = AppDataGraph(
        profile = profile,
        transport = transport,
        loginProtocol = loginProtocol,
        cacheStore = cacheStore,
        platformFamily = platform.family,
        appPreferences = preferences,
        onPreferencesChanged = { graphWork.mutate { bindings.onPreferencesChanged(it) } },
        securityCoordinator = securityCoordinator,
        captchaRecognizer = captchaRecognizer,
        credentialsProvider = { bindings.credentials },
        fileGateway = fileGateway,
        playbackRecovery = playbackRecovery,
        onPurgeLogout = { scope -> if (isCurrent(account)) bindings.onPurgeLogout(scope) },
        refreshDispatcher = refreshDispatcher,
        accountWork = graphWork,
        entryWork = entryAuthenticationWork,
        onCleanup = { operation -> cleanup(account, operation) },
    )
    val holder = MobileSessionHolder()
    val mobileRemote = createMobileRemoteAgent(platform, graph.cacheStore, securityCoordinator, graph.refreshCoordinator, holder, graphWork)
    mobileRemote?.controller?.cleanupCoordinator = cleanupCoordinator
    try {
        // 图书馆保留独立登录链路和仅内存的数据策略，凭据随本账号会话更新。
        val libraryProtocol = LibraryLoginProtocol(
            transport = graph.transport,
            credentialsProvider = { bindings.credentials?.takeIf(Credentials::isValid) },
        )
        val libraryModel = LibraryModel(DefaultLibraryRepository(
            remote = SchoolLibraryRemoteDataSource(graph.transport),
            authenticator = libraryProtocol,
        ), accountWork = graphWork, refreshScope = graphWork.scope(taskDispatcher))
        val presentation = AuthenticatedSession(
            profile = profile,
            entryLoggingIn = entryLoggingIn,
            gradeModel = graph.gradeModel,
            courseScheduleModel = graph.courseScheduleModel,
            examScheduleModel = graph.examScheduleModel,
            homeworkModel = graph.homeworkModel,
            coursewareModel = graph.coursewareModel,
            playbackModel = graph.playbackModel,
            otherFunctionModel = graph.otherFunctionModel,
            classroomModel = graph.classroomModel,
            classroomOccupancyModel = graph.classroomOccupancyModel,
            settingsModel = graph.settingsModel,
            loginSyncPreferences = preferences,
            mailboxModel = graph.mailboxModel,
            phyVlabModel = graph.phyVlabModel,
            physicsLabModel = graph.physicsLabModel,
            physicsDemoModel = graph.physicsDemoModel,
            phyVlabAccountModel = graph.phyVlabAccountModel,
            citelModel = graph.citelModel,
            libraryModel = libraryModel,
            aggregateFilterStore = AggregateAssignmentFilterStore(graph.cacheStore, profile.studentId),
            assignmentCompletionStore = graph.assignmentCompletionStore,
            homeModel = graph.homeModel,
            homeChangeFeed = graph.homeChangeFeed,
            fileGateway = fileGateway,
            directoryGateway = directoryGateway,
            systemCalendarGateway = systemCalendarGateway,
            onLogout = { requestLogout(account) },
            reauthenticateSession = { isCurrent(account) && graphWork.isActive && graph.sessionRecovery.attempt() },
            probeSession = { isCurrent(account) && graphWork.isActive && graphWork.run { loginProtocol.checkSession() is SessionProbeResult.Active } },
            refreshCoordinator = graph.refreshCoordinator,
            homeworkRepository = graph.homeworkRepository,
            assignmentDetails = graph.assignmentDetails,
            remoteAgent = mobileRemote?.controller,
            accountWork = graphWork,
        )
        account = AccountSession(id, profile.studentId.trim(), graph, presentation, bindings, taskDispatcher) {
            holder.session = null
            mobileRemote?.close()
        }
        holder.session = presentation
        account
    } catch (error: Throwable) {
        bindings.clear()
        holder.session = null
        try { mobileRemote?.close() } finally { graph.close() }
        throw error
    }
}

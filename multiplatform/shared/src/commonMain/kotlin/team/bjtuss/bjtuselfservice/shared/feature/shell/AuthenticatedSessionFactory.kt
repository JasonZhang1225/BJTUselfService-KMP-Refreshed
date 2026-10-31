package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import team.bjtuss.bjtuselfservice.shared.data.library.DefaultLibraryRepository
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryLoginProtocol
import team.bjtuss.bjtuselfservice.shared.data.library.SchoolLibraryRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.feature.library.LibraryModel
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.sync.AppDataGraph
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarGateway

/**
 * 已登录会话的组合根：在 Compose 内按账号装配全部 Repository/ScreenModel 并产出 [AuthenticatedSession]。
 * 从 LoginRoute 抽出，登录页只负责登录流程本身与会话生命周期通知。
 */
@Composable
internal fun rememberAuthenticatedSession(
    profile: StudentProfile,
    entryLoggingIn: Boolean,
    transport: SchoolHttpTransport,
    loginProtocol: SchoolLoginProtocol,
    cacheStore: CacheStore,
    platform: PlatformInfo,
    appPreferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Boolean,
    securityCoordinator: AccountSecurityCoordinator,
    captchaRecognizer: CaptchaRecognizer,
    username: String,
    password: String,
    homeworkFileGateway: HomeworkFileGateway,
    coursewareDirectoryGateway: CoursewareDirectoryGateway,
    systemCalendarGateway: SystemCalendarGateway,
    onLogout: (String) -> Unit,
    onPurgeLogout: (String) -> Unit,
): AuthenticatedSession {
    // 各学校业务会话的 Cookie 有不同生命周期。保留当前登录凭据的内存引用，
    // 让刷新发现会话过期时可以在 App 内自动恢复 CAS/教务链路，
    // 不把用户推到无法回传 Cookie 的系统浏览器。
    val latestCredentials = rememberUpdatedState(Credentials(username.trim(), password))
    val logoutScope = rememberCoroutineScope()
    // 业务依赖由普通 Kotlin 组合根装配（无窗口宿主复用同一份）；Compose 只负责按账号持有与释放。
    val graph = remember(profile.studentId, cacheStore, transport, loginProtocol, platform.family,
        securityCoordinator, captchaRecognizer, homeworkFileGateway) {
        AppDataGraph(
            profile = profile,
            transport = transport,
            loginProtocol = loginProtocol,
            cacheStore = cacheStore,
            platformFamily = platform.family,
            appPreferences = appPreferences,
            onPreferencesChanged = onPreferencesChanged,
            securityCoordinator = securityCoordinator,
            captchaRecognizer = captchaRecognizer,
            credentialsProvider = { latestCredentials.value },
            homeworkFileGateway = homeworkFileGateway,
            onPurgeLogout = onPurgeLogout,
            refreshDispatcher = Dispatchers.Main,
        )
    }
    // 手机/平板的 ChatGPT 插件：不常驻、不轮询，只在 App 每次刷新后上传快照。
    val sessionHolder = remember(graph) { MobileSessionHolder() }
    val mobileRemote = remember(graph) {
        createMobileRemoteAgent(platform, cacheStore, securityCoordinator, graph.refreshCoordinator, sessionHolder)
    }
    // 图书馆（opac）登录不在 CAS/MIS 链路上：它走独立的 OAuth2 授权页，
    // 但复用同一个统一身份认证会话与内存凭据。数据只保存在内存，不写缓存。
    // 以学号为 key 按账号隔离：换账号后不沿用上一账号的借阅快照、自动同步标记与 loanId。
    val libraryLoginProtocol = remember(profile.studentId, transport) {
        LibraryLoginProtocol(
            transport = transport,
            credentialsProvider = { latestCredentials.value.takeIf(Credentials::isValid) },
        )
    }
    val libraryModel = remember(profile.studentId, transport, libraryLoginProtocol) {
        LibraryModel(
            repository = DefaultLibraryRepository(
                remote = SchoolLibraryRemoteDataSource(transport),
                authenticator = libraryLoginProtocol,
            ),
        )
    }
    // 退出登录、切换账号或宿主销毁：取消旧账号的在途刷新，结果不会写进新账号缓存。
    DisposableEffect(graph) {
        onDispose {
            mobileRemote?.close()
            graph.close()
        }
    }
    val session = remember(graph, libraryModel, profile, appPreferences, coursewareDirectoryGateway, systemCalendarGateway) {
        AuthenticatedSession(
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
            loginSyncPreferences = appPreferences,
            mailboxModel = graph.mailboxModel,
            phyVlabModel = graph.phyVlabModel,
            physicsLabModel = graph.physicsLabModel,
            physicsDemoModel = graph.physicsDemoModel,
            phyVlabAccountModel = graph.phyVlabAccountModel,
            citelModel = graph.citelModel,
            libraryModel = libraryModel,
            aggregateFilterStore = team.bjtuss.bjtuselfservice.shared.feature.assignment.AggregateAssignmentFilterStore(cacheStore, profile.studentId),
            assignmentCompletionStore = graph.assignmentCompletionStore,
            homeModel = graph.homeModel,
            homeChangeFeed = graph.homeChangeFeed,
            homeworkFileGateway = homeworkFileGateway,
            coursewareDirectoryGateway = coursewareDirectoryGateway,
            systemCalendarGateway = systemCalendarGateway,
            onLogout = { logoutScope.launch { graph.playbackTaskManager.cancelAllAndJoin(); onLogout(profile.studentId) }; Unit },
            reauthenticateSession = graph.sessionRecovery::attempt,
            probeSession = {
                loginProtocol.checkSession() is SessionProbeResult.Active
            },
            refreshCoordinator = graph.refreshCoordinator,
            remoteAgent = mobileRemote?.controller,
        ).also { sessionHolder.session = it }
    }
    // 登录完成只更新可观察状态，不换会话实例：M17 原生壳按会话实例装配一级入口，
    // 换实例等于重建整条玻璃 TabBar——各 tab 的返回栈被丢弃、所有 Compose 宿主重来。
    SideEffect { session.entryLoggingIn = entryLoggingIn }
    return session
}

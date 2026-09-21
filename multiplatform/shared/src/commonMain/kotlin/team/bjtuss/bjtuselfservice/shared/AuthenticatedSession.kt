package team.bjtuss.bjtuselfservice.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.data.home.HomeChangeFeedRepository
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleScreenModel
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
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarGateway

/**
 * 登录后页面共享的应用级会话。
 *
 * 平台原生导航会为二级页面创建新的 Activity/UIViewController，但它们必须继续观察同一组
 * ScreenModel 和会话状态，不能各自重新登录或重新创建 Repository。此对象只在当前进程内
 * 存活，不负责落盘，也不持有 Activity、UIViewController 或 Window。
 */
class AuthenticatedSession(
    val profile: StudentProfile,
    entryLoggingIn: Boolean,
    val gradeModel: GradeScreenModel,
    val courseScheduleModel: CourseScheduleScreenModel,
    val examScheduleModel: ExamScheduleScreenModel,
    val homeworkModel: HomeworkScreenModel,
    val coursewareModel: CoursewareScreenModel,
    val otherFunctionModel: OtherFunctionScreenModel,
    val classroomModel: ClassroomScreenModel,
    val classroomOccupancyModel: ClassroomOccupancyScreenModel,
    val settingsModel: SettingsScreenModel,
    val loginSyncPreferences: AppPreferences,
    val mailboxModel: MailboxScreenModel,
    val phyVlabModel: PhyVlabScreenModel,
    val homeModel: HomeScreenModel,
    val homeChangeFeed: HomeChangeFeedRepository,
    val homeworkFileGateway: HomeworkFileGateway,
    val coursewareDirectoryGateway: CoursewareDirectoryGateway,
    val systemCalendarGateway: SystemCalendarGateway,
    val onLogout: () -> Unit,
    /** 业务会话失效时，在 App 内复用当前内存中的 CAS 凭据恢复。 */
    val reauthenticateSession: (suspend () -> Boolean)? = null,
    /** 刷新前探测 MIS 会话；返回 false 时由共享刷新协调器触发恢复。 */
    val probeSession: (suspend () -> Boolean)? = null,
) {
    private val appResumeGenerationState = MutableStateFlow(0L)
    private val appResumeMutex = Mutex()
    private var claimedAppResumeGeneration = 0L

    /**
     * M17：一级入口集合变化时通知宿主原生 tab 容器（例如「物理在线」开关会增减底栏项）。
     * 由宿主装配 tab 容器时赋值；Compose 状态变化不会自动传到 UIKit。
     */
    var onNativeTabItemsChanged: ((List<String>) -> Unit)? = null

    /**
     * 宿主玻璃 TabBar 实际占掉的底部高度（点；iOS 上 1pt == 1dp）。
     *
     * Compose 宿主是全出血的（内容要能伸进玻璃条下面，玻璃才有东西可折射），于是 UIKit 不会把
     * tab bar 算进 `WindowInsets.navigationBars`，那个数只有 home indicator。所以底栏真实高度
     * 只能由宿主在布局时写进来：可滚动的列表用它做尾部留白，**不可纵向滚动**的全览表格
     * （课程表色块概览）用它把整张表停在底栏上方。
     */
    var glassTabBarBottomInsetDp: Float by mutableStateOf(0f)

    /**
     * 宿主原生导航栏实际占掉的顶部高度（点；iOS 上 1pt == 1dp），即导航栏 frame.maxY。
     *
     * 与底栏同一道理：内容要能伸进导航栏下面，原生 blur 才有东西可采样，而 UIKit 不会把
     * 导航栏算进 Compose 的可用 inset（`WindowInsets.statusBars` 停在栏底），所以栏底高度
     * 只能由宿主在布局时写进来。只在 opt-in 的页面以滚动内容顶边距消费（`LocalTopBarClearance`），
     * 未 opt-in 的页面保持原来的 Spacer 占位，行为不变。
     */
    var glassTopBarInsetDp: Float by mutableStateOf(0f)

    /** 平台回到前台时递增；应用壳会针对当前页面的失效请求自动重试一次。 */
    val appResumeGeneration: StateFlow<Long> = appResumeGenerationState.asStateFlow()

    /** Android/iOS 宿主调用；同一前台事件只允许一个 Compose 壳消费。 */
    fun notifyAppBecameActive() {
        appResumeGenerationState.update { it + 1L }
    }

    internal suspend fun claimAppResume(generation: Long): Boolean = appResumeMutex.withLock {
        if (generation <= 0L || generation <= claimedAppResumeGeneration) {
            false
        } else {
            claimedAppResumeGeneration = generation
            true
        }
    }

    /**
     * 智慧教学明文 HTTP 风险提示是否已在本登录态关闭。
     * 必须挂在 session 上：原生 push 的二级页会新建 Compose 树，
     * 若只用 remember，每次进课件/作业都会再弹一次。
     */
    var legacyHttpWarningDismissed: Boolean = false

    /**
     * 教室人数估计首页引导 Banner（请选择教学楼 / 人数仅供参考）
     * 是否已在本登录态关闭。
     */
    var classroomIntroBannerDismissed: Boolean = false

    /**
     * 静默入场期间的「登录中」标记。必须是可观察状态而不是构造常量：
     * 原生壳（M17 玻璃 TabBar）按会话实例装配一级入口，若登录完成就换一个新实例，
     * 整条 tab 栏会重建、各 tab 的返回栈被丢弃。改为在既有实例上更新，宿主只换状态不换人。
     */
    var entryLoggingIn: Boolean by mutableStateOf(entryLoggingIn)
}

/** 只有这些目的地属于一级 tab 之上的原生导航层级。 */
fun isNativeDetailRoute(routeId: String): Boolean =
    routeId == "EXAMS" ||
        routeId == "COURSEWARE" ||
        routeId == "CLASSROOMS" ||
        routeId == "CLASSROOM_OCCUPANCY" ||
        routeId == "CLASSROOM_DETAIL" ||
        routeId == "CLASSROOM_OCCUPANCY_DETAIL" ||
        routeId == "HOMEWORK_DETAIL" ||
        routeId == "MAILBOX" ||
        routeId == "MAILBOX_DETAIL" ||
        routeId == "MAILBOX_COMPOSE" ||
        routeId == "CALENDAR" ||
        routeId == "REPORT_CARD_DOWNLOAD" ||
        routeId == "PHYVLAB" ||
        routeId == "PHYVLAB_DETAIL" ||
        routeId == "SETTINGS"

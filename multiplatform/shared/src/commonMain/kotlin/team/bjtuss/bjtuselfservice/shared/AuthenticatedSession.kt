package team.bjtuss.bjtuselfservice.shared

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
    val entryLoggingIn: Boolean,
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
) {
    private val appResumeGenerationState = MutableStateFlow(0L)
    private val appResumeMutex = Mutex()
    private var claimedAppResumeGeneration = 0L

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

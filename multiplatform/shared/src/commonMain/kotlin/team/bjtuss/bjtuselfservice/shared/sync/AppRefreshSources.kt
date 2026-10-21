package team.bjtuss.bjtuselfservice.shared.sync

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import team.bjtuss.bjtuselfservice.shared.agent.saveAgentMailInbox
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.SyncSource
import team.bjtuss.bjtuselfservice.shared.cache.syncedAt
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusFailure
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.decodePhyVlabCache
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelModel
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxFailure
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUiState
import team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoModel
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabModel
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabAccountModel
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabScreenModel

/** 全局刷新需要的模型集合：App 会话与无窗口宿主都从 [AppDataGraph] 取同一组实例。 */
class AppRefreshModels(
    val accountScope: String,
    val cache: CacheStore,
    val homeModel: HomeScreenModel,
    val mailboxModel: MailboxScreenModel,
    val homeworkModel: HomeworkScreenModel,
    val examScheduleModel: ExamScheduleScreenModel,
    val courseScheduleModel: CourseScheduleScreenModel,
    val gradeModel: GradeScreenModel,
    val physicsLabModel: PhysicsLabModel?,
    val physicsDemoModel: PhysicsDemoModel?,
    val citelModel: CitelModel?,
    val phyVlabModel: PhyVlabScreenModel,
    val phyVlabAccountModel: PhyVlabAccountModel?,
    /** 当前设置；开关在两次刷新之间可能被用户改动，每次刷新时读取。 */
    val preferences: () -> AppPreferences,
    val nowEpochSeconds: () -> Long,
)

/**
 * 与原 `AuthenticatedAppShell.refreshGlobal` 相同的来源与开关：
 * 首页、邮箱未读、课程平台作业、考试、课表、成绩（含课程性质补齐）走 MIS 会话；
 * 物理实验、CITEL、物理在线按设置开关；演示实验由模型自身判断启用与配置。
 */
fun appRefreshAdapters(models: AppRefreshModels): List<RefreshSourceAdapter> = with(models) {
    listOf(
        object : SchoolSessionAdapter(RefreshSource.HOME) {
            override suspend fun refresh() {
                homeModel.refresh()
                if (homeModel.state.value.failure == null) recordSyncTime(cache, accountScope, source, nowEpochSeconds())
            }
            override fun sessionExpired() = homeModel.state.value.failure == HomeStatusFailure.SESSION_EXPIRED
            override fun outcome() = failureResult(source, homeModel.state.value.failure?.name)
            override fun fetchedAtEpochSeconds() = cachedSourceSyncTime(cache, accountScope, source)
        },
        object : SchoolSessionAdapter(RefreshSource.MAIL_UNREAD) {
            private var probed = false
            override suspend fun refresh() {
                probed = mailboxModel.refreshUnreadInboxCount()
                if (probed) {
                    mailboxModel.lastInboxProbe?.let { saveAgentMailInbox(cache, accountScope, it) }
                    recordSyncTime(cache, accountScope, source, nowEpochSeconds())
                }
            }
            override fun sessionExpired() = when (val current = mailboxModel.state.value) {
                MailboxUiState.SessionUnavailable -> true
                is MailboxUiState.Ready -> current.failure == MailboxFailure.SESSION_EXPIRED
                else -> false
            }
            override fun outcome() = failureResult(source, if (probed) null else "network")
            override fun fetchedAtEpochSeconds() = cachedSourceSyncTime(cache, accountScope, source)
        },
        object : SchoolSessionAdapter(RefreshSource.HOMEWORK) {
            override suspend fun refresh() = homeworkModel.refresh()
            override fun sessionExpired() = homeworkModel.state.value.failure == HomeworkSyncFailure.SESSION_EXPIRED
            override fun outcome() = failureResult(source, homeworkModel.state.value.failure?.name)
            override fun fetchedAtEpochSeconds() = cachedSourceSyncTime(cache, accountScope, source)
        },
        object : SchoolSessionAdapter(RefreshSource.EXAMS) {
            override suspend fun refresh() = examScheduleModel.refresh()
            override fun sessionExpired() = examScheduleModel.state.value.failure == ExamScheduleSyncFailure.SESSION_EXPIRED
            override fun outcome() = failureResult(source, examScheduleModel.state.value.failure?.name)
            override fun fetchedAtEpochSeconds() = cachedSourceSyncTime(cache, accountScope, source)
        },
        object : SchoolSessionAdapter(RefreshSource.COURSES) {
            override suspend fun refresh() = courseScheduleModel.refresh()
            override fun sessionExpired() = courseScheduleModel.state.value.failure == CourseScheduleSyncFailure.SESSION_EXPIRED
            override fun outcome() = failureResult(source, courseScheduleModel.state.value.failure?.name)
            override fun fetchedAtEpochSeconds() = cachedSourceSyncTime(cache, accountScope, source)
        },
        object : SchoolSessionAdapter(RefreshSource.GRADES) {
            override suspend fun refresh() = gradeModel.refresh()
            override fun sessionExpired() = gradeModel.state.value.failure == GradeSyncFailure.SESSION_EXPIRED
            override fun outcome(): RefreshSourceResult = failureResult(source, gradeModel.state.value.failure?.name)
            override fun fetchedAtEpochSeconds() = cachedSourceSyncTime(cache, accountScope, source)
        }.let { grades ->
            // 课程性质补齐在成绩刷新之后串行执行，失败不影响成绩本身的结果。
            object : RefreshSourceAdapter by grades {
                override suspend fun refresh() {
                    grades.refresh()
                    if (gradeModel.state.value.courseTypesByCode == null) gradeModel.ensureProgramCourseTypes()
                }
            }
        },
        object : RefreshSourceAdapter {
            override val source = RefreshSource.PHYSICS_LAB
            override val usesSchoolSession = false
            private val model get() = physicsLabModel
            override suspend fun availability(): RefreshAvailability = when {
                model == null -> RefreshAvailability.NOT_CONFIGURED
                !preferences().isPhysicsLabEnabled -> RefreshAvailability.DISABLED
                else -> RefreshAvailability.ENABLED
            }
            override suspend fun refresh() {
                val lab = model ?: return
                lab.refresh()
                if (lab.state.value.synced && !lab.state.value.failed) recordSyncTime(cache, accountScope, source, nowEpochSeconds())
            }
            override fun outcome(): RefreshSourceResult {
                val state = model?.state?.value ?: return RefreshSourceResult(source, RefreshStatus.SKIPPED, RefreshReason.NOT_CONFIGURED)
                return when {
                    !state.failed -> RefreshSourceResult(source, RefreshStatus.SUCCESS)
                    !state.configured -> RefreshSourceResult(source, RefreshStatus.NEEDS_USER_ACTION, RefreshReason.NOT_CONFIGURED, state.message)
                    else -> RefreshSourceResult(source, RefreshStatus.FAILED, "network", state.message)
                }
            }
            override fun fetchedAtEpochSeconds() = cachedSourceSyncTime(cache, accountScope, source)
        },
        object : RefreshSourceAdapter {
            override val source = RefreshSource.PHYSICS_DEMO
            override val usesSchoolSession = false
            override suspend fun availability(): RefreshAvailability {
                val demo = physicsDemoModel ?: return RefreshAvailability.NOT_CONFIGURED
                demo.initialize()
                val state = demo.state.value
                return when {
                    !state.configured -> RefreshAvailability.NOT_CONFIGURED
                    !state.enabled -> RefreshAvailability.DISABLED
                    else -> RefreshAvailability.ENABLED
                }
            }
            override suspend fun refresh() {
                physicsDemoModel?.refresh()
            }
            override fun outcome(): RefreshSourceResult {
                val state = physicsDemoModel?.state?.value
                    ?: return RefreshSourceResult(source, RefreshStatus.SKIPPED, RefreshReason.NOT_CONFIGURED)
                return if (state.failed) RefreshSourceResult(source, RefreshStatus.FAILED, "network", state.message)
                else RefreshSourceResult(source, RefreshStatus.SUCCESS)
            }
            override fun fetchedAtEpochSeconds() = cachedSourceSyncTime(cache, accountScope, source)
        },
        object : RefreshSourceAdapter {
            override val source = RefreshSource.CITEL
            override val usesSchoolSession = false
            override suspend fun availability(): RefreshAvailability {
                val citel = citelModel ?: return RefreshAvailability.NOT_CONFIGURED
                if (!preferences().isCitelEnabled) return RefreshAvailability.DISABLED
                citel.initialize()
                // 模型未启用表示没有可用密码（未记住密码时重启后需要重新输入），刷新会直接返回。
                return if (citel.state.value.enabled) RefreshAvailability.ENABLED else RefreshAvailability.NOT_CONFIGURED
            }
            override suspend fun refresh() {
                citelModel?.refresh()
            }
            override fun outcome(): RefreshSourceResult {
                val state = citelModel?.state?.value
                    ?: return RefreshSourceResult(source, RefreshStatus.SKIPPED, RefreshReason.NOT_CONFIGURED)
                return if (state.failed) RefreshSourceResult(source, RefreshStatus.FAILED, "network", state.message)
                else RefreshSourceResult(source, RefreshStatus.SUCCESS)
            }
            override fun fetchedAtEpochSeconds() = cachedSourceSyncTime(cache, accountScope, source)
        },
        object : RefreshSourceAdapter {
            override val source = RefreshSource.PHYVLAB
            override val usesSchoolSession = false
            override suspend fun availability(): RefreshAvailability =
                if (preferences().isPhyVlabEnabled) RefreshAvailability.ENABLED else RefreshAvailability.DISABLED
            override suspend fun refresh() {
                phyVlabAccountModel?.initialize()
                phyVlabModel.refresh()
            }
            override fun outcome(): RefreshSourceResult {
                val state = phyVlabModel.state.value
                return when {
                    state.accountLoginRequired -> RefreshSourceResult(source, RefreshStatus.NEEDS_USER_ACTION,
                        RefreshReason.NOT_CONFIGURED, state.failureDetail ?: "请在设置中配置或检查物理在线账号。")
                    state.failure != null -> RefreshSourceResult(source, RefreshStatus.FAILED,
                        state.failure.name.lowercase(), state.failureDetail)
                    else -> RefreshSourceResult(source, RefreshStatus.SUCCESS)
                }
            }
            override fun fetchedAtEpochSeconds() = cachedSourceSyncTime(cache, accountScope, source)
        },
    )
}

private abstract class SchoolSessionAdapter(override val source: RefreshSource) : RefreshSourceAdapter {
    override val usesSchoolSession: Boolean get() = true
}

private fun failureResult(source: RefreshSource, failure: String?): RefreshSourceResult =
    if (failure == null) RefreshSourceResult(source, RefreshStatus.SUCCESS)
    else RefreshSourceResult(source, RefreshStatus.FAILED, failure.lowercase(), "刷新失败，保留原缓存。")

private fun recordedSyncKey(source: RefreshSource) = "refresh_synced_at_v1.${source.wire}"

/** 自身没有同步时间记录的来源（首页、邮箱未读、物理实验）：刷新成功后由适配层记一笔。 */
internal fun recordSyncTime(cache: CacheStore, accountScope: String, source: RefreshSource, epochSeconds: Long) {
    runCatching { cache.putMetadata(accountScope, recordedSyncKey(source), epochSeconds.toString()) }
}

/**
 * 每个来源缓存数据的真实同步时间（Unix 秒）。优先用来源自身的记录（与 CLI 的新旧提示一致），
 * 没有的来源用刷新服务记下的成功时间；都没有返回 null。
 */
fun cachedSourceSyncTime(cache: CacheStore, accountScope: String, source: RefreshSource): Long? = runCatching {
    when (source) {
        RefreshSource.COURSES -> cache.syncedAt(accountScope, SyncSource.COURSES)
        RefreshSource.HOMEWORK -> cache.syncedAt(accountScope, SyncSource.HOMEWORK)
        RefreshSource.EXAMS -> cache.syncedAt(accountScope, SyncSource.EXAMS)
        RefreshSource.GRADES -> cache.syncedAt(accountScope, SyncSource.GRADES)
        RefreshSource.CITEL -> cache.metadata(accountScope, "citel.lastSync")?.toLongOrNull()
        RefreshSource.PHYVLAB -> cache.metadata(accountScope, PHYVLAB_SNAPSHOT_KEY)?.let(::decodePhyVlabCache)
            ?.savedAtEpochMillis?.takeIf { it > 0 }?.div(1000)
        RefreshSource.PHYSICS_DEMO -> cache.metadata(accountScope, "phydemo.lastSync")?.let { text ->
            LocalDateTime.parse(text).toInstant(TimeZone.of("Asia/Shanghai")).epochSeconds
        }
        RefreshSource.HOME, RefreshSource.MAIL_UNREAD, RefreshSource.PHYSICS_LAB ->
            cache.metadata(accountScope, recordedSyncKey(source))?.toLongOrNull()
    }
}.getOrNull()

private const val PHYVLAB_SNAPSHOT_KEY = "phyvlab_snapshot_v1"

package team.bjtuss.bjtuselfservice.shared.agent

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabCacheSnapshot
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.decodePhyVlabCache
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.domain.grade.Grade
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AggregateAssignmentFilters
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTask
import team.bjtuss.bjtuselfservice.shared.feature.course.cachedAcademicWeeks
import team.bjtuss.bjtuselfservice.shared.feature.course.inferAcademicWeeks
import team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoBooking
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLab
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.physicsLabWeekCount
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailSummary
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.cachedSourceSyncTime

/** 离线读取方需要的全部缓存数据。只读，不含任何凭据。 */
data class AgentSnapshot(
    val studentId: String,
    val profileName: String?,
    val preferences: AppPreferences,
    val courses: List<Course>,
    val currentWeek: Int,
    val academicWeeks: List<OccupancyWeekDate>,
    /** true 表示没有缓存校历，日期由 currentWeek 推算，可能偏差。 */
    val academicWeeksInferred: Boolean,
    val exams: List<ExamSchedule>,
    val grades: List<Grade>,
    val homework: List<Homework>,
    val phyVlab: PhyVlabCacheSnapshot?,
    val citelTasks: List<CitelTask>,
    val citelLastSync: String?,
    /** null 表示约课缓存不可用（未启用/未配置）。 */
    val physicsLabs: List<PhysicsLab>?,
    val physicsDemos: List<PhysicsDemoBooking>?,
    val assignmentFilters: AggregateAssignmentFilters,
    /** 各数据源（RefreshSource.wire）最近一次成功同步（Unix 秒）；null 表示没有记录。只含已启用的来源。 */
    val syncedAt: Map<String, Long?> = emptyMap(),
    /** 收件箱最近邮件摘要（邮箱刷新时写入）；没有同步过为空。 */
    val mailInbox: List<AgentMailItem> = emptyList(),
) {
    val citelEnabled: Boolean get() = preferences.isCitelEnabled
    val phyVlabEnabled: Boolean get() = preferences.isPhyVlabEnabled
}

private val agentJson = Json { ignoreUnknownKeys = true }

fun loadAgentSnapshot(cache: CacheStore, studentId: String, today: LocalDate): AgentSnapshot {
    // 与 AuthenticatedSessionFactory 相同的迁移规则：偏好里还没写开关时沿用旧的账号级标记。
    val preferences = runCatching { cache.preferences() }.getOrDefault(AppPreferences()).let { stored ->
        stored.copy(
            physicsLabEnabled = stored.physicsLabEnabled ?: (cache.metadata(studentId, "physicslab.enabled") == "true"),
            citelEnabled = stored.citelEnabled ?: (cache.metadata(studentId, "citel.enabled") == "true"),
        )
    }
    val currentWeek = cache.courseCurrentWeek(studentId)
    val cachedWeeks = cache.cachedAcademicWeeks(studentId)
    val physicsLabs = if (preferences.isPhysicsLabEnabled && cache.metadata(studentId, "physicslab.configured") == "true") {
        val overrides = decodeOrNull<Map<String, Boolean>>(cache.metadata(studentId, "physicslab.twoWeekOverrides")).orEmpty()
        decodeOrNull<List<PhysicsLab>>(cache.metadata(studentId, "physicslab.results"))?.map { lab ->
            val twoWeeks = overrides[lab.durationSettingKey] ?: (physicsLabWeekCount(lab.name) == 2)
            lab.copy(weekCount = if (twoWeeks) 2 else 1)
        }
    } else null
    val physicsDemos = if (cache.metadata(studentId, "phydemo.configured") == "true" &&
        cache.metadata(studentId, "phydemo.enabled") == "true"
    ) decodeOrNull<List<PhysicsDemoBooking>>(cache.metadata(studentId, "phydemo.results")) else null
    return AgentSnapshot(
        studentId = studentId,
        profileName = cache.cachedProfile(studentId)?.name,
        preferences = preferences,
        courses = cache.courses(studentId),
        currentWeek = currentWeek,
        academicWeeks = cachedWeeks.ifEmpty { inferAcademicWeeks(currentWeek, today) },
        academicWeeksInferred = cachedWeeks.isEmpty(),
        exams = cache.exams(studentId),
        grades = cache.grades(studentId),
        homework = cache.homework(studentId),
        phyVlab = if (preferences.isPhyVlabEnabled) {
            cache.metadata(studentId, PHYVLAB_SNAPSHOT_KEY)?.let(::decodePhyVlabCache)
        } else null,
        citelTasks = if (preferences.isCitelEnabled) decodeOrNull<List<CitelTask>>(cache.metadata(studentId, "citel.tasks")).orEmpty() else emptyList(),
        citelLastSync = cache.metadata(studentId, "citel.lastSync"),
        physicsLabs = physicsLabs,
        physicsDemos = physicsDemos,
        assignmentFilters = decodeOrNull<AggregateAssignmentFilters>(cache.metadata(studentId, "assignment.aggregate.filters"))
            ?: AggregateAssignmentFilters(),
        syncedAt = RefreshSource.entries.filter { source ->
            when (source) {
                RefreshSource.PHYVLAB -> preferences.isPhyVlabEnabled
                RefreshSource.CITEL -> preferences.isCitelEnabled
                RefreshSource.PHYSICS_LAB -> physicsLabs != null
                RefreshSource.PHYSICS_DEMO -> physicsDemos != null
                RefreshSource.HOME -> false
                else -> true
            }
        }.associate { it.wire to cachedSourceSyncTime(cache, studentId, it) },
        mailInbox = loadAgentMailInbox(cache, studentId),
    )
}

/**
 * 选账号：只有一个缓存档案时直接用；多个时用 [preferred]（通常是记住的登录账号）。
 * 返回 null 表示无法确定。
 */
fun resolveAgentAccount(cache: CacheStore, preferred: String?): String? {
    val scopes = cache.metadataAccountScopes(PROFILE_NAME_KEY).distinct()
    return when {
        preferred != null && preferred in scopes -> preferred
        scopes.size == 1 -> scopes.single()
        else -> null
    }
}

private inline fun <reified T> decodeOrNull(value: String?): T? =
    value?.takeIf(String::isNotBlank)?.let { runCatching { agentJson.decodeFromString<T>(it) }.getOrNull() }

private const val PHYVLAB_SNAPSHOT_KEY = "phyvlab_snapshot_v1"
private const val PROFILE_NAME_KEY = "profile_name"

private const val MAIL_INBOX_KEY = "agent.mail.inbox"

/** 邮箱刷新后把收件箱摘要写进本机加密缓存，供 CLI 与远程快照读取（不含正文和附件）。 */
fun saveAgentMailInbox(cache: CacheStore, studentId: String, messages: List<MailSummary>) {
    val items = messages.map { message ->
        val time = mailTime(message.receivedAt.ifBlank { message.sentAt })
        AgentMailItem(
            id = message.id,
            from = message.sender.trim(),
            subject = message.subject.trim().ifBlank { "（无主题）" },
            preview = message.preview.trim().take(120).ifBlank { null },
            received = time?.second,
            receivedDate = time?.first,
            unread = !message.isRead,
            hasAttachments = message.hasAttachments,
        )
    }
    cache.putMetadata(studentId, MAIL_INBOX_KEY, agentDatasetJson.encodeToString(items))
}

private fun loadAgentMailInbox(cache: CacheStore, studentId: String): List<AgentMailItem> =
    cache.metadata(studentId, MAIL_INBOX_KEY)?.takeIf(String::isNotBlank)
        ?.let { runCatching { agentDatasetJson.decodeFromString<List<AgentMailItem>>(it) }.getOrNull() }.orEmpty()

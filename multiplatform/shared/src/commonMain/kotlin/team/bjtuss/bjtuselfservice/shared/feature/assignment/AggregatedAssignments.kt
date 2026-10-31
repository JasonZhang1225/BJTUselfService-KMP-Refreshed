package team.bjtuss.bjtuselfservice.shared.feature.assignment

import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.domain.time.deadlineDisplay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.domain.homework.*
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTask
import team.bjtuss.bjtuselfservice.shared.feature.citel.completed

@Serializable
enum class AssignmentSource(val label: String) { COURSE_PLATFORM("课程平台"), PHYVLAB("物理在线"), CITEL("CITEL") }

data class AggregatedAssignment(
    val source: AssignmentSource, val key: String, val courseId: Int, val courseName: String,
    val dueTime: Long?, val submitted: Boolean,
    val homework: Homework? = null, val physical: PhyVlabActivity? = null, val citel: CitelTask? = null,
)

fun aggregateAssignments(homework: List<Homework>, physical: List<PhyVlabActivity>, citel: List<CitelTask>,
    timeZone: TimeZone = TimeZone.currentSystemDefault()): List<AggregatedAssignment> =
    homework.map { AggregatedAssignment(AssignmentSource.COURSE_PLATFORM, "course:${it.stableKey()}", it.courseId, it.courseName,
        parseSchoolLocalDateTime(it.endTime)?.toInstant(timeZone)?.epochSeconds, isHomeworkSubmitted(it), homework = it) } +
    physical.map { AggregatedAssignment(AssignmentSource.PHYVLAB, "phy:${it.courseId}:${it.id}", it.courseId, it.courseName,
        it.dueTimestamp, it.completed, physical = it) } +
    citel.map { AggregatedAssignment(AssignmentSource.CITEL, "citel:${it.courseId}:${it.id}", it.courseId, it.courseName,
        it.dueTime, it.completed, citel = it) }

@Serializable
data class AggregateCourseFilter(val allCourses: Boolean = true, val courses: Set<Int> = emptySet()) {
    fun matches(courseId: Int): Boolean = allCourses || courseId in courses
    fun toggleAll(options: Set<Int>): AggregateCourseFilter =
        if (allCourses || (options.isNotEmpty() && courses.containsAll(options))) AggregateCourseFilter(false)
        else AggregateCourseFilter()
    fun toggleCourse(id: Int, options: Set<Int>): AggregateCourseFilter =
        if (allCourses) AggregateCourseFilter(false, options - id)
        else copy(courses = if (id in courses) courses - id else courses + id)
}

@Serializable
data class AggregateAssignmentFilters(
    val platformCourses: Map<AssignmentSource, AggregateCourseFilter> = emptyMap(),
    val hideExpired: Boolean = false, val hideSubmitted: Boolean = false, val sortOrder: Int = 1,
    val beforeDate: String? = null,
) {
    fun forSource(source: AssignmentSource): AggregateCourseFilter = platformCourses[source] ?: AggregateCourseFilter()
    fun withSource(source: AssignmentSource, filter: AggregateCourseFilter) = copy(platformCourses = platformCourses + (source to filter))
    val active: Boolean get() = beforeDate != null || hideExpired || hideSubmitted || platformCourses.values.any { !it.allCourses }
}

fun AggregatedAssignment.completionKey(): AssignmentCompletionKey? = when (source) {
    AssignmentSource.COURSE_PLATFORM -> homework?.completionKey()
    AssignmentSource.PHYVLAB -> physical?.completionKey()
    AssignmentSource.CITEL -> citel?.completionKey()
}

/** 已忽略的作业照常参与筛选和排序，之后由列表拆到末尾的“已忽略”分组，见 [partitionIgnoredAssignments]。 */
fun filterAggregateAssignments(items: List<AggregatedAssignment>, filters: AggregateAssignmentFilters, now: Long): List<AggregatedAssignment> {
    val before = filters.beforeDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val visible = items.filter { filters.forSource(it.source).matches(it.courseId) && (!filters.hideSubmitted || !it.submitted) &&
        (!filters.hideExpired || it.dueTime?.let { time -> time > now } != false) &&
        (before == null || it.dueTime?.let { time ->
            // Midnight is displayed as the previous day's 24:00 and belongs to that day.
            deadlineDisplay(Instant.fromEpochSeconds(time).toLocalDateTime(TimeZone.of("Asia/Shanghai"))).date < before
        } == true) }
    return sortAggregateAssignments(visible, filters.sortOrder)
}

private fun sortAggregateAssignments(items: List<AggregatedAssignment>, sortOrder: Int): List<AggregatedAssignment> =
    when (sortOrder) {
        1 -> items.sortedBy { it.dueTime ?: Long.MAX_VALUE }
        2 -> items.sortedByDescending { it.dueTime ?: Long.MIN_VALUE }
        else -> items
    }

/**
 * 搜索层结果：在全部作业里找，不看来源/课程/已提交/过期筛选，只沿用排序。
 * 还没输入时返回空表，由搜索层显示提示，而不是把整张列表原样搬过来。
 */
fun searchAggregateAssignments(items: List<AggregatedAssignment>, query: String, sortOrder: Int): List<AggregatedAssignment> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return emptyList()
    return sortAggregateAssignments(items.filter { assignmentMatchesSearch(it, keyword) }, sortOrder)
}

/** 聚合列表搜索：课程名、作业标题或来源名（课程平台 / 物理在线 / CITEL）。 */
fun assignmentMatchesSearch(item: AggregatedAssignment, query: String): Boolean {
    val keyword = query.trim()
    if (keyword.isEmpty()) return true
    val title = item.homework?.title ?: item.physical?.title ?: item.citel?.title.orEmpty()
    return item.courseName.contains(keyword, ignoreCase = true) ||
        title.contains(keyword, ignoreCase = true) ||
        item.source.label.contains(keyword, ignoreCase = true)
}

class AggregateAssignmentFilterStore(private val cache: CacheStore, private val account: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): AggregateAssignmentFilters = runCatching {
        json.decodeFromString<AggregateAssignmentFilters>(cache.metadata(account, "assignment.aggregate.filters").orEmpty())
    }.getOrDefault(AggregateAssignmentFilters())
    fun save(filters: AggregateAssignmentFilters) { cache.putMetadata(account, "assignment.aggregate.filters", json.encodeToString(filters)) }
}

data class AssignmentSourceSync(val source: AssignmentSource, val busy: Boolean, val failed: Boolean,
    val ready: Boolean, val cached: Boolean = false, val message: String? = null) {
    val status: String get() = when {
        busy -> "同步中"
        failed -> "同步失败" + if (cached) "·正显示缓存" else ""
        ready -> "已同步" + if (cached) "·缓存" else ""
        else -> "等待同步" + if (cached) "·正显示缓存" else ""
    }
}

fun aggregateAssignmentSyncStatus(sources: List<AssignmentSourceSync>): String = when {
    sources.any { it.busy } -> "同步中"
    sources.any { it.failed } -> "部分同步失败"
    sources.any { !it.ready } -> "等待同步"
    else -> "已同步"
}

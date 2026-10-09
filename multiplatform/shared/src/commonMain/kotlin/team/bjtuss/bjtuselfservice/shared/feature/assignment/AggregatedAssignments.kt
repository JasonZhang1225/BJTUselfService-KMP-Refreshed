package team.bjtuss.bjtuselfservice.shared.feature.assignment

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
) {
    fun forSource(source: AssignmentSource): AggregateCourseFilter = platformCourses[source] ?: AggregateCourseFilter()
    fun withSource(source: AssignmentSource, filter: AggregateCourseFilter) = copy(platformCourses = platformCourses + (source to filter))
    val active: Boolean get() = hideExpired || hideSubmitted || platformCourses.values.any { !it.allCourses }
}

fun filterAggregateAssignments(items: List<AggregatedAssignment>, filters: AggregateAssignmentFilters, now: Long): List<AggregatedAssignment> {
    val visible = items.filter { filters.forSource(it.source).matches(it.courseId) && (!filters.hideSubmitted || !it.submitted) &&
        (!filters.hideExpired || it.dueTime?.let { time -> time > now } != false) }
    return when (filters.sortOrder) {
        1 -> visible.sortedBy { it.dueTime ?: Long.MAX_VALUE }
        2 -> visible.sortedByDescending { it.dueTime ?: Long.MIN_VALUE }
        else -> visible
    }
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

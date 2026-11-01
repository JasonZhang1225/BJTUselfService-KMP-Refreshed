package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.domain.homework.parseUntilDate

/** Only preferences are persisted; visible tasks are recomputed from the current snapshot and time. */
@Serializable
data class AssignmentFilters(
    val courses: Set<Int> = emptySet(),
    val hideExpired: Boolean = false,
    val hideSubmitted: Boolean = false,
    val sortOrder: Int = 1,
    /** “截止日期”选了指定日期时的那一天（含当天），见 [DeadlineScope]。 */
    val untilDate: String? = null,
) {
    val until: LocalDate? get() = parseUntilDate(untilDate)
    val active: Boolean get() = courses.isNotEmpty() || hideExpired || hideSubmitted || until != null
    fun withDeadlineScope(hideExpired: Boolean, until: LocalDate?) =
        copy(hideExpired = hideExpired || until != null, untilDate = until?.toString())
}

class AssignmentFilterStore(private val cache: CacheStore, private val account: String, source: String) {
    private val key = "$source.filters"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): AssignmentFilters = runCatching {
        json.decodeFromString<AssignmentFilters>(cache.metadata(account, key).orEmpty())
    }.getOrDefault(AssignmentFilters()).let { it.copy(sortOrder = it.sortOrder.takeIf { value -> value in 0..2 } ?: 1) }
    fun save(filters: AssignmentFilters) { cache.putMetadata(account, key, json.encodeToString(filters)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssignmentFilterSheet(courses: List<Pair<Int, String>>, filters: AssignmentFilters, ignoredCount: Int,
    onChange: (AssignmentFilters) -> Unit) {
    // 弹层按内容定高（fitContentHeight），滚动和底部安全区由弹层负责，这里不再套滚动。
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("筛选与排序", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("课程", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filters.courses.isEmpty(), { onChange(filters.copy(courses = emptySet())) }, label = { Text("全部") })
                courses.forEach { (id, name) ->
                    FilterChip(id in filters.courses, {
                        onChange(filters.copy(courses = if (id in filters.courses) filters.courses - id else filters.courses + id))
                    }, label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
        AssignmentDeadlineFilter(filters.hideExpired, filters.until) { hideExpired, until ->
            onChange(filters.withDeadlineScope(hideExpired, until))
        }
        AssignmentSubmissionFilter(filters.hideSubmitted, ignoredCount) { onChange(filters.copy(hideSubmitted = it)) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("排序", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("原顺序", "由近到远", "由远到近").forEachIndexed { order, name ->
                    FilterChip(filters.sortOrder == order, { onChange(filters.copy(sortOrder = order)) },
                        shape = RoundedCornerShape(percent = 50), label = { Text(name) })
                }
            }
        }
    }
}

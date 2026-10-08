package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.shell.sheetScrollContentPadding

/** Only preferences are persisted; visible tasks are recomputed from the current snapshot and time. */
@Serializable
data class AssignmentFilters(
    val courses: Set<Int> = emptySet(),
    val hideExpired: Boolean = false,
    val hideSubmitted: Boolean = false,
    val sortOrder: Int = 1,
) {
    val active: Boolean get() = courses.isNotEmpty() || hideExpired || hideSubmitted
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
internal fun ColumnScope.AssignmentFilterSheet(courses: List<Pair<Int, String>>, filters: AssignmentFilters, onChange: (AssignmentFilters) -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).desktopTouchScroll(scroll)
            .sheetScrollContentPadding(),
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("截止时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!filters.hideExpired, { onChange(filters.copy(hideExpired = false)) }, shape = RoundedCornerShape(10.dp), label = { Text("显示全部日期") })
                FilterChip(filters.hideExpired, { onChange(filters.copy(hideExpired = true)) }, shape = RoundedCornerShape(10.dp), label = { Text("隐藏已过期") })
                FilterChip(filters.hideSubmitted, { onChange(filters.copy(hideSubmitted = !filters.hideSubmitted)) }, shape = RoundedCornerShape(10.dp), label = { Text("隐藏已提交") })
            }
        }
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

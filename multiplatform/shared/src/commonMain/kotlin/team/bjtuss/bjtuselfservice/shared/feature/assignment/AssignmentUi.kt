package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.files.*

data class AssignmentPresentation(
    val title: String, val course: String, val source: String, val status: String,
    val open: String? = null, val discount: String? = null, val due: String? = null,
    val grade: String? = null,
)

@Composable
fun AssignmentOverview(item: AssignmentPresentation, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${item.source} · ${item.course}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(item.status, style = MaterialTheme.typography.bodyMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("开始 · ${item.open?.takeIf { it.isNotBlank() } ?: "未提供"}")
                Text("折扣 · ${item.discount?.takeIf { it.isNotBlank() } ?: "平台未提供"}")
                Text("截止 · ${item.due?.takeIf { it.isNotBlank() } ?: "未提供"}")
            }
        }
        item.grade?.takeIf { it.isNotBlank() }?.let { Text("评分 · $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun AssignmentCard(item: AssignmentPresentation, onOpen: () -> Unit, selected: Boolean = false, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = onOpen, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        AssignmentOverview(item, Modifier.padding(16.dp))
    }
}

/** 三个来源共用的本地待上传文件列表，移除只作用于选择列表。 */
@Composable
fun PendingAssignmentFiles(files: List<HomeworkFileContent>, busy: Boolean, onPick: () -> Unit, onRemove: (Int) -> Unit) {
    OutlinedButton(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (files.isEmpty()) "选择文件" else "继续添加文件")
    }
    if (files.isEmpty()) Text("尚未选择文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
    files.forEachIndexed { index, file ->
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(file.fileName)
                    Text("${(file.bytes.size.toLong() + 1023) / 1024} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onRemove(index) }, enabled = !busy) { Text("移除") }
            }
        }
    }
}

/** Moodle 可编辑附件。远端附件在点保存之前只是标记，原提交不会即时改变。 */
@Composable
fun AssignmentFilesEditor(
    existing: List<String>, fileGateway: HomeworkFileGateway, busy: Boolean,
    note: String, saveLabel: String,
    onSave: (List<HomeworkFileContent>, Set<String>) -> Unit,
    revision: Long = 0,
) {
    val scope = rememberCoroutineScope()
    var added by remember(existing, revision) { mutableStateOf<List<HomeworkFileContent>>(emptyList()) }
    var removed by remember(existing, revision) { mutableStateOf<Set<String>>(emptySet()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("提交文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        existing.forEach { name ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name + if (name in removed) " · 待移除" else "", Modifier.weight(1f))
                TextButton(onClick = { removed = if (name in removed) removed - name else removed + name }, enabled = !busy) {
                    Text(if (name in removed) "保留" else "移除")
                }
            }
        }
        PendingAssignmentFiles(added, busy || !fileGateway.isAvailable, onPick = {
            scope.launch {
                when (val picked = fileGateway.pickFiles()) {
                    HomeworkFilePickResult.Cancelled -> Unit
                    is HomeworkFilePickResult.Failed -> feedback = "无法读取所选文件，请重新选择。"
                    is HomeworkFilePickResult.Selected -> { added = (added + picked.files).distinctBy { it.fileName }; feedback = null }
                }
            }
        }, onRemove = { index -> added = added.filterIndexed { i, _ -> i != index } })
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FilledTonalButton(onClick = { onSave(added, removed) }, enabled = !busy && (added.isNotEmpty() || removed.isNotEmpty()), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "正在保存…" else saveLabel)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

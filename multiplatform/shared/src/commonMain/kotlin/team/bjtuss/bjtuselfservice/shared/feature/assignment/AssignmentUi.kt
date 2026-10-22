package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet
import team.bjtuss.bjtuselfservice.shared.feature.shell.sheetScrollContentPadding
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

/** 上传类 sheet 的统一外壳：标题和“取消”在原生栏，正文可滚动，主操作放在正文底部。 */
@Composable
fun AssignmentUploadSheet(
    title: String,
    busy: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppleSheet(
        onDismissRequest = { if (!busy) onDismissRequest() },
        title = title,
        dismissLabel = "取消",
        dismissEnabled = !busy,
        needsFullHeight = true,
        scrollableBody = true,
    ) {
        val scroll = rememberScrollState()
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).desktopTouchScroll(scroll).sheetScrollContentPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
fun AssignmentUploadNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 待提交文件分组：远端已提交文件（可标记移除）+ 本地新选文件 + 末行“添加文件”。
 * 一个文件都没有时显示整块可点的选择区。移除只作用于选择列表，点主操作前不改动远端。
 */
@Composable
fun AssignmentUploadFiles(
    pending: List<HomeworkFileContent>,
    busy: Boolean,
    pickEnabled: Boolean,
    onPick: () -> Unit,
    onRemovePending: (Int) -> Unit,
    existing: List<String> = emptyList(),
    removed: Set<String> = emptySet(),
    onToggleExisting: (String) -> Unit = {},
    singleFile: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val total = existing.size - existing.count { it in removed } + pending.size
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text("提交文件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (total > 0) {
            Text("$total 个", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
    if (existing.isEmpty() && pending.isEmpty()) {
        Surface(
            onClick = onPick,
            enabled = pickEnabled && !busy,
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("＋", style = MaterialTheme.typography.headlineMedium,
                    color = if (pickEnabled) colors.primary else colors.onSurfaceVariant)
                Text(if (pickEnabled) "选择文件" else "当前平台未接入文件选择器",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    color = if (pickEnabled) colors.primary else colors.onSurfaceVariant)
                if (pickEnabled) {
                    Text(if (singleFile) "选择一个文件，提交前可以更换" else "可以一次选择多个文件，提交前可以移除",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        return
    }
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()) {
        Column {
            var first = true
            existing.forEach { name ->
                if (!first) UploadRowDivider()
                first = false
                val marked = name in removed
                UploadFileRow(
                    name = name,
                    detail = if (marked) "保存后移除" else "已提交",
                    detailColor = if (marked) colors.error else colors.onSurfaceVariant,
                    struck = marked,
                    actionLabel = if (marked) "保留" else "移除",
                    actionEnabled = !busy,
                    onAction = { onToggleExisting(name) },
                )
            }
            pending.forEachIndexed { index, file ->
                if (!first) UploadRowDivider()
                first = false
                UploadFileRow(
                    name = file.fileName,
                    detail = "待上传 · " + formatUploadSize(file.bytes.size.toLong()),
                    detailColor = colors.primary,
                    struck = false,
                    actionLabel = "移除",
                    actionEnabled = !busy,
                    onAction = { onRemovePending(index) },
                )
            }
            if (pickEnabled) {
                UploadRowDivider()
                Surface(onClick = onPick, enabled = !busy, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                    Text(if (singleFile) "更换文件" else "添加文件",
                        color = if (busy) colors.onSurfaceVariant else colors.primary,
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                }
            }
        }
    }
}

@Composable
private fun UploadRowDivider() {
    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
private fun UploadFileRow(
    name: String, detail: String, detailColor: Color, struck: Boolean,
    actionLabel: String, actionEnabled: Boolean, onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 4 }?.uppercase() ?: "FILE"
        Box(Modifier.size(40.dp).background(colors.primaryContainer, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Text(extension, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = colors.onPrimaryContainer, maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
                color = if (struck) colors.onSurfaceVariant else colors.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor)
        }
        TextButton(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
    }
}

/** 就地反馈：错误用错误容器色，成功/进度说明用次级容器色。 */
@Composable
fun AssignmentUploadMessage(text: String, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (isError) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (isError) colors.onErrorContainer else colors.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

@Composable
fun AssignmentUploadAction(label: String, busyLabel: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(10.dp))
        }
        Text(if (busy) busyLabel else label, style = MaterialTheme.typography.titleSmall)
    }
}

/** 选择失败的就地说明：选择器打不开与文件读不出来分开说。 */
fun HomeworkFilePickResult.Failed.pickFeedback(): String = when (reason) {
    HomeworkFileGatewayFailure.UNAVAILABLE -> "暂时无法打开文件选择器，请稍后重试。"
    HomeworkFileGatewayFailure.PERMISSION_DENIED -> "没有读取所选文件的权限，请换一个位置的文件。"
    HomeworkFileGatewayFailure.IO -> "无法读取所选文件，请重新选择。"
}

/** 同名文件以后选的为准，避免同一份作业重复上传。 */
fun mergePickedFiles(current: List<HomeworkFileContent>, picked: List<HomeworkFileContent>): List<HomeworkFileContent> {
    val latest = picked.distinctBy { it.fileName }
    return current.filter { old -> latest.none { it.fileName == old.fileName } } + latest
}

fun formatUploadSize(bytes: Long): String = when {
    bytes <= 0L -> "大小未知"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${(bytes + 1023) / 1024} KB"
    else -> "${bytes / (1024L * 1024L)}.${bytes % (1024L * 1024L) * 10 / (1024L * 1024L)} MB"
}

/** Moodle 可编辑附件（物理在线、CITEL）。远端附件在点保存之前只是标记，原提交不会即时改变。 */
@Composable
fun AssignmentFilesEditor(
    existing: List<String>, fileGateway: HomeworkFileGateway, busy: Boolean,
    note: String, saveLabel: String,
    onSave: (List<HomeworkFileContent>, Set<String>) -> Unit,
    revision: Long = 0,
    singleFile: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var added by remember(existing, revision) { mutableStateOf<List<HomeworkFileContent>>(emptyList()) }
    var removed by remember(existing, revision) { mutableStateOf<Set<String>>(emptySet()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    AssignmentUploadNote(note)
    AssignmentUploadFiles(
        pending = added,
        busy = busy,
        pickEnabled = fileGateway.isAvailable,
        onPick = {
            scope.launch {
                when (val picked = fileGateway.pickFiles()) {
                    HomeworkFilePickResult.Cancelled -> Unit
                    is HomeworkFilePickResult.Failed -> feedback = picked.pickFeedback()
                    is HomeworkFilePickResult.Selected -> {
                        added = if (singleFile) listOf(picked.files.last()) else mergePickedFiles(added, picked.files)
                        feedback = null
                    }
                }
            }
        },
        onRemovePending = { index -> added = added.filterIndexed { i, _ -> i != index } },
        existing = existing,
        removed = removed,
        onToggleExisting = { name -> removed = if (name in removed) removed - name else removed + name },
        singleFile = singleFile,
    )
    feedback?.let { AssignmentUploadMessage(it, isError = true) }
    AssignmentUploadAction(
        label = saveLabel,
        busyLabel = "正在保存…",
        busy = busy,
        enabled = added.isNotEmpty() || removed.isNotEmpty(),
        onClick = { onSave(added, removed) },
    )
}

package team.bjtuss.bjtuselfservice.shared.feature.mailbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailAttachment
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailAttachmentContent
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePreviewResult
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult

/** 邮箱工作区向详情页提供的附件能力；独立预览/测试时为 null，附件只显示元数据。 */
internal class MailAttachmentActions(
    val download: suspend (messageId: String, attachment: MailAttachment) -> MailAttachmentDownloadResult,
    val fileGateway: HomeworkFileGateway,
    val reauthenticate: (suspend () -> Boolean)?,
)

internal val LocalMailAttachmentActions = staticCompositionLocalOf<MailAttachmentActions?> { null }

private sealed interface AttachmentRowState {
    data object Idle : AttachmentRowState
    data object Working : AttachmentRowState
    data class Notice(val text: String, val isError: Boolean) : AttachmentRowState
}

private class InAppPreview(
    val attachment: MailAttachment,
    val content: MailAttachmentContent,
    val kind: MailAttachmentPreviewKind,
)

@Composable
internal fun MailboxAttachmentSection(messageId: String, attachments: List<MailAttachment>) {
    val actions = LocalMailAttachmentActions.current
    val scope = rememberCoroutineScope()
    // 同一封邮件内已下载的附件只保留在内存，预览后再保存不必重复下载；换邮件即丢弃。
    val downloaded = remember(messageId) { mutableStateMapOf<String, MailAttachmentContent>() }
    val rowStates = remember(messageId) { mutableStateMapOf<String, AttachmentRowState>() }
    var preview by remember(messageId) { mutableStateOf<InAppPreview?>(null) }

    suspend fun fetch(attachment: MailAttachment, key: String): MailAttachmentContent? {
        downloaded[key]?.let { return it }
        val currentActions = actions ?: return null
        var result = currentActions.download(messageId, attachment)
        val reauthenticate = currentActions.reauthenticate
        if (
            result is MailAttachmentDownloadResult.Failed &&
            result.reason == MailAttachmentFailure.SESSION_EXPIRED &&
            reauthenticate != null
        ) {
            val recovered = try {
                reauthenticate()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (recovered) result = currentActions.download(messageId, attachment)
        }
        return when (result) {
            is MailAttachmentDownloadResult.Success -> result.content.also { downloaded[key] = it }
            is MailAttachmentDownloadResult.Failed -> {
                rowStates[key] = AttachmentRowState.Notice(result.reason.message(), isError = true)
                null
            }
        }
    }

    suspend fun save(attachment: MailAttachment, key: String, content: MailAttachmentContent) {
        val gateway = actions?.fileGateway ?: return
        val outcome = gateway.saveFile(attachment.toFileContent(content))
        rowStates[key] = when (outcome) {
            HomeworkFileSaveResult.Saved -> AttachmentRowState.Notice("已保存", isError = false)
            HomeworkFileSaveResult.Cancelled -> AttachmentRowState.Idle
            is HomeworkFileSaveResult.Failed -> AttachmentRowState.Notice(outcome.reason.saveMessage(), isError = true)
        }
    }

    fun launchFor(attachment: MailAttachment, key: String, block: suspend () -> Unit) {
        if (rowStates[key] == AttachmentRowState.Working) return
        // 按下立即给出进度反馈，不等网络返回。
        rowStates[key] = AttachmentRowState.Working
        scope.launch {
            try {
                block()
            } finally {
                if (rowStates[key] == AttachmentRowState.Working) rowStates[key] = AttachmentRowState.Idle
            }
        }
    }

    val onPreview: (MailAttachment, String) -> Unit = { attachment, key ->
        launchFor(attachment, key) {
            val content = fetch(attachment, key) ?: return@launchFor
            val kind = mailAttachmentPreviewKind(attachment.name, content.contentType ?: attachment.contentType)
            val inAppText = kind == MailAttachmentPreviewKind.TEXT && content.bytes.size <= MAIL_TEXT_PREVIEW_MAX_BYTES
            val gateway = actions?.fileGateway
            when {
                kind == MailAttachmentPreviewKind.IMAGE || inAppText -> {
                    rowStates[key] = AttachmentRowState.Idle
                    preview = InAppPreview(attachment, content, if (inAppText) MailAttachmentPreviewKind.TEXT else kind)
                }
                gateway != null && gateway.isPreviewAvailable -> {
                    when (val outcome = gateway.previewFile(attachment.toFileContent(content))) {
                        HomeworkFilePreviewResult.Opened -> rowStates[key] = AttachmentRowState.Idle
                        is HomeworkFilePreviewResult.Failed -> rowStates[key] = AttachmentRowState.Notice(
                            if (outcome.reason == HomeworkFileGatewayFailure.UNAVAILABLE) {
                                "没有可以打开此类文件的应用，请改用保存。"
                            } else {
                                "无法打开预览，请改用保存。"
                            },
                            isError = true,
                        )
                    }
                }
                // 平台没有系统预览器时退回保存流程。
                else -> save(attachment, key, content)
            }
        }
    }
    val onSave: (MailAttachment, String) -> Unit = { attachment, key ->
        launchFor(attachment, key) {
            val content = fetch(attachment, key) ?: return@launchFor
            save(attachment, key, content)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        attachments.forEachIndexed { index, attachment ->
            val key = attachment.id ?: "#$index"
            MailboxAttachmentRow(
                attachment = attachment,
                state = rowStates[key] ?: AttachmentRowState.Idle,
                enabled = actions != null && actions.fileGateway.isAvailable && attachment.id != null,
                onPreview = { onPreview(attachment, key) },
                onSave = { onSave(attachment, key) },
            )
        }
    }

    preview?.let { current ->
        MailAttachmentPreviewSheet(
            preview = current,
            canSave = actions?.fileGateway?.isAvailable == true,
            onSave = {
                val key = current.attachment.id ?: return@MailAttachmentPreviewSheet
                preview = null
                launchFor(current.attachment, key) { save(current.attachment, key, current.content) }
            },
            onDismiss = { preview = null },
        )
    }
}

@Composable
private fun MailboxAttachmentRow(
    attachment: MailAttachment,
    state: AttachmentRowState,
    enabled: Boolean,
    onPreview: () -> Unit,
    onSave: () -> Unit,
) {
    val name = attachment.name.ifBlank { "未命名附件" }
    val working = state == AttachmentRowState.Working
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = onPreview,
            enabled = enabled && !working,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(11.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .semantics { contentDescription = "预览附件 $name" },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MailboxAttachmentMark(modifier = Modifier.size(17.dp))
                Column(Modifier.weight(1f).padding(start = 9.dp, end = 8.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    attachment.sizeBytes?.let { bytes ->
                        Text(
                            formatMailboxSize(bytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.76f),
                        )
                    }
                }
                if (working) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else if (enabled) {
                    Surface(
                        onClick = onSave,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .semantics { contentDescription = "保存附件 $name" },
                    ) {
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text("保存", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (state is AttachmentRowState.Notice) {
            Text(
                state.text,
                style = MaterialTheme.typography.labelMedium,
                color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun MailAttachmentPreviewSheet(
    preview: InAppPreview,
    canSave: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppleSheet(
        onDismissRequest = onDismiss,
        needsFullHeight = true,
        scrollableBody = true,
        title = preview.attachment.name.ifBlank { "附件预览" },
        confirmLabel = if (canSave) "保存" else null,
        onConfirm = if (canSave) onSave else null,
        dismissLabel = "关闭",
    ) {
        when (preview.kind) {
            MailAttachmentPreviewKind.IMAGE -> ImagePreview(preview.content.bytes)
            else -> TextPreview(preview.content.bytes)
        }
    }
}

@Composable
private fun ColumnScope.ImagePreview(bytes: ByteArray) {
    val bitmap: ImageBitmap? = remember(bytes) {
        try {
            bytes.decodeToImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }
    if (bitmap == null) {
        PreviewMessage("无法解码这张图片，请保存后用其他应用查看。")
        return
    }
    var scale by remember(bytes) { mutableFloatStateOf(1f) }
    var offsetX by remember(bytes) { mutableFloatStateOf(0f) }
    var offsetY by remember(bytes) { mutableFloatStateOf(0f) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        if (scale == 1f) {
            offsetX = 0f
            offsetY = 0f
        } else {
            offsetX += pan.x
            offsetY += pan.y
        }
    }
    Box(
        Modifier.weight(1f).fillMaxWidth().padding(16.dp).transformable(transform),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "附件图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
        )
    }
}

@Composable
private fun ColumnScope.TextPreview(bytes: ByteArray) {
    val text = remember(bytes) { decodeMailAttachmentText(bytes) }
    if (text.isBlank()) {
        PreviewMessage("文件内容为空。")
        return
    }
    SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}

@Composable
private fun ColumnScope.PreviewMessage(text: String) {
    Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun MailAttachment.toFileContent(content: MailAttachmentContent) = HomeworkFileContent(
    fileName = name.ifBlank { "attachment" },
    contentType = content.contentType ?: contentType ?: "application/octet-stream",
    bytes = content.bytes,
)

private fun MailAttachmentFailure.message(): String = when (this) {
    MailAttachmentFailure.NETWORK -> "下载失败，请检查网络后点按重试。"
    MailAttachmentFailure.SESSION_EXPIRED -> "邮箱会话已失效，请刷新邮箱后重试。"
    MailAttachmentFailure.TOO_LARGE -> "附件超过 50 MB，请在网页版中下载。"
    MailAttachmentFailure.UNAVAILABLE -> "这个附件无法下载，请在网页版中查看。"
}

private fun HomeworkFileGatewayFailure.saveMessage(): String = when (this) {
    HomeworkFileGatewayFailure.PERMISSION_DENIED -> "没有写入所选位置的权限。"
    HomeworkFileGatewayFailure.UNAVAILABLE -> "当前无法打开保存面板，请稍后重试。"
    HomeworkFileGatewayFailure.IO -> "保存失败，请重试。"
}

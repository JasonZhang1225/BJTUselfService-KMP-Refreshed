package team.bjtuss.bjtuselfservice.shared.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

actual val fileDropSupported: Boolean = true

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onDrop: (HomeworkFilePickResult) -> Unit,
): Modifier {
    val hover by rememberUpdatedState(onHoverChange)
    val drop by rememberUpdatedState(onDrop)
    val scope = rememberCoroutineScope()
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val files = event.droppedFiles() ?: return false
                scope.launch { drop(readDroppedFiles(files)) }
                return true
            }
        }
    }
    // 拖到一半转入忙碌状态时，目标被摘掉、收不到 onExited，要自己清掉高亮。
    LaunchedEffect(enabled) { if (!enabled) hover(false) }
    if (!enabled) return this
    return dragAndDropTarget(shouldStartDragAndDrop = { it.hasFiles() }, target = target)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.hasFiles(): Boolean =
    awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.droppedFiles(): List<File>? = runCatching {
    (awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()
}.getOrNull()?.takeIf { it.isNotEmpty() }

/** 与桌面文件选择器读文件的方式一致；文件夹不展开，避免把整个目录悄悄传上去。 */
internal suspend fun readDroppedFiles(files: List<File>): HomeworkFilePickResult = withContext(Dispatchers.IO) {
    if (files.any { !it.isFile }) return@withContext HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.IO)
    try {
        HomeworkFilePickResult.Selected(
            files.map { file ->
                HomeworkFileContent(
                    fileName = file.name,
                    contentType = Files.probeContentType(file.toPath()) ?: "application/octet-stream",
                    bytes = file.readBytes(),
                )
            },
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: SecurityException) {
        HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
    } catch (_: Exception) {
        HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.IO)
    }
}

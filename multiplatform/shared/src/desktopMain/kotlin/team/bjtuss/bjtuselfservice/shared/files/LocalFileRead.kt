package team.bjtuss.bjtuselfservice.shared.files

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

/** 一次选择或拖入的总大小上限；文件要整块读进内存，与命令行提交的上限一致。 */
internal const val MAX_LOCAL_READ_BYTES = 200L * 1024 * 1024

/**
 * macOS、Windows 的文件选择器和拖放共用：读取前先查大小，超限时一个字节都不读。
 * 文件夹不展开，避免把整个目录悄悄传上去。
 */
suspend fun readLocalFiles(files: List<File>, maxBytes: Long = MAX_LOCAL_READ_BYTES): HomeworkFilePickResult = withContext(Dispatchers.IO) {
    if (files.any { !it.isFile }) return@withContext HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.IO)
    if (files.sumOf(File::length) > maxBytes) return@withContext HomeworkFilePickResult.TooLarge
    val context = currentCoroutineContext()
    try {
        HomeworkFilePickResult.Selected(
            files.map { file ->
                context.ensureActive()
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

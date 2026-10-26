package team.bjtuss.bjtuselfservice.shared.files

import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

enum class HomeworkFileGatewayFailure {
    UNAVAILABLE,
    PERMISSION_DENIED,
    IO,
}

sealed interface HomeworkFilePickResult {
    class Selected(val files: List<HomeworkFileContent>) : HomeworkFilePickResult {
        init {
            require(files.isNotEmpty())
        }

        override fun toString(): String = "Selected(files=${files.size}, names=<redacted>)"
    }

    data object Cancelled : HomeworkFilePickResult
    data class Failed(val reason: HomeworkFileGatewayFailure) : HomeworkFilePickResult
}

sealed interface HomeworkFileSaveResult {
    data object Saved : HomeworkFileSaveResult
    data object Cancelled : HomeworkFileSaveResult
    data class Failed(val reason: HomeworkFileGatewayFailure) : HomeworkFileSaveResult
}

sealed interface HomeworkFilePreviewResult {
    data object Opened : HomeworkFilePreviewResult
    data class Failed(val reason: HomeworkFileGatewayFailure) : HomeworkFilePreviewResult
}

/** 平台实现必须使用系统文件面板；不得静默保存或把完整路径写入日志。 */
interface HomeworkFileGateway {
    val isAvailable: Boolean
    suspend fun pickFiles(): HomeworkFilePickResult
    suspend fun saveFile(file: HomeworkFileContent): HomeworkFileSaveResult

    /** 能否交给系统预览器（iOS 快速查看、macOS 默认应用、Android 打开方式）。 */
    val isPreviewAvailable: Boolean get() = false

    /**
     * 把文件写入应用临时目录后交给系统预览器，不写入用户的下载/文稿目录。
     * 临时文件由平台在下次预览或进程退出时清理。
     */
    suspend fun previewFile(file: HomeworkFileContent): HomeworkFilePreviewResult =
        HomeworkFilePreviewResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)

    /** 能否边下载边写盘（课程回放动辄 1–2 GB，不能整块放进内存再保存）。 */
    val isStreamingSaveAvailable: Boolean get() = false

    /**
     * 先让用户在系统面板选好位置，返回一个可以稍后（甚至在后台任务里）分块写入的目标。
     * 写入期间文件是临时的，[StreamingDestination.commit] 后才出现在用户选的位置；
     * [StreamingDestination.abort] 会删除未完成的内容。
     */
    suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult =
        StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)

    /**
     * 选位置后立即把 [produce] 写进来的字节依次写入，全部成功才落盘。
     * [produce] 抛出的异常（网络失败、取消）在删除未完成文件后原样抛给调用方；
     * 写盘本身失败返回 [HomeworkFileGatewayFailure.IO]。
     */
    suspend fun saveStreamingFile(
        fileName: String,
        contentType: String,
        produce: suspend (StreamingFileSink) -> Unit,
    ): HomeworkFileSaveResult {
        val destination = when (val opened = openStreamingDestination(fileName, contentType)) {
            StreamingDestinationResult.Cancelled -> return HomeworkFileSaveResult.Cancelled
            is StreamingDestinationResult.Failed -> return HomeworkFileSaveResult.Failed(opened.reason)
            is StreamingDestinationResult.Opened -> opened.destination
        }
        var committed = false
        try {
            produce(StreamingFileSink(destination::write))
            return destination.commit().also { committed = it == HomeworkFileSaveResult.Saved }
        } catch (_: StreamingSinkIoException) {
            return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
        } finally {
            if (!committed) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { destination.abort() }
        }
    }
}

/** 已选好位置、尚未写完的保存目标。写盘失败抛 [StreamingSinkIoException]。 */
interface StreamingDestination {
    /** 平台私有恢复句柄，允许落在应用私有任务记录里；不能包含账号会话或鉴权参数。 */
    val recoveryToken: String? get() = null
    /** 给用户看的名字（文件名；不含完整路径）。 */
    val displayName: String
    suspend fun write(bytes: ByteArray)
    suspend fun commit(): HomeworkFileSaveResult
    /** 保留所选文件和权限，关闭未完成写入；重试时由平台重新从文件开头写。 */
    suspend fun closeForRecovery() = Unit
    suspend fun abort()
}

sealed interface StreamingDestinationResult {
    class Opened(val destination: StreamingDestination) : StreamingDestinationResult
    data object Cancelled : StreamingDestinationResult
    data class Failed(val reason: HomeworkFileGatewayFailure) : StreamingDestinationResult
}

fun interface StreamingFileSink {
    suspend fun write(bytes: ByteArray)
}

/** 平台实现包装写盘异常用，好与生产者（网络）异常区分开。 */
class StreamingSinkIoException(cause: Throwable? = null) : Exception("Streaming file write failed", cause)

object UnavailableHomeworkFileGateway : HomeworkFileGateway {
    override val isAvailable: Boolean = false
    override suspend fun pickFiles(): HomeworkFilePickResult =
        HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
    override suspend fun saveFile(file: HomeworkFileContent): HomeworkFileSaveResult =
        HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
}

/**
 * 系统预览器用的 MIME。邮件常把 docx/xlsx 标成 `application/octet-stream`，
 * Android MimeTypeMap 也不认识这些扩展名，打开方式列表会空。
 */
fun previewContentType(fileName: String, contentType: String?): String {
    val declared = contentType?.substringBefore(';')?.trim().orEmpty()
    if (declared.isNotEmpty() && !declared.equals("application/octet-stream", ignoreCase = true) &&
        !declared.equals("binary/octet-stream", ignoreCase = true)
    ) {
        return declared
    }
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty() || extension == fileName.lowercase()) {
        return declared.ifEmpty { "application/octet-stream" }
    }
    return PREVIEW_MIME_BY_EXTENSION[extension] ?: declared.ifEmpty { "application/octet-stream" }
}

private val PREVIEW_MIME_BY_EXTENSION = mapOf(
    "pdf" to "application/pdf",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "zip" to "application/zip",
    "rar" to "application/vnd.rar",
    "7z" to "application/x-7z-compressed",
    "txt" to "text/plain",
    "csv" to "text/csv",
    "json" to "application/json",
    "xml" to "application/xml",
    "rtf" to "application/rtf",
    "mp3" to "audio/mpeg",
    "mp4" to "video/mp4",
    "wav" to "audio/wav",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "heic" to "image/heic",
)

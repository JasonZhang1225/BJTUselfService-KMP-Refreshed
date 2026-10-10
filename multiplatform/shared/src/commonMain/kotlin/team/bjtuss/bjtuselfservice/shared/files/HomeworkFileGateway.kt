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
}

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

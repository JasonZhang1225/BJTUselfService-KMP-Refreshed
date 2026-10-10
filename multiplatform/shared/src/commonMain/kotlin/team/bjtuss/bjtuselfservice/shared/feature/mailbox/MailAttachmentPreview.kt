package team.bjtuss.bjtuselfservice.shared.feature.mailbox

import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailAttachmentContent
import team.bjtuss.bjtuselfservice.shared.network.decodeLegacyGb18030OrNull

/** 单个附件的内存上限：附件整份读入内存再交给预览/保存，超过时引导到网页版下载。 */
internal const val MAIL_ATTACHMENT_MAX_BYTES = 50 * 1024 * 1024

/** 应用内文本预览的上限；更大的文本交给系统预览器。 */
internal const val MAIL_TEXT_PREVIEW_MAX_BYTES = 512 * 1024

enum class MailAttachmentFailure {
    NETWORK,
    SESSION_EXPIRED,
    TOO_LARGE,
    UNAVAILABLE,
}

sealed interface MailAttachmentDownloadResult {
    class Success(val content: MailAttachmentContent) : MailAttachmentDownloadResult
    data class Failed(val reason: MailAttachmentFailure) : MailAttachmentDownloadResult
}

/** 附件预览方式：图片和纯文本在应用内显示，其余交给系统预览器。 */
enum class MailAttachmentPreviewKind {
    IMAGE,
    TEXT,
    SYSTEM,
}

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
private val textExtensions = setOf(
    "txt", "text", "log", "csv", "tsv", "md", "markdown", "json", "xml",
    "yaml", "yml", "ini", "conf", "cfg", "properties",
)

internal fun mailAttachmentPreviewKind(fileName: String, contentType: String?): MailAttachmentPreviewKind {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        extension in imageExtensions -> MailAttachmentPreviewKind.IMAGE
        type in setOf("image/png", "image/jpeg", "image/gif", "image/bmp", "image/webp") ->
            MailAttachmentPreviewKind.IMAGE
        extension in textExtensions -> MailAttachmentPreviewKind.TEXT
        type == "text/plain" || type == "text/csv" || type == "application/json" -> MailAttachmentPreviewKind.TEXT
        else -> MailAttachmentPreviewKind.SYSTEM
    }
}

/**
 * 邮件里的文本附件常见 UTF-8（可能带 BOM）和 GBK/GB18030 两种编码。
 * UTF-8 解码出现替换字符时改用 GB18030；平台不支持时保留 UTF-8 结果。
 */
internal fun decodeMailAttachmentText(bytes: ByteArray): String {
    val utf8Start = if (
        bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) 3 else 0
    val utf8 = bytes.decodeToString(startIndex = utf8Start)
    if (!utf8.contains('�')) return utf8
    return decodeLegacyGb18030OrNull(bytes) ?: utf8
}

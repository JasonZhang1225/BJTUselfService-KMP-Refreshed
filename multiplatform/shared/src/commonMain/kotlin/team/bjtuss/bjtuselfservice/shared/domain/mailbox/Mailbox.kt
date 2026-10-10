package team.bjtuss.bjtuselfservice.shared.domain.mailbox

/** 邮箱文件夹的只读摘要。 */
data class MailboxFolder(
    val id: Int,
    val name: String,
    val unreadCount: Int? = null,
)

/** 收件箱列表中的一封邮件，不包含正文。 */
data class MailSummary(
    val id: String,
    val folderId: Int,
    val sender: String,
    val subject: String,
    val preview: String,
    val sentAt: String,
    val receivedAt: String,
    val sizeBytes: Int,
    val isRead: Boolean,
    val hasAttachments: Boolean,
    val recipients: List<String> = emptyList(),
)

data class MailboxPage(
    val totalCount: Int,
    val messages: List<MailSummary>,
)

/** 详情中的附件元数据；[id] 即 Coremail 的 part，用于下载。 */
data class MailAttachment(
    val id: String?,
    val name: String,
    val sizeBytes: Int?,
    val contentType: String?,
)

/** 邮件详情。正文保留为受控 HTML 字符串，UI 首版只转为纯文本显示。 */
data class MailMessage(
    val id: String,
    val folderId: Int,
    val from: List<String>,
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val bodyHtml: String,
    val sentAt: String,
    val attachments: List<MailAttachment>,
)

/** 写信/回复编辑器的最小草稿模型；正文在 UI 中以纯文本编辑，发送时再转成安全 HTML。 */
data class MailComposeDraft(
    val id: String,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val bodyText: String = "",
    val replyToMessageId: String? = null,
    val isReply: Boolean = false,
)

/** 已下载到内存的附件本体；只在预览/保存流程中短暂持有，不写入缓存或日志。 */
class MailAttachmentContent(
    val bytes: ByteArray,
    val contentType: String?,
) {
    override fun toString(): String = "MailAttachmentContent(bytes=${bytes.size}, contentType=$contentType)"
}

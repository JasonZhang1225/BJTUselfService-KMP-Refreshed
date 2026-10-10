package team.bjtuss.bjtuselfservice.shared.feature.mailbox

import kotlin.test.Test
import kotlin.test.assertEquals

class MailAttachmentPreviewTest {
    @Test
    fun classifiesByExtensionThenContentType() {
        assertEquals(MailAttachmentPreviewKind.IMAGE, mailAttachmentPreviewKind("照片.JPG", "application/octet-stream"))
        assertEquals(MailAttachmentPreviewKind.IMAGE, mailAttachmentPreviewKind("scan", "image/png"))
        assertEquals(MailAttachmentPreviewKind.TEXT, mailAttachmentPreviewKind("名单.csv", null))
        assertEquals(MailAttachmentPreviewKind.TEXT, mailAttachmentPreviewKind("readme", "text/plain; charset=gbk"))
        assertEquals(MailAttachmentPreviewKind.SYSTEM, mailAttachmentPreviewKind("申请表.docx", null))
        assertEquals(MailAttachmentPreviewKind.SYSTEM, mailAttachmentPreviewKind("通知.pdf", "application/pdf"))
        assertEquals(MailAttachmentPreviewKind.SYSTEM, mailAttachmentPreviewKind("photo.heic", "image/heic"))
        assertEquals(MailAttachmentPreviewKind.SYSTEM, mailAttachmentPreviewKind("page.html", "text/html"))
    }

    @Test
    fun decodesUtf8WithBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "姓名,学号".encodeToByteArray()
        assertEquals("姓名,学号", decodeMailAttachmentText(bytes))
    }

    @Test
    fun invalidUtf8NeverThrows() {
        // GB18030 编码的“姓名”；支持 GB18030 的平台得到中文，其他平台保留替换字符而不抛异常。
        val gbk = byteArrayOf(0xD0.toByte(), 0xD5.toByte(), 0xC3.toByte(), 0xFB.toByte())
        val decoded = decodeMailAttachmentText(gbk)
        assertEquals(true, decoded == "姓名" || decoded.contains('\uFFFD'))
    }
}

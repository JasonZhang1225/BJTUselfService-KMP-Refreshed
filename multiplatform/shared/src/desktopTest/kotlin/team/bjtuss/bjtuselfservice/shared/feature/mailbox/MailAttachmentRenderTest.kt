package team.bjtuss.bjtuselfservice.shared.feature.mailbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailAttachment
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailAttachmentContent
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePickResult
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult

/** 用虚构附件渲染邮件附件区与文本预览；无网络、无账号。输出 build/reports/mail-attachments/。 */
class MailAttachmentRenderTest {
    private val attachments = listOf(
        MailAttachment("3", "附件1：2025-2026学年奖学金申请表（请于周五前提交至学院办公室）.docx", 15693, null),
        MailAttachment("4", "名单.csv", 812, "text/csv"),
        MailAttachment("5", "现场照片.jpg", 2_400_000, "image/jpeg"),
    )

    private val gateway = object : HomeworkFileGateway {
        override val isAvailable = true
        override val isPreviewAvailable = true
        override suspend fun pickFiles() = HomeworkFilePickResult.Cancelled
        override suspend fun saveFile(file: HomeworkFileContent) = HomeworkFileSaveResult.Saved
    }

    private val actions = MailAttachmentActions(
        download = { _, _ ->
            MailAttachmentDownloadResult.Success(
                MailAttachmentContent("学号,姓名,班级\n000001,示例同学,计科2301\n000002,示例同学二,计科2302\n".encodeToByteArray(), "text/csv"),
            )
        },
        fileGateway = gateway,
        reauthenticate = null,
    )

    @Test fun renderAttachmentRowsAndTextPreview() {
        SwingUtilities.invokeAndWait {
            listOf("light", "dark", "large-font", "no-gateway", "text-preview").forEach { name ->
                val scene = ImageComposeScene(
                    width = 780, height = 1400,
                    density = Density(2f, if (name == "large-font") 1.5f else 1f),
                    coroutineContext = Dispatchers.Unconfined,
                ) {
                    PlatformAppTheme(useDarkTheme = name == "dark", dynamicColorEnabled = false) {
                        Surface(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(
                                LocalMailAttachmentActions provides if (name == "no-gateway") null else actions,
                            ) {
                                Column(Modifier.padding(18.dp)) {
                                    MailboxAttachmentSection(messageId = "fixture", attachments = attachments)
                                }
                            }
                        }
                    }
                }
                try {
                    repeat(4) { scene.render(1_000_000_000L + it * 100_000_000L).close() }
                    if (name == "text-preview") {
                        // 第二行（名单.csv）中部：padding 18dp + 第一行约 62dp + 间距 → 约 y=110dp，密度 2。
                        val point = Offset(120f * 2, 110f * 2)
                        scene.sendPointerEvent(PointerEventType.Press, point)
                        scene.sendPointerEvent(PointerEventType.Release, point)
                        repeat(10) { scene.render(1_500_000_000L + it * 100_000_000L).close() }
                    }
                    val rendered = scene.render(3_000_000_000L)
                    try {
                        val data = rendered.encodeToData(EncodedImageFormat.PNG)!!
                        val file = File("build/reports/mail-attachments/$name.png")
                        file.parentFile.mkdirs()
                        file.writeBytes(data.bytes)
                        data.close()
                        assertTrue(file.length() > 1000)
                    } finally { rendered.close() }
                } finally { scene.close() }
            }
        }
    }
}

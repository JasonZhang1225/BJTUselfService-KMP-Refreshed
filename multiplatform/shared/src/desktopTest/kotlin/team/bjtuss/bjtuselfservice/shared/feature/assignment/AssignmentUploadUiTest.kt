package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePickResult

class AssignmentUploadUiTest {
    private fun file(name: String, size: Int = 2048) = HomeworkFileContent(name, "application/octet-stream", ByteArray(size))

    @Test fun reselectingSameNameKeepsLatestFile() {
        val merged = mergePickedFiles(listOf(file("a.pdf", 1), file("b.docx")), listOf(file("a.pdf", 3), file("c.zip")))
        assertEquals(listOf("b.docx", "a.pdf", "c.zip"), merged.map { it.fileName })
        assertEquals(3, merged[1].bytes.size)
    }

    @Test fun pickerUnavailableIsNotReportedAsUnreadableFile() {
        assertEquals("暂时无法打开文件选择器，请稍后重试。",
            HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE).pickFeedback())
        assertEquals("无法读取所选文件，请重新选择。",
            HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.IO).pickFeedback())
    }

    @Test fun uploadSizeIsReadable() {
        assertEquals("大小未知", formatUploadSize(0))
        assertEquals("2 KB", formatUploadSize(2048))
        assertEquals("1.5 MB", formatUploadSize(1024L * 1024L * 3 / 2))
    }

    /** 离线检查上传正文：空选择区、已提交+待上传列表、错误反馈，浅色与深色各一张。 */
    @Test fun renderUploadStates() = SwingUtilities.invokeAndWait {
        listOf(false, true).forEach { dark ->
            val scene = ImageComposeScene(1180, 1080, density = Density(2f), coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(dark, false) {
                    Surface(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                AssignmentUploadNote("选好文件并核对列表后提交到智慧教学平台。")
                                AssignmentUploadFiles(emptyList(), busy = false, pickEnabled = true, onPick = {}, onRemovePending = {})
                                AssignmentUploadAction("提交作业", "正在上传并提交…", busy = false, enabled = false) {}
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                AssignmentUploadFiles(
                                    pending = listOf(file("实验报告-第三章-数据处理与误差分析.docx", 380_000), file("result.zip", 1_800_000)),
                                    busy = false, pickEnabled = true, onPick = {}, onRemovePending = {},
                                    existing = listOf("draft.pdf", "old.pdf"), removed = setOf("old.pdf"),
                                )
                                AssignmentUploadMessage("暂时无法打开文件选择器，请稍后重试。", isError = true)
                                AssignmentUploadAction("保存为草稿", "正在保存…", busy = true, enabled = true) {}
                            }
                        }
                    }
                }
            }
            try {
                repeat(3) { scene.render((it + 1) * 100_000_000L).close() }
                val image = scene.render(1_000_000_000L)
                try {
                    val data = image.encodeToData(EncodedImageFormat.PNG)!!
                    File("build/reports/assignment-ui/upload-${if (dark) "dark" else "light"}.png")
                        .apply { parentFile.mkdirs(); writeBytes(data.bytes) }
                    data.close()
                } finally { image.close() }
            } finally { scene.close() }
        }
    }
}

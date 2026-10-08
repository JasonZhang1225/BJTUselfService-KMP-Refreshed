package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme

class PhyVlabSubmissionVisualTest {
    @Test fun renderMandatoryFileCheckWithAndWithoutPlatformStatement() = SwingUtilities.invokeAndWait {
        listOf(false, true).forEach { dark ->
            listOf<String?>(null, "本人确认本次提交的报告为本人完成，已检查全部附件。").forEachIndexed { index, statement ->
                val scene = ImageComposeScene(480, 720, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    PlatformAppTheme(useDarkTheme = dark, dynamicColorEnabled = false) {
                        Surface(Modifier.fillMaxSize()) {
                            PhyVlabFinalizationDialog("综合实验报告", listOf("实验报告.pdf", "数据与计算过程.xlsx"),
                                statement, false, {}, {})
                        }
                    }
                }
                try {
                    repeat(5) { scene.render(1_000_000_000L + it * 100_000_000L).close() }
                    val image = scene.render(2_000_000_000L)
                    try {
                        val data = image.encodeToData(EncodedImageFormat.PNG)!!
                        try { File("build/reports/phyvlab-ui/final-confirm-${if (dark) "dark" else "light"}-$index.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) } }
                        finally { data.close() }
                    } finally { image.close() }
                } finally { scene.close() }
            }
        }
    }
}

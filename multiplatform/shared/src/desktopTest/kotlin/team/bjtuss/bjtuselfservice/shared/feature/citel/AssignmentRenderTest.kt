package team.bjtuss.bjtuselfservice.shared.feature.citel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import javax.swing.SwingUtilities
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*

class AssignmentRenderTest {
    @Test fun threeSourcesShareLayoutInLightDarkAndLargeType() {
        SwingUtilities.invokeAndWait {
            listOf("light", "dark", "large").forEach { name ->
                val scene = ImageComposeScene(width = 390, height = 1100, density = Density(1f, if (name == "large") 1.5f else 1f), coroutineContext = Dispatchers.Unconfined) {
                    PlatformAppTheme(useDarkTheme = name == "dark", dynamicColorEnabled = false) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf("智慧教学", "物理在线", "CITEL").forEach { source ->
                                    AssignmentCard(AssignmentPresentation("第 2 章 · 测试实验报告", "测试课程", source, "待提交",
                                        open = "2026-10-07 00:00", discount = if (source == "CITEL") "2026-10-14 23:59 · ×0.8" else null,
                                        due = "2026-10-18 23:59"), {})
                                }
                            }
                        }
                    }
                }
                try {
                    repeat(5) { scene.render(1_000_000_000L + it * 16_000_000L).close() }
                    val image = scene.render(2_000_000_000L)
                    try {
                        val data = image.encodeToData(EncodedImageFormat.PNG)!!
                        val file = File("build/reports/assignment-ui/three-sources-$name.png")
                        file.parentFile.mkdirs(); file.writeBytes(data.bytes); data.close()
                        assertTrue(file.length() > 1000)
                    } finally { image.close() }
                } finally { scene.close() }
            }
        }
    }
}

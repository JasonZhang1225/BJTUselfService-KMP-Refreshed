package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile

/** Offscreen Compose only: no account store or network access. */
class AppSidebarRenderTest {
    @Test
    fun sidebarShowsTabIconsInLightDarkAndLargeText() {
        SwingUtilities.invokeAndWait {
            val profile = StudentProfile("测试用户", "student", "本科生", "测试学院")
            val defaultTabs = listOf(AppSection.HOME, AppSection.SCHEDULE, AppSection.HOMEWORK, AppSection.MORE)
            val longTabs = listOf(
                AppSection.HOME,
                AppSection.CLASSROOM_OCCUPANCY,
                AppSection.PHYSICS_LAB,
                AppSection.MORE,
            )
            listOf(
                Triple("light", false, 1f) to defaultTabs,
                Triple("dark", true, 1f) to defaultTabs,
                Triple("light-large", false, 1.5f) to longTabs,
            ).forEach { (spec, tabs) ->
                val (name, dark, font) = spec
                // 默认 1080.dp 窗口里侧栏约 226.dp；按 2x 像素渲染，避免窄画布把标题挤没。
                val scene = ImageComposeScene(
                    width = 452,
                    height = 1440,
                    density = Density(2f, font),
                    coroutineContext = Dispatchers.Unconfined,
                ) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            AppSidebar(
                                profile = profile,
                                section = if (name == "light-large") AppSection.PHYSICS_LAB else AppSection.HOME,
                                showPhyVlab = false,
                                sections = tabs,
                                onSectionSelected = {},
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                try {
                    repeat(4) { scene.render(1_000_000_000L + it * 16_000_000L).close() }
                    val rendered = scene.render(2_000_000_000L)
                    try {
                        val data = rendered.encodeToData(EncodedImageFormat.PNG)!!
                        val file = File("build/reports/sidebar/sidebar-$name.png")
                        file.parentFile.mkdirs()
                        file.writeBytes(data.bytes)
                        data.close()
                        assertTrue(file.length() > 1000, "${file.name} should contain pixels")
                    } finally {
                        rendered.close()
                    }
                } finally {
                    scene.close()
                }
            }
        }
    }
}

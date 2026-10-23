package team.bjtuss.bjtuselfservice.shared.feature.settings

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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import team.bjtuss.bjtuselfservice.shared.update.UpdateSource

class VersionAndProjectCardRenderTest {
    @Test fun rendersVersionRowsSourceSliderAndRepositoryGroups() {
        SwingUtilities.invokeAndWait {
            data class Variant(val name: String, val dark: Boolean, val fontScale: Float, val source: UpdateSource, val checking: Boolean)
            val variants = listOf(
                Variant("light-auto", false, 1f, UpdateSource.AUTO, false),
                Variant("dark-auto", true, 1f, UpdateSource.AUTO, false),
                Variant("dark-gitee", true, 1f, UpdateSource.GITEE, false),
                Variant("light-github-checking", false, 1f, UpdateSource.GITHUB, true),
                Variant("dark-large-text", true, 1.4f, UpdateSource.AUTO, false),
            )
            for (variant in variants) {
                val opened = mutableListOf<String>()
                val scene = ImageComposeScene(
                    width = 1170, height = if (variant.fontScale > 1f) 2700 else 2200,
                    density = Density(3f, variant.fontScale),
                    coroutineContext = Dispatchers.Unconfined,
                ) {
                    MaterialTheme(colorScheme = if (variant.dark) darkColorScheme() else lightColorScheme()) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                            VersionAndProjectCard(
                                systemVersion = "iOS 27.2",
                                updateSource = variant.source,
                                checking = variant.checking,
                                onUpdateSource = {},
                                onCheckUpdate = {},
                                onOpenUri = { opened += it },
                            )
                        }
                    }
                }
                try {
                    repeat(3) { scene.render() }
                    val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
                    val output = File("../../.artifacts/settings-version-card/${variant.name}.png")
                    output.parentFile.mkdirs()
                    output.writeBytes(bytes)
                    assertTrue(bytes.size > 1000)
                    assertEquals(emptyList(), opened)
                } finally { scene.close() }
            }
        }
    }
}

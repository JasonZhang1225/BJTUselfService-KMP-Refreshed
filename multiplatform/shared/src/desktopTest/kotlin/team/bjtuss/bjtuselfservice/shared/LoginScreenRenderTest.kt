package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import team.bjtuss.bjtuselfservice.shared.auth.LoginState

class LoginScreenRenderTest {
    @Test
    fun rendersCompactExpandedAndLargeTextWithoutOverlap() {
        SwingUtilities.invokeAndWait {
            data class Variant(
                val name: String,
                val width: Int,
                val height: Int,
                val density: Float,
                val fontScale: Float,
                val dark: Boolean,
                val windowClass: WindowClass,
                val checkingUpdate: Boolean,
            )

            val variants = listOf(
                Variant("compact-light", 1170, 2532, 3f, 1f, false, WindowClass.Compact, false),
                Variant("expanded-dark", 1920, 1200, 2f, 1f, true, WindowClass.Expanded, false),
                Variant("compact-large-text-checking", 1170, 2532, 3f, 1.5f, true, WindowClass.Compact, true),
            )

            for (variant in variants) {
                val scene = ImageComposeScene(
                    width = variant.width,
                    height = variant.height,
                    density = Density(variant.density, variant.fontScale),
                    coroutineContext = Dispatchers.Unconfined,
                ) {
                    MaterialTheme(colorScheme = if (variant.dark) darkColorScheme() else lightColorScheme()) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            LoginScreen(
                                platform = PlatformInfo(PlatformFamily.MacOS, "macOS 27.0"),
                                windowClass = variant.windowClass,
                                state = LoginState.SignedOut,
                                username = "",
                                password = "",
                                canRememberCredentials = true,
                                rememberCredentials = true,
                                storageReady = true,
                                storageMessage = null,
                                automationMessage = null,
                                onUsernameChange = {},
                                onPasswordChange = {},
                                onRememberCredentialsChange = {},
                                onLogin = {},
                                checkingUpdate = variant.checkingUpdate,
                                onOpenVersionInfo = {},
                                onCheckUpdate = {},
                                onOpenRepository = {},
                            )
                        }
                    }
                }
                try {
                    repeat(3) { scene.render() }
                    val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
                    val output = File("../../.artifacts/login-update-check/${variant.name}.png")
                    output.parentFile.mkdirs()
                    output.writeBytes(bytes)
                    assertTrue(bytes.size > 1000)
                } finally {
                    scene.close()
                }
            }
        }
    }
}

package team.bjtuss.bjtuselfservice.shared

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
internal actual fun platformColorScheme(
    darkTheme: Boolean,
    dynamicColorEnabled: Boolean,
    increasedContrast: Boolean,
): ColorScheme {
    // macOS 没有 Material You 动态取色；开关忽略，仅品牌色 + 高对比。
    return when {
        darkTheme && increasedContrast -> HighContrastDarkColors
        darkTheme -> DarkColors
        increasedContrast -> HighContrastLightColors
        else -> LightColors
    }
}

actual fun platformSupportsDynamicColor(): Boolean = false

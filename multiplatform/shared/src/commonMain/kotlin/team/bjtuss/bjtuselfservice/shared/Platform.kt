package team.bjtuss.bjtuselfservice.shared

enum class PlatformFamily {
    Android,
    IOS,
    MacOS,
}

data class PlatformInfo(
    val family: PlatformFamily,
    val displayName: String,
    /** Desktop target identity kept separate because Windows currently reuses the MacOS family layout. */
    val isWindows: Boolean = false,
    /** 设备型号（如 Pixel 8、iPhone）；ChatGPT 插件配对时作为设备名的一部分。桌面端为空。 */
    val deviceModel: String = "",
) {
    val isMobile: Boolean get() = family == PlatformFamily.Android || family == PlatformFamily.IOS
}

expect fun currentPlatform(): PlatformInfo

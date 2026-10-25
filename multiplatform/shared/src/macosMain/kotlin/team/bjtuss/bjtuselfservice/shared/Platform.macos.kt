package team.bjtuss.bjtuselfservice.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo

@OptIn(ExperimentalForeignApi::class)
actual fun currentPlatform(): PlatformInfo {
    val version = NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        "${majorVersion}.${minorVersion}.${patchVersion}"
    }
    return PlatformInfo(
        family = PlatformFamily.MacOS,
        displayName = "macOS $version",
    )
}

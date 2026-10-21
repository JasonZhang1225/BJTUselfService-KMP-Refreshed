package team.bjtuss.bjtuselfservice.shared

import platform.UIKit.UIDevice

actual fun currentPlatform(): PlatformInfo {
    val device = UIDevice.currentDevice
    return PlatformInfo(
        family = PlatformFamily.IOS,
        displayName = "${device.systemName} ${device.systemVersion}",
        // iOS 16 起 device.name 需要额外授权，只用型号（iPhone / iPad）。
        deviceModel = device.model,
    )
}

package team.bjtuss.bjtuselfservice.shared.logging

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

actual object AppLog {
    // Debug 二进制默认开启；App Store 构建默认关闭，运行期可显式打开。
    @OptIn(ExperimentalNativeApi::class)
    actual var enabled: Boolean = Platform.isDebugBinary

    actual fun d(tag: String, message: String) {
        if (enabled) println("[$tag] $message")
    }
}

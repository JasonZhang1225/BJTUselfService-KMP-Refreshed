package team.bjtuss.bjtuselfservice.shared.logging

import android.util.Log

actual object AppLog {
    // 默认关闭；宿主 App 在 debug 构建中显式开启（见 androidApp MainActivity）。
    actual var enabled: Boolean = false

    actual fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, message)
    }
}

package team.bjtuss.bjtuselfservice.shared.logging

actual object AppLog {
    // 桌面端默认输出到 stdout，便于源码运行排查；正式分发包可关闭。
    actual var enabled: Boolean = true

    actual fun d(tag: String, message: String) {
        if (enabled) println("[$tag] $message")
    }
}

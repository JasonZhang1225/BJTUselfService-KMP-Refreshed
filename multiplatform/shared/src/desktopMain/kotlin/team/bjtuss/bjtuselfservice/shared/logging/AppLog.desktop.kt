package team.bjtuss.bjtuselfservice.shared.logging

actual object AppLog {
    // 正式分发默认关闭。源码排查时显式传 -Dbjtu.debug.logging=true，避免
    // 日后新增日志调用时无意把业务状态写入用户终端或诊断收集。
    actual var enabled: Boolean =
        System.getProperty("bjtu.debug.logging").equals("true", ignoreCase = true)

    actual fun d(tag: String, message: String) {
        if (enabled) println("[$tag] $message")
    }
}

package team.bjtuss.bjtuselfservice.shared.logging

/**
 * 统一调试日志出口，替代散落各处的 println。
 * 调用方必须自行脱敏（不记录账号、Cookie、正文、文件名）；本层只负责开关与输出。
 * release 构建默认关闭（见各平台 actual 的默认值），运行期可再显式开启。
 */
expect object AppLog {
    var enabled: Boolean

    fun d(tag: String, message: String)
}

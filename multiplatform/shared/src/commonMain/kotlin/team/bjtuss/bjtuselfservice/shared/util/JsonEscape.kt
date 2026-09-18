package team.bjtuss.bjtuselfservice.shared.util

/**
 * 手写 JSON 字符串转义（作业提交回执与课件缓存编解码共用）。
 * 仅用于必须精确控制请求格式的既有协议，不用于新代码。
 */
internal fun String.jsonEscape(): String = buildString {
    this@jsonEscape.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}

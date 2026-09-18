package team.bjtuss.bjtuselfservice.shared.data.phyvlab

import io.ktor.http.Url
import team.bjtuss.bjtuselfservice.shared.logging.AppLog

/**
 * 物理在线的诊断输出只记录粗粒度状态，不记录课程名、查询参数、Cookie、OAuth
 * code/state、Moodle sesskey 或其它账号内容。
 */
internal fun phyVlabDebug(message: String) {
    AppLog.d("phyvlab", message)
}

internal fun safePhyVlabEndpoint(url: String): String = runCatching {
    val parsed = Url(url)
    "${parsed.protocol.name}://${parsed.host}${parsed.encodedPath.ifBlank { "/" }}"
}.getOrElse { "<invalid-endpoint>" }

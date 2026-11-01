package team.bjtuss.bjtuselfservice.shared.domain.homework

import kotlinx.datetime.LocalDate
import team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone
import team.bjtuss.bjtuselfservice.shared.domain.time.endOfDayEpochSeconds

/**
 * 作业筛选“截止日期”切换器的三档，互斥。
 *
 * 持久化仍是旧字段 `hideExpired` 加截止点 `untilEpoch`，档位由二者推出：旧版本写下的
 * `hideExpired` 原样对应“未过期”，旧版本读到新数据也只会退回最接近的“未过期”。
 */
enum class DeadlineScope { ALL, UPCOMING, UNTIL_DATE }

fun deadlineScope(hideExpired: Boolean, untilEpoch: Long?): DeadlineScope = when {
    untilEpoch != null -> DeadlineScope.UNTIL_DATE
    hideExpired -> DeadlineScope.UPCOMING
    else -> DeadlineScope.ALL
}

/**
 * - 全部：不看截止时间。
 * - 未过期：截止时刻晚于现在；没有截止时间的作业保留。
 * - 指定日期：现在到截止点 [untilEpoch]（含）之间截止。截止点是选择那天在当时所在时区的 24:00，
 *   存的是时刻而不是日期，换时区后按新时区显示对应的本地时间；没有截止时间或已过期的不显示。
 */
fun deadlineInScope(dueEpochSeconds: Long?, nowEpochSeconds: Long, hideExpired: Boolean, untilEpoch: Long?): Boolean =
    when (deadlineScope(hideExpired, untilEpoch)) {
        DeadlineScope.ALL -> true
        DeadlineScope.UPCOMING -> dueEpochSeconds == null || dueEpochSeconds > nowEpochSeconds
        DeadlineScope.UNTIL_DATE -> dueEpochSeconds != null && dueEpochSeconds > nowEpochSeconds && dueEpochSeconds <= untilEpoch!!
    }

/** 持久化的日期文本；格式不对按未设置处理。 */
fun parseUntilDate(raw: String?): LocalDate? =
    raw?.trim()?.takeIf(String::isNotEmpty)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** 旧版本按北京时间日期存的“截至某天（含）”换成截止点。 */
fun legacyUntilDateToEpoch(raw: String?): Long? = parseUntilDate(raw)?.let { endOfDayEpochSeconds(it, SchoolTimeZone) }

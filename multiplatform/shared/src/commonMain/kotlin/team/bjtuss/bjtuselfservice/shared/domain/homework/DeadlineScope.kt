package team.bjtuss.bjtuselfservice.shared.domain.homework

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.domain.time.deadlineDisplay

/**
 * 作业筛选“截止日期”切换器的三档，互斥。
 *
 * 持久化仍是旧字段 `hideExpired` 加新字段 `untilDate`，档位由二者推出：旧版本写下的
 * `hideExpired` 原样对应“未过期”，旧版本读到新数据也只会退回最接近的“未过期”。
 */
enum class DeadlineScope { ALL, UPCOMING, UNTIL_DATE }

fun deadlineScope(hideExpired: Boolean, untilDate: LocalDate?): DeadlineScope = when {
    untilDate != null -> DeadlineScope.UNTIL_DATE
    hideExpired -> DeadlineScope.UPCOMING
    else -> DeadlineScope.ALL
}

/**
 * - 全部：不看截止时间。
 * - 未过期：截止时刻晚于现在；没有截止时间的作业保留。
 * - 指定日期：现在到 [untilDate] 当天结束之间截止（含当天，零点截止算前一天，与卡片上的 24:00 一致）；
 *   没有截止时间或已过期的不显示。
 */
fun deadlineInScope(deadline: LocalDateTime?, now: LocalDateTime, hideExpired: Boolean, untilDate: LocalDate?): Boolean =
    when (deadlineScope(hideExpired, untilDate)) {
        DeadlineScope.ALL -> true
        DeadlineScope.UPCOMING -> deadline == null || deadline > now
        DeadlineScope.UNTIL_DATE -> deadline != null && deadline > now && deadlineDisplay(deadline).date <= untilDate!!
    }

private val schoolZone = TimeZone.of("Asia/Shanghai")

/** Unix 秒版本（CITEL、物理在线、聚合列表），日期按北京时间归日。 */
fun deadlineInScope(dueEpochSeconds: Long?, nowEpochSeconds: Long, hideExpired: Boolean, untilDate: LocalDate?): Boolean =
    when (deadlineScope(hideExpired, untilDate)) {
        DeadlineScope.ALL -> true
        DeadlineScope.UPCOMING -> dueEpochSeconds == null || dueEpochSeconds > nowEpochSeconds
        DeadlineScope.UNTIL_DATE -> dueEpochSeconds != null && dueEpochSeconds > nowEpochSeconds &&
            deadlineDisplay(Instant.fromEpochSeconds(dueEpochSeconds).toLocalDateTime(schoolZone)).date <= untilDate!!
    }

/** 持久化的日期文本；格式不对按未设置处理。 */
fun parseUntilDate(raw: String?): LocalDate? =
    raw?.trim()?.takeIf(String::isNotEmpty)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

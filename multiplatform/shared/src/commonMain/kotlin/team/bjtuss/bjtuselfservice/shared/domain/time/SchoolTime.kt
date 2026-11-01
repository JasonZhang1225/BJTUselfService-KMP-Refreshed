package team.bjtuss.bjtuselfservice.shared.domain.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 时区规则：
 * - 学校接口给的日期时间文本都是北京时间，解析成时刻时用 [SchoolTimeZone]；
 * - 显示、“现在”“今天”、按日期归类都用手机当前时区 [displayTimeZone]。
 * 手机在东八区时两者相同，行为与以前一致。
 */
val SchoolTimeZone: TimeZone = TimeZone.of("Asia/Shanghai")

fun displayTimeZone(): TimeZone = TimeZone.currentSystemDefault()

/** 学校的北京时间墙钟换成 [zone] 的墙钟。 */
fun LocalDateTime.schoolToDisplay(zone: TimeZone = displayTimeZone()): LocalDateTime =
    if (zone == SchoolTimeZone) this else toInstant(SchoolTimeZone).toLocalDateTime(zone)

/** 学校的北京时间墙钟对应的 Unix 秒。 */
fun LocalDateTime.schoolEpochSeconds(): Long = toInstant(SchoolTimeZone).epochSeconds

/** Unix 秒在 [zone] 的墙钟。 */
fun epochToLocal(epochSeconds: Long, zone: TimeZone = displayTimeZone()): LocalDateTime =
    Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone)

/** [date] 在 [zone] 当天结束（次日 0 点）的 Unix 秒；“截至某天”的截止点。 */
fun endOfDayEpochSeconds(date: LocalDate, zone: TimeZone = displayTimeZone()): Long =
    date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds

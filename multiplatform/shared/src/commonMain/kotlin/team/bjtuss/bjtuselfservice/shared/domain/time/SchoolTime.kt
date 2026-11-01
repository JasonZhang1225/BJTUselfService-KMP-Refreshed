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

/** [zone] 的墙钟换成北京时间墙钟。 */
fun LocalDateTime.toSchoolTime(zone: TimeZone): LocalDateTime =
    if (zone == SchoolTimeZone) this else toInstant(zone).toLocalDateTime(SchoolTimeZone)

/** 学校的北京时间墙钟对应的 Unix 秒。 */
fun LocalDateTime.schoolEpochSeconds(): Long = toInstant(SchoolTimeZone).epochSeconds

/** Unix 秒在 [zone] 的墙钟。 */
fun epochToLocal(epochSeconds: Long, zone: TimeZone = displayTimeZone()): LocalDateTime =
    Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone)

/** [date] 在 [zone] 当天结束（次日 0 点）的 Unix 秒；“截至某天”的截止点。 */
fun endOfDayEpochSeconds(date: LocalDate, zone: TimeZone = displayTimeZone()): Long =
    date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds

/** 北京时间的今天；课表、教室查询这类按北京时间排的格子用它定位“今天”和当前节次。 */
fun schoolNow(): LocalDateTime = kotlin.time.Clock.System.now().toLocalDateTime(SchoolTimeZone)

private val schoolClock = Regex("""(\d{1,2}):(\d{2})""")

/**
 * 把文字里北京时间的时刻（`08:00–09:50`、`19:00`）换成手机时区，其余文字不动。
 * [schoolDate] 是这些时刻所在的北京日期；换算后不在同一天时，在第一个时刻前注明“前日 ”或“次日 ”。
 * 手机在东八区时原样返回。课表格子仍按北京时间排，只有显示的时刻随手机时区变化。
 */
fun displaySchoolClockText(
    text: String,
    schoolDate: LocalDate,
    zone: TimeZone = displayTimeZone(),
    /** 标注与时刻之间的分隔；窄列里用换行，标注单独占一行。 */
    markerSeparator: String = " ",
    /** 已按手机日期归日的地方（首页周日程）不需要“前日/次日”。 */
    markDayShift: Boolean = true,
): String {
    if (zone == SchoolTimeZone) return text
    var first = true
    return schoolClock.replace(text) { match ->
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return@replace match.value
        val local = LocalDateTime(schoolDate, kotlinx.datetime.LocalTime(hour, minute)).schoolToDisplay(zone)
        val clock = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
        val shift = local.date.toEpochDays() - schoolDate.toEpochDays()
        val marker = if (!first || !markDayShift) "" else when {
            shift < 0 -> "前日$markerSeparator"
            shift > 0 -> "次日$markerSeparator"
            else -> ""
        }
        first = false
        marker + clock
    }
}

/** 课表等页面顶部的说明：手机不在东八区时提示时刻已换算。东八区返回 null。 */
fun schoolTimeConversionNote(zone: TimeZone = displayTimeZone()): String? =
    if (zone == SchoolTimeZone) null else "时间已换算为当地时间（${zone.id}）；课表格子仍按北京时间的星期和节次排列。"

/** 同一周轴只有在各日期的时刻和跨日标记完全一致时才能共用当地时间标签。 */
fun schoolClockTextsAreUniform(
    texts: List<String>,
    schoolDates: List<LocalDate>,
    zone: TimeZone = displayTimeZone(),
): Boolean {
    val first = schoolDates.firstOrNull() ?: return true
    val reference = texts.map { displaySchoolClockText(it, first, zone) }
    return schoolDates.drop(1).all { date ->
        texts.map { displaySchoolClockText(it, date, zone) } == reference
    }
}

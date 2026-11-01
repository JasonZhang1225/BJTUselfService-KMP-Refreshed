package team.bjtuss.bjtuselfservice.shared.domain.exam

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import team.bjtuss.bjtuselfservice.shared.domain.calendar.parseExamCalendarTime
import team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone
import team.bjtuss.bjtuselfservice.shared.domain.time.chineseLabel
import team.bjtuss.bjtuselfservice.shared.domain.time.displayTimeZone
import team.bjtuss.bjtuselfservice.shared.domain.time.schoolToDisplay

data class ExamSchedule(
    val id: Int = 0,
    val examType: String,
    val courseName: String,
    val examTimeAndPlace: String,
    val examStatus: String,
    val detail: String,
)

/**
 * 考试“时间地点”的显示文字。学校给的是北京时间；手机在东八区时原样显示，
 * 否则把日期和起止时刻换成手机时区，地点照旧（例如 `2026/11/15 01:30-03:30 周日 逸夫教学楼YF611`）。
 * 未公布结束时间时只显示开始时间；日历事件的默认时长不用于界面。解析不了时原样显示。
 */
fun displayExamTimeAndPlace(raw: String, zone: TimeZone = displayTimeZone()): String {
    if (zone == SchoolTimeZone) return raw
    val parsed = parseExamCalendarTime(raw) ?: return raw
    val start = LocalDateTime(parsed.date, LocalTime(parsed.startHour, parsed.startMinute)).schoolToDisplay(zone)
    // 沿用学校原文的日期分隔符（`2026/11/15` 或 `2026-11-15`）。
    val separator = if (Regex("\\d{4}/").containsMatchIn(raw)) "/" else "-"
    fun date(value: LocalDate) = "${value.year}$separator${(value.month.ordinal + 1).pad()}$separator${value.day.pad()}"
    fun clock(value: LocalDateTime) = "${value.hour.pad()}:${value.minute.pad()}"
    val endText = if (parsed.hasExplicitEndTime) {
        val end = LocalDateTime(parsed.date, LocalTime(parsed.endHour, parsed.endMinute)).schoolToDisplay(zone)
        "-" + if (end.date == start.date) clock(end) else "${date(end.date)} ${clock(end)}"
    } else ""
    return listOf("${date(start.date)} ${clock(start)}$endText", start.date.dayOfWeek.chineseLabel(), parsed.location)
        .filter(String::isNotBlank).joinToString(" ")
}

/** 考试开始时刻在 [zone] 的日期；用于首页日程归日。 */
fun examDisplayDate(raw: String, zone: TimeZone = displayTimeZone()): LocalDate? =
    parseExamCalendarTime(raw)?.let { LocalDateTime(it.date, LocalTime(it.startHour, it.startMinute)).schoolToDisplay(zone).date }

private fun Int.pad(): String = toString().padStart(2, '0')

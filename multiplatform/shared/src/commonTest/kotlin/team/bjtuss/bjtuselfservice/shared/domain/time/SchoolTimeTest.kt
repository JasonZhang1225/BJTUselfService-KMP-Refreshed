package team.bjtuss.bjtuselfservice.shared.domain.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

class SchoolTimeTest {
    private val monday = LocalDate(2026, 11, 2)
    private val newYork = TimeZone.of("America/New_York")
    private val london = TimeZone.of("Europe/London")

    @Test fun beijingPhoneKeepsTextUnchanged() {
        assertEquals("第一节\n08:00–09:50", displaySchoolClockText("第一节\n08:00–09:50", monday, SchoolTimeZone))
        assertNull(schoolTimeConversionNote(SchoolTimeZone))
    }

    @Test fun periodLabelsConvertAndMarkTheDayShift() {
        // 北京 08:00 = 纽约前一天 19:00；北京 21:00 = 纽约当天 08:00；伦敦同一天 00:00。
        assertEquals("第一节\n前日 19:00–20:50", displaySchoolClockText("第一节\n08:00–09:50", monday, newYork))
        assertEquals("7\n08:00", displaySchoolClockText("7\n21:00", monday, newYork))
        assertEquals("第一节\n00:00–01:50", displaySchoolClockText("第一节\n08:00–09:50", monday, london))
        assertEquals("次日 01:00", displaySchoolClockText("20:00", monday, TimeZone.of("Pacific/Auckland")))
        assertEquals("没有时刻的文字", displaySchoolClockText("没有时刻的文字", monday, newYork))
    }

    @Test fun sharedAxisRequiresIdenticalClocksOnEveryDateAndEverySlot() {
        val week = (26..31).map { LocalDate(2026, 10, it) } + LocalDate(2026, 11, 1)
        // 北京周日 08:00 尚未回拨，14:10 已回拨，不能只比较早课或日界线的偏移。
        assertEquals("前日 20:00", displaySchoolClockText("08:00", week.last(), newYork))
        assertEquals("01:10", displaySchoolClockText("14:10", week.last(), newYork))
        assertEquals(true, schoolClockTextsAreUniform(listOf("08:00"), week, newYork))
        assertEquals(false, schoolClockTextsAreUniform(listOf("08:00", "14:10"), week, newYork))
        assertEquals(true, schoolClockTextsAreUniform(listOf("08:00", "14:10"), week, SchoolTimeZone))
        assertEquals(true, schoolClockTextsAreUniform(listOf("14:10"), week.take(5), newYork))
        val springWeek = (2..8).map { LocalDate(2026, 3, it) }
        assertEquals(false, schoolClockTextsAreUniform(listOf("19:00"), springWeek, newYork))
    }

    @Test fun conversionHelpersRoundTrip() {
        val beijing = LocalDateTime(2026, 11, 2, 8, 0)
        assertEquals(LocalDateTime(2026, 11, 1, 19, 0), beijing.schoolToDisplay(newYork))
        assertEquals(beijing, LocalDateTime(2026, 11, 1, 19, 0).toSchoolTime(newYork))
        assertEquals(beijing, epochToLocal(beijing.schoolEpochSeconds(), SchoolTimeZone))
    }
}

package team.bjtuss.bjtuselfservice.shared.domain.exam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import team.bjtuss.bjtuselfservice.shared.domain.home.examDate
import team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone

class ExamTimeZoneTest {
    private val raw = "2026/11/15 14:30-16:30 逸夫教学楼YF611"

    @Test fun beijingPhoneShowsSchoolTextUnchanged() {
        assertEquals(raw, displayExamTimeAndPlace(raw, SchoolTimeZone))
        assertEquals(LocalDate(2026, 11, 15), examDisplayDate(raw, SchoolTimeZone))
    }

    @Test fun otherTimeZonesShowConvertedTimesAndKeepTheRoom() {
        // 北京 14:30 = 纽约 01:30（同一天）；北京 14:30 = 洛杉矶前一天 22:30，结束跨到当天。
        assertEquals("2026/11/15 01:30-03:30 周日 逸夫教学楼YF611",
            displayExamTimeAndPlace(raw, TimeZone.of("America/New_York")))
        assertEquals("2026/11/14 22:30-2026/11/15 00:30 周六 逸夫教学楼YF611",
            displayExamTimeAndPlace(raw, TimeZone.of("America/Los_Angeles")))
        assertEquals(LocalDate(2026, 11, 14), examDisplayDate(raw, TimeZone.of("America/Los_Angeles")))
        val exam = ExamSchedule(examType = "期中", courseName = "课程", examTimeAndPlace = raw, examStatus = "", detail = "")
        assertEquals(LocalDate(2026, 11, 14), examDate(exam, TimeZone.of("America/Los_Angeles")))
    }

    @Test fun unparsableTextIsShownAsIs() {
        assertEquals("第18周 教室", displayExamTimeAndPlace("第18周 教室", TimeZone.UTC))
    }
}

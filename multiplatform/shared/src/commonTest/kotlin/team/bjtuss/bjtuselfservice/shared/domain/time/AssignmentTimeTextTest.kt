package team.bjtuss.bjtuselfservice.shared.domain.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class AssignmentTimeTextTest {
    @Test
    fun startKeepsMidnightAndShowsWeekday() {
        assertEquals("2026年10月14日 00:00 · 周三", assignmentStartText("2026年10月14日 00:00"))
        assertEquals("2026年09月28日 00:00 · 周一", assignmentStartText("2026-09-28 00:00"))
        assertEquals("2026年09月28日 00:00 · 周一", assignmentStartText("2026-09-28 00:00:00"))
    }

    @Test
    fun deadlineMidnightShowsAsPreviousDay2400() {
        assertEquals("2026年10月19日 24:00 · 周一", assignmentDeadlineText("2026年10月20日 00:00"))
        assertEquals("2026年10月19日 24:00 · 周一", assignmentDeadlineText("2026-10-20 00:00"))
        assertEquals("2026年10月28日 23:59 · 周三", assignmentDeadlineText("2026-10-28 23:59"))
    }

    @Test
    fun missingValueIsUnspecified() {
        assertEquals("未指定", assignmentStartText(null as String?))
        assertEquals("未指定", assignmentStartText(""))
        assertEquals("未指定", assignmentStartText("  "))
        assertEquals("未指定", assignmentDeadlineText(null as String?))
        assertEquals("未指定", assignmentDeadlineText(""))
    }

    @Test
    fun unparseableValueIsKeptAsIs() {
        assertEquals("未公布", assignmentDeadlineText("未公布"))
    }

    @Test
    fun epochValuesUseBeijingTime() {
        // 北京时间 2026-10-28 23:59 = UTC 15:59。
        val deadline = Instant.parse("2026-10-28T15:59:00Z").epochSeconds
        assertEquals("2026年10月28日 23:59 · 周三", assignmentDeadlineText(deadline))
        // 北京时间 2026-10-14 00:00 = UTC 2026-10-13 16:00；开始时间不前移。
        val start = Instant.parse("2026-10-13T16:00:00Z").epochSeconds
        assertEquals("2026年10月14日 00:00 · 周三", assignmentStartText(start))
        assertEquals("未指定", assignmentStartText(null as Long?))
    }
}

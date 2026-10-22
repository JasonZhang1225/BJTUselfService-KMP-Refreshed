package team.bjtuss.bjtuselfservice.shared.feature.home

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetDay
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetSession
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetSnapshot

class LiveActivityPlanTest {
    private val day = LocalDate(2026, 10, 12)

    private fun session(name: String, start: Int, end: Int, kind: String = "required") =
        CourseWidgetSession(name, "思源西楼 SX101", "教师", start, end, color = 0x4F6FD8, kind = kind)

    private val snapshot = CourseWidgetSnapshot(
        generatedAt = 1L,
        days = listOf(
            CourseWidgetDay(
                day.toString(),
                sessions = listOf(
                    session("高等数学", 480, 590),
                    session("线性代数", 610, 720),
                    session("物理实验", 800, 950, kind = "lab"),
                ),
            ),
        ),
    )
    private val today = snapshot.liveActivityTargets(day)

    private fun at(hour: Int, minute: Int) = LocalDateTime(2026, 10, 12, hour, minute)

    private fun plan(
        now: LocalDateTime,
        manualId: String? = null,
        fallback: LiveActivityTarget? = null,
        dismissed: Set<String> = emptySet(),
        lead: Int = 10,
        running: Boolean = false,
    ) = planLiveActivity(today, now, manualId, fallback, dismissed, lead, running)

    @Test fun snapshotTargetsUseCardIdFormat() {
        assertEquals("2026-10-12|480|590|高等数学", today[0].id)
        assertEquals("lab", today[2].kind)
        assertTrue(snapshot.liveActivityTargets(LocalDate(2026, 10, 13)).isEmpty())
    }

    @Test fun appearsLeadMinutesBeforeStart() {
        assertNull(plan(at(7, 49)).target)
        assertEquals("高等数学", plan(at(7, 50)).target?.name)
        assertEquals("高等数学", plan(at(7, 40), lead = 20).target?.name)
        assertEquals("线性代数", plan(at(7, 50)).next?.name)
    }

    @Test fun handsOffAcrossShortBreakOnlyWhileRunning() {
        // 9:50 下课，10:10 上课：没在显示时按 10 分钟出现；已在显示时课间 20 分钟直接接上。
        assertNull(plan(at(9, 55)).target)
        assertEquals("线性代数", plan(at(9, 55), running = true).target?.name)
        // 12:00 下课到 13:20 实验，课间 80 分钟超过接力窗口，收起。
        assertNull(plan(at(12, 10), running = true).target)
    }

    @Test fun dismissedItemDoesNotReappear() {
        val dismissed = setOf(today[0].id)
        assertNull(plan(at(8, 0), dismissed = dismissed).target)
    }

    @Test fun manualStartWinsEarlyAndExpiresAfterEnd() {
        val lab = today[2]
        assertEquals(lab, plan(at(8, 0), manualId = lab.id).target)
        val ended = plan(at(15, 51), manualId = lab.id)
        assertNull(ended.target)
        assertTrue(ended.manualExpired)
        assertFalse(plan(at(8, 0), manualId = lab.id).manualExpired)
    }

    @Test fun manualFallbackWhenSnapshotLacksItem() {
        val extra = today[0].copy(id = "2026-10-12|1140|1250|临时", name = "临时", startMinute = 1140, endMinute = 1250)
        assertEquals("临时", plan(at(18, 0), manualId = extra.id, fallback = extra).target?.name)
    }

    @Test fun phaseAndStatusText() {
        val lab = today[2]
        assertEquals("即将开始", lab.statusText(at(13, 0)))
        assertEquals("实验中", lab.statusText(at(13, 20)))
        assertEquals("实验结束", lab.statusText(at(15, 50)))
        assertEquals(LiveActivityPhase.FINISHED, lab.phase(LocalDateTime(2026, 10, 13, 0, 0)))
    }

    @Test fun nextBoundaryWalksLeadStartEndThenMidnight() {
        assertEquals(at(7, 50), liveActivityNextBoundary(today, at(7, 0), 10))
        assertEquals(at(8, 0), liveActivityNextBoundary(today, at(7, 50), 10))
        assertEquals(at(9, 50), liveActivityNextBoundary(today, at(8, 0), 10))
        assertEquals(LocalDateTime(2026, 10, 13, 0, 0), liveActivityNextBoundary(today, at(16, 0), 10))
    }

    @Test fun pruneKeepsTodayAndLater() {
        val ids = setOf("2026-10-11|480|590|旧", "2026-10-12|480|590|今", "2026-10-13|480|590|明")
        assertEquals(setOf("2026-10-12|480|590|今", "2026-10-13|480|590|明"), pruneLiveActivityDismissed(ids, day))
    }
}

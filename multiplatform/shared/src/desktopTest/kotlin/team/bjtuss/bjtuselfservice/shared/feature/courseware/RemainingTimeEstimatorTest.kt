package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlin.test.*

class RemainingTimeEstimatorTest {
    @Test fun warmsUpSmoothsBurstsAndHidesStalledEstimate() {
        var time = 0L
        val eta = RemainingTimeEstimator { time }
        time = 1000
        assertNull(eta.sample(100.0, 900.0))
        for (second in 2..10) {
            time = second * 1000L
            eta.sample(second * 100.0, 10000.0 - second * 100)
        }
        time = 11000
        val burst = assertNotNull(eta.sample(2100.0, 7900.0))
        assertTrue(burst > 40, "一秒突增不能把剩余时间压到瞬时速度的 8 秒")
        time = 22000
        assertNull(eta.sample(2100.0, 7900.0), "长时间停顿时不显示过期估计")
    }

    @Test fun compositionUsesItsOwnProgressAndClock() {
        var time = 0L
        val eta = RemainingTimeEstimator { time }
        for (second in 1..10) {
            time = second * 1000L
            val fraction = second / 100.0
            val seconds = eta.sample(fraction, 1 - fraction)
            if (second == 10) assertTrue(assertNotNull(seconds) in 89..91)
        }
        val task = PlaybackTask("test", "", "", emptyList(), true,
            phase = PlaybackTaskPhase.COMPOSITING, progress = .637f, stageProgress = .1f, remainingSeconds = 90)
        assertEquals("合成剩余约 2 分钟 · 随后保存文件", task.remainingLabel)
    }

    @Test fun progressiveWallClockUsesMaxWhenEncodingOverlaps() {
        // 无预估下限：仍取 max(下载, 合成)
        assertEquals(120L, progressiveWallClockSeconds(60, 120, provisionalEncodeSeconds = null, encodingOverlapsDownload = true))
        assertEquals(60L, progressiveWallClockSeconds(60, null, null, encodingOverlapsDownload = true))
        // 有预估：早期乐观合成 ETA 不能压过按进度折算的下限（900×(1-0)=900）
        assertEquals(900L, progressiveWallClockSeconds(60, 120, provisionalEncodeSeconds = 900, encodingOverlapsDownload = true, stageProgress = 0.0))
        // 过半后下限折半；若实测合成更慢则跟实测
        assertEquals(450L, progressiveWallClockSeconds(30, 200, provisionalEncodeSeconds = 900, encodingOverlapsDownload = true, stageProgress = 0.5))
        assertEquals(500L, progressiveWallClockSeconds(30, 500, provisionalEncodeSeconds = 900, encodingOverlapsDownload = true, stageProgress = 0.5))
    }

    @Test fun progressiveWallClockAddsProvisionalWhenEncodeFollowsDownload() {
        assertEquals(960L, progressiveWallClockSeconds(60, null, provisionalEncodeSeconds = 900, encodingOverlapsDownload = false))
        assertEquals(150L, progressiveWallClockSeconds(50, 100, provisionalEncodeSeconds = 900, encodingOverlapsDownload = false))
        assertEquals(900L, progressiveWallClockSeconds(null, null, provisionalEncodeSeconds = 900, encodingOverlapsDownload = false))
        assertNull(progressiveWallClockSeconds(null, null, null, encodingOverlapsDownload = false))
    }

    @Test fun macProvisionalFloorMatchesGuiLessonScale() {
        // 7022s 片长 / 5.4 ≈ 1301；早期 stage≈0 时剩余下限约 22 分钟量级，不应落到 13–15。
        val full = kotlin.math.ceil(7022.0 / 5.4).toLong()
        assertTrue(full in 1290L..1310L)
        val early = progressiveWallClockSeconds(
            downloadSeconds = 100,
            compositionSeconds = 805, // 早期按 headless 偏快外推
            provisionalEncodeSeconds = full,
            encodingOverlapsDownload = true,
            stageProgress = 0.01,
        )
        assertNotNull(early)
        assertTrue(early!! >= 1200L, "early ETA remaining=$early should stay near ~20min floor, not ~13min")
    }

    @Test fun pipeliningLabelMarksPostDownloadEncodeWhileStageIdle() {
        val pending = PlaybackTask("t", "", "", emptyList(), true,
            phase = PlaybackTaskPhase.PIPELINING, stageProgress = 0f, remainingSeconds = 960, progressive = true)
        assertEquals("预计剩余约 16 分钟（含下载后合成）", pending.remainingLabel)
        val overlapping = PlaybackTask("t", "", "", emptyList(), true,
            phase = PlaybackTaskPhase.PIPELINING, stageProgress = 0.2f, remainingSeconds = 120, progressive = true)
        assertEquals("预计剩余约 2 分钟（下载与合成并行）", overlapping.remainingLabel)
    }
}

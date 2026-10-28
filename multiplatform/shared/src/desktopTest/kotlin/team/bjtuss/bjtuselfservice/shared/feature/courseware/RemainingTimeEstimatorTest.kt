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
}

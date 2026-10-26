package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadSpeedMeterTest {
    @Test fun measuresElapsedTimeAndFallsToZeroWhenStalled() {
        var now = 0L
        val meter = DownloadSpeedMeter { now }
        meter.add(4_000_000)
        assertNull(meter.sample(), "首片突发不除以接近零的时间")
        now = 1000
        assertEquals(4_000_000, meter.sample())
        now = 2000
        meter.add(2_000_000)
        assertEquals(3_000_000, meter.sample())
        for (second in 3..7) { now = second * 1000L; meter.sample() }
        assertEquals(0, meter.sample(), "断流后速度必须归零")
    }
}

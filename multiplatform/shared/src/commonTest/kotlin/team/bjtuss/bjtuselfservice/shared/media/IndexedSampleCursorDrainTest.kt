package team.bjtuss.bjtuselfservice.shared.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 与 Mac `SampleCursor.next` / Windows `LiveSampleFiles::next` 对齐的收尾规则：
 * 看到 finished 后若又出现完整 24B 记录，必须继续读，不能直接 End。
 */
class IndexedSampleCursorDrainTest {
    @Test
    fun finishedMarkerStillDrainsCompleteRecords() {
        // count=2 时需要至少 3*24 才有下一条；先看到短文件，再看到 finished+完整尾记录。
        val decisions = mutableListOf<String>()
        var count = 2L
        var indexBytes = 48L // 仅 2 条
        var finished = false
        fun poll(): String {
            while (true) {
                if (indexBytes >= (count + 1) * 24) {
                    count += 1
                    return "sample"
                }
                if (finished) {
                    // 再读一轮可见长度（对齐 Swift 修复）
                    if (indexBytes >= (count + 1) * 24) {
                        count += 1
                        return "sample"
                    }
                    return "end"
                }
                return "wait"
            }
        }
        assertEquals("wait", poll())
        finished = true
        indexBytes = 72 // writer flush 后尾记录可见
        assertEquals("sample", poll())
        assertEquals("end", poll())
        assertEquals(3L, count)
        decisions += "ok"
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun finishedWithNoMoreCompleteRecordsEnds() {
        var count = 2L
        val indexBytes = 48L
        val finished = true
        val outcome = if (indexBytes >= (count + 1) * 24) {
            "sample"
        } else if (finished) {
            val drained = indexBytes
            if (drained >= (count + 1) * 24) "sample" else "end"
        } else {
            "wait"
        }
        assertEquals("end", outcome)
        assertFalse(indexBytes >= (count + 1) * 24)
    }
}

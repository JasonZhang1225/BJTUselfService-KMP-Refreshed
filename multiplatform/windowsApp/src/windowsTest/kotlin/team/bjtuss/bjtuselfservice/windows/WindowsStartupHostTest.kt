package team.bjtuss.bjtuselfservice.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsStartupHostTest {
    @Test fun taskManagerToggleIsRespected() {
        assertTrue(startupApprovedEnabled(null), "没有记录视为启用")
        assertTrue(startupApprovedEnabled(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertFalse(startupApprovedEnabled(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)), "任务管理器里禁用")
        assertTrue(startupApprovedEnabled(byteArrayOf(6, 0, 0, 0)))
        assertFalse(startupApprovedEnabled(byteArrayOf(7, 0, 0, 0)))
    }

    @Test fun commandQuotesChinesePath() {
        assertEquals("\"C:\\Program Files\\交大自由行 KMP\\交大自由行 KMP.exe\" --background",
            autostartCommand("C:\\Program Files\\交大自由行 KMP\\交大自由行 KMP.exe"))
    }
}

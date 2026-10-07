package team.bjtuss.bjtuselfservice.shared.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeIdleStatusTest {
    @Test fun waitingSourceCannotShowTheSuccessCheckmark() {
        assertEquals("等待同步", homeIdleStatusText(false, false, false, false,
            hasAnySource = true, hasWaitingSource = true))
    }
    @Test
    fun homeFailureIsFullSyncFailureEvenIfChildrenSucceeded() {
        assertEquals(
            "同步失败",
            homeIdleStatusText(
                homeFailed = true,
                homeworkFailed = false,
                examFailed = false,
                courseFailed = false,
                hasAnySource = true,
            ),
        )
    }

    @Test
    fun onlyHomeworkFailureIsPartial() {
        assertEquals(
            "部分同步失败",
            homeIdleStatusText(
                homeFailed = false,
                homeworkFailed = true,
                examFailed = false,
                courseFailed = false,
                hasAnySource = true,
            ),
        )
    }

    @Test
    fun onlyPhyVlabFailureIsPartial() {
        assertEquals(
            "部分同步失败",
            homeIdleStatusText(
                homeFailed = false,
                homeworkFailed = false,
                examFailed = false,
                courseFailed = false,
                phyVlabFailed = true,
                hasAnySource = true,
            ),
        )
    }

    @Test
    fun noFailureWithSourceIsSynced() {
        assertEquals(
            "已同步",
            homeIdleStatusText(
                homeFailed = false,
                homeworkFailed = false,
                examFailed = false,
                courseFailed = false,
                hasAnySource = true,
            ),
        )
    }

    @Test
    fun noFailureWithoutSourceIsUnsynced() {
        assertEquals(
            "未同步",
            homeIdleStatusText(
                homeFailed = false,
                homeworkFailed = false,
                examFailed = false,
                courseFailed = false,
                hasAnySource = false,
            ),
        )
    }
}

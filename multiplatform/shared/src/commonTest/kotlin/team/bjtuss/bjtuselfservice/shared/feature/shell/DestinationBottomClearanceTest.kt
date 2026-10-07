package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class DestinationBottomClearanceTest {
    @Test fun androidRootMeetsBarWithoutAnExtraBackgroundStrip() {
        assertEquals(104.dp, destinationBottomClearance(false, true, 104.dp, 24.dp, contentGap = 0.dp))
        assertEquals(104.dp, destinationBottomClearance(false, true, 0.dp, 24.dp, contentGap = 0.dp))
        assertEquals(24.dp, destinationBottomClearance(false, false, 104.dp, 24.dp, contentGap = 0.dp))
    }

    @Test fun rootReservesCompleteBarFrameOnce() {
        assertEquals(122.dp, destinationBottomClearance(false, true, 114.dp, 34.dp))
        assertEquals(88.dp, destinationBottomClearance(false, true, 80.dp, 34.dp))
    }

    @Test fun secondaryAndUnpinnedPagesReserveOnlySystemSafeArea() {
        assertEquals(34.dp, destinationBottomClearance(false, false, 114.dp, 34.dp))
    }

    @Test fun expandedWindowHasSidebarAndNoBottomBar() {
        assertEquals(0.dp, destinationBottomClearance(true, true, 114.dp, 34.dp))
        assertEquals(0.dp, destinationBottomClearance(true, false, 114.dp, 34.dp))
    }

    @Test fun safeAreaIsFallbackBeforeHostMeasuresBar() {
        assertEquals(122.dp, destinationBottomClearance(false, true, 0.dp, 34.dp))
        assertEquals(88.dp, destinationBottomClearance(false, true, (-4).dp, (-8).dp))
        assertEquals(0.dp, destinationBottomClearance(false, false, (-4).dp, (-8).dp))
    }
}

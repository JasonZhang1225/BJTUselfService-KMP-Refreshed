package team.bjtuss.bjtuselfservice.shared.feature.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhyVlabAutoSyncTest {
    @Test
    fun sharedShellStartsPhysicalOnlineSync() {
        assertTrue(
            shouldStartPhyVlabAutoSync(
                forcedRouteId = null,
                nativeTabBarEnabled = false,
            ),
        )
    }

    @Test
    fun liquidHomeTabStartsTheSharedPhysicalOnlineSync() {
        assertTrue(
            shouldStartPhyVlabAutoSync(
                forcedRouteId = AppSection.HOME.name,
                nativeTabBarEnabled = true,
            ),
        )
    }

    @Test
    fun otherLiquidTabsDoNotStartASecondPhysicalOnlineSync() {
        assertFalse(
            shouldStartPhyVlabAutoSync(
                forcedRouteId = AppSection.SCHEDULE.name,
                nativeTabBarEnabled = true,
            ),
        )
    }

    @Test
    fun pushedRoutesDoNotRefreshTheWholePhysicalOnlineSnapshot() {
        assertFalse(
            shouldStartPhyVlabAutoSync(
                forcedRouteId = AppSection.HOMEWORK.name,
                nativeTabBarEnabled = false,
            ),
        )
    }
}

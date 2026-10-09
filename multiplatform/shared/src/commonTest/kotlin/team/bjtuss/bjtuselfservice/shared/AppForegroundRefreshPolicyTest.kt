package team.bjtuss.bjtuselfservice.shared

import kotlin.test.*

class AppForegroundRefreshPolicyTest {
    @Test fun briefFocusChangesAndRepeatedActiveEventsDoNotRefresh() {
        var now = 0L
        val policy = AppForegroundRefreshPolicy(nowMillis = { now })
        assertFalse(policy.foreground())
        policy.background()
        now = 899_999
        assertFalse(policy.foreground())
        assertFalse(policy.foreground())
        policy.background()
        now += 1
        assertFalse(policy.foreground())
    }

    @Test fun fifteenMinutesInBackgroundRefreshesOnceAndDuplicateInactiveDoesNotResetTimer() {
        var now = 0L
        val policy = AppForegroundRefreshPolicy(nowMillis = { now })
        policy.background()
        now = 800_000
        policy.background()
        now = 900_000
        assertTrue(policy.foreground())
        assertFalse(policy.foreground())
        policy.background()
        now += 1_000_000
        assertTrue(policy.foreground())
        assertFalse(policy.foreground())
    }
    @Test fun windowsOnlyRefreshesOnNextClickAndRepeatedClicksResetIdleTime() {
        var now = 0L
        val policy = AppIdleClickRefreshPolicy(nowMillis = { now })
        now = 899_999
        assertFalse(policy.clicked())
        now += 899_999
        assertFalse(policy.clicked())
        // No callback or timer runs during the idle interval.
        now += 900_000
        assertTrue(policy.clicked())
        assertFalse(policy.clicked())
        now += 1_000_000
        assertTrue(policy.clicked())
    }
}

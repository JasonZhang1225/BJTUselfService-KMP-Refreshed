package team.bjtuss.bjtuselfservice.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthenticatedSessionRouteTest {
    @Test
    fun mailboxRootAndDetailsArePlatformNativeRoutes() {
        assertTrue(isNativeDetailRoute("MAILBOX"))
        assertTrue(isNativeDetailRoute("MAILBOX_DETAIL"))
        assertTrue(isNativeDetailRoute("MAILBOX_COMPOSE"))
        assertTrue(isNativeDetailRoute("PHYVLAB_DETAIL"))
        assertTrue(isNativeDetailRoute("PHYSICS_LAB_SETTINGS"))
        assertFalse(isNativeDetailRoute("UNKNOWN_ROUTE"))
    }
}

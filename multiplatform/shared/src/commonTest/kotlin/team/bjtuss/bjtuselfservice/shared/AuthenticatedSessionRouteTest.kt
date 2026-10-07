package team.bjtuss.bjtuselfservice.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthenticatedSessionRouteTest {
    @Test
    fun everyApplicationEntryCanOpenAfterRemovingItFromTheBottomBar() {
        val tabs = listOf(team.bjtuss.bjtuselfservice.shared.feature.shell.AppSection.HOME,
            team.bjtuss.bjtuselfservice.shared.feature.shell.AppSection.MORE)
        team.bjtuss.bjtuselfservice.shared.feature.shell.AppSection.entries.forEach { section ->
            if (team.bjtuss.bjtuselfservice.shared.feature.shell.shouldOpenNativeSectionRoute(section.name, true, tabs)) {
                assertTrue(isNativeDetailRoute(section.name), "Rejected application entry: ${section.name}")
            }
        }
        assertFalse(isNativeDetailRoute("HOME"))
        assertFalse(isNativeDetailRoute("MORE"))
    }
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

package team.bjtuss.bjtuselfservice.shared.feature.shell

import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences

class NewMigrationNavigationTest {
    @Test fun fixedEntriesAndLimitSurviveMalformedSavedConfiguration() {
        val preferences = AppPreferences(bottomNavigationItems = listOf("HOME", "MAILBOX", "MAILBOX", "UNKNOWN", "CLASSROOMS", "EXAMS", "COURSEWARE", "SETTINGS", "GRADES", "MORE"))
        val tabs = bottomNavSections(preferences)
        assertEquals(listOf(AppSection.HOME, AppSection.MAILBOX, AppSection.EXAMS, AppSection.COURSEWARE, AppSection.SETTINGS, AppSection.MORE), tabs)
        assertTrue(applicationSections(preferences).none { it in tabs })
        assertFalse(AppSection.CLASSROOMS in applicationSections(preferences))
    }
    @Test fun emptySelectionMeansTwoTabsAndEveryOtherFeatureInApplications() {
        val preferences = AppPreferences(bottomNavigationItems = emptyList())
        assertEquals(listOf(AppSection.HOME, AppSection.MORE), bottomNavSections(preferences))
        assertTrue(AppSection.SCHEDULE in applicationSections(preferences))
        assertTrue(AppSection.GRADES in applicationSections(preferences))
        assertTrue(shouldOpenNativeSectionRoute("GRADES", true, bottomNavSections(preferences)))
    }
    @Test fun optionalPhysicsFeaturesFollowIndependentMasterSwitches() {
        val enabled = AppPreferences(bottomNavigationItems = emptyList(), physicsLabEnabled = true)
        val sections = applicationSections(enabled)
        assertEquals(sections.indexOf(AppSection.PHYVLAB) + 1, sections.indexOf(AppSection.PHYSICS_LAB))
        val offline = enabled.copy(autoSyncPhyVlab = false, showPhyVlabInBottomNav = false)
        assertFalse(AppSection.PHYVLAB in applicationSections(offline))
        assertTrue(AppSection.PHYSICS_LAB in applicationSections(offline))
        val disabled = offline.copy(physicsLabEnabled = false)
        assertFalse(AppSection.PHYSICS_LAB in applicationSections(disabled))
        assertFalse(AppSection.PHYSICS_LAB in bottomNavSections(disabled.copy(bottomNavigationItems = listOf("PHYSICS_LAB"))))
        val pinned = enabled.copy(bottomNavigationItems = listOf("PHYSICS_LAB"))
        assertTrue(AppSection.PHYSICS_LAB in bottomNavSections(pinned))
        assertFalse(AppSection.PHYSICS_LAB in applicationSections(pinned))
        assertFalse(shouldOpenNativeSectionRoute("PHYSICS_LAB", true, bottomNavSections(pinned)))
        assertTrue(shouldOpenNativeSectionRoute("PHYSICS_LAB", true, bottomNavSections(enabled)))
    }
    @Test fun configuredMailboxBecomesTabWhileUnpinnedScheduleBecomesSecondaryRoute() {
        val tabs = bottomNavSections(AppPreferences(bottomNavigationItems = listOf("MAILBOX")))
        assertFalse(shouldOpenNativeSectionRoute("MAILBOX", true, tabs))
        assertTrue(shouldOpenNativeSectionRoute("SCHEDULE", true, tabs))
    }
}

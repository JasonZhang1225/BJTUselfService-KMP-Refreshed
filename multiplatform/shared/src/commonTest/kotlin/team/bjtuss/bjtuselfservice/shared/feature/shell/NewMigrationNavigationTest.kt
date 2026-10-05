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
    @Test fun physicsLabEntrySitsWithPhysicalOnlineRatherThanAfterSettings() {
        val sections = applicationSections(AppPreferences())
        val index = physicsLabApplicationIndex(sections)
        assertTrue(index < sections.lastIndex)
        assertEquals(AppSection.SETTINGS, sections.last())
        assertTrue(sections[index].ordinal > AppSection.PHYVLAB.ordinal)
        assertTrue(sections.take(index).all { it.ordinal < AppSection.PHYVLAB.ordinal })
    }
    @Test fun configuredMailboxBecomesTabWhileUnpinnedScheduleBecomesSecondaryRoute() {
        val tabs = bottomNavSections(AppPreferences(bottomNavigationItems = listOf("MAILBOX")))
        assertFalse(shouldOpenNativeSectionRoute("MAILBOX", true, tabs))
        assertTrue(shouldOpenNativeSectionRoute("SCHEDULE", true, tabs))
    }
}

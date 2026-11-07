package team.bjtuss.bjtuselfservice.shared.update

import kotlin.test.*

class BundledChangelogTest {
    @Test fun versionsRemainDistinctIncludingTestAndDebugBuilds() {
        val entries = BundledChangelog.entries
        assertEquals(entries.size, entries.map { it.tag }.distinct().size)
        assertEquals("v2.0.0-KMP", entries.first().tag)
        assertTrue(entries.first().label.contains("草稿"))
        assertTrue(entries.any { it.tag == "v1.8.6-KMP" })
        assertTrue(entries.any { it.tag == "v1.8.5-EmergencyFix2" })
    }

    @Test fun everyVersionHasThreeReadableSections() {
        BundledChangelog.entries.forEach { release ->
            assertEquals(listOf("新增", "优化", "修复"), release.sections.map { it.title }, release.tag)
            assertTrue(release.sections.all { section -> section.items.isNotEmpty() && section.items.all { it.body.isNotBlank() } })
            val texts = release.sections.flatMap { it.items }.flatMap { listOfNotNull(it.title, it.body) }
            assertFalse(texts.any { "**" in it || "`" in it })
            assertTrue(texts.all { it.isNotBlank() })
        }
    }

    @Test fun boldLeadInBecomesItemTitle() {
        val replay = BundledChangelog.entries.first().sections.first().items.first()
        assertEquals("课程回放", replay.title)
        assertFalse(replay.body.startsWith("课程回放"))
    }
}

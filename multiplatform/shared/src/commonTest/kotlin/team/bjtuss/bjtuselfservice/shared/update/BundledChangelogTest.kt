package team.bjtuss.bjtuselfservice.shared.update

import kotlin.test.*

class BundledChangelogTest {
    @Test fun versionsRemainDistinctIncludingTestAndDebugBuilds() {
        val entries = BundledChangelog.entries
        assertEquals(entries.size, entries.map { it.tag }.distinct().size)
        assertEquals("v2.0.0-KMP", entries.first().tag)
        assertTrue(entries.first().label.contains("草稿"))
        assertTrue(entries.single { it.tag == "debug-1.8.5-EmergencyFix2" }.label.contains("调试"))
        assertTrue(entries.single { it.tag == "v1.0.0-gitee-test" }.label.contains("测试"))
        assertTrue(entries.any { it.tag == "v1.8.5-EmergencyFix2" })
    }

    @Test fun everyVersionHasThreeReadableSections() {
        BundledChangelog.entries.forEach { release ->
            assertEquals(listOf("新增", "优化", "修复"), release.sections.map { it.title }, release.tag)
            assertTrue(release.sections.all { section -> section.items.isNotEmpty() && section.items.all { it.isNotBlank() } })
            assertFalse(release.sections.flatMap { it.items }.any { "**" in it || "`" in it })
        }
    }
}

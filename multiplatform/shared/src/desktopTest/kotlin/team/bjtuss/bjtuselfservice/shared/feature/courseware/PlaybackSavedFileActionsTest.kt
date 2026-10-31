package team.bjtuss.bjtuselfservice.shared.feature.courseware

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSavedFileActionsTest {
    @Test fun opensExactUnicodePathAndParentWithoutCopyingAndHandlesMissingFile() = runBlocking {
        val root = Files.createTempDirectory("playback-open-test-").toFile()
        try {
            val video = File(root, "课程 & 测试（1）.mp4").apply { writeText("preserve original") }
            val openedVideos = mutableListOf<File>()
            val revealed = mutableListOf<File>()
            val openedDirs = mutableListOf<File>()
            val actions = DesktopPlaybackSavedFileActions(
                openVideo = { openedVideos += it },
                revealVideo = { revealed += it },
                openDirectory = { openedDirs += it },
            )
            assertNull(actions.open(video.absolutePath, false))
            assertNull(actions.open(video.absolutePath, true))
            assertEquals(listOf(video), openedVideos)
            assertEquals(listOf(video), revealed)
            assertTrue(openedDirs.isEmpty())
            assertEquals("preserve original", video.readText())
            video.delete()
            assertNotNull(actions.open(video.absolutePath, false))
            assertNull(actions.open(video.absolutePath, true), "视频移动后仍可打开原文件夹")
            assertEquals(listOf(root), openedDirs)
            assertNotNull(actions.open("relative.mp4", false))
            assertNotNull(actions.open(File(root, "not-video.exe").absolutePath, false))
            assertEquals(1, openedVideos.size)
            assertEquals(1, revealed.size)
            video.writeText("preserve original")
            assertNotNull(DesktopPlaybackSavedFileActions(
                openVideo = { error("no file association") },
            ).open(video.absolutePath, false))
            Unit
        } finally { root.deleteRecursively() }
    }

    @Test fun desktopActionsAreEnabledOnAllDesktopTargets() {
        assertNotNull(playbackSavedFileActions())
    }

    @Test fun completedOutputPathsSurviveHistoryRoundTripAndOldRecordsRemainReadable() {
        val task = PlaybackTask("t123-0", "课程", "课节", listOf("教师"), false,
            phase = PlaybackTaskPhase.DONE, outputs = listOf("课堂.mp4"), outputPaths = listOf("D:\\录像\\课堂.mp4"))
        val json = encodePlaybackTaskRecords(listOf(StoredPlaybackTask(task)))
        assertEquals(task.outputPaths, decodePlaybackTaskRecords(json).single().task.outputPaths)
        val old = json.replace("\"outputPaths\":[\"D:\\\\录像\\\\课堂.mp4\"],", "")
        assertTrue(decodePlaybackTaskRecords(old).single().task.outputPaths.isEmpty())
    }
}

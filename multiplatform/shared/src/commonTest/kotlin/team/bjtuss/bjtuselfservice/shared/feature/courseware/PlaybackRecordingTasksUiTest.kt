package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Bugbot #81：移动端关闭下载后遗留任务仍须露出任务 UI。 */
class PlaybackRecordingTasksUiTest {
    @Test
    fun desktopDownloadAvailableAlwaysShowsControls() {
        assertTrue(playbackRecordingTasksUiVisible(downloadAvailable = true, tasks = emptyList()))
    }

    @Test
    fun mobileHidesControlsWhenNoLeftoverTasks() {
        assertFalse(playbackRecordingTasksUiVisible(downloadAvailable = false, tasks = emptyList()))
    }

    @Test
    fun mobileShowsControlsForLeftoverFailedTask() {
        val leftover = PlaybackTask(
            id = "t1-0",
            courseName = "课",
            lessonTitle = "节",
            streamLabels = listOf("教师"),
            composite = false,
            phase = PlaybackTaskPhase.FAILED,
            message = PlaybackTaskManager.MOBILE_RECORDING_DOWNLOAD_DISABLED_MESSAGE,
        )
        assertTrue(playbackRecordingTasksUiVisible(downloadAvailable = false, tasks = listOf(leftover)))
    }
}

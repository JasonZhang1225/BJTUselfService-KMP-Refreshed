package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Windows 合成小程序的定位与释放。
 *
 * 小程序以资源形式放在 jar 里（`native/BJTUPlaybackCompositor.exe`），运行时释放到
 * `%LOCALAPPDATA%\BJTUselfServiceKMP\tools\`；这里只测"找得到 / 放得下"这部分，
 * 真机合成由 `BJTUPlaybackCompositor.exe` 自己负责。
 */
class WindowsPlaybackCompositorTest {

    @Test
    fun helperIsWrittenWithContentDigestAndOldVersionsAreRemoved() {
        val directory = Files.createTempDirectory("bjtu-helper-test").toFile()
        try {
            val first = assertNotNull(writeHelperFile(directory, byteArrayOf(1, 2, 3)))
            assertTrue(first.name.startsWith("BJTUPlaybackCompositor-"), first.name)
            assertTrue(first.name.endsWith(".exe"), first.name)
            assertContentEquals(byteArrayOf(1, 2, 3), first.readBytes())

            // 同样的内容再写一次：还是同一个文件，不会重复释放。
            val again = assertNotNull(writeHelperFile(directory, byteArrayOf(1, 2, 3)))
            assertEquals(first.absolutePath, again.absolutePath)

            // 内容变了就是新文件，旧的那份顺手清掉，目录里只留当前版本。
            val second = assertNotNull(writeHelperFile(directory, byteArrayOf(4, 5, 6)))
            assertFalse(first.absolutePath == second.absolutePath, "不同内容要用不同文件名")
            assertFalse(first.exists(), "旧版本应当被清掉")
            assertContentEquals(byteArrayOf(4, 5, 6), second.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun systemPropertyOverridesTheHelperLocation() {
        val directory = Files.createTempDirectory("bjtu-helper-prop").toFile()
        try {
            val helper = File(directory, "custom-helper.exe").apply { writeBytes(byteArrayOf(7)) }
            val previous = System.getProperty("bjtu.playback.compositor.helper")
            System.setProperty("bjtu.playback.compositor.helper", helper.absolutePath)
            try {
                assertEquals(helper.absolutePath, assertNotNull(locateHelper()).absolutePath)
            } finally {
                if (previous == null) {
                    System.clearProperty("bjtu.playback.compositor.helper")
                } else {
                    System.setProperty("bjtu.playback.compositor.helper", previous)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingHelperIsReportedInsteadOfThrowing() {
        // 共享层自己的测试环境里没有随包分发的小程序（它在 windowsApp 的 jar 里），
        // 这时要返回 null：合成功能降级为「两路分别保存」，不能让整个 App 起不来。
        if (WindowsPlaybackCompositor::class.java.getResource("/native/BJTUPlaybackCompositor.exe") == null) {
            assertNull(locateHelper())
            assertFalse(WindowsPlaybackCompositor.isAvailable)
        }
    }
}

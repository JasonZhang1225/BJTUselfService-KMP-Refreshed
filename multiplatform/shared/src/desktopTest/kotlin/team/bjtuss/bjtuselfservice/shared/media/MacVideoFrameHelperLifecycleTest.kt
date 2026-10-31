package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.feature.courseware.locateVideoFrameHelper

/** macOS helper 在父进程退出后必须自行结束，避免源码实例强退后残留编码。 */
class MacVideoFrameHelperLifecycleTest {
    private fun findHelper(): File? {
        locateVideoFrameHelper()?.let { return it }
        val roots = listOf(
            File("../desktopApp/build/generated/video-frame/BJTUVideoFrameHelper"),
            File("../desktopApp/build/compose/binaries/main/app/BJTUselfServiceKMP.app/Contents/Resources/VideoFrame/BJTUVideoFrameHelper"),
        )
        return roots.map { it.canonicalFile }.firstOrNull { it.canExecute() }
    }

    @Test
    fun helperExitsWhenWatchedParentDies() {
        val helper = findHelper() ?: return
        assertTrue(helper.canExecute())
        val root = Files.createTempDirectory("mac-helper-lifecycle-").toFile().canonicalFile
        try {
            // 生产路径 compose-indexed：空索引目录会让 helper 一直等输入，存活状态与机器快慢无关。
            val left = File(root, "mac-input-0").also { check(it.mkdirs()) }
            val right = File(root, "mac-input-1").also { check(it.mkdirs()) }
            val out = File(root, "out.mp4")
            val parent = ProcessBuilder("sleep", "3600").start()
            val child = ProcessBuilder(
                helper.absolutePath,
                "compose-indexed",
                left.absolutePath,
                right.absolutePath,
                out.absolutePath,
                "640",
                "180",
                "400000",
                "--duration",
                "60",
                "--parent-pid",
                parent.pid().toString(),
            ).start()
            try {
                Thread.sleep(400)
                assertTrue(child.isAlive, "合成应已启动")
                parent.destroyForcibly()
                assertTrue(parent.waitFor(3, TimeUnit.SECONDS))
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "父进程退出后 helper 应在数秒内结束")
                assertFalse(child.isAlive)
            } finally {
                if (child.isAlive) child.destroyForcibly()
                if (parent.isAlive) parent.destroyForcibly()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

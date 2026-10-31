package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.feature.courseware.locateVideoFrameHelper

/**
 * Bugbot #81：compose-indexed 读索引/样本失败必须非 0 退出，不能当正常结束。
 * 无 helper 的 Linux CI 直接跳过。
 */
class MacIndexedComposeErrorTest {
    private fun findHelper(): File? {
        locateVideoFrameHelper()?.let { return it }
        val roots = listOf(
            File("../desktopApp/build/generated/video-frame/BJTUVideoFrameHelper"),
            File("../desktopApp/build/compose/binaries/main/app/BJTUselfServiceKMP.app/Contents/Resources/VideoFrame/BJTUVideoFrameHelper"),
        )
        return roots.map { it.canonicalFile }.firstOrNull { it.canExecute() }
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/media/$name")).use { it.readBytes() }

    @Test
    fun composeIndexedFailsWhenSamplePayloadIsTruncated() = runBlocking {
        val helper = findHelper() ?: return@runBlocking
        assertTrue(helper.canExecute())
        val root = Files.createTempDirectory("mac-indexed-corrupt-").toFile().canonicalFile
        try {
            val left = File(root, "mac-input-0").also { check(it.mkdirs()) }
            val right = File(root, "mac-input-1").also { check(it.mkdirs()) }
            val leftIn = WindowsIndexedTsInput(left, needsAudio = true)
            val rightIn = WindowsIndexedTsInput(right, needsAudio = false)
            leftIn.append(fixture("synthetic-0.ts"))
            leftIn.append(fixture("synthetic-1.ts"))
            leftIn.finish()
            rightIn.append(fixture("synthetic-0.ts"))
            rightIn.append(fixture("synthetic-1.ts"))
            rightIn.finish()
            // 截断样本文件：索引仍指向完整 size，读取端应得到 .error 并失败退出。
            val samples = File(left, "samples.data")
            assertTrue(samples.isFile && samples.length() > 64)
            samples.writeBytes(samples.readBytes().copyOf(samples.length().toInt() / 2))

            val out = File(root, "out.mp4")
            val err = File(root, "stderr.txt")
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
                "4",
            ).redirectError(err).redirectOutput(File(root, "stdout.txt")).start()
            assertTrue(child.waitFor(120, TimeUnit.SECONDS), "helper timeout")
            assertNotEquals(0, child.exitValue(), "corrupt sample must fail; stderr=${err.readText()}")
        } finally {
            root.deleteRecursively()
        }
    }
}

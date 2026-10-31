package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 本机调试：对真实 8s TS 跑 compose-indexed，产物留在
 * `.artifacts/macos-playback-p1/indexed-debug/`。
 */
class MacIndexedRealClipSmokeTest {
    private fun findHelper(): File? {
        locateCandidates().firstOrNull { it.canExecute() }?.let { return it }
        return null
    }

    private fun locateCandidates(): List<File> = listOf(
        File("../desktopApp/build/generated/video-frame/BJTUVideoFrameHelper"),
        File("../desktopApp/build/compose/binaries/main/app/BJTUselfServiceKMP.app/Contents/Resources/VideoFrame/BJTUVideoFrameHelper"),
    ).map { it.canonicalFile }

    @Test
    fun composesRealEightSecondClips() = runBlocking {
        val helper = findHelper() ?: return@runBlocking
        val debug = File("../../.artifacts/macos-playback-p1/indexed-debug").canonicalFile
        val prefer60 = File(debug, "teacher-60s.ts").isFile && File(debug, "screen-60s.ts").isFile
        val teacher = File(debug, if (prefer60) "teacher-60s.ts" else "teacher-8s.ts")
        val screen = File(debug, if (prefer60) "screen-60s.ts" else "screen-8s.ts")
        if (!teacher.isFile || !screen.isFile) return@runBlocking
        val duration = if (prefer60) 60 else 8
        val tag = if (prefer60) "real-60s" else "real-8s"

        val root = File(debug, "work-$tag").also {
            it.deleteRecursively()
            check(it.mkdirs())
        }
        val left = File(root, "mac-input-0").also { check(it.mkdirs()) }
        val right = File(root, "mac-input-1").also { check(it.mkdirs()) }
        val leftIn = WindowsIndexedTsInput(left, needsAudio = true)
        val rightIn = WindowsIndexedTsInput(right, needsAudio = false)
        leftIn.append(teacher.readBytes()); leftIn.finish()
        rightIn.append(screen.readBytes()); rightIn.finish()

        val out = File(debug, "$tag.mp4")
        val err = File(debug, "$tag.stderr.txt")
        val stdout = File(debug, "$tag.stdout.txt")
        val started = System.nanoTime()
        val child = ProcessBuilder(
            helper.absolutePath, "compose-indexed",
            left.absolutePath, right.absolutePath, out.absolutePath,
            "1280", "360", "1200000", "--duration", duration.toString(),
        ).redirectError(err).redirectOutput(stdout).start()
        assertTrue(child.waitFor(600, TimeUnit.SECONDS), "helper timeout")
        val wallMs = (System.nanoTime() - started) / 1_000_000L
        File(debug, "$tag.wall-ms.txt").writeText(wallMs.toString())
        assertEquals(0, child.exitValue(), "stderr=${err.readText()} stdout=${stdout.readText()}")
        assertTrue(out.isFile && out.length() > 50_000, "output too small: ${out.length()}")
        System.err.println("mac-indexed-$tag wallMs=$wallMs stderr=${err.readText().trim()}")
    }
}

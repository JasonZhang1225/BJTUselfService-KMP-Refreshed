package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality

class WindowsIndexedTsInputTest {
    @Test fun publishesCompleteSamplesBeforeTheInputFinishes() = runBlocking {
        val directory = Files.createTempDirectory("windows-indexed-test-").toFile().canonicalFile
        val input = WindowsIndexedTsInput(directory, needsAudio = true)
        try {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/media/synthetic-0.ts")).readBytes()
            input.append(bytes)
            assertTrue(input.videoReady)
            assertTrue(input.audioReady)
            assertFalse(File(directory, "finished").exists())
            val data = File(directory, "samples.data").readBytes()
            val video = File(directory, "video.idx").readBytes()
            val audio = File(directory, "audio.idx").readBytes()
            assertTrue(video.size >= 24 && video.size % 24 == 0)
            assertTrue(audio.size >= 48 && audio.size % 24 == 0)
            fun records(raw: ByteArray): List<Triple<Long, Long, Int>> {
                val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                return List(raw.size / 24) {
                    val offset = buffer.long; val pts = buffer.long; val size = buffer.int; buffer.int
                    assertTrue(offset >= 0 && size > 0 && offset + size <= data.size)
                    Triple(offset, pts, size)
                }
            }
            val videos = records(video)
            val audios = records(audio)
            assertEquals(0L, videos.first().second)
            assertTrue(videos.zipWithNext().all { (a, b) -> b.second > a.second })
            assertEquals(1024L * 10_000_000 / 48000, audios[1].second - audios[0].second)
            input.finish()
            assertTrue(File(directory, "finished").isFile)
        } finally {
            input.close()
            check(directory.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile && directory.name.startsWith("windows-indexed-test-"))
            directory.deleteRecursively()
        }
    }

    /** 可选本机端到端探针：真实 TS 增量写入并由 Windows 系统合成器读取。 */
    @Test fun composesWhileCapturedInputsAreStillArrivingWhenConfigured() = runBlocking {
        val rootPath = System.getenv("BJTU_WINDOWS_PROGRESSIVE_FIXTURE")?.takeIf(String::isNotBlank) ?: return@runBlocking
        val helper = System.getenv("BJTU_WINDOWS_PROGRESSIVE_HELPER")?.takeIf(String::isNotBlank) ?: return@runBlocking
        val fixture = File(rootPath).canonicalFile
        require(fixture.name == "android-full-acceptance" && fixture.parentFile.name == ".artifacts")
        val outputRoot = File(fixture.parentFile, "windows-gpu-playback").canonicalFile
        require(outputRoot.parentFile == fixture.parentFile && outputRoot.name == "windows-gpu-playback")
        val output = Files.createTempDirectory(outputRoot.toPath(), "kotlin-progressive-160-").toFile().canonicalFile
        require(output.parentFile == outputRoot)
        val previous = System.getProperty("bjtu.playback.compositor.helper")
        System.setProperty("bjtu.playback.compositor.helper", File(helper).canonicalPath)
        val session = WindowsProgressiveComposition(MediaWorkDirectory(Path(output.absolutePath)), 160.005, PlaybackCompositeQuality.HIGH)
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        val overlap = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            withTimeout(120_000) {
                coroutineScope {
                    val writers = listOf("teacher-160.ts", "screen-160.ts").mapIndexed { index, name ->
                        async(Dispatchers.IO) {
                            File(fixture, name).inputStream().buffered().use { input ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    session.append(index, buffer.copyOf(count))
                                    delay(20)
                                }
                            }
                            session.finishInput(index)
                            finished.incrementAndGet()
                        }
                    }
                    session.compose({ progress -> if (progress > 0f && finished.get() < 2) overlap.set(true) }, {}, {})
                    writers.forEach { it.await() }
                }
            }
            assertTrue(overlap.get(), "输入尚未全部到达时应已开始编码")
            assertTrue(File(output, "composite.mp4").length() > 0)
            println("progressive fixture output=${output.absolutePath}")
            session.close()
            // 成片已完成时重新打开不需要解码器和网络。
            System.setProperty("bjtu.playback.compositor.helper", File(output, "missing.exe").absolutePath)
            val restored = WindowsProgressiveComposition(MediaWorkDirectory(Path(output.absolutePath)), 160.005, PlaybackCompositeQuality.HIGH)
            assertTrue(restored.isComplete)
            restored.compose({}, {}, {})
            restored.close()
        } finally {
            withContext(Dispatchers.IO) { session.close() }
            if (previous == null) System.clearProperty("bjtu.playback.compositor.helper")
            else System.setProperty("bjtu.playback.compositor.helper", previous)
        }
    }
}

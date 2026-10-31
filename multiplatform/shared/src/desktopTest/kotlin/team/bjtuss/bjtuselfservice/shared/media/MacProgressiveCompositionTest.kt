package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.feature.courseware.locateVideoFrameHelper

class MacProgressiveCompositionTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/media/$name")).use { it.readBytes() }

    /** 与生命周期测试同一套查找：打包 Resources 或 Gradle 生成物。 */
    private fun findHelper(): File? {
        locateVideoFrameHelper()?.let { return it }
        val roots = listOf(
            File("../desktopApp/build/generated/video-frame/BJTUVideoFrameHelper"),
            File("../desktopApp/build/compose/binaries/main/app/BJTUselfServiceKMP.app/Contents/Resources/VideoFrame/BJTUVideoFrameHelper"),
        )
        return roots.map { it.canonicalFile }.firstOrNull { it.canExecute() }
    }

    @Test
    fun macDefaultCompositorIsProgressive() {
        val compositor = defaultVideoCompositor()
        assertTrue(compositor is ProgressiveVideoCompositor)
        assertEquals(false, compositor.retainsEncodedSegmentsOnInterrupt)
        assertEquals(true, compositor.encodingOverlapsDownload)
    }

    @Test
    fun remuxesParallelStreamsIntoMp4Inputs() = runBlocking {
        val root = Files.createTempDirectory("mac-progressive-remux-").toFile().canonicalFile
        try {
            val work = MediaWorkDirectory(Path(root.absolutePath))
            coroutineScope {
                val left = async {
                    MacRemuxedTsInput(work, 0).also { input ->
                        try {
                            input.append(fixture("synthetic-0.ts"))
                            input.append(fixture("synthetic-1.ts"))
                            input.finish()
                        } finally {
                            input.closeQuietly()
                        }
                    }
                }
                val right = async {
                    MacRemuxedTsInput(work, 1).also { input ->
                        try {
                            input.append(fixture("synthetic-0.ts"))
                            input.append(fixture("synthetic-1.ts"))
                            input.finish()
                        } finally {
                            input.closeQuietly()
                        }
                    }
                }
                left.await()
                right.await()
            }
            listOf(0, 1).forEach { index ->
                val mp4 = java.io.File(root, "s$index.mp4")
                assertTrue(mp4.isFile && mp4.length() > 32, "s$index.mp4 missing")
                val head = mp4.inputStream().use { it.readNBytes(8) }
                assertEquals("ftyp", head.copyOfRange(4, 8).decodeToString())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun progressiveSessionComposesWhenHelperPresent() = runBlocking {
        val helper = findHelper() ?: return@runBlocking
        assertTrue(helper.canExecute())
        val previous = System.getProperty("bjtu.video-frame.helper")
        System.setProperty("bjtu.video-frame.helper", helper.absolutePath)
        val artifacts = File("../../.artifacts/macos-playback-p1/indexed-smoke").canonicalFile
        Files.createDirectories(artifacts.toPath())
        val root = Files.createTempDirectory(artifacts.toPath(), "session-").toFile().canonicalFile
        val stageSeen = AtomicInteger(0)
        try {
            val session = MacProgressiveComposition(
                MediaWorkDirectory(Path(root.absolutePath)),
                durationSeconds = 4.0,
                PlaybackCompositeQuality.MEDIUM,
            )
            try {
                listOf(0, 1).forEach { index ->
                    session.append(index, fixture("synthetic-0.ts"))
                    session.append(index, fixture("synthetic-1.ts"))
                    session.finishInput(index)
                }
                withTimeoutOrNull(60_000) {
                    session.compose(
                        onProgress = { stage -> stageSeen.updateAndGet { maxOf(it, (stage * 1000).toInt()) } },
                        onStatus = {},
                        onFinalizing = {},
                        onDevice = {},
                    )
                } ?: error("MacProgressiveComposition 60s 超时；work=$root")
                val output = File(root, "composite.mp4")
                assertTrue(output.isFile && output.length() > 0, "缺成片")
                assertEquals("v1:MEDIUM", File(root, "mac-composite-complete").readText())
                assertTrue(stageSeen.get() > 0, "合成进度应推进")
                assertHasAudioTrack(output)
                artifacts.listFiles()
                    ?.filter { it.isDirectory && it != root && (it.name.startsWith("work-") || it.name.startsWith("session-")) }
                    ?.forEach { it.deleteRecursively() }
            } finally {
                session.close()
            }
        } finally {
            if (previous == null) System.clearProperty("bjtu.video-frame.helper")
            else System.setProperty("bjtu.video-frame.helper", previous)
        }
    }

    /** 慢灌第二片期间若合成进度已 >0，证明编码与输入重叠（真边下边合）。 */
    @Test
    fun progressiveComposeOverlapsSlowSecondSegments() = runBlocking {
        val helper = findHelper() ?: return@runBlocking
        val previous = System.getProperty("bjtu.video-frame.helper")
        System.setProperty("bjtu.video-frame.helper", helper.absolutePath)
        val root = Files.createTempDirectory("mac-indexed-overlap-").toFile().canonicalFile
        val stageDuringInput = AtomicInteger(0)
        try {
            val session = MacProgressiveComposition(
                MediaWorkDirectory(Path(root.absolutePath)),
                durationSeconds = 4.0,
                PlaybackCompositeQuality.MEDIUM,
            )
            try {
                coroutineScope {
                    val compose = async {
                        session.compose(
                            onProgress = { stage ->
                                if (stage > 0f) stageDuringInput.updateAndGet { maxOf(it, (stage * 1000).toInt()) }
                            },
                            onStatus = {},
                            onFinalizing = {},
                            onDevice = {},
                        )
                    }
                    listOf(0, 1).map { index ->
                        async {
                            session.append(index, fixture("synthetic-0.ts"))
                            // 首片已够启动 VT；故意拖延第二片，观察编码是否已推进。
                            delay(1_200)
                            val seen = stageDuringInput.get()
                            session.append(index, fixture("synthetic-1.ts"))
                            session.finishInput(index)
                            seen
                        }
                    }.forEach { it.await() }
                    withTimeoutOrNull(60_000) { compose.await() }
                        ?: error("重叠夹具合成超时")
                }
                assertTrue(stageDuringInput.get() > 0, "第二片到达前合成进度应已 >0，实际 milli=${stageDuringInput.get()}")
                assertTrue(File(root, "composite.mp4").isFile)
                assertHasAudioTrack(File(root, "composite.mp4"))
            } finally {
                session.close()
            }
        } finally {
            if (previous == null) System.clearProperty("bjtu.video-frame.helper")
            else System.setProperty("bjtu.video-frame.helper", previous)
            root.deleteRecursively()
        }
    }

    private fun assertHasAudioTrack(mp4: File) {
        val probe = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "a",
            "-show_entries", "stream=codec_name", "-of", "csv=p=0", mp4.absolutePath,
        ).redirectErrorStream(true).start()
        val out = probe.inputStream.bufferedReader().readText().trim()
        assertTrue(probe.waitFor(30, TimeUnit.SECONDS), "ffprobe 超时")
        assertEquals(0, probe.exitValue(), "ffprobe 失败: $out")
        assertTrue(out.contains("aac"), "成片应含 AAC 音轨，实际: $out")
    }
}

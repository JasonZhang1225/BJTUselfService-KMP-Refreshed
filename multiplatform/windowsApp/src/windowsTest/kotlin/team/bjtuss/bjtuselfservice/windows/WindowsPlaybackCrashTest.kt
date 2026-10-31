package team.bjtuss.bjtuselfservice.windows

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.domain.courseware.*
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.feature.courseware.*
import team.bjtuss.bjtuselfservice.shared.files.*
import team.bjtuss.bjtuselfservice.shared.media.*
import team.bjtuss.bjtuselfservice.shared.network.*

/** 使用独立 JVM 和假媒体，验证强制结束时真实记录、工作目录和目标文件的生命周期。 */
class WindowsPlaybackCrashTest {
    @Test fun killedDuringSavingRestoresAndCleansOnlyOwnedFiles() = runBlocking {
        val root = Files.createTempDirectory("playback-crash-test-").toFile().canonicalFile
        val cp = linkedSetOf<String>()
        fun addLoader(start: ClassLoader?) {
            var loader = start
            while (loader != null) {
                if (loader is URLClassLoader) loader.getURLs().filter { it.protocol == "file" }.forEach { cp += File(it.toURI()).absolutePath }
                loader = loader.parent
            }
        }
        addLoader(javaClass.classLoader)
        addLoader(Thread.currentThread().contextClassLoader)
        cp += System.getProperty("java.class.path").split(File.pathSeparator)
        val javaExecutable = File(System.getProperty("java.home"), "bin/java.exe")
        val log = File(root, "child.log")
        val child = ProcessBuilder(javaExecutable.absolutePath, "-cp", cp.joinToString(File.pathSeparator),
            PlaybackCrashFixture::class.java.name, root.absolutePath)
            .redirectErrorStream(true).redirectOutput(log).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(20_000) { while (!File(root, "ready").isFile && child.isAlive) delay(20) }
            assertTrue(File(root, "ready").isFile, "子进程未进入保存阶段：${log.readText()}")
            child.destroyForcibly()
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            val target = File(root, "user-video.mp4")
            val unrelated = File(root, ".bjtu-save-unrelated.tmp")
            assertEquals("已有视频", target.readText())
            val recovery = WindowsPlaybackRecovery(File(root, "state"))
            val store = recovery.storeFor("crash-test")
            val record = store.load().single()
            assertEquals(PlaybackTaskPhase.SAVING, record.task.phase)
            val temporary = File(root, File(root, "temp-name").readText())
            assertTrue(temporary.isFile)
            assertTrue(File(store.workRoot.toString(), "${record.task.id}/composite.mp4").isFile)
            val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(PlaybackCrashFixture.transport, 0),
                { PlaybackCrashFixture.compositor }, { null }, store, restoreDestination = recovery::restoreDestination)
            withTimeout(5000) { while (manager.tasks.value.isEmpty()) delay(10) }
            assertTrue(manager.tasks.value.single().canResume)
            manager.retry(record.task.id)
            withTimeout(10_000) { while (manager.tasks.value.single().phase != PlaybackTaskPhase.DONE ||
                File(store.workRoot.toString(), record.task.id).exists() || temporary.exists()) delay(10) }
            assertEquals("合成结果", target.readText())
            assertFalse(temporary.exists())
            assertEquals("其他文件", unrelated.readText())
            withTimeout(5000) { while (store.load().single().request != null) delay(10) }
        } finally {
            if (child.isAlive) { child.destroyForcibly(); child.waitFor() }
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
            check(root.parentFile == tempRoot && root.name.startsWith("playback-crash-test-"))
            root.deleteRecursively()
        }
    }
}

/** 子进程只写入测试临时目录，不访问网络或用户账号。 */
object PlaybackCrashFixture {
    val transport = object : SchoolHttpTransport {
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            val body = if (request.url.endsWith(".m3u8"))
                "#EXTM3U\n#EXTINF:2,\nseg-0.ts\n#EXT-X-ENDLIST\n".encodeToByteArray()
            else byteArrayOf(1, 2, 3, 4)
            return SchoolHttpResponse(200, request.url, body = body)
        }
        override fun clearSession() = Unit
    }
    val compositor = object : ProgressiveVideoCompositor {
        override val isAvailable = true
        override suspend fun composeSideBySide(leftPath: String, rightPath: String, outputPath: String,
            quality: PlaybackCompositeQuality, onProgress: (Float) -> Unit, onStatus: (String?) -> Unit) = error("必须使用渐进路径")
        override suspend fun openProgressive(work: MediaWorkDirectory, durationSeconds: Double,
            quality: PlaybackCompositeQuality): ProgressiveComposition = object : ProgressiveComposition {
            private val count = java.util.concurrent.atomic.AtomicInteger()
            private val finished = CompletableDeferred<Unit>()
            override val resumeSeconds = 0.0
            override suspend fun append(index: Int, bytes: ByteArray) = Unit
            override suspend fun finishInput(index: Int) { if (count.incrementAndGet() == 2) finished.complete(Unit) }
            override suspend fun compose(onProgress: (Float) -> Unit, onStatus: (String?) -> Unit,
                onFinalizing: (Float) -> Unit, onDevice: (String?) -> Unit) {
                finished.await()
                File(work.file("composite.mp4").toString()).writeText("合成结果")
            }
            override suspend fun close() = Unit
        }
    }

    @JvmStatic fun main(args: Array<String>) { runBlocking {
        val root = File(args.single()).canonicalFile
        check(root.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile && root.name.startsWith("playback-crash-test-"))
        val target = File(root, "user-video.mp4").apply { writeText("已有视频") }
        File(root, ".bjtu-save-unrelated.tmp").writeText("其他文件")
        val recovery = WindowsPlaybackRecovery(File(root, "state"))
        val store = recovery.storeFor("crash-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = object : HomeworkFileGateway {
            override val isAvailable = true
            override val isStreamingSaveAvailable = true
            override suspend fun pickFiles() = HomeworkFilePickResult.Cancelled
            override suspend fun saveFile(file: HomeworkFileContent) = HomeworkFileSaveResult.Cancelled
            override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult {
                val destination = prepareFileStreamingDestination(target)
                File(root, "temp-name").writeText(destination.temporary.fileName.toString())
                return StreamingDestinationResult.Opened(object : StreamingDestination by destination {
                    override suspend fun write(bytes: ByteArray) {
                        destination.write(bytes)
                        File(root, "ready").writeText("saving")
                        awaitCancellation()
                    }
                })
            }
        }
        val streams = listOf(
            PlaybackStream(PlaybackStreamKind.TEACHER, "https://lmt6.bjtu.edu.cn:8880/vod/a/tea/playlist.m3u8"),
            PlaybackStream(PlaybackStreamKind.SCREEN, "https://lmt6.bjtu.edu.cn:8880/vod/a/vga/playlist.m3u8"))
        val lesson = PlaybackLesson(1, 1, "", "测试课节", "", "", true)
        val detail = PlaybackDetail(lesson, true, streams, streams.map { it.kind }, "")
        val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { compositor }, { null }, store, restoreDestination = recovery::restoreDestination)
        assertIs<PlaybackEnqueueResult.Started>(manager.enqueue(detail, streams, "测试", true, gateway))
        awaitCancellation()
    } }
}

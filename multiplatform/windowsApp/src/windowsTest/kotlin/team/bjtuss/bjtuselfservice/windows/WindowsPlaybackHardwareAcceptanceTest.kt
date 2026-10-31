package team.bjtuss.bjtuselfservice.windows

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.domain.courseware.*
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.feature.courseware.*
import team.bjtuss.bjtuselfservice.shared.files.*
import team.bjtuss.bjtuselfservice.shared.media.ProgressiveVideoCompositor
import team.bjtuss.bjtuselfservice.shared.media.defaultVideoCompositor
import team.bjtuss.bjtuselfservice.shared.network.*

/** 可选本机验收：设置 BJTU_PLAYBACK_HARDWARE_ACCEPT=1，需已有 ffmpeg/ffprobe；不访问学校或正式安装。 */
class WindowsPlaybackHardwareAcceptanceTest {
    @Test fun realHelperQueueHandlesCompletionPauseCancellationAndFailure() = runBlocking {
        assumeTrue("仅在明确启用的 Windows 硬件验收中运行", System.getenv("BJTU_PLAYBACK_HARDWARE_ACCEPT") == "1")
        val project = File(System.getProperty("user.dir")).canonicalFile
        check(project.name == "windowsApp" && project.parentFile.name == "multiplatform")
        val helper = File(project, "build/generated/windows-native/native/BJTUPlaybackCompositor.exe").canonicalFile
        check(helper.isFile)
        val fixtures = File(project.parentFile, "shared/src/desktopTest/resources/media").canonicalFile
        val segments = listOf("synthetic-0.ts", "synthetic-1.ts").map { File(fixtures, it).readBytes() }
        val directory = Files.createTempDirectory("playback-hardware-test-").toFile().canonicalFile
        val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
        check(directory.parentFile == tempRoot && directory.name.startsWith("playback-hardware-test-"))
        val previousHelper = System.getProperty("bjtu.playback.compositor.helper")
        System.setProperty("bjtu.playback.compositor.helper", helper.absolutePath)
        val gate = CompletableDeferred<Unit>()
        val cancelGate = CompletableDeferred<Unit>()
        val failureGate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = object : SchoolHttpTransport {
            override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
                val bytes = when {
                    request.url.endsWith(".m3u8") -> "#EXTM3U\n#EXTINF:2,\nseg-0.ts\n#EXTINF:2,\nseg-1.ts\n#EXT-X-ENDLIST\n".encodeToByteArray()
                    request.url.endsWith("seg-0.ts") -> segments[0]
                    request.url.endsWith("seg-1.ts") -> {
                        when {
                            "/cancel/" in request.url -> cancelGate.await()
                            "/failure/" in request.url -> failureGate.await()
                            else -> gate.await()
                        }
                        segments[1]
                    }
                    else -> error("意外的测试请求")
                }
                return SchoolHttpResponse(200, request.url, body = bytes)
            }
            override fun clearSession() = Unit
        }
        val outputNumber = AtomicInteger()
        val outputDirectory = File(directory, "outputs").apply { mkdir() }
        val unrelated = File(outputDirectory, ".bjtu-save-unrelated.tmp").apply { writeText("保留") }
        val gateway = object : HomeworkFileGateway {
            override val isAvailable = true
            override val isStreamingSaveAvailable = true
            override suspend fun pickFiles() = HomeworkFilePickResult.Cancelled
            override suspend fun saveFile(file: HomeworkFileContent) = HomeworkFileSaveResult.Cancelled
            override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult {
                val index = outputNumber.incrementAndGet()
                val target = File(outputDirectory, "fixture-$index.mp4")
                if (index == 4 || index == 6) target.writeText("已有视频")
                return StreamingDestinationResult.Opened(prepareFileStreamingDestination(target))
            }
        }
        val recovery = WindowsPlaybackRecovery(File(directory, "state"))
        val store = recovery.storeFor("hardware-test")
        val compositor = defaultVideoCompositor()
        assertTrue(compositor is ProgressiveVideoCompositor && compositor.isAvailable)
        val streams = listOf(
            PlaybackStream(PlaybackStreamKind.TEACHER, "https://lmt6.bjtu.edu.cn:8880/vod/a/tea/playlist.m3u8"),
            PlaybackStream(PlaybackStreamKind.SCREEN, "https://lmt6.bjtu.edu.cn:8880/vod/a/vga/playlist.m3u8"))
        val detail = PlaybackDetail(PlaybackLesson(1, 1, "", "模拟课节", "", "", true), true,
            streams, streams.map { it.kind }, "")
        val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { compositor }, { null },
            store, restoreDestination = recovery::restoreDestination)
        try {
            suspend fun enqueue(number: Int): String = assertIs<PlaybackEnqueueResult.Started>(
                manager.enqueue(detail, streams, "模拟课程 $number", true, gateway)).taskId
            val first = enqueue(1)
            val second = enqueue(2)
            val third = enqueue(3)
            val workRoot = File(store.workRoot.toString())
            val firstWork = File(workRoot, first)
            withTimeout(30_000) {
                while (manager.tasks.value.first { it.id == first }.compositionDevice == null) {
                    val task = manager.tasks.value.first { it.id == first }
                    check(task.phase != PlaybackTaskPhase.FAILED) { "原生合成器启动失败：${task.message}" }
                    delay(20)
                }
            }
            assertEquals(PlaybackTaskPhase.QUEUED, manager.tasks.value.first { it.id == second }.phase)
            assertEquals(PlaybackTaskPhase.QUEUED, manager.tasks.value.first { it.id == third }.phase)
            assertFalse(File(workRoot, second).exists())
            assertFalse(File(workRoot, third).exists())
            val child = ProcessHandle.current().descendants().filter { process ->
                process.info().command().orElse("").equals(helper.absolutePath, ignoreCase = true)
            }.findFirst().orElseThrow { IllegalStateException("没有找到本测试启动的原生合成进程") }
            val device = manager.tasks.value.first { it.id == first }.compositionDevice
            manager.pause(first)
            withTimeout(20_000) { while (!manager.tasks.value.first { it.id == first }.canResume) delay(20) }
            assertEquals(PlaybackTaskPhase.PAUSED, manager.tasks.value.first { it.id == first }.phase)
            assertFalse(child.isAlive, "暂停后原生合成进程必须停止")
            assertTrue(File(firstWork, "segments-0/0.ts").isFile)
            assertTrue(File(firstWork, "segments-1/0.ts").isFile)
            assertFalse(File(firstWork, "composite.partial.mp4").exists())
            gate.complete(Unit)
            withTimeout(120_000) {
                while (listOf(second, third).any { id -> manager.tasks.value.first { it.id == id }.phase != PlaybackTaskPhase.DONE ||
                    File(workRoot, id).exists() }) {
                    val failed = manager.tasks.value.firstOrNull { it.id in setOf(second, third) && it.phase == PlaybackTaskPhase.FAILED }
                    check(failed == null) { "排队任务失败：${failed?.message}" }
                    delay(25)
                }
            }
            manager.retry(first)
            withTimeout(120_000) {
                while (manager.tasks.value.first { it.id == first }.phase != PlaybackTaskPhase.DONE || firstWork.exists()) {
                    val task = manager.tasks.value.first { it.id == first }
                    check(task.phase != PlaybackTaskPhase.FAILED) { "恢复失败：${task.message}" }
                    delay(25)
                }
            }
            val cancelStreams = listOf(
                PlaybackStream(PlaybackStreamKind.TEACHER, "https://lmt6.bjtu.edu.cn:8880/vod/cancel/tea/playlist.m3u8"),
                PlaybackStream(PlaybackStreamKind.SCREEN, "https://lmt6.bjtu.edu.cn:8880/vod/cancel/vga/playlist.m3u8"))
            val cancelDetail = detail.copy(streams = cancelStreams)
            val cancelled = assertIs<PlaybackEnqueueResult.Started>(
                manager.enqueue(cancelDetail, cancelStreams, "模拟取消", true, gateway)).taskId
            withTimeout(30_000) {
                while (manager.tasks.value.first { it.id == cancelled }.compositionDevice == null) {
                    val task = manager.tasks.value.first { it.id == cancelled }
                    check(task.phase != PlaybackTaskPhase.FAILED) { "取消测试未启动：${task.message}" }
                    delay(20)
                }
            }
            val cancelChild = ProcessHandle.current().descendants().filter { process ->
                process.info().command().orElse("").equals(helper.absolutePath, ignoreCase = true)
            }.findFirst().orElseThrow { IllegalStateException("没有找到取消测试的原生合成进程") }
            val afterCancel = enqueue(5)
            assertEquals(PlaybackTaskPhase.QUEUED, manager.tasks.value.first { it.id == afterCancel }.phase)
            manager.cancel(cancelled)
            withTimeout(20_000) { while (manager.tasks.value.first { it.id == cancelled }.phase != PlaybackTaskPhase.CANCELLED ||
                File(workRoot, cancelled).exists()) delay(20) }
            assertFalse(cancelChild.isAlive, "取消后原生合成进程必须停止")
            assertEquals("已有视频", File(outputDirectory, "fixture-4.mp4").readText())
            withTimeout(120_000) {
                while (manager.tasks.value.first { it.id == afterCancel }.phase != PlaybackTaskPhase.DONE ||
                    File(workRoot, afterCancel).exists()) {
                    val task = manager.tasks.value.first { it.id == afterCancel }
                    check(task.phase != PlaybackTaskPhase.FAILED) { "取消后的任务失败：${task.message}" }
                    delay(25)
                }
            }
            val failureStreams = listOf(
                PlaybackStream(PlaybackStreamKind.TEACHER, "https://lmt6.bjtu.edu.cn:8880/vod/failure/tea/playlist.m3u8"),
                PlaybackStream(PlaybackStreamKind.SCREEN, "https://lmt6.bjtu.edu.cn:8880/vod/failure/vga/playlist.m3u8"))
            val failureDetail = detail.copy(streams = failureStreams)
            val failed = assertIs<PlaybackEnqueueResult.Started>(
                manager.enqueue(failureDetail, failureStreams, "模拟进程异常", true, gateway)).taskId
            withTimeout(30_000) {
                while (manager.tasks.value.first { it.id == failed }.compositionDevice == null) {
                    val task = manager.tasks.value.first { it.id == failed }
                    check(task.phase != PlaybackTaskPhase.FAILED) { "异常测试未启动：${task.message}" }
                    delay(20)
                }
            }
            val failureChild = ProcessHandle.current().descendants().filter { process ->
                process.info().command().orElse("").equals(helper.absolutePath, ignoreCase = true)
            }.findFirst().orElseThrow { IllegalStateException("没有找到异常测试的原生合成进程") }
            val afterFailure = enqueue(7)
            assertEquals(PlaybackTaskPhase.QUEUED, manager.tasks.value.first { it.id == afterFailure }.phase)
            assertTrue(failureChild.destroyForcibly(), "无法结束本测试启动的合成进程")
            withTimeout(20_000) { while (!manager.tasks.value.first { it.id == failed }.canResume) delay(20) }
            assertEquals(PlaybackTaskPhase.FAILED, manager.tasks.value.first { it.id == failed }.phase)
            val failedWork = File(workRoot, failed)
            assertTrue(File(failedWork, "segments-0/0.ts").isFile)
            assertTrue(File(failedWork, "segments-1/0.ts").isFile)
            assertEquals("已有视频", File(outputDirectory, "fixture-6.mp4").readText())
            withTimeout(120_000) {
                while (manager.tasks.value.first { it.id == afterFailure }.phase != PlaybackTaskPhase.DONE ||
                    File(workRoot, afterFailure).exists()) {
                    val task = manager.tasks.value.first { it.id == afterFailure }
                    check(task.phase != PlaybackTaskPhase.FAILED) { "异常后的任务失败：${task.message}" }
                    delay(25)
                }
            }
            withTimeout(20_000) { while (manager.tasks.value.any { it.id == failed } || failedWork.exists()) {
                manager.dismiss(failed); delay(20)
            } }
            assertEquals("已有视频", File(outputDirectory, "fixture-6.mp4").readText())
            val outputs = listOf(1, 2, 3, 5, 7).map { File(outputDirectory, "fixture-$it.mp4") }
            outputs.forEach { output ->
                assertTrue(output.isFile && output.length() > 0)
                val probe = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height",
                    "-of", "csv=p=0", output.absolutePath).redirectErrorStream(true).start()
                val description = probe.inputStream.bufferedReader().readText()
                assertEquals(0, probe.waitFor())
                assertTrue(description.contains("h264,1920,540") && description.contains("aac"), description)
                val decode = ProcessBuilder("ffmpeg", "-v", "error", "-xerror", "-i", output.absolutePath,
                    "-f", "null", "-").redirectErrorStream(true).start()
                val diagnostics = decode.inputStream.bufferedReader().readText()
                assertTrue(decode.waitFor(30, TimeUnit.SECONDS))
                assertEquals(0, decode.exitValue(), diagnostics)
            }
            assertEquals("保留", unrelated.readText())
            assertEquals((listOf(unrelated.name, "fixture-4.mp4", "fixture-6.mp4") + outputs.map { it.name }).sorted(),
                outputDirectory.listFiles().orEmpty().map { it.name }.sorted())
            assertTrue(workRoot.listFiles().orEmpty().isEmpty(), "所有任务结束后工作根目录不能残留临时文件")
            val pendingRecord = File(checkNotNull(workRoot.parentFile), "tasks.json.new")
            withTimeout(5000) { while (store.load().any { it.request != null } || pendingRecord.exists()) delay(100) }
            println("Windows 原生合成设备：$device；暂停恢复、取消/异常后推进和五项输出的完整解码均通过。")
        } finally {
            gate.complete(Unit)
            cancelGate.complete(Unit)
            failureGate.complete(Unit)
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            if (previousHelper == null) System.clearProperty("bjtu.playback.compositor.helper")
            else System.setProperty("bjtu.playback.compositor.helper", previousHelper)
            check(directory.parentFile == tempRoot && directory.name.startsWith("playback-hardware-test-"))
            directory.deleteRecursively()
        }
    }
}

package team.bjtuss.bjtuselfservice.windows

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.domain.courseware.*
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.feature.courseware.*
import team.bjtuss.bjtuselfservice.shared.files.*
import team.bjtuss.bjtuselfservice.shared.media.*

/** 手工验收界面：仅假媒体和独立测试目录，不加载用户账户、安装版或真实下载。 */
fun main(args: Array<String>) {
    val root = File(args.single()).canonicalFile
    val project = File(System.getProperty("user.dir")).canonicalFile
    check(project.name == "multiplatform")
    val base = File(project.parentFile, ".artifacts/playback-queue-lifecycle").canonicalFile
    check(root.name.startsWith("lifecycle-ui-") && root.parentFile == base)
    check(root.isDirectory || root.mkdirs())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val recovery = WindowsPlaybackRecovery(File(root, "state"))
    val compositor = LifecycleHarnessCompositor()
    val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(PlaybackCrashFixture.transport, 0),
        { compositor }, { null }, recovery.storeFor("visual-fixture"), restoreDestination = recovery::restoreDestination)
    var outputNumber = 0
    val gateway = object : HomeworkFileGateway {
        override val isAvailable = true
        override val isStreamingSaveAvailable = true
        override suspend fun pickFiles() = HomeworkFilePickResult.Cancelled
        override suspend fun saveFile(file: HomeworkFileContent) = HomeworkFileSaveResult.Cancelled
        override suspend fun openStreamingDestination(fileName: String, contentType: String) =
            StreamingDestinationResult.Opened(prepareFileStreamingDestination(File(root, "测试视频-${++outputNumber}.mp4")))
    }
    val streams = listOf(
        PlaybackStream(PlaybackStreamKind.TEACHER, "https://lmt6.bjtu.edu.cn:8880/vod/a/tea/playlist.m3u8"),
        PlaybackStream(PlaybackStreamKind.SCREEN, "https://lmt6.bjtu.edu.cn:8880/vod/a/vga/playlist.m3u8"))
    val detail = PlaybackDetail(PlaybackLesson(1, 1, "", "模拟课节", "", "", true), true, streams, streams.map { it.kind }, "")
    application {
        Window(onCloseRequest = { scope.cancel(); exitApplication() }, title = "回放队列隔离验收") {
            var dark by remember { mutableStateOf(false) }
            var large by remember { mutableStateOf(false) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.35f else 1f)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    val tasks by manager.tasks.collectAsState()
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { scope.launch {
                                    repeat(3) { manager.enqueue(detail, streams, "模拟课程 ${it + 1}", true, gateway) }
                                } }, enabled = tasks.isEmpty()) { Text("添加三个任务") }
                                Button(onClick = { compositor.actions.trySend(true) }) { Text("模拟失败") }
                                Button(onClick = { dark = !dark }) { Text(if (dark) "浅色" else "深色") }
                                Button(onClick = { large = !large }) { Text(if (large) "普通字号" else "大字号") }
                            }
                            PlaybackTasksPage(tasks, manager, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private class LifecycleHarnessCompositor : ProgressiveVideoCompositor {
    val actions = Channel<Boolean>(Channel.UNLIMITED)
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
            onProgress(0.3f)
            if (actions.receive()) throw CompositionException("模拟故障")
            File(work.file("composite.mp4").toString()).writeText("测试视频")
        }
        override suspend fun close() = Unit
    }
}

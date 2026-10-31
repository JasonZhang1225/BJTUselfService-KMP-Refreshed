package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import team.bjtuss.bjtuselfservice.shared.system.DesktopWindowHandle
import team.bjtuss.bjtuselfservice.shared.system.DesktopWindowLifecycle
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStreamKind
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackEnqueueResult
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTaskManager
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTaskPhase
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTasksPage
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePickResult
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestination
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingSinkIoException
import team.bjtuss.bjtuselfservice.shared.media.PlaybackPlatformHooks
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport

/**
 * macOS 隔离验收窗口：不读钥匙串、不碰安装版缓存。
 *
 * - 夹具：`--playback-ui-harness <root> <seg0.ts> <seg1.ts>`
 * - 真实 HLS：`--playback-ui-harness <root> --live <streams.json>`
 *   （streams.json 由探测任务写出；下载走公开 lmt HTTPS，窗口内不再登录）
 *
 * `BJTU_HARNESS_AUTO_START=1` 时自动入队；`BJTU_HARNESS_QUALITY=MEDIUM|HIGH|…` 选清晰度。
 */
fun runDesktopPlaybackUiHarness(args: Array<String>) {
    require(args.isNotEmpty()) { "需要隔离根目录" }
    val root = File(args[0]).canonicalFile
    require(root.name.startsWith("ui-harness") && root.parentFile.name == "macos-playback-p1") {
        "隔离根目录须为 .artifacts/macos-playback-p1/ui-harness*"
    }
    check(root.isDirectory || root.mkdirs())
    val live = args.getOrNull(1) == "--live"
    val prepared = if (live) {
        require(args.size >= 3) { "需要：根目录 --live streams.json" }
        loadLiveHarnessMedia(File(args[2]))
    } else {
        require(args.size >= 3) { "需要：根目录 synthetic-0.ts synthetic-1.ts" }
        loadFixtureHarnessMedia(File(args[1]), File(args[2]))
    }
    val quality = System.getenv("BJTU_HARNESS_QUALITY")
        ?.let { runCatching { PlaybackCompositeQuality.valueOf(it) }.getOrNull() }
        ?: if (live) PlaybackCompositeQuality.MEDIUM else PlaybackCompositeQuality.HIGH
    val autoStart = System.getenv("BJTU_HARNESS_AUTO_START") == "1"
    val exitOnDone = System.getenv("BJTU_HARNESS_EXIT_ON_DONE") == "1"
    val manager = PlaybackTaskManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
        HlsRecordingDownloader(prepared.transport, 0),
        { PlaybackPlatformHooks.effectiveCompositor },
        { null },
    )
    val gateway = HarnessHomeworkGateway(root)
    val phaseLog = File(root, "phase.jsonl")
    phaseLog.writeText("")
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        var previous: String? = null
        manager.tasks.collect { tasks ->
            val task = tasks.firstOrNull() ?: return@collect
            val line = buildString {
                append("ts=").append(System.currentTimeMillis())
                append(" phase=").append(task.phase)
                append(" progressive=").append(task.progressive)
                append(" step=").append(task.step.replace('\n', ' '))
                append(" download=").append(task.downloadProgress)
                append(" stage=").append(task.stageProgress)
                append(" eta=").append(task.remainingSeconds)
                append(" bps=").append(task.bytesPerSecond)
                task.notice?.takeIf { it.isNotBlank() }?.let {
                    append(" notice=").append(it.replace('\n', ' ').replace(' ', '_'))
                }
                task.message?.takeIf { it.isNotBlank() }?.let {
                    append(" message=").append(it.replace('\n', ' ').replace(' ', '_'))
                }
            }
            if (line != previous) {
                previous = line
                phaseLog.appendText(line + "\n")
            }
            if (exitOnDone && task.phase == PlaybackTaskPhase.DONE) {
                delay(1_500)
                kotlin.system.exitProcess(0)
            }
            if (exitOnDone && task.phase == PlaybackTaskPhase.FAILED) {
                delay(500)
                kotlin.system.exitProcess(2)
            }
        }
    }
    val title = if (live) "课程回放 macOS 整节隔离验收（无钥匙串）" else "课程回放 macOS 隔离验收（无钥匙串）"
    application {
        // 与生产 Main 一致：关窗=隐藏，JVM/任务继续；DONE 仍可由 EXIT_ON_DONE 主动 exitProcess。
        val lifecycle = remember { DesktopWindowLifecycle() }
        Window(onCloseRequest = { lifecycle.closeWindow() }, title = title) {
            val windowHandle = remember(window) {
                object : DesktopWindowHandle {
                    override fun hide() {
                        window.isVisible = false
                    }

                    override fun showAndFocus() {
                        window.isVisible = true
                        window.toFront()
                        window.requestFocusInWindow()
                    }
                }
            }
            DisposableEffect(lifecycle, windowHandle) {
                lifecycle.attach(windowHandle)
                onDispose { lifecycle.detach(windowHandle) }
            }
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val tasks by manager.tasks.collectAsState()
                suspend fun start() {
                    val result = manager.enqueue(
                        prepared.detail,
                        prepared.streams,
                        prepared.courseName,
                        true,
                        gateway,
                        quality,
                    )
                    if (result is PlaybackEnqueueResult.Failed) System.err.println(result.message)
                }
                if (autoStart) {
                    LaunchedEffect(Unit) {
                        delay(800)
                        if (manager.tasks.value.none { it.isActive }) start()
                    }
                }
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (live) "真实学校 HLS · 不写钥匙串。清晰度 $quality。关窗隐藏不退出。自动/手动开始后应进入 PIPELINING。"
                        else "夹具媒体 · 不使用钥匙串 / 不登录。关窗隐藏不退出。点开始后应进入 PIPELINING，完成后可打开视频与文件夹。",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { start() } },
                            enabled = tasks.none { it.isActive },
                        ) { Text(if (live) "开始整节下载" else "开始测试下载") }
                    }
                    PlaybackTasksPage(tasks, manager, Modifier.weight(1f))
                }
            }
        }
    }
}

private data class HarnessMedia(
    val transport: SchoolHttpTransport,
    val detail: PlaybackDetail,
    val streams: List<PlaybackStream>,
    val courseName: String,
)

private fun loadFixtureHarnessMedia(seg0File: File, seg1File: File): HarnessMedia {
    val seg0 = seg0File.readBytes()
    val seg1 = seg1File.readBytes()
    val base = "https://lmt6.bjtu.edu.cn:8880/vod/a/"
    val transport = object : SchoolHttpTransport {
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            val segmentDelayMs = System.getenv("BJTU_HARNESS_SEGMENT_DELAY_MS")?.toLongOrNull() ?: 2_000L
            if (request.url.endsWith(".ts") && segmentDelayMs > 0) delay(segmentDelayMs)
            // 每路各一片完整 TS：勿把整段课反复拼进同一索引（PTS 回绕会导致 VT 写入失败）。
            val body = when {
                request.url.contains("/tea/") && request.url.endsWith("/playlist.m3u8") ->
                    "#EXTM3U\n#EXTINF:8,\nseg-0.ts\n#EXT-X-ENDLIST\n".encodeToByteArray()
                request.url.contains("/vga/") && request.url.endsWith("/playlist.m3u8") ->
                    "#EXTM3U\n#EXTINF:8,\nseg-1.ts\n#EXT-X-ENDLIST\n".encodeToByteArray()
                request.url.endsWith("/seg-0.ts") -> seg0
                request.url.endsWith("/seg-1.ts") -> seg1
                else -> null
            }
            return SchoolHttpResponse(if (body == null) 404 else 200, request.url, body = body ?: ByteArray(0))
        }
        override suspend fun executeWithoutRedirects(request: SchoolHttpRequest) = execute(request)
        override fun clearSession() = Unit
    }
    val streams = listOf(
        PlaybackStream(PlaybackStreamKind.TEACHER, "${base}tea/playlist.m3u8"),
        PlaybackStream(PlaybackStreamKind.SCREEN, "${base}vga/playlist.m3u8"),
    )
    return HarnessMedia(
        transport,
        PlaybackDetail(
            PlaybackLesson(1, 1, "隔离验收课节", "测试时间", "", "", true),
            true, streams, streams.map { it.kind }, "",
        ),
        streams,
        "隔离测试课程",
    )
}

private fun loadLiveHarnessMedia(streamsFile: File): HarnessMedia {
    val root = Json.parseToJsonElement(streamsFile.readText()).jsonObject
    val courseName = root.getValue("courseName").jsonPrimitive.content
    val lessonTitle = root.getValue("lessonTitle").jsonPrimitive.content
    val timeRange = root["timeRange"]?.jsonPrimitive?.content.orEmpty()
    val streams = root.getValue("streams").jsonArray.map { item ->
        val data = item.jsonObject
        PlaybackStream(
            PlaybackStreamKind.valueOf(data.getValue("kind").jsonPrimitive.content),
            data.getValue("url").jsonPrimitive.content,
        )
    }
    require(streams.size >= 2) { "整节合成至少需要两路" }
    return HarnessMedia(
        createSchoolHttpTransport(),
        PlaybackDetail(
            PlaybackLesson(1, 1, lessonTitle, timeRange, "", "", true),
            true, streams, streams.map { it.kind }, "",
        ),
        streams,
        courseName,
    )
}

private class HarnessHomeworkGateway(private val root: File) : HomeworkFileGateway {
    override val isAvailable = true
    override val isStreamingSaveAvailable = true
    override suspend fun pickFiles() = HomeworkFilePickResult.Cancelled
    override suspend fun saveFile(file: HomeworkFileContent) = HomeworkFileSaveResult.Cancelled

    override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult =
        withContext(Dispatchers.IO) {
            val target = File(root, fileName.replace(Regex("[\\\\/]"), "_"))
            val temporary = Files.createTempFile(root.toPath(), ".bjtu-save-", ".tmp")
            StreamingDestinationResult.Opened(object : StreamingDestination {
                private var output: java.io.OutputStream? = Files.newOutputStream(temporary)
                override val displayName: String = target.name
                override val outputPath: String = target.absolutePath
                override suspend fun write(bytes: ByteArray) {
                    val stream = output ?: throw StreamingSinkIoException()
                    withContext(Dispatchers.IO) { stream.write(bytes) }
                }
                override suspend fun commit(): HomeworkFileSaveResult = withContext(Dispatchers.IO) {
                    try {
                        output?.close(); output = null
                        Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        HomeworkFileSaveResult.Saved
                    } catch (_: Exception) {
                        HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
                    }
                }
                override suspend fun abort() {
                    withContext(Dispatchers.IO) {
                        runCatching { output?.close() }; output = null
                        runCatching { Files.deleteIfExists(temporary) }
                    }
                }
            })
        }
}

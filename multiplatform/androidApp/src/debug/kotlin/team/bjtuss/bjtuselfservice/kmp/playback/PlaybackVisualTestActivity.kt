package team.bjtuss.bjtuselfservice.kmp.playback

import android.os.Bundle
import android.net.TrafficStats
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import android.util.JsonReader
import java.io.StringReader
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.domain.courseware.*
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.feature.courseware.*
import team.bjtuss.bjtuselfservice.shared.files.*
import team.bjtuss.bjtuselfservice.shared.media.defaultVideoCompositor
import team.bjtuss.bjtuselfservice.shared.network.*
import java.io.File

/** 只在 debug 包中：脱离账号页验证生产播放器、预览和任务卡；不读取学校凭据。 */
class PlaybackVisualTestActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val transport by lazy { createSchoolHttpTransport() as KtorSchoolHttpTransport }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(filesDir, "playback-visual-streams.json")
        if (!file.isFile) { finish(); return }
        val streams: List<PlaybackStream> = JsonReader(StringReader(file.readText())).use { reader ->
            buildList {
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginObject()
                    var kind = ""
                    var url = ""
                    while (reader.hasNext()) when (reader.nextName()) {
                        "kind" -> kind = reader.nextString()
                        "url" -> url = reader.nextString()
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                    add(PlaybackStream(PlaybackStreamKind.valueOf(kind), url))
                }
                reader.endArray()
            }
        }
        var offline = false
        val shortLists = mutableMapOf<String, ByteArray>()
        val testTransport = object : SchoolHttpTransport by transport {
            override suspend fun executePublic(request: SchoolHttpRequest): SchoolHttpResponse {
                if (offline) throw java.io.IOException("debug-network-interruption")
                shortLists[request.url]?.let { return SchoolHttpResponse(200, request.url, body = it) }
                delay(3_000) // 留出稳定的交互窗口，用界面触发中断而不修改模拟器网络设置。
                return transport.executePublic(request)
            }
        }
        val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(testTransport, 1000), { defaultVideoCompositor() }, { null })
        val gateway = object : HomeworkFileGateway {
            override val isAvailable = true
            override val isStreamingSaveAvailable = true
            override suspend fun pickFiles() = HomeworkFilePickResult.Cancelled
            override suspend fun saveFile(file: HomeworkFileContent) = HomeworkFileSaveResult.Cancelled
            override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult {
                val output = File(cacheDir, "visual-$fileName")
                val sink = output.outputStream()
                return StreamingDestinationResult.Opened(object : StreamingDestination {
                    override val displayName = fileName
                    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { sink.write(bytes) }
                    override suspend fun commit(): HomeworkFileSaveResult { sink.close(); return HomeworkFileSaveResult.Saved }
                    override suspend fun abort() { sink.close(); output.delete() }
                })
            }
        }
        setContent {
            var dark by remember { mutableStateOf(false) }
            var large by remember { mutableStateOf(false) }
            var downloads by remember { mutableStateOf(false) }
            var interrupted by remember { mutableStateOf(false) }
            var playing by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf("") }
            var received by remember { mutableLongStateOf(0) }
            val tasks by manager.tasks.collectAsState()
            LaunchedEffect(Unit) { while (true) { received = TrafficStats.getUidRxBytes(android.os.Process.myUid()); delay(500) } }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.4f else 1f)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
                            Text("课程回放 · 调试验证", style = MaterialTheme.typography.titleMedium)
                            Row {
                                TextButton(onClick = { downloads = !downloads; playing = false }) { Text(if (downloads) "画面验证" else "下载验证") }
                                TextButton(onClick = { dark = !dark }) { Text("浅/深色") }
                                TextButton(onClick = { large = !large }) { Text("字号") }
                            }
                            if (downloads) {
                                Text("系统累计接收：${received / 1024} KB", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    Button(onClick = {
                                        scope.launch {
                                            try {
                                                streams.forEach { stream ->
                                                    if (stream.hlsUrl !in shortLists) {
                                                        val list = HlsRecordingDownloader(transport).resolvePlaylist(stream.hlsUrl)
                                                        shortLists[stream.hlsUrl] = ("#EXTM3U\n" + list.segmentUrls.take(16).joinToString("") { "#EXTINF:10,\n$it\n" } + "#EXT-X-ENDLIST\n").encodeToByteArray()
                                                    }
                                                }
                                                val detail = PlaybackDetail(PlaybackLesson(1, 1, "", "测试录像", "", "", true), true, streams, streams.map { it.kind }, "")
                                                manager.enqueue(detail, streams, "验证课程", false, gateway)
                                            } catch (_: Exception) { message = "媒体加载失败" }
                                        }
                                    }) { Text("下载短样本") }
                                    TextButton(onClick = { interrupted = !interrupted; offline = interrupted }) { Text(if (interrupted) "恢复网络" else "模拟断网") }
                                }
                                Text(message)
                                PlaybackTasksPage(tasks, manager, Modifier.weight(1f))
                            } else Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (!playing) streams.forEach { stream ->
                                    Text(stream.kind.label)
                                    PlaybackFramePreview(stream.hlsUrl, stream.kind.label, Modifier.fillMaxWidth().height(150.dp))
                                }
                                Button(onClick = { playing = !playing }) { Text(if (playing) "停止" else "播放两路") }
                                if (playing) PlaybackPlayerView("", "", Modifier.fillMaxWidth().height(560.dp), streams)
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onDestroy() { scope.cancel(); transport.close(); super.onDestroy() }
}

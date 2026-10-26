package team.bjtuss.bjtuselfservice.kmp.playback

import android.os.Bundle
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
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
import team.bjtuss.bjtuselfservice.kmp.AndroidHomeworkFileGateway
import java.io.File

/** 只在 debug 包中：脱离账号页验证生产播放器、预览和任务卡；不读取学校凭据。 */
class PlaybackVisualTestActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private companion object {
        val transport by lazy { createSchoolHttpTransport() as KtorSchoolHttpTransport }
        var offline = false
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        team.bjtuss.bjtuselfservice.shared.logging.AppLog.enabled = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        val publicTransport = transport
        val testTransport = object : SchoolHttpTransport by transport {
            override suspend fun executePublic(request: SchoolHttpRequest): SchoolHttpResponse {
                if (offline) {
                    android.util.Log.d("PlaybackVisualTest", "simulated request failure at ${android.os.SystemClock.elapsedRealtime()}")
                    throw java.io.IOException("debug-network-interruption")
                }
                return publicTransport.executePublic(request)
            }
        }
        // 使用生产保存面板、可持久化 URI、任务存储和前台服务；仅标题是虚构数据。
        val gateway = AndroidHomeworkFileGateway(this)
        val manager = (gateway.playbackRecovery as AndroidPlaybackRecovery).managerFor("visual-test", testTransport)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2102)
        }
        setContent {
            var dark by remember { mutableStateOf(false) }
            var large by remember { mutableStateOf(false) }
            var downloads by remember { mutableStateOf(true) }
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
                                TextButton(onClick = { recreate() }) { Text("重建页面") }
                            }
                            if (downloads) {
                                Text("系统累计接收：${received / 1024} KB", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    Button(onClick = {
                                        scope.launch {
                                            try {
                                                val detail = PlaybackDetail(PlaybackLesson(1, 1, "", "测试录像", "", "", true), true, streams, streams.map { it.kind }, "")
                                                message = manager.enqueue(detail, streams, "验证课程", true, gateway).toString()
                                            } catch (_: Exception) { message = "媒体加载失败" }
                                        }
                                    }) { Text("完整下载并合成") }
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
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

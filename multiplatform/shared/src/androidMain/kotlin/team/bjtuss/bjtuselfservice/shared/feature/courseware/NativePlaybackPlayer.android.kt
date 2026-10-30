package team.bjtuss.bjtuselfservice.shared.feature.courseware

import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import team.bjtuss.bjtuselfservice.shared.data.homework.isSchoolPlaybackStreamUrl
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream

actual val playbackPlaysInApp: Boolean = true
actual val playbackRecordingDownloadAvailable: Boolean = false
actual fun openPlaybackInSystemBrowser(html: String): Boolean = false

/** Android WebView 的 video 元素不可靠地支持 HLS，直接使用系统 MediaPlayer。 */
@Composable
actual fun PlaybackPlayerView(html: String, baseUrl: String, modifier: Modifier, streams: List<PlaybackStream>) {
    val sources = remember(streams) { streams.filter { isSchoolPlaybackStreamUrl(it.hlsUrl) }.take(2) }
    val players = remember(sources) { sources.map { NativeHlsPlayer(it.hlsUrl) } }
    var playing by remember(sources) { mutableStateOf(false) }
    var audio by remember(sources) { mutableIntStateOf(0) }
    var layout by remember(sources) { mutableIntStateOf(-1) }
    var rate by remember(sources) { mutableFloatStateOf(1f) }
    var position by remember(sources) { mutableIntStateOf(0) }
    var scrubbing by remember(sources) { mutableStateOf(false) }
    var fullscreen by remember(sources) { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()
    val ready = players.isNotEmpty() && players.all { it.prepared }
    DisposableEffect(players) { onDispose { players.forEach { it.release() } } }
    DisposableEffect(activity) {
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(stopped: Activity) { if (stopped === activity) playing = false }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        }
        activity?.application?.registerActivityLifecycleCallbacks(callbacks)
        onDispose { activity?.application?.unregisterActivityLifecycleCallbacks(callbacks) }
    }
    LaunchedEffect(players, playing, audio, rate, ready) {
        players.forEachIndexed { index, player ->
            player.volume(index == audio)
            player.rate(rate)
            player.play(playing && ready)
        }
    }
    LaunchedEffect(players) {
        while (true) {
            delay(400)
            val master = players.firstOrNull() ?: continue
            if (!scrubbing) position = master.position
            if (master.prepared && !master.isPlaying && position >= master.duration - 500 && master.duration > 0) playing = false
            if (playing && !scrubbing && master.isPlaying) players.drop(1).forEach {
                if (it.prepared && kotlin.math.abs(it.position - position) > 500) it.seek(position)
            }
        }
    }
    fun seek(value: Int) { players.forEach { it.seek(value) }; position = value }
    @Composable
    fun content(contentModifier: Modifier) {
    MaterialTheme(colorScheme = darkColorScheme()) {
    Column(contentModifier.background(Color.Black).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            @Composable fun pane(index: Int, paneModifier: Modifier) {
                Column(paneModifier) {
                    Text(sources[index].kind.label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    NativeVideoSurface(players[index], Modifier.fillMaxSize(), visible = layout == -1 || layout == index)
                }
            }
            if (!fullscreen && maxWidth < 500.dp && layout == -1) Column(Modifier.fillMaxSize()) {
                players.indices.forEach { pane(it, Modifier.weight(1f).fillMaxWidth()) }
            } else Row(Modifier.fillMaxSize()) {
                players.indices.forEach { index ->
                    pane(index, if (layout == -1 || layout == index) Modifier.weight(1f).fillMaxHeight() else Modifier.size(0.dp))
                }
            }
        }
        val error = players.firstNotNullOfOrNull { it.error }
        if (error != null) {
            Text(error, color = Color(0xffffb4ab), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { playing = false; players.forEach { it.reload() } }) { Text("重新加载") }
        } else if (!ready) Text("正在加载画面…", color = Color.White)
        val duration = players.firstOrNull()?.duration ?: 0
        Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
            onValueChange = { scrubbing = true; position = it.toInt() },
            onValueChangeFinished = { seek(position); scrubbing = false },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = ready)
        Text("${videoTime(position)} / ${videoTime(duration)}", color = Color.White, style = MaterialTheme.typography.labelSmall)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { playing = !playing }, enabled = ready) { Text(if (playing) "暂停" else "播放") }
            TextButton(onClick = { layout = -1; fullscreen = !fullscreen }) { Text(if (fullscreen) "退出全屏" else "全屏") }
            TextButton(onClick = { seek((position - 15_000).coerceAtLeast(0)) }, enabled = ready) { Text("−15 秒") }
            TextButton(onClick = { seek((position + 15_000).coerceAtMost(duration)) }, enabled = ready) { Text("+15 秒") }
            PlayerChoice("倍速 ${rate}×", listOf(0.75f, 1f, 1.25f, 1.5f, 2f).map { "$it×" }) {
                rate = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)[it]
            }
            if (players.size > 1) {
                PlayerChoice("声音：${sources[audio].kind.label}", sources.map { it.kind.label }) { audio = it }
                PlayerChoice("画面：${if (layout < 0) "两路" else sources[layout].kind.label}", listOf("两路") + sources.map { it.kind.label }) { layout = it - 1 }
            }
        }
    }
    }
    }
    if (fullscreen) {
        Box(modifier.background(Color.Black))
        DisposableEffect(activity) {
            val previous = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose { if (previous != null) activity?.requestedOrientation = previous }
        }
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val view = LocalView.current
            DisposableEffect(view) {
                val window = (view.parent as? DialogWindowProvider)?.window
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                view.post {
                    if (Build.VERSION.SDK_INT >= 30) {
                        window?.insetsController?.apply {
                            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            hide(WindowInsets.Type.systemBars())
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        view.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    }
                }
                onDispose { }
            }
            content(Modifier.fillMaxSize())
        }
    } else content(modifier)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PlayerChoice(label: String, choices: List<String>, select: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEachIndexed { index, text -> DropdownMenuItem(text = { Text(text) }, onClick = { select(index); expanded = false }) }
        }
    }
}

@Composable
actual fun PlaybackFramePreview(url: String, fallbackLabel: String, modifier: Modifier) {
    val player = remember(url) { NativeHlsPlayer(url, preview = true) }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(modifier.background(Color.Black)) {
        val poster = player.poster
        if (poster != null) Image(poster.asImageBitmap(), fallbackLabel, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else NativeVideoSurface(player, Modifier.fillMaxSize())
        if (player.error != null) Text("$fallbackLabel · 预览加载失败", color = Color.White, style = MaterialTheme.typography.bodySmall)
        else if (!player.frameShown) CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun NativeVideoSurface(player: NativeHlsPlayer, modifier: Modifier, visible: Boolean = true) {
    AndroidView(modifier = modifier, factory = { context ->
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { player.attach(this@apply, texture) }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { player.fit(width, height) }
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { player.detach(this@apply); return true }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { player.capturePreview() }
            }
        }
    }, update = { it.alpha = if (visible) 1f else 0f }, onRelease = { player.detach(it) })
}

private class NativeHlsPlayer(private val url: String, private val preview: Boolean = false) {
    var prepared by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var duration by mutableIntStateOf(0)
    var frameShown by mutableStateOf(false)
    var poster by mutableStateOf<Bitmap?>(null)
        private set
    private var media: MediaPlayer? = null
    private var surface: Surface? = null
    private var view: TextureView? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var stopped = false
    private var desiredPlaying = false
    val position: Int get() = if (prepared) runCatching { media?.currentPosition ?: 0 }.getOrDefault(0) else 0
    val isPlaying: Boolean get() = prepared && runCatching { media?.isPlaying == true }.getOrDefault(false)

    fun attach(target: TextureView, texture: SurfaceTexture) {
        view = target
        surface?.release()
        surface = Surface(texture)
        if (media == null) reload() else media?.setSurface(surface)
        fit(target.width, target.height)
        if (prepared && desiredPlaying) runCatching { media?.start() }
    }

    fun reload() {
        media?.release()
        media = null
        prepared = false
        error = null
        frameShown = false
        if (stopped || surface == null || !isSchoolPlaybackStreamUrl(url)) return
        try {
            media = MediaPlayer().apply {
                setSurface(surface)
                setVolume(0f, 0f)
                setOnErrorListener { _, _, _ -> error = "画面加载失败，请检查网络后重新加载。"; prepared = false; true }
                setOnVideoSizeChangedListener { _, width, height -> this@NativeHlsPlayer.videoWidth = width; this@NativeHlsPlayer.videoHeight = height; view?.let { fit(it.width, it.height) } }
                setOnPreparedListener {
                    prepared = true
                    this@NativeHlsPlayer.duration = it.duration.coerceAtLeast(0)
                    if (preview) it.seekTo(minOf(600_000, duration / 2).toLong(), MediaPlayer.SEEK_CLOSEST)
                }
                setOnSeekCompleteListener { if (preview && !frameShown) it.start() }
                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        frameShown = true
                        if (preview) view?.postDelayed({ play(false) }, 150)
                    }
                    false
                }
                setDataSource(url)
                prepareAsync()
            }
        } catch (_: Exception) { error = "画面加载失败，请检查网络后重新加载。" }
    }

    fun fit(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val matrix = android.graphics.Matrix()
        matrix.setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f)
        view?.setTransform(matrix)
    }
    fun capturePreview() {
        if (!preview || !frameShown || poster != null) return
        if (videoWidth <= 0 || videoHeight <= 0) return
        // TextureView.bitmap 默认按容器尺寸拉伸拷贝；宽卡片会因此得到变形的海报。
        // 用源视频宽高比截帧，再交给 Image 的 Fit 居中留边。
        val width = minOf(videoWidth, 768)
        val height = (width.toLong() * videoHeight / videoWidth).toInt().coerceAtLeast(1)
        val bitmap = view?.getBitmap(width, height) ?: return
        poster = bitmap
        // 预览成为静态图片后释放解码器，三路预览不能占住正式播放需要的硬件资源。
        play(false)
        media?.release()
        media = null
        prepared = false
        stopped = true
    }
    fun play(value: Boolean) { desiredPlaying = value; if (prepared && surface != null) runCatching { if (value) media?.start() else media?.pause() } }
    fun volume(audible: Boolean) { if (prepared) media?.setVolume(if (audible && !preview) 1f else 0f, if (audible && !preview) 1f else 0f) }
    fun rate(value: Float) { if (prepared) runCatching { media?.let { val wasPlaying = it.isPlaying; it.playbackParams = it.playbackParams.setSpeed(value); if (!wasPlaying) it.pause() } } }
    fun seek(value: Int) { if (prepared) media?.seekTo(value.toLong().coerceIn(0, duration.toLong()), MediaPlayer.SEEK_CLOSEST) }
    fun detach(target: TextureView) {
        if (view !== target) return // 全屏切换时旧 View 的迟到回调不能拆掉新 Surface。
        if (prepared) runCatching { media?.pause() }
        media?.setSurface(null); surface?.release(); surface = null; view = null
    }
    fun release() { stopped = true; media?.release(); media = null; prepared = false; surface?.release(); surface = null; view = null }
}

private fun videoTime(millis: Int): String {
    val seconds = millis / 1000
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

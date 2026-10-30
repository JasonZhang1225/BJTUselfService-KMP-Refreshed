package team.bjtuss.bjtuselfservice.shared.feature.courseware

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import team.bjtuss.bjtuselfservice.shared.currentPlatform
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsMediaPlaylist
import team.bjtuss.bjtuselfservice.shared.data.courseware.fetchHlsBytes
import team.bjtuss.bjtuselfservice.shared.data.homework.isSchoolPlaybackStreamUrl
import team.bjtuss.bjtuselfservice.shared.media.TsToMp4Remuxer
import team.bjtuss.bjtuselfservice.shared.media.awaitPlaybackHelper
import team.bjtuss.bjtuselfservice.shared.media.locateHelper
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.KtorSchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport

actual val playbackPlaysInApp: Boolean = false
actual val playbackRecordingDownloadAvailable: Boolean = true

/** 桌面端不内嵌浏览器内核（见 SchoolWebView.desktop），只在调用方误用时占位。 */
@Composable
actual fun PlaybackPlayerView(html: String, baseUrl: String, modifier: Modifier, streams: List<team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream>) {
    Box(modifier)
}

/**
 * 写入当前用户的系统临时目录后交给默认浏览器（Safari、Chrome、Edge 均原生支持 HLS）。
 * 每次打开前清掉上一份，进程退出时再删一次；页面里没有账号信息，只有公开的录播地址。
 */
actual fun openPlaybackInSystemBrowser(html: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false
    val root = File(System.getProperty("java.io.tmpdir"), "BJTUselfService-playback").apply { mkdirs() }
    root.listFiles()?.forEach { it.delete() }
    val page = Files.createTempFile(root.toPath(), "playback-", ".html").toFile()
    page.writeText(html, Charsets.UTF_8)
    page.deleteOnExit()
    Desktop.getDesktop().browse(page.toURI())
    true
}.getOrDefault(false)

private const val VIDEO_FRAME_HELPER_PROPERTY = "bjtu.video-frame.helper"

/** 仅缓存最近 24 张成功预览；失败允许用户重试，页面退出会取消请求和子进程。 */
private val previewCache = LinkedHashMap<String, ImageBitmap>()
private val previewPermits = Semaphore(2)

@Composable
actual fun PlaybackFramePreview(url: String, fallbackLabel: String, modifier: Modifier) {
    var frame by remember(url) { mutableStateOf(synchronized(previewCache) { previewCache[url] }?.let { Result.success(it) }) }
    var attempt by remember(url) { mutableStateOf(0) }
    LaunchedEffect(url, attempt) {
        if (frame?.isSuccess == true) return@LaunchedEffect
        frame = null
        frame = try {
            val image = withTimeout(30_000) { previewPermits.withPermit {
                withContext(Dispatchers.IO) {
                    check(isSchoolPlaybackStreamUrl(url))
                    if (currentPlatform().isWindows) {
                        val helper = locateHelper() ?: error("preview component unavailable")
                        val transport = createSchoolHttpTransport()
                        try { captureWindowsPlaybackPreview(url, helper, transport) }
                        finally { (transport as? KtorSchoolHttpTransport)?.close() }
                    } else {
                        captureFrame(locateVideoFrameHelper() ?: error("preview component unavailable"), url)
                    }
                }
            } }
            synchronized(previewCache) {
                previewCache[url] = image
                if (previewCache.size > 24) previewCache.remove(previewCache.keys.first())
            }
            Result.success(image)
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(IllegalStateException("preview timeout"))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.failure(IllegalStateException("preview unavailable"))
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = frame?.getOrNull()
        when {
            bitmap != null -> Image(bitmap, contentDescription = "${fallbackLabel}预览", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            frame == null -> CircularProgressIndicator()
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${fallbackLabel}\n预览加载失败", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                TextButton(onClick = { attempt++ }) { Text("重试预览") }
            }
        }
    }
}

private suspend fun captureFrame(helper: File, url: String): ImageBitmap = withContext(Dispatchers.IO) {
        val output = Files.createTempFile("bjtu-frame-", ".jpg").toFile()
        try {
            val process = ProcessBuilder(helper.absolutePath, url, "600", output.absolutePath, "640")
                .start()
            try { check(awaitPlaybackHelper(process, {}).code == 0) }
            finally { if (process.isAlive) process.destroyForcibly() }
            org.jetbrains.skia.Image.makeFromEncoded(output.readBytes()).toComposeImageBitmap()
        } finally {
            output.delete()
        }
}

/** 播放列表中的分片长度近似均匀；短课取中点，其他取第 10 分钟附近。 */
internal fun playbackPreviewSegmentIndex(playlist: HlsMediaPlaylist): Int {
    require(playlist.segmentUrls.isNotEmpty() && playlist.durationSeconds > 0)
    val second = minOf(600.0, playlist.durationSeconds / 2)
    return (second / playlist.durationSeconds * playlist.segmentUrls.size).toInt().coerceIn(playlist.segmentUrls.indices)
}

/** 只抓取预览位置的一片 TS，在后台无损整理后交给随包的系统解码助手。 */
internal suspend fun captureWindowsPlaybackPreview(url: String, helper: File, transport: SchoolHttpTransport): ImageBitmap =
    withContext(Dispatchers.IO) {
        require(isSchoolPlaybackStreamUrl(url))
        val playlist = HlsRecordingDownloader(transport).resolvePlaylist(url)
        val bytes = fetchHlsBytes(transport, playlist.segmentUrls[playbackPreviewSegmentIndex(playlist)], 2_000)
        check(bytes.size <= 64 * 1024 * 1024)
        val media = Files.createTempFile("bjtu-preview-", ".mp4").toFile()
        val output = Files.createTempFile("bjtu-preview-", ".bmp").toFile()
        try {
            val data = ByteArrayOutputStream()
            val remuxer = TsToMp4Remuxer { data.write(it) }
            remuxer.feed(bytes)
            remuxer.finish()
            media.outputStream().use { it.write(remuxer.buildHeader()); data.writeTo(it) }
            val process = ProcessBuilder(helper.absolutePath, "thumbnail", media.absolutePath, output.absolutePath, "640").start()
            try { check(awaitPlaybackHelper(process, {}).code == 0) }
            finally { if (process.isAlive) process.destroyForcibly() }
            org.jetbrains.skia.Image.makeFromEncoded(output.readBytes()).toComposeImageBitmap()
        } finally { media.delete(); output.delete() }
    }

/** 与日历助手同一套查找规则：打包后的 App 用自身 Resources，`desktopApp:run` 回退到 Gradle 注入的路径。 */
internal fun locateVideoFrameHelper(): File? {
    val executable = ProcessHandle.current().info().command().orElse(null)?.let(::File)
    val contents = executable?.parentFile?.parentFile
    contents?.let { File(it, "Resources/VideoFrame/BJTUVideoFrameHelper") }
        ?.takeIf(File::canExecute)
        ?.let { return it }
    return System.getProperty(VIDEO_FRAME_HELPER_PROPERTY)?.let(::File)?.takeIf(File::canExecute)
}

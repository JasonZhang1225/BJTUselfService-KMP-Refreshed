package team.bjtuss.bjtuselfservice.shared.feature.courseware

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files

actual val playbackPlaysInApp: Boolean = false

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

/** 同一路画面在本次运行里只截一次；失败记为 null，不反复拉起截帧进程。 */
private val previewCache = ConcurrentHashMap<String, Result<ImageBitmap>>()

@Composable
actual fun PlaybackFramePreview(url: String, fallbackLabel: String, modifier: Modifier) {
    var frame by remember(url) { mutableStateOf(previewCache[url]) }
    val helper = remember { locateVideoFrameHelper() }
    LaunchedEffect(url, helper) {
        if (frame == null && helper != null) {
            frame = captureFrame(helper, url).also { previewCache[url] = it }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = frame?.getOrNull()
        when {
            bitmap != null -> Image(bitmap, contentDescription = "${fallbackLabel}预览", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            helper != null && frame == null -> CircularProgressIndicator()
            else -> Text(
                if (helper == null) fallbackLabel else "${fallbackLabel}\n预览加载失败",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private suspend fun captureFrame(helper: File, url: String): Result<ImageBitmap> = withContext(Dispatchers.IO) {
    runCatching {
        val output = Files.createTempFile("bjtu-frame-", ".jpg").toFile()
        try {
            val process = ProcessBuilder(helper.absolutePath, url, "600", output.absolutePath, "640")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("frame helper timeout")
            }
            check(process.exitValue() == 0) { "frame helper exit ${process.exitValue()}" }
            org.jetbrains.skia.Image.makeFromEncoded(output.readBytes()).toComposeImageBitmap()
        } finally {
            output.delete()
        }
    }
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

package team.bjtuss.bjtuselfservice.kmp.playback

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackBackgroundState
import team.bjtuss.bjtuselfservice.shared.media.CompositionException
import team.bjtuss.bjtuselfservice.shared.media.TsToMp4Remuxer
import team.bjtuss.bjtuselfservice.shared.media.defaultVideoCompositor

/**
 * 仅 debug 构建存在的回放管线自测入口（release 包不含这个 Activity）。
 *
 * 用法（adb）：
 * ```
 * adb shell am start --es mode remux --es inputs /sdcard/Download/a.ts,/sdcard/Download/b.ts \
 *   --es output teacher.mp4 -n team.bjtuss.bjtuselfservice.kmp/.playback.PlaybackSelfTestActivity
 * adb shell am start --es mode compose --es left left.mp4 --es right right.mp4 \
 *   --es output composite.mp4 -n team.bjtuss.bjtuselfservice.kmp/.playback.PlaybackSelfTestActivity
 * ```
 * 结果写在 logcat（`PlaybackSelfTest` 标签）和同目录的 `<output>.result.txt`。
 */
class PlaybackSelfTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode").orEmpty()
        // `am` 的 --esa 只接受一个值，这里用逗号分隔多个输入路径。
        val inputs = (intent.getStringExtra("inputs") ?: "")
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        val left = intent.getStringExtra("left").orEmpty()
        val right = intent.getStringExtra("right").orEmpty()
        val outputName = intent.getStringExtra("output").orEmpty().ifBlank { "output.mp4" }
        val qualityName = intent.getStringExtra("quality").orEmpty()
        val workDir = getExternalFilesDir(null) ?: filesDir
        Thread {
            val message = runCatching {
                when (mode) {
                    "remux" -> remux(workDir, inputs, outputName)
                    "compose" -> compose(workDir, left, right, outputName, qualityName)
                    "notify" -> notify()
                    else -> error("未知 mode：$mode")
                }
            }.fold(
                onSuccess = { "OK $it" },
                onFailure = { error ->
                    "FAIL ${error::class.java.simpleName}: ${error.message}"
                },
            )
            Log.i(TAG, message)
            runCatching { File(workDir, "$outputName.result.txt").writeText(message) }
            finish()
        }.start()
    }

    /** 把 TS 分片按 App 的方式转封装成 MP4（moov 在前），验证 Android 能读这个格式。 */
    private fun remux(workDir: File, inputs: List<String>, outputName: String): String {
        if (inputs.isEmpty()) error("没有输入文件")
        val output = File(workDir, outputName)
        val data = File(workDir, "$outputName.data")
        val header = runBlocking {
            data.outputStream().buffered().use { stream ->
                val remuxer = TsToMp4Remuxer { bytes -> stream.write(bytes) }
                inputs.forEach { remuxer.feed(File(it).readBytes()) }
                remuxer.finish()
                remuxer.buildHeader()
            }
        }
        output.outputStream().buffered().use { stream ->
            stream.write(header)
            data.inputStream().use { it.copyTo(stream) }
        }
        data.delete()
        return "remux -> ${output.absolutePath} (${output.length()} bytes)"
    }

    /** 两路 MP4 左右并排合成，验证 MediaCodec + OpenGL + MediaMuxer 这条链路。 */
    private fun compose(workDir: File, left: String, right: String, outputName: String, qualityName: String): String {
        val compositor = defaultVideoCompositor()
        if (!compositor.isAvailable) error("当前设备没有可用的 H.264 编码器")
        val output = File(workDir, outputName)
        output.delete()
        val quality = PlaybackCompositeQuality.entries.firstOrNull { it.name == qualityName } ?: PlaybackCompositeQuality.HIGH
        Log.i(TAG, "compose quality=${quality.name} ${quality.width}x${quality.height} @${quality.bitRate}")
        runBlocking {
            compositor.composeSideBySide(
                leftPath = resolve(workDir, left),
                rightPath = resolve(workDir, right),
                outputPath = output.absolutePath,
                quality = quality,
                onProgress = { fraction -> Log.i(TAG, "progress ${(fraction * 100).toInt()}%") },
                onStatus = { status -> Log.i(TAG, "status $status") },
            )
        }
        if (!output.isFile) throw CompositionException("没有生成输出文件")
        return "compose -> ${output.absolutePath} (${output.length()} bytes)"
    }

    /** 相对路径按工作目录解析，绝对路径原样使用。 */
    private fun resolve(workDir: File, path: String): String {
        val file = File(path)
        return if (file.isAbsolute) file.absolutePath else File(workDir, path).absolutePath
    }

    /** 前台服务保活：开一次通知栏进度，再收掉，验证服务能起也能停。 */
    private fun notify(): String {
        val host = team.bjtuss.bjtuselfservice.shared.media.PlaybackPlatformHooks.backgroundHost
            ?: AndroidPlaybackBackgroundHost(applicationContext).also {
                team.bjtuss.bjtuselfservice.shared.media.PlaybackPlatformHooks.backgroundHost = it
            }
        host.update(
            true,
            PlaybackBackgroundState(
                title = "2026-10-20 16:20-18:10",
                phaseLabel = "下载中",
                detail = "下载教师画面（第 1/2 路）",
                progress = 0.42f,
                sizeLabel = "1.2 MB / 约 5.4 GB",
                speedLabel = "9.1 MB/s",
            ),
        )
        Thread.sleep(12_000)
        val running = PlaybackForegroundService.isRunning
        host.update(false, null)
        Thread.sleep(1_000)
        return "notify -> 服务在跑=$running，结束后=${PlaybackForegroundService.isRunning}"
    }

    private companion object {
        const val TAG = "PlaybackSelfTest"
    }
}

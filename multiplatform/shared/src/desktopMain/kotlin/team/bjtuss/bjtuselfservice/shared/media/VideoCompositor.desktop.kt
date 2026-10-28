package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.feature.courseware.locateVideoFrameHelper

/**
 * 桌面端按系统分派：macOS 用随 App 打包的 `BJTUVideoFrameHelper compose`
 * （AVFoundation 读写，硬件 H.264 编码）；Windows 用 `BJTUPlaybackCompositor.exe`
 * （Media Foundation 解码编码）。
 *
 * 桌面端两套实现共用同一个 jvm 目标（shared 的 desktop），所以在这里按操作系统名分派；
 * 缺少对应小程序时 [VideoCompositor.isAvailable] 为 false，任务改为分别保存各路。
 */
actual fun defaultVideoCompositor(): VideoCompositor =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        WindowsPlaybackCompositor
    } else {
        MacHelperVideoCompositor
    }

private object MacHelperVideoCompositor : VideoCompositor {
    override val isAvailable: Boolean get() = locateVideoFrameHelper() != null

    override suspend fun composeSideBySide(
        leftPath: String,
        rightPath: String,
        outputPath: String,
        quality: PlaybackCompositeQuality,
        onProgress: (Float) -> Unit,
        onStatus: (String?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val helper = locateVideoFrameHelper() ?: throw CompositionException("缺少合成组件")
        val process = ProcessBuilder(
            helper.absolutePath,
            "compose",
            leftPath,
            rightPath,
            outputPath,
            quality.width.toString(),
            quality.height.toString(),
            quality.bitRate.toString(),
        )
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    coroutineContext.ensureActive()
                    line.removePrefix("progress ").toFloatOrNull()?.let(onProgress)
                }
            }
            val code = process.waitFor()
            if (code != 0) {
                val reason = process.errorStream.bufferedReader().readText().trim().ifBlank { "退出码 $code" }
                File(outputPath).delete()
                throw CompositionException(reason)
            }
        } catch (error: CancellationException) {
            process.destroyForcibly()
            File(outputPath).delete()
            throw error
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** 桌面端关窗不退出 App，任务照常进行，无需额外保活。 */
actual fun defaultPlaybackBackgroundHost(): PlaybackBackgroundHost? = null

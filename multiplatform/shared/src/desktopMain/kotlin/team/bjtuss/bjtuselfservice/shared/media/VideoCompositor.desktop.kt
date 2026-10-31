package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

/**
 * macOS：完整 MP4 合成 + 渐进管线（下载并行 remux，两端齐后 compose）。
 * 中断不保留已编码进度；已下载 HLS 分片仍可恢复。
 */
internal object MacHelperVideoCompositor : ProgressiveVideoCompositor {
    override val isAvailable: Boolean get() = locateVideoFrameHelper() != null
    override val retainsEncodedSegmentsOnInterrupt: Boolean get() = false
    override val encodingOverlapsDownload: Boolean get() = true

    /**
     * NV12 解码器缩放 + 异步双路解码后，本机（M2）GUI 实网整节合成约 20.7× 片长（7022 s 片长 339 s 合成），
     * 瓶颈是硬件解码。取 20× 作编码墙钟下限，开跑即报约 6 分钟，与实跑 5.7 分钟对齐。
     */
    override fun provisionalEncodeSeconds(
        contentDurationSeconds: Double,
        quality: PlaybackCompositeQuality,
    ): Long? {
        if (contentDurationSeconds <= 0) return null
        return kotlin.math.ceil(contentDurationSeconds / 20.0).toLong().coerceAtLeast(1)
    }

    override suspend fun openProgressive(
        work: MediaWorkDirectory,
        durationSeconds: Double,
        quality: PlaybackCompositeQuality,
    ): ProgressiveComposition = withContext(Dispatchers.IO) {
        MacProgressiveComposition(work, durationSeconds, quality)
    }

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
            "--parent-pid",
            ProcessHandle.current().pid().toString(),
        ).start()
        try {
            val result = awaitPlaybackHelper(process, onProgress, onStatus)
            if (result.code != 0) {
                val reason = result.diagnostic.ifBlank { "退出码 ${result.code}" }
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

package team.bjtuss.bjtuselfservice.shared.media

import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality

actual fun defaultVideoCompositor(): VideoCompositor = AndroidVideoCompositor

/** 前台服务需要 Context，由 MainActivity 启动时注入 [PlaybackPlatformHooks.backgroundHost]。 */
actual fun defaultPlaybackBackgroundHost(): PlaybackBackgroundHost? = null

/**
 * Android 合成：MediaExtractor 读两路 MP4 → 两个 MediaCodec 解码到 SurfaceTexture →
 * OpenGL ES 左右并排画进编码器输入 Surface → MediaCodec H.264 编码 → MediaMuxer；
 * 声音直接拷左路 AAC（不重编码）。
 *
 * 设备没有支持 Surface 输入的 H.264 编码器时 [isAvailable] 为 false，
 * 任务自动改为分别保存各路 MP4。实现见 `docs/plans/playback-composite-android-windows.md`。
 */
internal object AndroidVideoCompositor : ProgressiveVideoCompositor {
    override val isAvailable: Boolean get() = H264SurfaceEncoder.isAvailable()

    override suspend fun openProgressive(work: MediaWorkDirectory, durationSeconds: Double,
        quality: PlaybackCompositeQuality): ProgressiveComposition = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        work.create()
        AndroidProgressiveComposition(work, durationSeconds, quality)
    }

    override suspend fun composeSideBySide(
        leftPath: String,
        rightPath: String,
        outputPath: String,
        quality: PlaybackCompositeQuality,
        onProgress: (Float) -> Unit,
        onStatus: (String?) -> Unit,
    ) {
        onStatus(null)
        AndroidMediaCompositor(
            leftPath = leftPath,
            rightPath = rightPath,
            outputPath = outputPath,
            quality = quality,
        ).run(onProgress, onStatus)
    }
}

package team.bjtuss.bjtuselfservice.shared.media

import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackBackgroundState

/**
 * 把两路 MP4 左右并排合成为一个 MP4（硬件编码，H.264；声音取左路）。
 * 各平台用系统自带的编解码：Apple 走 AVFoundation，Android 走 MediaCodec + OpenGL ES；
 * 不打包 ffmpeg 等第三方库。不支持的平台 [isAvailable] 为 false，任务改为分别保存各路。
 */
interface VideoCompositor {
    val isAvailable: Boolean

    /**
     * @param quality 输出清晰度（分辨率与码率）；设备编码器不支持时平台可自行退一档并说明。
     * @param onProgress 0..1
     * @param onStatus 需要提示用户的状态（例如 iOS 旧系统“回到 App 后继续合成”）；null 表示清除提示。
     * 失败抛 [CompositionException]；协程取消时必须停止并删掉半成品。
     */
    suspend fun composeSideBySide(
        leftPath: String,
        rightPath: String,
        outputPath: String,
        quality: PlaybackCompositeQuality,
        onProgress: (Float) -> Unit,
        onStatus: (String?) -> Unit,
    )
}

/** 支持边接收 MPEG-TS 边合成的离线管线。其他平台继续使用完整 MP4 输入。 */
interface ProgressiveVideoCompositor : VideoCompositor {
    /** 是否能在中断后复用已编码部分；不能时仍可恢复已下载的原始分片。 */
    val retainsEncodedSegmentsOnInterrupt: Boolean get() = true

    /**
     * 编码墙钟是否与下载重叠（Windows / macOS `compose-indexed` 均为 true）。
     * false 时 PIPELINING 总 ETA = 下载剩余 + [provisionalEncodeSeconds]。
     * true 时总 ETA = max(下载, 合成, 按进度折算的预估编码剩余)。
     */
    val encodingOverlapsDownload: Boolean get() = true

    /**
     * 整段编码的保守墙钟（秒）。重叠路径按 (1−stage) 折成剩余后与实测合成 ETA 取较大值，
     * 避免早期合成进度把总时长压得过乐观。默认 null：不设下限。
     */
    fun provisionalEncodeSeconds(contentDurationSeconds: Double, quality: PlaybackCompositeQuality): Long? = null

    suspend fun openProgressive(
        work: MediaWorkDirectory,
        durationSeconds: Double,
        quality: PlaybackCompositeQuality,
    ): ProgressiveComposition
}

interface ProgressiveComposition {
    /** 已持久化的合成边界；恢复时不重新编码这一段。 */
    val resumeSeconds: Double
    val isComplete: Boolean get() = false
    suspend fun append(index: Int, bytes: ByteArray)
    suspend fun finishInput(index: Int)
    /** [onDevice] 报告当前实际使用的合成设备；未知或没有在合成时为 null。 */
    suspend fun compose(onProgress: (Float) -> Unit, onStatus: (String?) -> Unit,
        onFinalizing: (Float) -> Unit = {}, onDevice: (String?) -> Unit = {})
    suspend fun close()
}

class CompositionException(message: String) : Exception(message)

object UnavailableVideoCompositor : VideoCompositor {
    override val isAvailable: Boolean = false
    override suspend fun composeSideBySide(
        leftPath: String,
        rightPath: String,
        outputPath: String,
        quality: PlaybackCompositeQuality,
        onProgress: (Float) -> Unit,
        onStatus: (String?) -> Unit,
    ) = throw CompositionException("当前平台不支持合成")
}

/** 平台默认合成器：macOS 用随 App 打包的 AVFoundation 小程序，Android 用 MediaCodec，其他为不可用。 */
expect fun defaultVideoCompositor(): VideoCompositor

/** iOS 自带后台保活实现；Android 由宿主 Activity 注入前台服务；桌面不需要。 */
expect fun defaultPlaybackBackgroundHost(): PlaybackBackgroundHost?

/**
 * 长任务期间让系统别把 App 挂起或杀掉：Android 开前台服务（通知栏显示进度），
 * iOS 申请后台时间；桌面端关窗不退出，无需处理。
 *
 * 同一个接口也承载实时活动的内容：iOS 把它画成灵动岛/锁屏实时活动，
 * Android 把它画成前台服务通知（实时动态）。
 */
interface PlaybackBackgroundHost {
    /** [state] 为 null 表示没有任务了，宿主应撤掉前台服务或结束实时活动。 */
    fun update(active: Boolean, state: PlaybackBackgroundState?)
}

/** 宿主（Android Activity、iOS Swift 入口）在启动时注入的平台能力。 */
object PlaybackPlatformHooks {
    var compositor: VideoCompositor? = null
    var backgroundHost: PlaybackBackgroundHost? = defaultPlaybackBackgroundHost()

    val effectiveCompositor: VideoCompositor get() = compositor ?: defaultVideoCompositor()
}

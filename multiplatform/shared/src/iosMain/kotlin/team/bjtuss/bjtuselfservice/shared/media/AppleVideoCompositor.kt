package team.bjtuss.bjtuselfservice.shared.media

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetReaderVideoCompositionOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVMutableVideoCompositionInstruction
import platform.AVFoundation.AVMutableVideoCompositionLayerInstruction
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoMaxKeyFrameIntervalKey
import platform.AVFoundation.AVVideoProfileLevelH264HighAutoLevel
import platform.AVFoundation.AVVideoProfileLevelKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.duration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGAffineTransform
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGSizeApplyAffineTransform
import platform.CoreGraphics.CGSizeMake
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMaximum
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import kotlin.math.abs
import kotlin.math.min

/**
 * iOS：AVFoundation 读两路 MP4，按左右并排的视频合成指令解码叠加，
 * 再用系统硬件 H.264 编码写出；声音取左路解码后重新编码为 AAC（避免跨语言传递格式描述）。
 *
 * 系统不允许后台 App 使用硬件编码：切到后台时暂停喂数据；若编码会话被系统作废，
 * 回到前台后从头重来（最多 3 次）。iOS 26 起由 [ContinuedProcessing] 申请后台继续。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object AppleVideoCompositor : VideoCompositor {
    override val isAvailable: Boolean = true

    override suspend fun composeSideBySide(
        leftPath: String,
        rightPath: String,
        outputPath: String,
        quality: PlaybackCompositeQuality,
        onProgress: (Float) -> Unit,
        onStatus: (String?) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            attempt++
            val backgroundedBefore = IosAppState.backgroundTransitions
            try {
                composeOnce(leftPath, rightPath, outputPath, quality, onProgress, onStatus)
                onStatus(null)
                return
            } catch (error: CancellationException) {
                NSFileManager.defaultManager.removeItemAtPath(outputPath, error = null)
                throw error
            } catch (error: CompositionException) {
                NSFileManager.defaultManager.removeItemAtPath(outputPath, error = null)
                // 只有中途进过后台（编码会话可能被系统作废）才自动重来。
                if (attempt >= 3 || IosAppState.backgroundTransitions == backgroundedBefore) throw error
                onStatus("合成在后台被系统中断，回到 App 后会自动重新合成。")
                waitUntilForeground()
            }
        }
    }

    private suspend fun composeOnce(
        leftPath: String,
        rightPath: String,
        outputPath: String,
        quality: PlaybackCompositeQuality,
        onProgress: (Float) -> Unit,
        onStatus: (String?) -> Unit,
    ) {
        val leftAsset = AVURLAsset(uRL = NSURL.fileURLWithPath(leftPath), options = null)
        val rightAsset = AVURLAsset(uRL = NSURL.fileURLWithPath(rightPath), options = null)
        val leftVideo = leftAsset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
            ?: throw CompositionException("找不到视频轨道")
        val rightVideo = rightAsset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
            ?: throw CompositionException("找不到视频轨道")
        val composition = AVMutableComposition()
        val compLeft = composition.addMutableTrackWithMediaType(AVMediaTypeVideo, kCMPersistentTrackID_Invalid)
            ?: throw CompositionException("无法创建合成轨道")
        val compRight = composition.addMutableTrackWithMediaType(AVMediaTypeVideo, kCMPersistentTrackID_Invalid)
            ?: throw CompositionException("无法创建合成轨道")
        val zero = CMTimeMake(0, 1)
        if (!compLeft.insertTimeRange(CMTimeRangeMake(zero, leftAsset.duration), leftVideo, zero, null) ||
            !compRight.insertTimeRange(CMTimeRangeMake(zero, rightAsset.duration), rightVideo, zero, null)
        ) {
            throw CompositionException("插入视频失败")
        }
        var compAudio: AVMutableCompositionTrack? = null
        val audioSource = listOf(leftAsset, rightAsset).firstOrNull { it.tracksWithMediaType(AVMediaTypeAudio).isNotEmpty() }
        if (audioSource != null) {
            val track = audioSource.tracksWithMediaType(AVMediaTypeAudio).first() as AVAssetTrack
            compAudio = composition.addMutableTrackWithMediaType(AVMediaTypeAudio, kCMPersistentTrackID_Invalid)
            compAudio?.insertTimeRange(CMTimeRangeMake(zero, audioSource.duration), track, zero, null)
        }
        val duration = CMTimeMaximum(leftAsset.duration, rightAsset.duration)
        val halfWidth = quality.width / 2.0
        val width = quality.width
        val height = quality.height

        fun layer(track: AVMutableCompositionTrack, source: AVAssetTrack, x: Double): AVMutableVideoCompositionLayerInstruction {
            val instruction = AVMutableVideoCompositionLayerInstruction.videoCompositionLayerInstructionWithAssetTrack(track)
            val preferred = source.preferredTransform
            val (w, h) = CGSizeApplyAffineTransform(source.naturalSize, preferred).useContents { abs(this.width) to abs(this.height) }
            val scale = min(halfWidth / w, height / h)
            val dx = x + (halfWidth - w * scale) / 2
            val dy = (height - h * scale) / 2
            val transform: CValue<CGAffineTransform> = CGAffineTransformConcat(
                CGAffineTransformConcat(preferred, CGAffineTransformMakeScale(scale, scale)),
                CGAffineTransformMakeTranslation(dx, dy),
            )
            instruction.setTransform(transform, atTime = zero)
            return instruction
        }

        val instruction = AVMutableVideoCompositionInstruction()
        instruction.setTimeRange(CMTimeRangeMake(zero, duration))
        instruction.setLayerInstructions(listOf(layer(compLeft, leftVideo, 0.0), layer(compRight, rightVideo, halfWidth)))
        val videoComposition = AVMutableVideoComposition()
        videoComposition.setRenderSize(CGSizeMake(width.toDouble(), height.toDouble()))
        videoComposition.setFrameDuration(CMTimeMake(1, 25))
        videoComposition.setInstructions(listOf(instruction))

        NSFileManager.defaultManager.removeItemAtPath(outputPath, error = null)
        val reader = AVAssetReader(asset = composition, error = null)
        val writer = AVAssetWriter(uRL = NSURL.fileURLWithPath(outputPath), fileType = AVFileTypeMPEG4, error = null)
        val videoOut = AVAssetReaderVideoCompositionOutput(
            videoTracks = listOf(compLeft, compRight),
            videoSettings = mapOf<Any?, Any?>("PixelFormatType" to NSNumber(unsignedInt = 875704438u)), // 420v
        )
        videoOut.setVideoComposition(videoComposition)
        videoOut.setAlwaysCopiesSampleData(false)
        if (!reader.canAddOutput(videoOut)) throw CompositionException("无法读取合成画面")
        reader.addOutput(videoOut)
        val videoIn = AVAssetWriterInput(
            mediaType = AVMediaTypeVideo,
            outputSettings = mapOf<Any?, Any?>(
                AVVideoCodecKey to AVVideoCodecTypeH264,
                AVVideoWidthKey to NSNumber(int = width),
                AVVideoHeightKey to NSNumber(int = height),
                AVVideoCompressionPropertiesKey to mapOf<Any?, Any?>(
                    AVVideoAverageBitRateKey to NSNumber(int = quality.bitRate),
                    AVVideoMaxKeyFrameIntervalKey to NSNumber(int = 250),
                    AVVideoProfileLevelKey to AVVideoProfileLevelH264HighAutoLevel,
                ),
            ),
        )
        videoIn.setExpectsMediaDataInRealTime(false)
        writer.addInput(videoIn)
        var audioOut: AVAssetReaderTrackOutput? = null
        var audioIn: AVAssetWriterInput? = null
        if (compAudio != null) {
            val out = AVAssetReaderTrackOutput(
                track = compAudio,
                outputSettings = mapOf<Any?, Any?>("AVFormatIDKey" to NSNumber(unsignedInt = 1819304813u)), // 'lpcm'
            )
            val input = AVAssetWriterInput(
                mediaType = AVMediaTypeAudio,
                outputSettings = mapOf<Any?, Any?>(
                    "AVFormatIDKey" to NSNumber(unsignedInt = 1633772320u), // 'aac '
                    "AVSampleRateKey" to NSNumber(double = 48_000.0),
                    "AVNumberOfChannelsKey" to NSNumber(int = 2),
                    "AVEncoderBitRateKey" to NSNumber(int = 128_000),
                ),
            )
            input.setExpectsMediaDataInRealTime(false)
            if (reader.canAddOutput(out) && writer.canAddInput(input)) {
                reader.addOutput(out)
                writer.addInput(input)
                audioOut = out
                audioIn = input
            }
        }
        if (!reader.startReading() || !writer.startWriting()) throw CompositionException("无法开始合成")
        writer.startSessionAtSourceTime(zero)

        val total = CMTimeGetSeconds(duration).coerceAtLeast(0.001)
        var videoDone = false
        var audioDone = audioOut == null
        var lastReported = -1
        try {
            withContext(Dispatchers.Default) {
                while (!(videoDone && audioDone)) {
                    coroutineContext.ensureActive()
                    if (IosAppState.inBackground) {
                        // 旧系统后台不能用硬件编码：停着等回到前台；iOS 26 起有继续处理任务时照常进行。
                        if (!IosAppState.continuedProcessingActive) {
                            onStatus("App 在后台，回到 App 后继续合成。")
                            delay(500)
                            continue
                        }
                    } else {
                        onStatus(null)
                    }
                    var progressed = false
                    if (!videoDone && videoIn.isReadyForMoreMediaData()) {
                        val sample = videoOut.copyNextSampleBuffer()
                        if (sample != null) {
                            val seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
                            val appended = videoIn.appendSampleBuffer(sample)
                            CFRelease(sample)
                            if (!appended) throw CompositionException("写入画面失败")
                            val permille = (seconds / total * 1000).toInt()
                            if (permille != lastReported) {
                                lastReported = permille
                                onProgress((seconds / total).toFloat().coerceIn(0f, 1f))
                            }
                        } else {
                            videoIn.markAsFinished()
                            videoDone = true
                        }
                        progressed = true
                    }
                    val aIn = audioIn
                    val aOut = audioOut
                    if (!audioDone && aIn != null && aOut != null && aIn.isReadyForMoreMediaData()) {
                        val sample = aOut.copyNextSampleBuffer()
                        if (sample != null) {
                            val appended = aIn.appendSampleBuffer(sample)
                            CFRelease(sample)
                            if (!appended) throw CompositionException("写入声音失败")
                        } else {
                            aIn.markAsFinished()
                            audioDone = true
                        }
                        progressed = true
                    }
                    if (reader.status == AVAssetReaderStatusFailed) throw CompositionException("读取失败")
                    if (!progressed) delay(2)
                }
            }
            suspendCancellableCoroutine { continuation ->
                writer.finishWritingWithCompletionHandler { continuation.resumeWith(Result.success(Unit)) }
            }
            if (writer.status != AVAssetWriterStatusCompleted) throw CompositionException("写出文件失败")
            onProgress(1f)
        } catch (error: Throwable) {
            reader.cancelReading()
            writer.cancelWriting()
            throw error
        }
    }
}

internal suspend fun waitUntilForeground() {
    while (IosAppState.inBackground) delay(500)
}

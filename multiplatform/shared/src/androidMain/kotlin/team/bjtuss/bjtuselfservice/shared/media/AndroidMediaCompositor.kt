package team.bjtuss.bjtuselfservice.shared.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.os.Bundle
import android.os.Build
import team.bjtuss.bjtuselfservice.shared.logging.AppLog
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality

/** 合成输出固定 25 fps。 */
private const val OUTPUT_FRAME_RATE = 25

/** I 帧间隔（秒）：10 秒一个关键帧，兼顾体积与拖动。 */
private const val OUTPUT_I_FRAME_INTERVAL_SECONDS = 10

private fun isHardwareCodec(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= 29) {
    info.isHardwareAccelerated
} else {
    !info.name.startsWith("OMX.google.", ignoreCase = true) && !info.name.startsWith("c2.android.", ignoreCase = true)
}

/** 合成器支持的系统 H.264 编码器查询。 */
internal object H264SurfaceEncoder {
    private const val MIME = "video/avc"

    fun isAvailable(): Boolean = candidates().isNotEmpty()

    fun supportsSize(width: Int, height: Int): Boolean = candidates(width, height).isNotEmpty()

    private fun candidates(width: Int? = null, height: Int? = null): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            if (!info.isEncoder || info.supportedTypes.none { it.equals(MIME, ignoreCase = true) }) return@filter false
            val capabilities = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: return@filter false
            if (capabilities.colorFormats.none { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }) return@filter false
            if (width == null || height == null) return@filter true
            runCatching { capabilities.videoCapabilities?.isSizeSupported(width, height) == true }.getOrDefault(false)
        }

    data class Started(val name: String, val codec: MediaCodec, val surface: Surface)

    /** 尺寸检查与实际创建使用同一候选；资源不足时再试其他兼容编码器。 */
    fun start(width: Int, height: Int, bitRate: Int, pinnedName: String?, onStatus: (String?) -> Unit): Started {
        val eligible = prioritizedCodecCandidates(candidates(width, height), pinnedName, { it.name }, ::isHardwareCodec)
        for ((index, info) in eligible.withIndex()) {
            var codec: MediaCodec? = null
            var surface: Surface? = null
            try {
                codec = MediaCodec.createByCodecName(info.name)
                fun format() = MediaFormat.createVideoFormat(MIME, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_I_FRAME_INTERVAL_SECONDS)
                }
                configureOfflineCodec(codec, ::format, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                codec.start()
                if (!isHardwareCodec(info)) onStatus("硬件编码器不可用，正在使用软件编码，可能较慢。")
                else if (index > 0) onStatus("默认编码器不可用，已切换到另一硬件编码器。")
                return Started(info.name, codec, surface)
            } catch (error: Throwable) {
                runCatching { surface?.release() }
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                if (error is CancellationException || error !is Exception) throw error
                AppLog.d("PlaybackCompose", "encoder candidate ${info.name} unavailable (${error::class.simpleName})")
            }
        }
        throw CompositionException(if (pinnedName != null) "原编码器暂不可用，已保留合成分段，请稍后继续。"
            else "没有可用的 H.264 编码器，请稍后重试。")
    }
}

private data class StartedVideoDecoder(val codec: MediaCodec, val hardware: Boolean, val alternate: Boolean)

/** 某个硬件解码器资源不足时，释放它并尝试其他兼容实现。 */
private fun startVideoDecoder(mime: String, format: () -> MediaFormat, surface: Surface): StartedVideoDecoder {
    val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }
    val eligible = prioritizedCodecCandidates(candidates, null, { it.name }, ::isHardwareCodec)
    for ((index, info) in eligible.withIndex()) {
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createByCodecName(info.name)
            configureOfflineCodec(codec, format, surface, 0)
            codec.start()
            return StartedVideoDecoder(codec, isHardwareCodec(info), index > 0)
        } catch (error: Throwable) {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (error is CancellationException || error !is Exception) throw error
            AppLog.d("PlaybackCompose", "decoder candidate ${info.name} unavailable (${error::class.simpleName})")
        }
    }
    throw CompositionException("视频解码器暂不可用，已保留合成断点，请稍后继续。")
}

/**
 * Android 合成：MediaExtractor 读两路 MP4 → 两个 MediaCodec 解码到 SurfaceTexture →
 * OpenGL ES 左右并排画进编码器输入 Surface → MediaCodec H.264 编码 → MediaMuxer 写 MP4；
 * 声音直接拷左路 AAC（不重编码）。
 *
 * 不引入 Media3 等第三方库，只用系统自带编解码。
 */
internal class AndroidMediaCompositor(
    private val leftPath: String,
    private val rightPath: String,
    private val outputPath: String,
    private val quality: PlaybackCompositeQuality,
    private val samplesFactory: (String, String) -> AndroidMediaSamples? = { path, kind -> openFileSamples(path, kind) },
    private val durationUsHint: Long = 0L,
    private val journal: AndroidCompositionJournal? = null,
) {
    suspend fun run(onProgress: (Float) -> Unit, onStatus: (String?) -> Unit) = withContext(Dispatchers.IO) {
        val target = resolveTargetQuality(onStatus)
        val pipeline = MediaComposePipeline(
            leftPath = leftPath,
            rightPath = rightPath,
            outputPath = outputPath,
            width = target.width,
            height = target.height,
            bitRate = target.bitRate,
            samplesFactory = samplesFactory,
            durationUsHint = durationUsHint,
            journal = journal,
            onStatus = onStatus,
        )
        var completed = false
        try {
            pipeline.run(onProgress)
            completed = true
        } finally {
            pipeline.release()
            if (!completed) {
                runCatching { File(outputPath).delete() }
            }
        }
    }

    /**
     * 编码器不支持这个尺寸时退一档（分辨率与码率一起降），并在任务卡片上说明实际尺寸。
     * 模拟器的软件编码器只支持到 1920 宽，1280/960 都能用。
     */
    private fun resolveTargetQuality(onStatus: (String?) -> Unit): PlaybackCompositeQuality {
        if (H264SurfaceEncoder.supportsSize(quality.width, quality.height)) return quality
        var candidate = quality.smaller()
        while (candidate != null) {
            if (H264SurfaceEncoder.supportsSize(candidate.width, candidate.height)) {
                onStatus("设备不支持 ${quality.width}×${quality.height} 合成，已改用 ${candidate.width}×${candidate.height}。")
                return candidate
            }
            candidate = candidate.smaller()
        }
        return quality
    }
}

private class MediaComposePipeline(
    private val leftPath: String,
    private val rightPath: String,
    private val outputPath: String,
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val samplesFactory: (String, String) -> AndroidMediaSamples?,
    private val durationUsHint: Long,
    private val journal: AndroidCompositionJournal?,
    private val onStatus: (String?) -> Unit,
) {
    private var muxer: MediaMuxer? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var renderer: EglComposerRenderer? = null
    private var left: VideoSide? = null
    private var right: VideoSide? = null
    private var audioExtractor: AndroidMediaSamples? = null
    private var audioTrackIndex = -1
    private var audioSampleRate = 44100
    private var audioFramesRead = 0L
    private var partAudioFrames = 0L
    private var audioOriginUs = 0L
    private var audioFormat: MediaFormat? = null
    private var videoFormat: MediaFormat? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var encoderFinished = false
    private val pendingAudio = ArrayDeque<Pair<Long, ByteArray>>()
    private var pendingAudioBytes = 0L
    private val audioSampleBuffer: ByteBuffer = ByteBuffer.allocate(256 * 1024)
    private val frameThread = HandlerThread("PlaybackFrames").apply { start() }
    private val startUs = journal?.resumeUs ?: 0L
    private val skipAudioFrames = journal?.resumeAudioFrames ?: 0L
    private var partStartUs = startUs
    private var nextPartUs = startUs + 60_000_000L
    private var syncRequested = false
    private var lastEncodedPtsUs = -1L
    private var submittedFrames = 0L
    private var encodedFrames = 0L
    private var partVideoFrames = 0L
    private val decoderWorkers = Executors.newFixedThreadPool(2) { worker ->
        Thread(worker, "PlaybackDecode")
    }

    suspend fun run(onProgress: (Float) -> Unit) {
        val muxer = MediaMuxer(journal?.file()?.absolutePath ?: outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        this.muxer = muxer
        prepareAudioTrack(muxer)

        val pinnedEncoder = journal?.encoderName?.takeIf { journal.parts.isNotEmpty() }
        val encoderStarted = H264SurfaceEncoder.start(width, height, bitRate, pinnedEncoder, onStatus)
        val encoder = encoderStarted.codec
        this.encoder = encoder
        val inputSurface = encoderStarted.surface
        encoderSurface = inputSurface
        journal?.bindEncoder(encoderStarted.name)
        AppLog.d("PlaybackCompose", "encoder=${encoder.name} size=${width}x$height")

        val renderer = EglComposerRenderer(inputSurface)
        this.renderer = renderer
        renderer.setCanvasSize(width, height)
        // 解码器输出到 SurfaceTexture 时要建外部纹理，所以先让 GL 上下文生效。
        renderer.makeCurrent()

        val frameHandler = Handler(frameThread.looper)
        val left = VideoSide(leftPath, frameHandler, samplesFactory, durationUsHint, onStatus)
        this.left = left
        val right = VideoSide(rightPath, frameHandler, samplesFactory, durationUsHint, onStatus)
        this.right = right
        left.primeBefore(startUs)
        right.primeBefore(startUs)

        val totalUs = maxOf(left.durationUs, right.durationUs).coerceAtLeast(1_000L)
        val frameIntervalUs = 1_000_000L / OUTPUT_FRAME_RATE
        var timeUs = startUs
        var lastReported = -1
        val started = SystemClock.elapsedRealtimeNanos()
        var decodeNs = 0L
        var drawNs = 0L
        var encodeNs = 0L
        var frames = 0
        val decodeContext = coroutineContext
        fun prepareFrame(targetUs: Long): List<Future<*>> = listOf(
            decoderWorkers.submit { left.advanceTo(targetUs, decodeContext) },
            decoderWorkers.submit { right.advanceTo(targetUs, decodeContext) },
        )
        var prepared = prepareFrame(timeUs)
        while (true) {
            coroutineContext.ensureActive()
            if (timeUs > totalUs + 10_000_000L) throw CompositionException("录像实际时长与播放列表不一致")
            val decodeStart = SystemClock.elapsedRealtimeNanos()
            prepared.forEach { future ->
                try { future.get() } catch (error: ExecutionException) { throw error.cause ?: error }
            }
            left.updateTexture()
            right.updateTexture()
            val nextUs = timeUs + frameIntervalUs
            val hasNext = nextUs <= totalUs || (journal != null && (!left.hasEnded || !right.hasEnded))
            // SurfaceTexture 已取得当前帧；解码器现在准备下一帧，与 GL 和编码器同时工作。
            if (hasNext) prepared = prepareFrame(nextUs)
            val drawStart = SystemClock.elapsedRealtimeNanos()
            decodeNs += drawStart - decodeStart
            if (journal != null && !syncRequested && timeUs >= nextPartUs) {
                encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                syncRequested = true
            }
            renderer.drawFrame(
                leftTexture = left.textureId,
                leftSize = left.sourceSize,
                leftTransform = left.textureTransform,
                rightTexture = right.textureId,
                rightSize = right.sourceSize,
                rightTransform = right.textureTransform,
            )
            renderer.setPresentationTime(timeUs * 1_000L)
            renderer.swapBuffers()
            submittedFrames++
            val encodeStart = SystemClock.elapsedRealtimeNanos()
            drawNs += encodeStart - drawStart
            drainEncoder()
            drainAudio(lastEncodedPtsUs)
            encodeNs += SystemClock.elapsedRealtimeNanos() - encodeStart
            frames++
            if (frames % 1000 == 0) AppLog.d("PlaybackCompose",
                "frames=$frames elapsedMs=${(SystemClock.elapsedRealtimeNanos()-started)/1_000_000} decodeMs=${decodeNs/1_000_000} drawMs=${drawNs/1_000_000} encodeMs=${encodeNs/1_000_000}")
            val permille = ((timeUs.toDouble() / totalUs) * 1000).toInt()
            if (permille != lastReported) {
                lastReported = permille
                onProgress((timeUs.toDouble() / totalUs).toFloat().coerceIn(0f, 1f))
            }
            timeUs += frameIntervalUs
            if (!hasNext) break
        }

        // 收尾：通知编码器输入结束，把剩下的编码输出写进文件。
        encoder.signalEndOfInputStream()
        val drainDeadline = SystemClock.elapsedRealtime() + 30_000
        while (!encoderFinished) {
            coroutineContext.ensureActive()
            if (SystemClock.elapsedRealtime() > drainDeadline) throw CompositionException("编码器收尾超时，已合成分段已保留")
            drainEncoder()
            if (!encoderFinished) Thread.sleep(2)
        }
        drainAudio(Long.MAX_VALUE)
        if (encodedFrames != submittedFrames) throw CompositionException("编码器丢帧，已保留合成断点，请继续重试")
        AppLog.d("PlaybackCompose", "frame audit submitted=$submittedFrames encoded=$encodedFrames")
        if (muxerStarted) {
            this.muxer?.stop()
            this.muxer?.release()
            this.muxer = null
            journal?.commit(lastEncodedPtsUs + frameIntervalUs, partAudioFrames, final = true)
        }
    }

    fun release() {
        decoderWorkers.shutdownNow()
        decoderWorkers.awaitTermination(5, TimeUnit.SECONDS)
        runCatching { renderer?.release() }
        renderer = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        runCatching { left?.release() }
        left = null
        runCatching { right?.release() }
        right = null
        runCatching { audioExtractor?.release() }
        audioExtractor = null
        runCatching { muxer?.release() }
        muxer = null
        if (journal != null && !journal.complete) journal.file().delete()
        frameThread.quitSafely()
    }

    private fun prepareAudioTrack(muxer: MediaMuxer) {
        val extractor = samplesFactory(leftPath, "audio/") ?: return
        try {
            val format = extractor.format
            audioSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            audioOriginUs = journal?.audioOriginUs ?: extractor.sampleTime.coerceAtLeast(0)
            if (journal != null) journal.audioOriginUs = audioOriginUs
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            audioFormat = format
            audioTrackIndex = muxer.addTrack(format)
            audioExtractor = extractor
        } catch (_: Throwable) {
            runCatching { extractor.release() }
        }
    }

    /** 编码输出写进 MP4；首次拿到输出格式时开轨道并启动 muxer。 */
    private fun drainEncoder() {
        val encoder = encoder ?: return
        if (muxer == null) return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        encoder.releaseOutputBuffer(index, false)
                        continue
                    }
                    if (videoTrackIndex >= 0 && info.size > 0) {
                        if (journal != null && info.presentationTimeUs >= nextPartUs &&
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) rollPart(info.presentationTimeUs)
                        val buffer = encoder.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val local = MediaCodec.BufferInfo().apply { set(info.offset, info.size, info.presentationTimeUs - partStartUs, info.flags) }
                            this.muxer?.writeSampleData(videoTrackIndex, buffer, local)
                            encodedFrames++
                            partVideoFrames++
                            lastEncodedPtsUs = info.presentationTimeUs
                        }
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderFinished = true
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (videoTrackIndex < 0) {
                        videoFormat = encoder.outputFormat
                        videoTrackIndex = this.muxer!!.addTrack(encoder.outputFormat)
                        this.muxer!!.start()
                        muxerStarted = true
                    }
                }
                else -> return
            }
        }
    }

    /** 声音直接拷左路 AAC；muxer 还没启动时先在内存里缓一段。 */
    private fun drainAudio(untilUs: Long) {
        val extractor = audioExtractor ?: return
        while (true) {
            val nativeTime = extractor.sampleTime
            if (nativeTime < 0) break
            val sampleTime = nativeTime
            // MediaExtractor 已统一返回微秒，不能再按容器 timescale 换算。
            if (sampleTime > untilUs) break
            audioSampleBuffer.clear()
            val size = extractor.readSampleData(audioSampleBuffer, 0)
            if (size < 0) break
            val data = ByteArray(size)
            audioSampleBuffer.position(0)
            audioSampleBuffer.limit(size)
            audioSampleBuffer.get(data)
            extractor.advance()
            normalizeAacSamples(data, sampleTime, audioSampleRate) { _, frame ->
                val index = audioFramesRead++
                val pts = audioOriginUs + index * 1024 * 1_000_000 / audioSampleRate
                if (index >= skipAudioFrames) {
                    pendingAudio.addLast(pts to frame)
                    pendingAudioBytes += frame.size
                    if (pendingAudioBytes > 8L * 1024 * 1024) throw CompositionException("音频缓冲区未能及时写出")
                }
            }
        }
        flushPendingAudio(untilUs)
    }

    private fun flushPendingAudio(untilUs: Long) {
        if (!muxerStarted) return
        while (pendingAudio.isNotEmpty() && pendingAudio.first().first <= untilUs) {
            val (sampleTime, data) = pendingAudio.removeFirst()
            writeAudio(sampleTime, data)
            pendingAudioBytes -= data.size
        }
    }

    private fun writeAudio(sampleTime: Long, data: ByteArray) {
        val muxer = muxer ?: return
        if (audioTrackIndex < 0) return
        val info = MediaCodec.BufferInfo()
        val pts = if (journal == null) sampleTime else (if (partStartUs == 0L) audioOriginUs else 0L) + partAudioFrames * 1024 * 1_000_000 / audioSampleRate
        info.set(0, data.size, pts, 0)
        muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(data), info)
        partAudioFrames++
    }

    private fun rollPart(boundaryUs: Long) {
        val journal = journal ?: return
        if (partVideoFrames != (boundaryUs - partStartUs) * OUTPUT_FRAME_RATE / 1_000_000)
            throw CompositionException("当前合成分段帧数不完整，已保留之前的断点")
        // 编码器输出可能落后输入几帧，先把边界之前的 AAC 排到旧段，不能按 PES 整组分配。
        drainAudio(boundaryUs - 1)
        muxer!!.stop()
        muxer!!.release()
        muxer = null
        journal.commit(boundaryUs, partAudioFrames)
        partAudioFrames = 0
        partVideoFrames = 0
        partStartUs = boundaryUs
        nextPartUs = boundaryUs + 60_000_000L
        syncRequested = false
        val next = MediaMuxer(journal.file().absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = next
        audioTrackIndex = audioFormat?.let(next::addTrack) ?: -1
        videoTrackIndex = next.addTrack(checkNotNull(videoFormat))
        next.start()
        AppLog.d("PlaybackCompose", "checkpoint parts=${journal.parts.size} throughUs=$boundaryUs")
    }
}

/** 一路视频：抽取视频轨 → MediaCodec 解码 → SurfaceTexture（供 OpenGL 取样）。 */
private class VideoSide(
    path: String,
    frameHandler: Handler,
    samplesFactory: (String, String) -> AndroidMediaSamples?,
    durationUsHint: Long,
    onStatus: (String?) -> Unit,
) {
    private val extractor = samplesFactory(path, "video/") ?: throw CompositionException("找不到视频轨道")
    private val decoder: MediaCodec
    private val frameLock = Object()
    private var frameAvailable = false
    private var pendingIndex = -1
    private var pendingPtsUs = Long.MIN_VALUE
    private var inputDone = false
    private var outputDone = false
    private var primed = false
    private var released = false
    private var idleRetries = 0
    private var texturePending = false
    private data class CompressedFrame(val bytes: ByteArray, val pts: Long, val flags: Int)
    private val preRoll = ArrayDeque<CompressedFrame>()

    val textureId: Int
    val surfaceTexture: SurfaceTexture
    val textureTransform = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
    private val surface: Surface

    var sourceSize: Pair<Int, Int> = 0 to 0
        private set

    val durationUs: Long
    val hasEnded: Boolean get() = outputDone && pendingIndex < 0

    init {
        var createdTexture = 0
        var createdSurfaceTexture: SurfaceTexture? = null
        var createdSurface: Surface? = null
        var createdDecoder: MediaCodec? = null
        try {
            val format = extractor.format
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            sourceSize = sourceWidth to sourceHeight
            durationUs = extractor.durationUs.takeIf { it > 0L } ?: durationUsHint

            createdTexture = createExternalTexture()
            val texture = SurfaceTexture(createdTexture)
            createdSurfaceTexture = texture
            texture.setDefaultBufferSize(sourceWidth, sourceHeight)
            texture.setOnFrameAvailableListener({
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            }, frameHandler)
            val output = Surface(texture)
            createdSurface = output
            val started = startVideoDecoder(mime, { extractor.format }, output)
            createdDecoder = started.codec
            if (!started.hardware) onStatus("硬件解码器不可用，正在使用软件解码，可能较慢。")
            else if (started.alternate) onStatus("默认解码器不可用，已切换到另一硬件解码器。")
            AppLog.d("PlaybackCompose", "decoder=${started.codec.name} size=${sourceWidth}x$sourceHeight")
            textureId = createdTexture
            surfaceTexture = texture
            surface = output
            decoder = started.codec
        } catch (error: Throwable) {
            runCatching { createdDecoder?.stop() }
            runCatching { createdDecoder?.release() }
            runCatching { createdSurface?.release() }
            runCatching { createdSurfaceTexture?.release() }
            if (createdTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(createdTexture), 0)
            runCatching { extractor.release() }
            throw error
        }
    }

    /** 扫描恢复点以前的压缩数据，只给解码器保留最近一个 GOP，不重做已合成内容。 */
    suspend fun primeBefore(targetUs: Long) {
        if (targetUs <= 0L) return
        var retainedBytes = 0L
        var scratch = ByteBuffer.allocateDirect(1024 * 1024)
        while (extractor.sampleTime >= 0 && extractor.sampleTime < targetUs) {
            coroutineContext.ensureActive()
            val flags = extractor.sampleFlags
            if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { preRoll.clear(); retainedBytes = 0L }
            val sizeHint = extractor.sampleSize
            if (sizeHint > 32L * 1024 * 1024) throw CompositionException("单帧过大，无法恢复合成")
            if (sizeHint > scratch.capacity()) scratch = ByteBuffer.allocateDirect(sizeHint.toInt())
            scratch.clear()
            val size = extractor.readSampleData(scratch, 0)
            if (size < 0) break
            val bytes = ByteArray(size)
            scratch.position(0)
            scratch.get(bytes)
            preRoll.addLast(CompressedFrame(bytes, extractor.sampleTime, if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0))
            retainedBytes += size
            if (retainedBytes > 32L * 1024 * 1024) throw CompositionException("关键帧间隔过长，无法恢复合成")
            extractor.advance()
        }
    }

    /** 把这一路推进到“最新一帧 pts ≤ targetUs”，并把该帧送进纹理。 */
    fun advanceTo(targetUs: Long, context: CoroutineContext) {
        val info = MediaCodec.BufferInfo()
        var candidateIndex = -1
        while (true) {
            context.ensureActive()
            if (Thread.currentThread().isInterrupted) throw InterruptedException("decoder stopped")
            if (pendingIndex >= 0) {
                if (pendingPtsUs <= targetUs || (!primed && candidateIndex < 0)) {
                    if (candidateIndex >= 0) decoder.releaseOutputBuffer(candidateIndex, false)
                    candidateIndex = pendingIndex
                    pendingIndex = -1
                    pendingPtsUs = Long.MIN_VALUE
                } else {
                    present(candidateIndex)
                    return
                }
            }
            if (outputDone) { present(candidateIndex); return }
            if (!inputDone) {
                // 离线合成必须先排空输出，不能每帧等 10ms 才发现输入缓冲区已满。
                val inputIndex = decoder.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inputIndex)
                    if (buffer == null) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buffer.clear()
                        val warmup = preRoll.removeFirstOrNull()
                        if (warmup != null) {
                            buffer.put(warmup.bytes)
                            decoder.queueInputBuffer(inputIndex, 0, warmup.bytes.size, warmup.pts, warmup.flags)
                        } else {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                        }
                    }
                    // 用不阻塞的查询把可用输入槽填满，硬件可提前解码；随后再取输出。
                    continue
                }
            }
            when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) {
                        // 输入已结束但解码器还在冲刷尾部帧；连续多次没有输出才算到流末尾。
                        idleRetries++
                        if (idleRetries > IDLE_RETRY_LIMIT) outputDone = true
                    } else {
                        Thread.sleep(1)
                    }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    idleRetries = 0
                    val flags = info.flags
                    if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    } else if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && info.size == 0) {
                        decoder.releaseOutputBuffer(outputIndex, false)
                        outputDone = true
                    } else {
                        pendingIndex = outputIndex
                        pendingPtsUs = info.presentationTimeUs
                        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        }
    }

    private fun present(index: Int) {
        if (index < 0) return
        decoder.releaseOutputBuffer(index, true)
        texturePending = true
        primed = true
    }

    fun updateTexture() {
        if (!texturePending) return
        awaitFrame()
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureTransform)
        texturePending = false
    }

    private fun awaitFrame(timeoutMs: Long = 3_000L) {
        synchronized(frameLock) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (!frameAvailable) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) throw CompositionException("解码画面超时")
                try {
                    frameLock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CompositionException("解码被中断")
                }
            }
            frameAvailable = false
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        runCatching { extractor.release() }
    }
}

/** 建一个可以接 SurfaceTexture 的外部纹理；调用前必须已有 GL 上下文。 */
private fun createExternalTexture(): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
    GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
    GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    return ids[0]
}

private const val TIMEOUT_US = 10_000L

/** 离线工作速率与成片 25fps 分开。设备不接受该提示时用原格式重试，保持兼容性。
 * 参考 AndroidX Transformer DefaultEncoderFactory / DefaultDecoderFactory 的性能设置。
 */
private fun configureOfflineCodec(codec: MediaCodec, format: () -> MediaFormat, surface: Surface?, flags: Int) {
    val fast = format()
    if (flags and MediaCodec.CONFIGURE_FLAG_ENCODE != 0) {
        fast.setInteger(MediaFormat.KEY_PRIORITY, 1)
        // 1000 高于可行工作速率，同时避开部分高通固件对 Int.MAX_VALUE 的溢出问题。
        fast.setInteger(MediaFormat.KEY_OPERATING_RATE, 1000)
    } else {
        val mime = fast.getString(MediaFormat.KEY_MIME) ?: "video/avc"
        val maximum = runCatching {
            codec.codecInfo.getCapabilitiesForType(mime).videoCapabilities
                ?.getSupportedFrameRatesFor(fast.getInteger(MediaFormat.KEY_WIDTH), fast.getInteger(MediaFormat.KEY_HEIGHT))
                ?.upper
                ?: 240.0
        }.getOrDefault(240.0)
        fast.setFloat(MediaFormat.KEY_OPERATING_RATE, maximum.coerceIn(25.0, 1000.0).toFloat())
    }
    try {
        codec.configure(fast, surface, null, flags)
    } catch (_: Exception) {
        AppLog.d("PlaybackCompose", "offline rate hint rejected; using codec defaults")
        codec.reset()
        codec.configure(format(), surface, null, flags)
    }
}

/** 输入结束后的空闲重试次数：10 ms 一次，20 次约 200 ms。 */
private const val IDLE_RETRY_LIMIT = 20

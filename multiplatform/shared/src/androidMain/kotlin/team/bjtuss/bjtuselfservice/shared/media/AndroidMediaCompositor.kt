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
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality

/** 合成输出固定 25 fps。 */
private const val OUTPUT_FRAME_RATE = 25

/** I 帧间隔（秒）：10 秒一个关键帧，兼顾体积与拖动。 */
private const val OUTPUT_I_FRAME_INTERVAL_SECONDS = 10

/** 合成器支持的系统 H.264 编码器查询。 */
internal object H264SurfaceEncoder {
    private const val MIME = "video/avc"

    fun isAvailable(): Boolean = findEncoder() != null

    fun supportsSize(width: Int, height: Int): Boolean {
        val info = findEncoder() ?: return false
        val capabilities = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: return false
        val video = capabilities.videoCapabilities ?: return false
        return runCatching { video.isSizeSupported(width, height) }.getOrDefault(false)
    }

    private fun findEncoder(): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MIME, ignoreCase = true) }) continue
            val capabilities = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: continue
            if (capabilities.colorFormats.any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }) {
                return info
            }
        }
        return null
    }
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
) {
    private var muxer: MediaMuxer? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var renderer: EglComposerRenderer? = null
    private var left: VideoSide? = null
    private var right: VideoSide? = null
    private var audioExtractor: MediaExtractor? = null
    private var audioTrackIndex = -1
    private var audioTimescale = 1
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var encoderFinished = false
    private val pendingAudio = ArrayDeque<Pair<Long, ByteArray>>()
    private var pendingAudioBytes = 0L
    private val audioSampleBuffer: ByteBuffer = ByteBuffer.allocate(256 * 1024)

    suspend fun run(onProgress: (Float) -> Unit) {
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        this.muxer = muxer
        prepareAudioTrack(muxer)

        val encoder = MediaCodec.createEncoderByType("video/avc")
        this.encoder = encoder
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_I_FRAME_INTERVAL_SECONDS)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoderSurface = inputSurface
        encoder.start()

        val renderer = EglComposerRenderer(inputSurface)
        this.renderer = renderer
        renderer.setCanvasSize(width, height)
        // 解码器输出到 SurfaceTexture 时要建外部纹理，所以先让 GL 上下文生效。
        renderer.makeCurrent()

        val left = VideoSide(leftPath)
        val right = VideoSide(rightPath)
        this.left = left
        this.right = right

        val totalUs = maxOf(left.durationUs, right.durationUs).coerceAtLeast(1_000L)
        val frameIntervalUs = 1_000_000L / OUTPUT_FRAME_RATE
        var timeUs = 0L
        var lastReported = -1
        while (timeUs <= totalUs) {
            coroutineContext.ensureActive()
            left.advanceTo(timeUs)
            right.advanceTo(timeUs)
            renderer.drawFrame(
                leftTexture = left.textureId,
                leftSize = left.sourceSize,
                rightTexture = right.textureId,
                rightSize = right.sourceSize,
            )
            renderer.setPresentationTime(timeUs * 1_000L)
            renderer.swapBuffers()
            drainEncoder()
            drainAudio(timeUs)
            val permille = ((timeUs.toDouble() / totalUs) * 1000).toInt()
            if (permille != lastReported) {
                lastReported = permille
                onProgress((timeUs.toDouble() / totalUs).toFloat().coerceIn(0f, 1f))
            }
            timeUs += frameIntervalUs
        }

        // 收尾：通知编码器输入结束，把剩下的编码输出写进文件。
        encoder.signalEndOfInputStream()
        while (!encoderFinished) {
            drainEncoder()
            if (!encoderFinished) Thread.sleep(2)
        }
        drainAudio(Long.MAX_VALUE)
        if (muxerStarted) {
            runCatching { muxer.stop() }
        }
    }

    fun release() {
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
    }

    private fun prepareAudioTrack(muxer: MediaMuxer) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(leftPath)
            val track = selectTrack(extractor, "audio/") ?: return
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            audioTimescale = runCatching { format.getInteger(KEY_TIME_SCALE) }
                .getOrDefault(1)
                .coerceAtLeast(1)
            audioTrackIndex = muxer.addTrack(format)
            audioExtractor = extractor
        } catch (_: Throwable) {
            runCatching { extractor.release() }
        }
    }

    /** 编码输出写进 MP4；首次拿到输出格式时开轨道并启动 muxer。 */
    private fun drainEncoder() {
        val encoder = encoder ?: return
        val muxer = muxer ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        encoder.releaseOutputBuffer(index, false)
                        continue
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoder.releaseOutputBuffer(index, false)
                        encoderFinished = true
                        continue
                    }
                    if (videoTrackIndex >= 0) {
                        val buffer = encoder.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrackIndex, buffer, info)
                        }
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (videoTrackIndex < 0) {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        flushPendingAudio()
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
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0) return
            val sampleUs = sampleTime * 1_000_000L / audioTimescale
            if (sampleUs > untilUs) return
            val size = extractor.readSampleData(audioSampleBuffer, 0)
            if (size < 0) return
            val data = ByteArray(size)
            audioSampleBuffer.position(0)
            audioSampleBuffer.limit(size)
            audioSampleBuffer.get(data)
            extractor.advance()
            if (muxerStarted) {
                writeAudio(sampleTime, data)
            } else {
                pendingAudio.addLast(sampleTime to data)
                pendingAudioBytes += size
                // 正常情况下只会缓冲开头一瞬；超过上限就丢弃，保证不占内存。
                if (pendingAudioBytes > 8L * 1024 * 1024) {
                    pendingAudio.clear()
                    pendingAudioBytes = 0
                }
            }
        }
    }

    private fun flushPendingAudio() {
        val muxer = muxer ?: return
        while (pendingAudio.isNotEmpty()) {
            val (sampleTime, data) = pendingAudio.removeFirst()
            writeAudio(sampleTime, data)
        }
        pendingAudioBytes = 0
    }

    private fun writeAudio(sampleTime: Long, data: ByteArray) {
        val muxer = muxer ?: return
        if (audioTrackIndex < 0) return
        val info = MediaCodec.BufferInfo()
        info.set(0, data.size, sampleTime, 0)
        muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(data), info)
    }
}

/** 一路视频：抽取视频轨 → MediaCodec 解码 → SurfaceTexture（供 OpenGL 取样）。 */
private class VideoSide(private val path: String) {
    private val extractor = MediaExtractor()
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
    private var lastRenderedPtsUs = Long.MIN_VALUE

    val textureId: Int
    val surfaceTexture: SurfaceTexture
    private val surface: Surface

    var sourceSize: Pair<Int, Int> = 0 to 0
        private set

    val durationUs: Long

    init {
        extractor.setDataSource(path)
        val track = selectTrack(extractor, "video/")
            ?: throw CompositionException("找不到视频轨道")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
        val sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        val sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        sourceSize = sourceWidth to sourceHeight
        durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)

        textureId = createExternalTexture()
        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setDefaultBufferSize(sourceWidth, sourceHeight)
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(frameLock) {
                frameAvailable = true
                frameLock.notifyAll()
            }
        }
        surface = Surface(surfaceTexture)
        decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, surface, null, 0)
        decoder.start()
    }

    /** 把这一路推进到“最新一帧 pts ≤ targetUs”，并把该帧送进纹理。 */
    suspend fun advanceTo(targetUs: Long) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            coroutineContext.ensureActive()
            if (pendingIndex >= 0) {
                if (pendingPtsUs <= targetUs || !primed) {
                    renderPending()
                    continue
                }
                // 下一帧还在后面，当前纹理里的帧就是这一时刻该显示的画面。
                return
            }
            if (outputDone) return
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inputIndex)
                    if (buffer == null) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
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
                    } else if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoder.releaseOutputBuffer(outputIndex, false)
                        outputDone = true
                    } else {
                        pendingIndex = outputIndex
                        pendingPtsUs = info.presentationTimeUs
                    }
                }
            }
        }
    }

    private fun renderPending() {
        val index = pendingIndex
        val pts = pendingPtsUs
        pendingIndex = -1
        pendingPtsUs = Long.MIN_VALUE
        decoder.releaseOutputBuffer(index, true)
        awaitFrame()
        runCatching { surfaceTexture.updateTexImage() }
        primed = true
        lastRenderedPtsUs = pts
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

private fun selectTrack(extractor: MediaExtractor, prefix: String): Int {
    for (index in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(prefix, ignoreCase = true)) return index
    }
    return -1
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

/** MediaFormat 没有公开的 timescale 常量，音轨时长换算要用这个键。 */
private const val KEY_TIME_SCALE = "timescale"

/** 输入结束后的空闲重试次数：10 ms 一次，20 次约 200 ms。 */
private const val IDLE_RETRY_LIMIT = 20

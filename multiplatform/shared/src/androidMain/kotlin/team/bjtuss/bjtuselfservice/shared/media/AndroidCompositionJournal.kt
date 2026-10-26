package team.bjtuss.bjtuselfservice.shared.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

@Serializable
internal data class EncodedPart(val startUs: Long, val endUs: Long, val audioFrames: Long? = null)
@Serializable
private data class EncodedIndex(val quality: String, val parts: List<EncodedPart>, val complete: Boolean = false,
    val audioOriginUs: Long? = null)

/** 只提交已 stop/release 的独立 MP4；崩溃最多重做当前约一分钟媒体内容。 */
internal class AndroidCompositionJournal(private val directory: File, private val quality: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val index = AtomicFile(File(directory, "index.json"))
    val parts = mutableListOf<EncodedPart>()
    var complete = false
        private set
    var audioOriginUs: Long? = null
    val resumeUs: Long get() = parts.lastOrNull()?.endUs ?: 0L
    val resumeAudioFrames: Long get() = parts.sumOf { it.audioFrames ?: 0L }
    fun file(index: Int = parts.size): File = File(directory, "part-$index.mp4")

    init {
        check(directory.isDirectory || directory.mkdirs())
        if (File(directory, "index.json").exists() || File(directory, "index.json.bak").exists()) {
            val saved = json.decodeFromString<EncodedIndex>(index.openRead().bufferedReader().use { it.readText() })
            if (saved.quality != quality) throw CompositionException("合成断点清晰度不一致")
            audioOriginUs = saved.audioOriginUs
            var end = 0L
            for ((i, part) in saved.parts.withIndex()) {
                if (part.startUs != end || part.endUs <= end || !file(i).isFile || file(i).length() == 0L) break
                parts += if (part.audioFrames != null) part else part.copy(audioFrames = inspectAudio(file(i), i == 0))
                end = part.endUs
            }
            complete = saved.complete && parts.size == saved.parts.size
        }
        // 上次中断的当前段没有进入索引，不能误当作已完成部分。
        if (!complete) file().delete()
    }

    fun commit(endUs: Long, audioFrames: Long, final: Boolean = false) {
        check(endUs > resumeUs && file().length() > 0)
        parts += EncodedPart(resumeUs, endUs, audioFrames)
        complete = final
        val stream = index.startWrite()
        try {
            stream.write(json.encodeToString(EncodedIndex(quality, parts.toList(), complete, audioOriginUs)).toByteArray())
            index.finishWrite(stream)
        } catch (error: Throwable) { index.failWrite(stream); throw error }
    }

    /** 无重编码整理各段。用原来的全局时间戳，不累加各容器尾部的四舍五入时长。 */
    fun concatenate(output: File, context: CoroutineContext, onProgress: (Float) -> Unit) {
        check(complete && parts.isNotEmpty())
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val formats = mutableMapOf<String, MediaFormat>()
        val targets = mutableMapOf<String, Int>()
        val lastPts = mutableMapOf<String, Long>()
        var audioFrames = 0L
        var audioRate = 44100
        val info = MediaCodec.BufferInfo()
        var buffer = ByteBuffer.allocateDirect(1024 * 1024)
        var success = false
        val totalBytes = parts.indices.sumOf { file(it).length() }.coerceAtLeast(1)
        var copied = 0L
        var reported = -1
        onProgress(0f)
        try {
            for ((partIndex, part) in parts.withIndex()) {
                context.ensureActive()
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file(partIndex).absolutePath)
                    val mapping = mutableMapOf<Int, String>()
                    for (track in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(track)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                        val kind = mime.substringBefore('/')
                        mapping[track] = kind
                        if (partIndex == 0) {
                            formats[kind] = format
                            if (kind == "audio") audioRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            targets[kind] = muxer.addTrack(format)
                        } else {
                            val previous = formats[kind] ?: throw CompositionException("合成分段音视频轨道不一致")
                            if (mime != previous.getString(MediaFormat.KEY_MIME) || !sameConfig(previous, format))
                                throw CompositionException("合成分段编码配置不一致，原分段已保留")
                        }
                        extractor.selectTrack(track)
                    }
                    if (partIndex == 0) muxer.start()
                    while (extractor.sampleTrackIndex >= 0) {
                        context.ensureActive()
                        val kind = mapping.getValue(extractor.sampleTrackIndex)
                        val sizeHint = extractor.sampleSize
                        if (sizeHint > buffer.capacity()) buffer = ByteBuffer.allocateDirect(sizeHint.toInt())
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        // AAC 按采样数连接，避免 TS 的 PES 分组时间戳误差在跨段/恢复时累积。
                        val pts = if (kind == "audio") (audioOriginUs ?: 0L) + audioFrames++ * 1024 * 1_000_000 / audioRate
                            else part.startUs + extractor.sampleTime
                        if (pts <= (lastPts[kind] ?: -1L)) throw CompositionException("合成分段时间戳重叠")
                        lastPts[kind] = pts
                        info.set(0, size, pts, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(targets.getValue(kind), buffer, info)
                        copied += size
                        val progress = (copied.toDouble() / totalBytes).coerceIn(0.0, 0.999)
                        val bucket = (progress * 200).toInt()
                        if (bucket != reported) { reported = bucket; onProgress(progress.toFloat()) }
                        extractor.advance()
                    }
                } finally { extractor.release() }
            }
            muxer.stop()
            success = true
            onProgress(1f)
        } finally {
            muxer.release()
            if (!success) output.delete()
        }
    }

    private fun inspectAudio(file: File, first: Boolean): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return 0L
            extractor.selectTrack(track)
            if (first && audioOriginUs == null && extractor.sampleTime >= 0) audioOriginUs = extractor.sampleTime
            var count = 0L
            while (extractor.sampleTime >= 0) { count++; extractor.advance() }
            return count
        } finally { extractor.release() }
    }

    private fun sameConfig(a: MediaFormat, b: MediaFormat): Boolean = (0..1).all { index ->
        fun bytes(format: MediaFormat): ByteArray? = format.getByteBuffer("csd-$index")?.duplicate()?.let { data ->
            ByteArray(data.remaining()).also(data::get)
        }
        val first = bytes(a)
        val next = bytes(b)
        if (first == null || next == null) first == null && next == null else first.contentEquals(next)
    }
}

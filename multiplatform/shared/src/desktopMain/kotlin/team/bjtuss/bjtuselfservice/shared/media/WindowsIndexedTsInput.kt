package team.bjtuss.bjtuselfservice.shared.media

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 顺序解析 TS，先落压缩样本，再公开定长索引；原生读取端可等待后续索引而不用等待整节下载。
 * 这些索引可从持久化的 HLS 分片重建，不承担断点的唯一副本。
 */
internal class WindowsIndexedTsInput(private val directory: File, private val needsAudio: Boolean) {
    private val data: BufferedOutputStream
    private val videoIndex: BufferedOutputStream
    private val audioIndex: BufferedOutputStream
    private var origin90k: Long? = null
    private var audioOrigin100ns: Long? = null
    private var audioFrames = 0L
    @Volatile private var videoConfigWritten = false
    @Volatile private var audioConfigWritten = false
    @Volatile private var noAudioWritten = false
    private var finished = false

    private val remuxer = TsToMp4Remuxer(collectSampleTable = false, onSample = { sample ->
        val origin = origin90k ?: sample.timestamp90k.also { origin90k = it }
        val pts = if (sample.video) (sample.timestamp90k - origin) * 1000L / 9L else {
            val first = audioOrigin100ns ?: ((sample.timestamp90k - origin) * 1000L / 9L).also { audioOrigin100ns = it }
            val rate = checkNotNull(remuxerAudioRate())
            first + audioFrames++ * 1024L * 10_000_000L / rate
        }
        val record = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(sample.offset).putLong(pts).putInt(sample.size).putInt(if (sample.sync) 1 else 0).array()
        (if (sample.video) videoIndex else audioIndex).write(record)
    }) { data.write(it) }

    init {
        check(directory.isDirectory || directory.mkdirs())
        // 旧输入可由 HLS 检查点重新生成，不能让上次的 finished/config 被新读取端误用。
        listOf("finished", "video-config.bin", "audio-config.bin", "no-audio", "video-config.bin.tmp", "audio-config.bin.tmp")
            .forEach { File(directory, it).delete() }
        data = BufferedOutputStream(FileOutputStream(File(directory, "samples.data")), 256 * 1024)
        videoIndex = BufferedOutputStream(FileOutputStream(File(directory, "video.idx")), 256 * 1024)
        audioIndex = BufferedOutputStream(FileOutputStream(File(directory, "audio.idx")), 256 * 1024)
    }

    private fun remuxerAudioRate(): Int? = remuxer.audioConfiguration?.sampleRate

    suspend fun append(bytes: ByteArray) = withContext(Dispatchers.IO) {
        check(!finished)
        remuxer.feed(bytes)
        publish()
    }

    suspend fun finish() = withContext(Dispatchers.IO) {
        if (finished) return@withContext
        remuxer.finish()
        publish()
        if (needsAudio && !audioConfigWritten && !noAudioWritten) publishNoAudio()
        File(directory, "finished").writeBytes(byteArrayOf())
        finished = true
    }

    private fun publish() {
        // 文件数据必须先可见，随后才能公开对应的样本索引。
        data.flush()
        videoIndex.flush()
        audioIndex.flush()
        if (!videoConfigWritten) remuxer.videoConfiguration?.let { config ->
            val bytes = ByteBuffer.allocate(16 + config.sps.size + config.pps.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(config.width).putInt(config.height).putInt(config.sps.size).putInt(config.pps.size)
                .put(config.sps).put(config.pps).array()
            writeAtomic("video-config.bin", bytes)
            videoConfigWritten = true
        }
        if (needsAudio && !audioConfigWritten) remuxer.audioConfiguration?.let { config ->
            if (noAudioWritten) throw CompositionException("录像音轨在下载途中发生变化")
            val bytes = ByteBuffer.allocate(12 + config.specific.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(config.sampleRate).putInt(config.channels).putInt(config.specific.size).put(config.specific).array()
            writeAtomic("audio-config.bin", bytes)
            audioConfigWritten = true
        }
        if (needsAudio && !audioConfigWritten && !noAudioWritten && remuxer.hasAudioTrack == false) publishNoAudio()
    }

    private fun publishNoAudio() {
        File(directory, "no-audio").writeBytes(byteArrayOf())
        noAudioWritten = true
    }

    private fun writeAtomic(name: String, bytes: ByteArray) {
        val temporary = File(directory, "$name.tmp").toPath()
        val destination = File(directory, name).toPath()
        Files.write(temporary, bytes)
        try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    val videoReady: Boolean get() = videoConfigWritten
    val audioReady: Boolean get() = !needsAudio || audioConfigWritten || noAudioWritten

    fun close() {
        runCatching { data.close() }
        runCatching { videoIndex.close() }
        runCatching { audioIndex.close() }
        listOf("samples.data", "video.idx", "audio.idx", "video-config.bin", "audio-config.bin", "no-audio", "finished",
            "video-config.bin.tmp", "audio-config.bin.tmp").forEach { File(directory, it).delete() }
        directory.delete()
    }
}

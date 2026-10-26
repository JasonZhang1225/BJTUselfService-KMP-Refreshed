package team.bjtuss.bjtuselfservice.shared.media

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** TS 只解析一次：压缩帧和定长索引落盘，直接供硬件解码，避免再封装、再跨进程抽取。 */
internal class AndroidIndexedTsInput(private val directory: File, private val durationHintUs: Long) {
    init { check(directory.isDirectory || directory.mkdirs()) }
    private val lock = Object()
    private val dataFile = File(directory, "samples.data")
    private val data = dataFile.outputStream().buffered(256 * 1024)
    private val indexes = List(2) { DataOutputStream(File(directory, "track-$it.idx").outputStream().buffered(256 * 1024)) }
    private val pendingCounts = IntArray(2)
    private val counts = IntArray(2)
    private var origin90k: Long? = null
    private var finished = false
    private var cancelled = false
    @Volatile var readerJob: Job? = null
    private val remuxer = TsToMp4Remuxer(collectSampleTable = false, onSample = { sample ->
        if (origin90k == null) origin90k = sample.timestamp90k
        val track = if (sample.video) 0 else 1
        indexes[track].apply {
            writeLong(sample.offset)
            writeLong((sample.timestamp90k - checkNotNull(origin90k)) * 1_000_000 / 90_000)
            writeInt(sample.size)
            writeInt(if (sample.sync) MediaExtractor.SAMPLE_FLAG_SYNC else 0)
        }
        pendingCounts[track]++
    }) { data.write(it) }

    suspend fun append(bytes: ByteArray) = withContext(Dispatchers.IO) {
        remuxer.feed(bytes)
        publish()
    }

    suspend fun finish() = withContext(Dispatchers.IO) {
        remuxer.finish()
        publish()
        synchronized(lock) { finished = true; lock.notifyAll() }
    }

    private fun publish() {
        data.flush()
        indexes.forEach { it.flush() }
        synchronized(lock) {
            for (i in counts.indices) { counts[i] += pendingCounts[i]; pendingCounts[i] = 0 }
            lock.notifyAll()
        }
    }

    private fun awaitSample(track: Int, index: Int): Boolean = synchronized(lock) {
        while (index >= counts[track] && !finished && !cancelled) {
            if (readerJob?.isActive == false) throw IOException("composition interrupted")
            lock.wait(100)
        }
        if (cancelled || readerJob?.isActive == false) throw IOException("composition interrupted")
        index < counts[track]
    }

    fun open(kind: String): AndroidMediaSamples? {
        val track = if (kind == "video/") 0 else 1
        if (!awaitSample(track, 0)) return null
        val format = synchronized(lock) {
            if (track == 0) {
                val config = checkNotNull(remuxer.videoConfiguration)
                MediaFormat.createVideoFormat("video/avc", config.width, config.height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + config.sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + config.pps))
                    setLong(MediaFormat.KEY_DURATION, durationHintUs)
                }
            } else {
                val config = checkNotNull(remuxer.audioConfiguration)
                MediaFormat.createAudioFormat("audio/mp4a-latm", config.sampleRate, config.channels).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(config.specific))
                    setLong(MediaFormat.KEY_DURATION, durationHintUs)
                }
            }
        }
        return object : AndroidMediaSamples {
            val records = DataInputStream(File(directory, "track-$track.idx").inputStream().buffered(256 * 1024))
            val input = RandomAccessFile(dataFile, "r")
            var index = -1
            var offset = 0L
            var time = -1L
            var size = 0
            var flags = 0
            var bytes = ByteArray(256 * 1024)
            init { advance() }
            override val format = format
            override val durationUs get() = durationHintUs
            override val sampleTime get() = time
            override val sampleFlags get() = flags
            override val sampleSize get() = size.toLong()
            override fun advance(): Boolean {
                if (!awaitSample(track, ++index)) { time = -1; return false }
                offset = records.readLong(); time = records.readLong()
                size = records.readInt(); flags = records.readInt()
                return true
            }
            override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
                if (time < 0) return -1
                if (size > bytes.size) bytes = ByteArray(size)
                input.seek(this.offset)
                input.readFully(bytes, 0, size)
                if (track == 0) {
                    var at = 0
                    while (at + 4 <= size) {
                        val length = ((bytes[at].toInt() and 255) shl 24) or ((bytes[at + 1].toInt() and 255) shl 16) or
                            ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)
                        if (length <= 0 || length > size - at - 4) throw IOException("invalid AVC sample")
                        bytes[at] = 0; bytes[at + 1] = 0; bytes[at + 2] = 0; bytes[at + 3] = 1
                        at += 4 + length
                    }
                }
                buffer.position(offset)
                buffer.put(bytes, 0, size)
                return size
            }
            override fun release() { records.close(); input.close() }
        }
    }

    fun cancel() {
        synchronized(lock) { cancelled = true; lock.notifyAll() }
        runCatching { data.close() }
        indexes.forEach { runCatching { it.close() } }
    }
}

package team.bjtuss.bjtuselfservice.shared.media

import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/** 已选定一路轨道，时间戳都在整节录像的同一时间轴上。 */
internal interface AndroidMediaSamples {
    val format: MediaFormat
    val durationUs: Long
    val sampleTime: Long
    val sampleFlags: Int
    val sampleSize: Long
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int
    fun advance(): Boolean
    fun release()
}

internal class ExtractorMediaSamples(private val extractor: MediaExtractor, private val track: Int,
    private val offsetUs: Long = 0L) : AndroidMediaSamples {
    override val format: MediaFormat get() = extractor.getTrackFormat(track)
    override val durationUs: Long get() = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
    override val sampleTime: Long get() = extractor.sampleTime.let { if (it < 0) it else it + offsetUs }
    override val sampleFlags: Int get() = extractor.sampleFlags
    override val sampleSize: Long get() = extractor.sampleSize
    override fun readSampleData(buffer: ByteBuffer, offset: Int) = extractor.readSampleData(buffer, offset)
    override fun advance() = extractor.advance()
    override fun release() = extractor.release()
}

internal fun openFileSamples(path: String, kind: String, offsetUs: Long = 0): AndroidMediaSamples? {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true }
        if (track == null) { extractor.release(); return null }
        extractor.selectTrack(track)
        return ExtractorMediaSamples(extractor, track, offsetUs)
    } catch (error: Throwable) { extractor.release(); throw error }
}

package team.bjtuss.bjtuselfservice.shared.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 夹具为 ffmpeg 生成的 64×48 测试图 + 正弦波（H.264 Baseline、AAC-LC 48 kHz 双声道，每段 2 秒），与录播流结构一致。 */
class TsToMp4RemuxerTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/media/$name")).use { it.readBytes() }

    private suspend fun remux(chunks: List<ByteArray>): Pair<TsToMp4Remuxer, ByteArray> {
        val data = ByteArrayBuilder()
        val remuxer = TsToMp4Remuxer { data.append(it) }
        chunks.forEach { remuxer.feed(it) }
        remuxer.finish()
        return remuxer to (remuxer.buildHeader() + data.toByteArray())
    }

    @Test
    fun remuxesSegmentsIntoPlayableMp4WithAllSamples() = runBlocking {
        val (remuxer, mp4) = remux(listOf(fixture("synthetic-0.ts"), fixture("synthetic-1.ts")))
        assertEquals(100, remuxer.videoSampleCount)
        assertEquals(189, remuxer.audioSampleCount)
        assertEquals("ftyp", mp4.copyOfRange(4, 8).decodeToString())
        val boxes = topLevelBoxes(mp4)
        assertEquals(listOf("ftyp", "moov", "mdat"), boxes.map { it.first })
        // mdat 用 64 位长度且恰好覆盖到文件末尾。
        val mdatStart = boxes.last().second
        assertEquals(mp4.size.toLong() - mdatStart, readLong(mp4, mdatStart + 8))
        val moov = mp4.copyOfRange(boxes[1].second, mdatStart)
        assertTrue(moov.containsAscii("avcC") && moov.containsAscii("esds") && moov.containsAscii("stss"))
        // 第一个视频样本必须落在 mdat 里，且是长度前缀格式的 NAL。
        val firstOffset = firstChunkOffset(moov).toInt()
        assertTrue(firstOffset >= mdatStart + 16)
        val nalLength = readInt(mp4, firstOffset)
        assertTrue(nalLength in 1..mp4.size)
        assertEquals(64 to 48, parseSpsDimensionsFromMoov(moov))
    }

    @Test
    fun packetsSplitAcrossFeedsGiveIdenticalOutput() = runBlocking {
        val whole = fixture("synthetic-0.ts") + fixture("synthetic-1.ts")
        val (_, expected) = remux(listOf(whole))
        val odd = whole.toList().chunked(1000).map { it.toByteArray() }
        val (_, actual) = remux(odd)
        assertContentEquals(expected, actual)
    }

    @Test
    fun rejectsStreamWithoutVideo() = runBlocking {
        val remuxer = TsToMp4Remuxer { }
        remuxer.feed(ByteArray(188 * 3) { if (it % 188 == 0) 0x47 else 0x00 })
        assertFailsWith<RemuxException> { remuxer.finish() }
        Unit
    }

    private fun topLevelBoxes(bytes: ByteArray): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        var i = 0
        while (i + 8 <= bytes.size) {
            val size32 = readInt(bytes, i).toLong() and 0xFFFFFFFFL
            val type = bytes.copyOfRange(i + 4, i + 8).decodeToString()
            val size = if (size32 == 1L) readLong(bytes, i + 8) else size32
            result += type to i
            i += size.toInt()
        }
        return result
    }

    private fun firstChunkOffset(moov: ByteArray): Long {
        val at = moov.indexOfAscii("co64")
        return readLong(moov, at + 12)
    }

    private fun parseSpsDimensionsFromMoov(moov: ByteArray): Pair<Int, Int>? {
        val at = moov.indexOfAscii("avcC") + 4
        val spsLength = ((moov[at + 6].toInt() and 0xFF) shl 8) or (moov[at + 7].toInt() and 0xFF)
        return parseSpsDimensions(moov.copyOfRange(at + 8, at + 8 + spsLength))
    }

    private fun ByteArray.indexOfAscii(s: String): Int {
        val needle = s.encodeToByteArray()
        for (i in 0..size - needle.size) if ((needle.indices).all { this[i + it] == needle[it] }) return i
        return -1
    }

    private fun ByteArray.containsAscii(s: String) = indexOfAscii(s) >= 0

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun readLong(b: ByteArray, at: Int): Long =
        ((readInt(b, at).toLong() and 0xFFFFFFFFL) shl 32) or (readInt(b, at + 4).toLong() and 0xFFFFFFFFL)
}

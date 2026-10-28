package team.bjtuss.bjtuselfservice.shared.media

import kotlin.test.*

class AacSampleNormalizerTest {
    private fun adts(payload: ByteArray, crc: Boolean = false): ByteArray {
        val header = if (crc) 9 else 7
        val size = payload.size + header
        return byteArrayOf(0xff.toByte(), (if (crc) 0xf0 else 0xf1).toByte(), 0x50, (0x40 or (size shr 11)).toByte(),
            (size shr 3).toByte(), (((size and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte()) +
            (if (crc) byteArrayOf(0, 0) else byteArrayOf()) + payload
    }
    @Test fun splitsGroupedAdtsAndKeepsEachFramePayloadAndTimestamp() {
        val result = mutableListOf<Pair<Long, ByteArray>>()
        normalizeAacSamples(adts(byteArrayOf(1, 2, 3)) + adts(byteArrayOf(4, 5), crc = true), 1000000, 44100) { pts, data -> result += pts to data }
        assertEquals(listOf(1000000L, 1023219L), result.map { it.first })
        assertContentEquals(byteArrayOf(1, 2, 3), result[0].second)
        assertContentEquals(byteArrayOf(4, 5), result[1].second)
    }
    @Test fun rawMp4AacRemainsUnchangedAndTruncatedAdtsFails() {
        val raw = byteArrayOf(1, 2, 3)
        normalizeAacSamples(raw, 123, 44100) { pts, bytes -> assertEquals(123, pts); assertSame(raw, bytes) }
        assertFailsWith<CompositionException> { normalizeAacSamples(adts(raw).dropLast(1).toByteArray(), 0, 44100) { _, _ -> } }
    }
}

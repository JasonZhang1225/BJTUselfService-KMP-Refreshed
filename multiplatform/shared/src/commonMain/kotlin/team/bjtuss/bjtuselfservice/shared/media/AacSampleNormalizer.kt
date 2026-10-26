package team.bjtuss.bjtuselfservice.shared.media

/** TS 解复用器可能一次返回一组 ADTS 帧；MP4 每个样本必须是去掉 ADTS 头的一帧 AAC。 */
internal fun normalizeAacSamples(
    bytes: ByteArray,
    presentationTimeUs: Long,
    sampleRate: Int,
    write: (Long, ByteArray) -> Unit,
) {
    fun isAdts(at: Int): Boolean = at + 7 <= bytes.size && bytes[at].toInt() and 0xff == 0xff &&
        bytes[at + 1].toInt() and 0xf6 == 0xf0
    if (!isAdts(0)) { write(presentationTimeUs, bytes); return }
    var offset = 0
    var samples = 0L
    while (offset < bytes.size) {
        if (!isAdts(offset)) throw CompositionException("AAC 分帧头不完整")
        val headerSize = if (bytes[offset + 1].toInt() and 1 != 0) 7 else 9
        val length = ((bytes[offset + 3].toInt() and 3) shl 11) or
            ((bytes[offset + 4].toInt() and 0xff) shl 3) or ((bytes[offset + 5].toInt() and 0xe0) shr 5)
        if (length <= headerSize || offset + length > bytes.size || sampleRate <= 0) throw CompositionException("AAC 分帧长度不完整")
        write(presentationTimeUs + samples * 1_000_000 / sampleRate, bytes.copyOfRange(offset + headerSize, offset + length))
        samples += 1024 * ((bytes[offset + 6].toInt() and 3) + 1)
        offset += length
    }
}

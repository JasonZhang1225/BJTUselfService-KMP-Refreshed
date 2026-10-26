package team.bjtuss.bjtuselfservice.shared.media

/**
 * 把学校录播的 MPEG-TS 分段（H.264 + AAC ADTS）无损改封装为 MP4，不解码、不重新编码。
 *
 * 用法：按顺序 [feed] 每个 .ts 分段；样本字节经 [writeSample] 依次追加到数据文件；
 * 全部喂完后 [finish]，再用 [buildHeader] 生成 `ftyp + moov + mdat 头`，
 * 最终文件 = 头 + 数据文件原样拼接。`moov` 放在最前面，播放器不用读到文件末尾就能起播。
 *
 * 只处理录播实际使用的流类型：H.264（0x1B，avc1 + avcC）与 AAC-ADTS（0x0F）；其余（如 ID3 元数据）忽略。
 */
class TsToMp4Remuxer(private val writeSample: suspend (ByteArray) -> Unit) {
    private val carry = ByteArrayBuilder()
    private var pmtPid = -1
    private var videoPid = -1
    private var audioPid = -1
    private val pes = mutableMapOf<Int, ByteArrayBuilder>()

    private val video = TrackTable()
    private val audio = TrackTable()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var audioConfig: AdtsConfig? = null
    private var videoDtsUnwrap = TimestampUnwrapper()
    private var audioPtsUnwrap = TimestampUnwrapper()
    private var nextAudioPts90k: Long? = null
    private var dataOffset = 0L
    private var finished = false

    val videoSampleCount: Int get() = video.count
    val audioSampleCount: Int get() = audio.count
    val dataSize: Long get() = dataOffset

    suspend fun feed(bytes: ByteArray) {
        check(!finished)
        carry.append(bytes)
        val buffer = carry.toByteArray()
        var index = 0
        // 找同步字节：分段之间理论上对齐，但容忍前导垃圾。
        while (index + TS_PACKET <= buffer.size) {
            if (buffer[index] != SYNC) {
                index++
                continue
            }
            handlePacket(buffer, index)
            index += TS_PACKET
        }
        carry.clear()
        if (index < buffer.size) carry.append(buffer, index, buffer.size - index)
    }

    suspend fun finish() {
        if (finished) return
        if (videoPid >= 0) pes.remove(videoPid)?.let { flushPes(videoPid, it.toByteArray()) }
        if (audioPid >= 0) pes.remove(audioPid)?.let { flushPes(audioPid, it.toByteArray()) }
        finished = true
        if (video.count == 0 || sps == null || pps == null) throw RemuxException("没有找到 H.264 视频")
    }

    /** 生成放在数据前面的 `ftyp + moov + mdat` 头；样本偏移按头长度整体后移。 */
    fun buildHeader(): ByteArray {
        check(finished)
        val probe = assemble(baseOffset = 0)
        return assemble(baseOffset = probe.size.toLong())
    }

    private fun assemble(baseOffset: Long): ByteArray {
        val ftyp = box("ftyp") {
            fourcc("isom"); u32(0x200); fourcc("isom"); fourcc("iso2"); fourcc("avc1"); fourcc("mp41")
        }
        val moovWithoutBase = buildMoov(baseOffset)
        val mdatHeader = Bytes().apply {
            u32(1); fourcc("mdat"); u64(16L + dataOffset)
        }.toByteArray()
        return ftyp + moovWithoutBase + mdatHeader
    }

    private fun buildMoov(headerSize: Long): ByteArray {
        val firstVideo = video.firstTime ?: 0L
        val firstAudio = audio.firstTime90k
        val movieStart90k = listOfNotNull(firstVideo, firstAudio).min()
        val videoDuration90k = video.totalDuration
        val audioConfig = audioConfig
        val audioDurationSamples = audio.totalDuration
        val audioDuration90k = if (audioConfig != null) audioDurationSamples * 90_000L / audioConfig.sampleRate else 0L
        val videoDelay90k = firstVideo - movieStart90k
        val audioDelay90k = (firstAudio ?: movieStart90k) - movieStart90k
        val movieDuration = maxOf(videoDelay90k + videoDuration90k, audioDelay90k + audioDuration90k) * MOVIE_TIMESCALE / 90_000L
        // mdat 头 16 字节后才是样本数据。
        val dataBase = headerSize
        val (width, height) = parseSpsDimensions(sps!!) ?: (1920 to 1080)
        return box("moov") {
            raw(box("mvhd") {
                u32(0); u32(0); u32(0); u32(MOVIE_TIMESCALE); u32(movieDuration)
                u32(0x00010000); u16(0x0100); u16(0); u32(0); u32(0)
                matrix(); repeat(6) { u32(0) }; u32(if (audioConfig != null && audio.count > 0) 3 else 2)
            })
            raw(trak(
                trackId = 1,
                handler = "vide",
                timescale = 90_000,
                mediaDuration = videoDuration90k,
                delay90k = videoDelay90k,
                width = width,
                height = height,
                table = video,
                dataBase = dataBase,
                sampleEntry = avc1(width, height),
            ))
            if (audioConfig != null && audio.count > 0) {
                raw(trak(
                    trackId = 2,
                    handler = "soun",
                    timescale = audioConfig.sampleRate,
                    mediaDuration = audioDurationSamples,
                    delay90k = audioDelay90k,
                    width = 0,
                    height = 0,
                    table = audio,
                    dataBase = dataBase,
                    sampleEntry = mp4a(audioConfig),
                ))
            }
        }
    }

    private fun trak(
        trackId: Int,
        handler: String,
        timescale: Int,
        mediaDuration: Long,
        delay90k: Long,
        width: Int,
        height: Int,
        table: TrackTable,
        dataBase: Long,
        sampleEntry: ByteArray,
    ): ByteArray {
        val mediaDurationMovie = mediaDuration * MOVIE_TIMESCALE / timescale
        val delayMovie = delay90k * MOVIE_TIMESCALE / 90_000L
        val isVideo = handler == "vide"
        return box("trak") {
            raw(box("tkhd") {
                u32(0x00000003); u32(0); u32(0); u32(trackId); u32(0)
                u32(delayMovie + mediaDurationMovie); u32(0); u32(0)
                u16(0); u16(0); u16(if (isVideo) 0 else 0x0100); u16(0)
                matrix(); u32(width.toLong() shl 16); u32(height.toLong() shl 16)
            })
            if (delayMovie > 0) {
                raw(box("edts") {
                    raw(box("elst") {
                        u32(0); u32(2)
                        u32(delayMovie); u32(0xFFFFFFFFL); u32(0x00010000)
                        u32(mediaDurationMovie); u32(0); u32(0x00010000)
                    })
                })
            }
            raw(box("mdia") {
                raw(box("mdhd") { u32(0); u32(0); u32(0); u32(timescale.toLong()); u32(mediaDuration); u16(0x55C4); u16(0) })
                raw(box("hdlr") {
                    u32(0); u32(0); fourcc(handler); u32(0); u32(0); u32(0)
                    raw(if (isVideo) "VideoHandler\u0000".encodeToByteArray() else "SoundHandler\u0000".encodeToByteArray())
                })
                raw(box("minf") {
                    raw(if (isVideo) box("vmhd") { u32(1); u16(0); u16(0); u16(0); u16(0) } else box("smhd") { u32(0); u16(0); u16(0) })
                    raw(box("dinf") { raw(box("dref") { u32(0); u32(1); raw(box("url ") { u32(1) }) }) })
                    raw(box("stbl") {
                        raw(box("stsd") { u32(0); u32(1); raw(sampleEntry) })
                        raw(box("stts") {
                            val runs = table.durationRuns()
                            u32(0); u32(runs.size.toLong())
                            runs.forEach { (count, duration) -> u32(count.toLong()); u32(duration) }
                        })
                        if (isVideo) {
                            raw(box("stss") {
                                val keys = table.keyFrameNumbers()
                                u32(0); u32(keys.size.toLong()); keys.forEach { u32(it.toLong()) }
                            })
                        }
                        raw(box("stsc") { u32(0); u32(1); u32(1); u32(1); u32(1) })
                        raw(box("stsz") {
                            u32(0); u32(0); u32(table.count.toLong())
                            for (i in 0 until table.count) u32(table.sizes[i].toLong())
                        })
                        raw(box("co64") {
                            u32(0); u32(table.count.toLong())
                            for (i in 0 until table.count) u64(dataBase + table.offsets[i])
                        })
                    })
                })
            })
        }
    }

    private fun avc1(width: Int, height: Int): ByteArray = box("avc1") {
        repeat(6) { u8(0) }; u16(1)
        u16(0); u16(0); u32(0); u32(0); u32(0)
        u16(width); u16(height); u32(0x00480000); u32(0x00480000); u32(0); u16(1)
        repeat(32) { u8(0) }
        u16(0x0018); u16(0xFFFF)
        val sps = sps!!
        val pps = pps!!
        raw(box("avcC") {
            u8(1); u8(sps[1].toInt() and 0xFF); u8(sps[2].toInt() and 0xFF); u8(sps[3].toInt() and 0xFF)
            u8(0xFF); u8(0xE1); u16(sps.size); raw(sps); u8(1); u16(pps.size); raw(pps)
        })
    }

    private fun mp4a(config: AdtsConfig): ByteArray = box("mp4a") {
        repeat(6) { u8(0) }; u16(1)
        u32(0); u32(0); u16(config.channels); u16(16); u16(0); u16(0)
        u32(config.sampleRate.toLong() shl 16)
        val asc = config.audioSpecificConfig()
        // ES_Descriptor → DecoderConfigDescriptor(0x40 AAC) → DecoderSpecificInfo(ASC) + SLConfig
        val decoderSpecific = descriptor(0x05, asc)
        val decoderConfig = descriptor(
            0x04,
            byteArrayOf(0x40, 0x15, 0, 0, 0) + intBytes(0) + intBytes(0) + decoderSpecific,
        )
        val sl = descriptor(0x06, byteArrayOf(0x02))
        val es = descriptor(0x03, byteArrayOf(0, 2, 0) + decoderConfig + sl)
        raw(box("esds") { u32(0); raw(es) })
    }

    private suspend fun handlePacket(buffer: ByteArray, start: Int) {
        val pusi = (buffer[start + 1].toInt() and 0x40) != 0
        val pid = ((buffer[start + 1].toInt() and 0x1F) shl 8) or (buffer[start + 2].toInt() and 0xFF)
        val adaptation = (buffer[start + 3].toInt() shr 4) and 0x03
        var offset = start + 4
        if (adaptation and 0x02 != 0) offset += 1 + (buffer[start + 4].toInt() and 0xFF)
        if (adaptation and 0x01 == 0 || offset >= start + TS_PACKET) return
        val end = start + TS_PACKET
        when (pid) {
            0 -> if (pusi) parsePat(buffer, offset, end)
            pmtPid -> if (pusi) parsePmt(buffer, offset, end)
            videoPid, audioPid -> {
                if (pid < 0) return
                if (pusi) {
                    pes.remove(pid)?.let { flushPes(pid, it.toByteArray()) }
                    pes[pid] = ByteArrayBuilder()
                }
                pes[pid]?.append(buffer, offset, end - offset)
            }
        }
    }

    private fun parsePat(buffer: ByteArray, payloadStart: Int, end: Int) {
        val p = payloadStart + 1 + (buffer[payloadStart].toInt() and 0xFF)
        if (p + 12 > end) return
        val sectionLength = ((buffer[p + 1].toInt() and 0x0F) shl 8) or (buffer[p + 2].toInt() and 0xFF)
        var i = p + 8
        val limit = minOf(p + 3 + sectionLength - 4, end)
        while (i + 4 <= limit) {
            val program = ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
            val pid = ((buffer[i + 2].toInt() and 0x1F) shl 8) or (buffer[i + 3].toInt() and 0xFF)
            if (program != 0) {
                pmtPid = pid
                return
            }
            i += 4
        }
    }

    private fun parsePmt(buffer: ByteArray, payloadStart: Int, end: Int) {
        val p = payloadStart + 1 + (buffer[payloadStart].toInt() and 0xFF)
        if (p + 12 > end) return
        val sectionLength = ((buffer[p + 1].toInt() and 0x0F) shl 8) or (buffer[p + 2].toInt() and 0xFF)
        val programInfoLength = ((buffer[p + 10].toInt() and 0x0F) shl 8) or (buffer[p + 11].toInt() and 0xFF)
        var i = p + 12 + programInfoLength
        val limit = minOf(p + 3 + sectionLength - 4, end)
        while (i + 5 <= limit) {
            val type = buffer[i].toInt() and 0xFF
            val pid = ((buffer[i + 1].toInt() and 0x1F) shl 8) or (buffer[i + 2].toInt() and 0xFF)
            val infoLength = ((buffer[i + 3].toInt() and 0x0F) shl 8) or (buffer[i + 4].toInt() and 0xFF)
            when (type) {
                0x1B -> if (videoPid < 0) videoPid = pid
                0x0F -> if (audioPid < 0) audioPid = pid
            }
            i += 5 + infoLength
        }
    }

    private suspend fun flushPes(pid: Int, data: ByteArray) {
        if (data.size < 9 || data[0] != 0.toByte() || data[1] != 0.toByte() || data[2] != 1.toByte()) return
        val flags = (data[7].toInt() and 0xFF) shr 6
        val headerLength = data[8].toInt() and 0xFF
        val payloadStart = 9 + headerLength
        if (payloadStart > data.size) return
        val pts = if (flags and 0x02 != 0) readTimestamp(data, 9) else null
        val dts = if (flags == 0x03) readTimestamp(data, 14) else pts
        val payload = data.copyOfRange(payloadStart, data.size)
        if (pid == videoPid) {
            onVideo(payload, dts ?: return)
        } else if (pid == audioPid) {
            onAudio(payload, pts)
        }
    }

    private suspend fun onVideo(accessUnit: ByteArray, rawDts: Long) {
        val dts = videoDtsUnwrap.unwrap(rawDts)
        val sample = Bytes()
        var keyFrame = false
        for (nal in splitAnnexB(accessUnit)) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> { if (sps == null) sps = nal; continue }
                8 -> { if (pps == null) pps = nal; continue }
                9 -> continue
                5 -> keyFrame = true
            }
            sample.u32(nal.size.toLong())
            sample.raw(nal)
        }
        val bytes = sample.toByteArray()
        if (bytes.isEmpty()) return
        // 首个样本必须是关键帧，否则开头会花屏；之前的帧丢弃。
        if (video.count == 0 && !keyFrame) return
        video.add(dataOffset, bytes.size, dts, keyFrame)
        writeSample(bytes)
        dataOffset += bytes.size
    }

    private suspend fun onAudio(payload: ByteArray, rawPts: Long?) {
        if (video.count == 0) return // 与视频首个关键帧对齐之前的音频丢弃
        var pts90k = rawPts?.let(audioPtsUnwrap::unwrap) ?: nextAudioPts90k ?: return
        var i = 0
        while (i + 7 <= payload.size) {
            if (payload[i] != 0xFF.toByte() || (payload[i + 1].toInt() and 0xF0) != 0xF0) {
                i++
                continue
            }
            val protectionAbsent = payload[i + 1].toInt() and 0x01
            val profile = (payload[i + 2].toInt() shr 6) and 0x03
            val sfi = (payload[i + 2].toInt() shr 2) and 0x0F
            val channels = ((payload[i + 2].toInt() and 0x01) shl 2) or ((payload[i + 3].toInt() shr 6) and 0x03)
            val frameLength = ((payload[i + 3].toInt() and 0x03) shl 11) or
                ((payload[i + 4].toInt() and 0xFF) shl 3) or ((payload[i + 5].toInt() shr 5) and 0x07)
            val headerLength = if (protectionAbsent == 1) 7 else 9
            if (frameLength < headerLength || i + frameLength > payload.size) break
            val sampleRate = ADTS_SAMPLE_RATES.getOrNull(sfi) ?: break
            if (audioConfig == null) audioConfig = AdtsConfig(profile + 1, sfi, sampleRate, channels)
            val frame = payload.copyOfRange(i + headerLength, i + frameLength)
            if (audio.count == 0) audio.firstTime90k = pts90k
            audio.add(dataOffset, frame.size, 1024L, keyFrame = true, explicitDuration = true)
            writeSample(frame)
            dataOffset += frame.size
            pts90k += 1024L * 90_000L / sampleRate
            i += frameLength
        }
        nextAudioPts90k = pts90k
    }

    private fun readTimestamp(data: ByteArray, at: Int): Long =
        (((data[at].toLong() shr 1) and 0x07) shl 30) or
            ((data[at + 1].toLong() and 0xFF) shl 22) or
            (((data[at + 2].toLong() and 0xFF) shr 1) shl 15) or
            ((data[at + 3].toLong() and 0xFF) shl 7) or
            ((data[at + 4].toLong() and 0xFF) shr 1)

    private companion object {
        const val TS_PACKET = 188
        const val SYNC: Byte = 0x47
        const val MOVIE_TIMESCALE = 1000L
        val ADTS_SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}

class RemuxException(message: String) : Exception(message)

private data class AdtsConfig(val objectType: Int, val frequencyIndex: Int, val sampleRate: Int, val channels: Int) {
    fun audioSpecificConfig(): ByteArray = byteArrayOf(
        ((objectType shl 3) or (frequencyIndex shr 1)).toByte(),
        (((frequencyIndex and 1) shl 7) or (channels shl 3)).toByte(),
    )
}

/** 33 位 PTS/DTS 回绕处理（26.5 小时一圈；起点可能就在回绕附近）。 */
private class TimestampUnwrapper {
    private var last: Long? = null
    private var offset = 0L
    fun unwrap(raw: Long): Long {
        val previous = last
        if (previous != null) {
            if (raw < previous - WRAP / 2) offset += WRAP
            else if (raw > previous + WRAP / 2) offset -= WRAP
        }
        last = raw
        return raw + offset
    }

    private companion object { const val WRAP = 1L shl 33 }
}

/** 每条轨道的样本表；视频用 90 kHz DTS 推算时长，音频固定每帧 1024 个采样。 */
private class TrackTable {
    var offsets = LongArray(1024)
    var sizes = IntArray(1024)
    private var times = LongArray(1024)
    private var keys = BooleanArray(1024)
    var count = 0
        private set
    var firstTime90k: Long? = null
    private var explicitDurations = false
    val firstTime: Long? get() = if (count > 0 && !explicitDurations) times[0] else null

    fun add(offset: Long, size: Int, time: Long, keyFrame: Boolean, explicitDuration: Boolean = false) {
        if (count == offsets.size) {
            offsets = offsets.copyOf(count * 2); sizes = sizes.copyOf(count * 2)
            times = times.copyOf(count * 2); keys = keys.copyOf(count * 2)
        }
        explicitDurations = explicitDuration
        offsets[count] = offset; sizes[count] = size; times[count] = time; keys[count] = keyFrame
        count++
    }

    private fun duration(i: Int): Long = when {
        explicitDurations -> times[i]
        i + 1 < count -> (times[i + 1] - times[i]).coerceAtLeast(1)
        count >= 2 -> (times[count - 1] - times[count - 2]).coerceAtLeast(1)
        else -> 3600
    }

    val totalDuration: Long get() = (0 until count).sumOf(::duration)

    fun durationRuns(): List<Pair<Int, Long>> {
        val runs = mutableListOf<Pair<Int, Long>>()
        for (i in 0 until count) {
            val d = duration(i)
            if (runs.isNotEmpty() && runs.last().second == d) runs[runs.lastIndex] = runs.last().first + 1 to d
            else runs += 1 to d
        }
        return runs
    }

    fun keyFrameNumbers(): List<Int> = (0 until count).filter { keys[it] }.map { it + 1 }
}

internal fun splitAnnexB(data: ByteArray): List<ByteArray> {
    val units = mutableListOf<ByteArray>()
    var i = 0
    var start = -1
    while (i + 2 < data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            if (start >= 0) units += trimTrailingZeros(data, start, i)
            i += 3
            start = i
        } else {
            i++
        }
    }
    if (start >= 0 && start < data.size) units += trimTrailingZeros(data, start, data.size)
    return units
}

private fun trimTrailingZeros(data: ByteArray, from: Int, to: Int): ByteArray {
    var end = to
    while (end > from && data[end - 1] == 0.toByte()) end--
    return data.copyOfRange(from, end)
}

/** 从 SPS 解出显示宽高（含裁剪）；解析失败返回 null。 */
internal fun parseSpsDimensions(sps: ByteArray): Pair<Int, Int>? = runCatching {
    val rbsp = Bytes().apply {
        var zeros = 0
        for (k in 1 until sps.size) {
            val b = sps[k].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            zeros = if (b == 0) zeros + 1 else 0
            u8(b)
        }
    }.toByteArray()
    val r = BitReader(rbsp)
    val profile = r.bits(8); r.bits(16); r.ue()
    var chromaFormat = 1
    if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
        chromaFormat = r.ue()
        if (chromaFormat == 3) r.bits(1)
        r.ue(); r.ue(); r.bits(1)
        if (r.bits(1) == 1) {
            repeat(if (chromaFormat != 3) 8 else 12) { idx ->
                if (r.bits(1) == 1) {
                    var last = 8; var next = 8
                    repeat(if (idx < 6) 16 else 64) {
                        if (next != 0) next = (last + r.se() + 256) % 256
                        last = if (next == 0) last else next
                    }
                }
            }
        }
    }
    r.ue()
    when (r.ue()) {
        0 -> r.ue()
        1 -> { r.bits(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
    }
    r.ue(); r.bits(1)
    val widthMbs = r.ue() + 1
    val heightMapUnits = r.ue() + 1
    val frameMbsOnly = r.bits(1)
    if (frameMbsOnly == 0) r.bits(1)
    r.bits(1)
    var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
    if (r.bits(1) == 1) { cropLeft = r.ue(); cropRight = r.ue(); cropTop = r.ue(); cropBottom = r.ue() }
    val cropUnitX = if (chromaFormat == 0) 1 else 2
    val cropUnitY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
    val width = widthMbs * 16 - (cropLeft + cropRight) * cropUnitX
    val height = (2 - frameMbsOnly) * heightMapUnits * 16 - (cropTop + cropBottom) * cropUnitY
    width to height
}.getOrNull()?.takeIf { it.first in 16..8192 && it.second in 16..8192 }

private class BitReader(private val data: ByteArray) {
    private var bit = 0
    fun bits(n: Int): Int {
        var v = 0
        repeat(n) {
            val byte = data[bit / 8].toInt() and 0xFF
            v = (v shl 1) or ((byte shr (7 - bit % 8)) and 1)
            bit++
        }
        return v
    }
    fun ue(): Int {
        var zeros = 0
        while (bits(1) == 0) { zeros++; require(zeros < 32) }
        return (1 shl zeros) - 1 + bits(zeros)
    }
    fun se(): Int { val k = ue(); return if (k % 2 == 1) (k + 1) / 2 else -(k / 2) }
}

/** 简单可增长字节缓冲（common 里没有 ByteArrayOutputStream）。 */
internal class ByteArrayBuilder {
    private var data = ByteArray(4096)
    var size = 0
        private set
    fun append(bytes: ByteArray, from: Int = 0, length: Int = bytes.size) {
        ensure(size + length)
        bytes.copyInto(data, size, from, from + length)
        size += length
    }
    fun appendByte(b: Int) { ensure(size + 1); data[size++] = b.toByte() }
    fun clear() { size = 0 }
    fun toByteArray(): ByteArray = data.copyOf(size)
    private fun ensure(capacity: Int) {
        if (capacity > data.size) data = data.copyOf(maxOf(capacity, data.size * 2))
    }
}

/** 大端写入工具，供拼 MP4 盒子用。 */
internal open class Bytes {
    private val out = ByteArrayBuilder()
    fun u8(v: Int) = out.appendByte(v)
    fun u16(v: Int) { u8(v shr 8 and 0xFF); u8(v and 0xFF) }
    fun u32(v: Long) { u8((v shr 24).toInt() and 0xFF); u8((v shr 16).toInt() and 0xFF); u8((v shr 8).toInt() and 0xFF); u8(v.toInt() and 0xFF) }
    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
    fun fourcc(s: String) = raw(s.encodeToByteArray())
    fun raw(b: ByteArray) = out.append(b)
    fun matrix() { u32(0x00010000); u32(0); u32(0); u32(0); u32(0x00010000); u32(0); u32(0); u32(0); u32(0x40000000) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

internal fun box(type: String, body: Bytes.() -> Unit): ByteArray {
    val content = Bytes().apply(body).toByteArray()
    return Bytes().apply { u32((8 + content.size).toLong()); fourcc(type); raw(content) }.toByteArray()
}

private fun descriptor(tag: Int, payload: ByteArray): ByteArray {
    // 用 4 字节长度编码（0x80 0x80 0x80 len），兼容所有解析器。
    val n = payload.size
    return byteArrayOf(
        tag.toByte(),
        (0x80 or (n shr 21 and 0x7F)).toByte(),
        (0x80 or (n shr 14 and 0x7F)).toByte(),
        (0x80 or (n shr 7 and 0x7F)).toByte(),
        (n and 0x7F).toByte(),
    ) + payload
}

private fun intBytes(v: Int): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

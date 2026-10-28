package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** 元数据只来自已经完成并写入日志的分段。 */
internal data class Mp4JoinPart(val file: File, val startUs: Long, val endUs: Long, val audioFrames: Long?)

/** 不支持或无法证明安全的 MP4 结构必须退回逐样本整理。 */
internal class UnsupportedMp4Join(message: String) : Exception(message)

/**
 * 合并 MediaMuxer 分段：整块复制 mdat，重新生成索引，不解码或改写压缩样本。
 * 只接受可证明轨道、时间戳和样本表兼容的结构；其他设备走原有 MediaMuxer 路径。
 */
internal object FastMp4PartJoiner {
    fun concatenate(parts: List<Mp4JoinPart>, audioOriginUs: Long?, output: File,
        context: CoroutineContext, onProgress: (Float) -> Unit) {
        requireSupported(parts.isNotEmpty() && (audioOriginUs ?: 0L) == 0L, "不支持非零音频起点")
        requireSupported(parts.none { it.file.canonicalFile == output.canonicalFile }, "输出不能覆盖分段")
        var previousEnd = 0L
        val parsed = parts.map { part ->
            context.ensureActive()
            requireSupported(part.startUs == previousEnd && part.endUs > part.startUs, "分段时间不连续")
            previousEnd = part.endUs
            parsePart(part)
        }
        val first = parsed.first()
        requireSupported(parsed.all { it.tracks.keys == setOf("soun", "vide") }, "需要一条音轨和一条视频轨")
        val merged = mutableMapOf<String, MergedTrack>()
        var precedingBytes = 0L
        val outputDataStart = first.ftyp.size.toLong() + 16L
        for ((partIndex, part) in parsed.withIndex()) {
            for ((kind, track) in part.tracks) {
                val all = merged.getOrPut(kind) { MergedTrack(track.config, track.timescale) }
                requireSupported(all.config.contentEquals(track.config) && all.timescale == track.timescale, "分段编码配置不一致")
                val sampleBase = all.sizes.size
                val chunkBase = all.offsets.size
                all.sizes.addAll(track.sizes)
                val durations = track.durations.toMutableList()
                if (kind == "soun") {
                    requireSupported(part.input.audioFrames == null || part.input.audioFrames == durations.size.toLong(), "音频帧数不一致")
                    durations.indices.forEach { durations[it] = 1024L }
                } else if (partIndex + 1 < parsed.size) {
                    val spanUs = parsed[partIndex + 1].input.startUs - part.input.startUs
                    val span = scaleTime(spanUs, track.timescale, 1_000_000L)
                    val last = span - durations.dropLast(1).sum()
                    requireSupported(last in 1..0xffffffffL, "视频时间戳重叠")
                    durations[durations.lastIndex] = last
                }
                all.durations.addAll(durations)
                all.keys.addAll(track.keys.map { it + sampleBase })
                all.chunks.addAll(track.chunks.map { it.copy(first = it.first + chunkBase) })
                for (offset in track.offsets) {
                    requireSupported(offset >= part.dataStart && offset < part.dataStart + part.dataSize, "块偏移超出 mdat")
                    all.offsets += outputDataStart + precedingBytes + offset - part.dataStart
                }
            }
            precedingBytes += part.dataSize
        }
        val movieScale = readTimescale(find(first.moov, "mvhd").body)
        val movieDuration = merged.values.maxOf { ceilScale(it.durations.sum(), movieScale, it.timescale) }
        val moov = makeBox("moov", rewrite(first.moov, null, merged, movieScale, movieDuration))
        var copied = 0L
        var reported = 0
        onProgress(0f)
        var completed = false
        try {
            FileOutputStream(output).use { sink ->
                sink.write(first.ftyp)
                sink.write(Bytes().apply { u32(1); fourcc("mdat"); u64(16L + precedingBytes) }.toByteArray())
                for (part in parsed) {
                    RandomAccessFile(part.input.file, "r").use { source ->
                        var position = part.dataStart
                        var remaining = part.dataSize
                        val buffer = ByteBuffer.allocateDirect(1024 * 1024)
                        while (remaining > 0) {
                            context.ensureActive()
                            val count = source.channel.transferTo(position, minOf(remaining, 8L * 1024 * 1024), sink.channel)
                            val actual = if (count > 0) count else {
                                buffer.clear()
                                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                                source.channel.position(position)
                                val read = source.channel.read(buffer)
                                if (read <= 0) throw UnsupportedMp4Join("分段数据读取失败")
                                buffer.flip()
                                while (buffer.hasRemaining()) sink.channel.write(buffer)
                                read.toLong()
                            }
                            position += actual
                            remaining -= actual
                            copied += actual
                            val progress = (copied.toDouble() / precedingBytes.coerceAtLeast(1)).toFloat().coerceAtMost(.999f)
                            val bucket = (progress * 200).toInt()
                            if (bucket != reported) { reported = bucket; onProgress(progress) }
                        }
                    }
                }
                context.ensureActive()
                sink.write(moov)
            }
            onProgress(1f)
            completed = true
        } finally {
            if (!completed) output.delete()
        }
    }

    private data class Box(val type: String, val raw: ByteArray, val header: Int) {
        val body: ByteArray get() = raw.copyOfRange(header, raw.size)
    }
    private data class Chunk(val first: Int, val samples: Int, val description: Int)
    private data class Track(val config: ByteArray, val timescale: Long, val sizes: List<Int>,
        val durations: List<Long>, val offsets: List<Long>, val chunks: List<Chunk>, val keys: List<Int>)
    private data class ParsedPart(val input: Mp4JoinPart, val ftyp: ByteArray, val moov: ByteArray,
        val dataStart: Long, val dataSize: Long, val tracks: Map<String, Track>)
    private class MergedTrack(val config: ByteArray, val timescale: Long) {
        val sizes = mutableListOf<Int>()
        val durations = mutableListOf<Long>()
        val offsets = mutableListOf<Long>()
        val chunks = mutableListOf<Chunk>()
        val keys = mutableListOf<Int>()
    }

    private fun parsePart(part: Mp4JoinPart): ParsedPart {
        var ftyp: ByteArray? = null
        var moov: ByteArray? = null
        var dataStart = -1L
        var dataSize = -1L
        RandomAccessFile(part.file, "r").use { file ->
            var position = 0L
            val length = file.length()
            while (position < length) {
                requireSupported(length - position >= 8, "MP4 盒头不完整")
                file.seek(position)
                val size32 = file.readInt().toLong() and 0xffffffffL
                val name = ByteArray(4).also(file::readFully).decodeToString()
                val header = if (size32 == 1L) 16 else 8
                val size = when (size32) { 0L -> length - position; 1L -> file.readLong(); else -> size32 }
                requireSupported(size >= header && size <= length - position, "MP4 盒长度无效")
                when (name) {
                    "ftyp", "moov" -> {
                        requireSupported(size <= 64L * 1024 * 1024, "MP4 元数据过大")
                        val data = ByteArray(size.toInt())
                        file.seek(position); file.readFully(data)
                        if (name == "ftyp") { requireSupported(ftyp == null, "多个 ftyp"); ftyp = data }
                        else { requireSupported(moov == null, "多个 moov"); moov = data.copyOfRange(header, data.size) }
                    }
                    "mdat" -> {
                        requireSupported(dataStart < 0, "多个 mdat")
                        dataStart = position + header
                        dataSize = size - header
                    }
                }
                position += size
            }
        }
        val movie = moov ?: throw UnsupportedMp4Join("缺少 moov")
        val tracks = mutableMapOf<String, Track>()
        for (item in boxes(movie).filter { it.type == "trak" }) {
            val trak = item.body
            requireSupported(boxes(trak).none { it.type == "edts" }, "不支持编辑列表")
            val mdia = find(trak, "mdia").body
            val handler = find(mdia, "hdlr").body
            requireSupported(handler.size >= 12, "轨道类型缺失")
            val kind = handler.copyOfRange(8, 12).decodeToString()
            requireSupported(kind == "soun" || kind == "vide", "不支持其他轨道")
            val table = find(find(mdia, "minf").body, "stbl").body
            val items = boxes(table)
            requireSupported(items.map { it.type }.distinct().size == items.size, "重复样本表")
            requireSupported(items.all { it.type in setOf("stsd", "stts", "stss", "stsz", "stsc", "stco", "co64") }, "不支持的样本表")
            val mapping = items.associateBy { it.type }
            val sizesBody = mapping["stsz"]?.body ?: throw UnsupportedMp4Join("缺少 stsz")
            requireSupported(sizesBody.size >= 12 && u32(sizesBody, 0) == 0L, "stsz 不完整")
            val sampleSize = u32(sizesBody, 4)
            val count = checkedCount(u32(sizesBody, 8))
            requireSupported(count > 0 && (sampleSize == 0L && sizesBody.size.toLong() == 12L + 4L * count ||
                sampleSize > 0L && sizesBody.size == 12), "stsz 无效")
            val sizes = List(count) { if (sampleSize != 0L) checkedCount(sampleSize) else checkedCount(u32(sizesBody, 12 + it * 4)) }
            requireSupported(sizes.all { it > 0 }, "空样本")
            val durations = mutableListOf<Long>()
            for (entry in entries(mapping["stts"]?.body ?: throw UnsupportedMp4Join("缺少 stts"), 8)) {
                val repetitions = checkedCount(u32(entry, 0)); val duration = u32(entry, 4)
                requireSupported(duration > 0 && repetitions <= count - durations.size, "stts 无效")
                repeat(repetitions) { durations += duration }
            }
            requireSupported(durations.size == count, "样本时长数量不一致")
            val offsetsBox = mapping["co64"] ?: mapping["stco"] ?: throw UnsupportedMp4Join("缺少块偏移")
            requireSupported(!(mapping.containsKey("co64") && mapping.containsKey("stco")), "重复块偏移")
            val offsetWidth = if (offsetsBox.type == "co64") 8 else 4
            val offsets = entries(offsetsBox.body, offsetWidth).map { if (offsetWidth == 8) u64(it, 0) else u32(it, 0) }
            val chunks = entries(mapping["stsc"]?.body ?: throw UnsupportedMp4Join("缺少 stsc"), 12).map {
                Chunk(checkedCount(u32(it, 0)), checkedCount(u32(it, 4)), checkedCount(u32(it, 8)))
            }
            val keys = mapping["stss"]?.let { entries(it.body, 4).map { e -> checkedCount(u32(e, 0)) } }
                ?: if (kind == "vide") (1..count).toList() else emptyList()
            requireSupported(keys.zipWithNext().all { it.first < it.second } && keys.all { it in 1..count }, "关键帧表无效")
            val config = mapping["stsd"]?.body ?: throw UnsupportedMp4Join("缺少 stsd")
            requireSupported(config.size >= 16 && u32(config, 0) == 0L && u32(config, 4) == 1L &&
                u32(config, 8) == config.size.toLong() - 8L, "不支持的样本描述")
            val subtype = config.copyOfRange(12, 16).decodeToString()
            val timescale = readTimescale(find(mdia, "mdhd").body)
            if (kind == "soun") {
                requireSupported(subtype == "mp4a" && config.size >= 44 && (u32(config, 40) shr 16) == timescale &&
                    durations.all { it in 1023L..1025L }, "不支持的 AAC 采样时长或时间尺度")
            } else {
                requireSupported(subtype == "avc1", "不支持的视频编码格式")
            }
            val track = Track(config, timescale, sizes, durations, offsets, chunks, keys)
            validateChunks(track, dataStart, dataSize)
            requireSupported(tracks.put(kind, track) == null, "重复轨道")
        }
        requireSupported(dataStart >= 0 && dataSize > 0, "缺少 mdat")
        return ParsedPart(part, ftyp ?: throw UnsupportedMp4Join("缺少 ftyp"), movie, dataStart, dataSize, tracks)
    }

    private fun validateChunks(track: Track, dataStart: Long, dataSize: Long) {
        val chunks = track.chunks
        requireSupported(track.offsets.isNotEmpty() && chunks.isNotEmpty() && chunks.first().first == 1 &&
            chunks.zipWithNext().all { it.first.first < it.second.first } &&
            chunks.all { it.first in 1..track.offsets.size && it.samples > 0 && it.description == 1 }, "stsc 无效")
        var entry = 0
        var sample = 0
        for (i in track.offsets.indices) {
            while (entry + 1 < chunks.size && chunks[entry + 1].first <= i + 1) entry++
            val count = chunks[entry].samples
            requireSupported(count <= track.sizes.size - sample, "块样本数过多")
            var bytes = 0L
            repeat(count) { bytes += track.sizes[sample++].toLong() }
            val offset = track.offsets[i]
            requireSupported(offset >= dataStart && offset <= dataStart + dataSize && bytes <= dataStart + dataSize - offset,
                "样本越过 mdat 边界")
        }
        requireSupported(sample == track.sizes.size, "块样本数不足")
    }

    private fun rewrite(data: ByteArray, kind: String?, tracks: Map<String, MergedTrack>, movieScale: Long,
        movieDuration: Long): ByteArray {
        val result = Bytes()
        for (item in boxes(data)) {
            val body = item.body
            val currentKind = if (item.type == "trak") {
                val handler = find(find(body, "mdia").body, "hdlr").body
                handler.copyOfRange(8, 12).decodeToString()
            } else kind
            val track = tracks[currentKind]
            val raw = when (item.type) {
                "trak", "mdia", "minf" -> makeBox(item.type, rewrite(body, currentKind, tracks, movieScale, movieDuration))
                "stbl" -> makeBox(item.type, rewriteSampleTable(body, track ?: throw UnsupportedMp4Join("轨道不存在"), currentKind == "vide"))
                "mvhd" -> makeBox(item.type, withDuration(body, 16, 24, movieDuration))
                "mdhd" -> makeBox(item.type, withDuration(body, 16, 24, track?.durations?.sum() ?: throw UnsupportedMp4Join("轨道不存在")))
                "tkhd" -> {
                    val t = track ?: throw UnsupportedMp4Join("轨道不存在")
                    makeBox(item.type, withDuration(body, 20, 28, ceilScale(t.durations.sum(), movieScale, t.timescale)))
                }
                else -> item.raw
            }
            result.raw(raw)
        }
        return result.toByteArray()
    }

    private fun rewriteSampleTable(data: ByteArray, track: MergedTrack, video: Boolean): ByteArray {
        val result = Bytes()
        var wroteKeys = false
        for (item in boxes(data)) {
            val raw = when (item.type) {
                "stts" -> {
                    val runs = mutableListOf<Pair<Int, Long>>()
                    for (duration in track.durations) {
                        if (runs.isNotEmpty() && runs.last().second == duration) {
                            val last = runs.removeAt(runs.lastIndex); runs += (last.first + 1) to duration
                        } else runs += 1 to duration
                    }
                    makeBox("stts", Bytes().apply { u32(0); u32(runs.size); runs.forEach { u32(it.first); u32(it.second) } }.toByteArray())
                }
                "stsz" -> makeBox("stsz", Bytes().apply { u32(0); u32(0); u32(track.sizes.size); track.sizes.forEach { u32(it) } }.toByteArray())
                "stsc" -> makeBox("stsc", Bytes().apply { u32(0); u32(track.chunks.size); track.chunks.forEach { u32(it.first); u32(it.samples); u32(it.description) } }.toByteArray())
                "stco", "co64" -> makeBox("co64", Bytes().apply { u32(0); u32(track.offsets.size); track.offsets.forEach(::u64) }.toByteArray())
                "stss" -> { wroteKeys = true; makeBox("stss", Bytes().apply { u32(0); u32(track.keys.size); track.keys.forEach { u32(it) } }.toByteArray()) }
                else -> item.raw
            }
            result.raw(raw)
        }
        if (video && !wroteKeys) result.raw(makeBox("stss", Bytes().apply { u32(0); u32(track.keys.size); track.keys.forEach { u32(it) } }.toByteArray()))
        return result.toByteArray()
    }

    private fun boxes(data: ByteArray): List<Box> {
        val result = mutableListOf<Box>()
        var at = 0
        while (at < data.size) {
            requireSupported(data.size - at >= 8, "MP4 盒头不完整")
            val size32 = u32(data, at)
            val header = if (size32 == 1L) 16 else 8
            val size = when (size32) { 0L -> (data.size - at).toLong(); 1L -> u64(data, at + 8); else -> size32 }
            requireSupported(size >= header && size <= data.size - at, "MP4 盒长度无效")
            result += Box(data.copyOfRange(at + 4, at + 8).decodeToString(), data.copyOfRange(at, at + size.toInt()), header)
            at += size.toInt()
        }
        return result
    }
    private fun find(data: ByteArray, type: String): Box = boxes(data).singleOrNull { it.type == type }
        ?: throw UnsupportedMp4Join("缺少或重复 $type")
    private fun entries(data: ByteArray, width: Int): List<ByteArray> {
        requireSupported(data.size >= 8 && u32(data, 0) == 0L, "不支持的样本表版本")
        val count = checkedCount(u32(data, 4))
        requireSupported(data.size.toLong() == 8L + count.toLong() * width, "样本表长度无效")
        return List(count) { data.copyOfRange(8 + it * width, 8 + (it + 1) * width) }
    }
    private fun checkedCount(value: Long): Int {
        requireSupported(value in 0..20_000_000L, "样本表数量过大")
        return value.toInt()
    }
    private fun u32(data: ByteArray, at: Int): Long {
        requireSupported(at >= 0 && at <= data.size - 4, "读取 MP4 越界")
        return ((data[at].toLong() and 255) shl 24) or ((data[at+1].toLong() and 255) shl 16) or
            ((data[at+2].toLong() and 255) shl 8) or (data[at+3].toLong() and 255)
    }
    private fun u64(data: ByteArray, at: Int): Long {
        val high = u32(data, at); val low = u32(data, at + 4)
        requireSupported(high <= 0x7fffffffL, "MP4 偏移过大")
        return (high shl 32) or low
    }
    private fun readTimescale(data: ByteArray): Long {
        requireSupported(data.isNotEmpty() && data[0].toInt() in 0..1, "不支持的时间表版本")
        return u32(data, if (data[0].toInt() == 1) 20 else 12).also { requireSupported(it > 0, "时间尺度无效") }
    }
    private fun withDuration(data: ByteArray, offset0: Int, offset1: Int, value: Long): ByteArray {
        requireSupported(data.isNotEmpty() && data[0].toInt() in 0..1, "不支持的时间表版本")
        val longVersion = data[0].toInt() == 1
        val at = if (longVersion) offset1 else offset0
        val bytes = if (longVersion) Bytes().apply { u64(value) }.toByteArray() else {
            requireSupported(value in 0..0xffffffffL, "时长超过容器上限")
            Bytes().apply { u32(value) }.toByteArray()
        }
        requireSupported(at <= data.size - bytes.size, "时长字段越界")
        return data.copyOf().also { bytes.copyInto(it, at) }
    }
    private fun makeBox(type: String, data: ByteArray): ByteArray = Bytes().apply {
        u32(8L + data.size); fourcc(type); raw(data)
    }.toByteArray()
    private fun scaleTime(value: Long, scale: Long, divisor: Long): Long {
        requireSupported(value >= 0 && value <= (Long.MAX_VALUE - divisor / 2) / scale, "时间戳过大")
        return (value * scale + divisor / 2) / divisor
    }
    private fun ceilScale(value: Long, scale: Long, divisor: Long): Long {
        requireSupported(value >= 0 && value <= (Long.MAX_VALUE - divisor + 1) / scale, "时长过大")
        return (value * scale + divisor - 1) / divisor
    }
    private fun requireSupported(condition: Boolean, reason: String) { if (!condition) throw UnsupportedMp4Join(reason) }
}

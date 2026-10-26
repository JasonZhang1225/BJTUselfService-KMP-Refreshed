package team.bjtuss.bjtuselfservice.shared.data.courseware

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import team.bjtuss.bjtuselfservice.shared.data.homework.isSchoolPlaybackStreamUrl
import team.bjtuss.bjtuselfservice.shared.files.StreamingFileSink
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

enum class HlsDownloadFailure {
    NETWORK,
    MALFORMED_PLAYLIST,
    /** 加密分片（EXT-X-KEY）或地址不在学校 HTTPS 录播服务器范围内。 */
    UNSUPPORTED,
}

/**
 * 录播下载失败。[reason] 是给界面看的失败分类；[detail] 只用于排查（分片序号、脱敏后的地址），
 * 不含凭据：查询串可能带 token，写进异常就会跟着日志流出。
 */
class HlsDownloadException(
    val reason: HlsDownloadFailure,
    detail: String? = null,
) : Exception(
    if (detail == null) "HLS download failed: ${reason.name}" else "HLS download failed: ${reason.name} ($detail)",
)

data class HlsMediaPlaylist(
    val segmentUrls: List<String>,
    val durationSeconds: Double,
)

/** 下载进度：分片数、已写字节数、录像总时长。用来算速度和预计体积。 */
data class HlsDownloadProgress(
    val completedSegments: Int,
    val totalSegments: Int,
    val writtenBytes: Long,
    val durationSeconds: Double,
    /** 本次调用实际从网络取回的字节，不含重放的断点缓存。 */
    val transferredBytes: Long = writtenBytes,
)

/**
 * 解析一级 HLS 播放列表。学校录播（VodHlsNginx）直接给媒体列表：每 10 秒一个 .ts 分片、
 * `EXT-X-PLAYLIST-TYPE:VOD`、不加密。遇到主列表时返回 null 并由调用方读取第一个变体。
 */
internal fun parseHlsPlaylist(body: String, playlistUrl: String): HlsParsedPlaylist {
    val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.firstOrNull() != "#EXTM3U") throw HlsDownloadException(HlsDownloadFailure.MALFORMED_PLAYLIST)
    val segments = mutableListOf<String>()
    var duration = 0.0
    var variant: String? = null
    var expectVariantUri = false
    for (line in lines) {
        when {
            line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE") ->
                throw HlsDownloadException(HlsDownloadFailure.UNSUPPORTED)
            line.startsWith("#EXT-X-STREAM-INF") -> expectVariantUri = true
            line.startsWith("#EXTINF:") ->
                duration += line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
            line.startsWith("#") -> Unit
            expectVariantUri -> {
                if (variant == null) variant = resolveHlsUri(playlistUrl, line)
                expectVariantUri = false
            }
            else -> segments += resolveHlsUri(playlistUrl, line)
        }
    }
    variant?.let { if (segments.isEmpty()) return HlsParsedPlaylist.Master(it) }
    if (segments.isEmpty()) throw HlsDownloadException(HlsDownloadFailure.MALFORMED_PLAYLIST)
    return HlsParsedPlaylist.Media(HlsMediaPlaylist(segments, duration))
}

internal sealed interface HlsParsedPlaylist {
    data class Master(val variantUrl: String) : HlsParsedPlaylist
    data class Media(val playlist: HlsMediaPlaylist) : HlsParsedPlaylist
}

private fun resolveHlsUri(base: String, reference: String): String {
    val resolved = runCatching { URLBuilder(Url(base)).takeFrom(reference).buildString() }.getOrNull()
        ?: throw HlsDownloadException(HlsDownloadFailure.MALFORMED_PLAYLIST)
    // 分片必须和播放列表同一台录播服务器、同为 HTTPS，不跟随列表把流量引到别处。
    if (!isSchoolPlaybackStreamUrl(resolved) || Url(resolved).host != Url(base).host) {
        throw HlsDownloadException(HlsDownloadFailure.UNSUPPORTED)
    }
    return resolved
}

/** 按顺序取回分片。默认直接逐个请求；iOS 换成系统后台下载会话，App 挂起时也能继续。 */
interface HlsSegmentSource {
    suspend fun fetchInOrder(urls: List<String>, onSegment: suspend (index: Int, bytes: ByteArray) -> Unit)

    /**
     * 并发抓取分片，仍按索引顺序交付。
     *
     * 默认回退到串行的 [fetchInOrder]（平台自带后台下载会话的实现保持原样）；
     * 能并发抓的实现覆盖它。交付顺序必须与索引一致，调用方写出的字节才和串行版相同。
     */
    suspend fun fetchConcurrently(
        urls: List<String>,
        concurrency: Int,
        onRetry: (Int, Long) -> Unit = { _, _ -> },
        onSegment: suspend (index: Int, bytes: ByteArray) -> Unit,
    ) = fetchInOrder(urls, onSegment)
}

/**
 * 直接用学校公开通道（无 Cookie）抓分片，单个分片失败重试 3 次。
 * [fetchConcurrently] 同时最多 [DEFAULT_SEGMENT_CONCURRENCY] 个分片在飞，但仍按索引顺序交付。
 */
class DirectHlsSegmentSource(
    private val transport: SchoolHttpTransport,
    private val retryDelayMillis: Long = 2_000,
) : HlsSegmentSource {
    override suspend fun fetchInOrder(urls: List<String>, onSegment: suspend (index: Int, bytes: ByteArray) -> Unit) {
        urls.forEachIndexed { index, url -> onSegment(index, fetchSegment(index, url)) }
    }

    override suspend fun fetchConcurrently(
        urls: List<String>,
        concurrency: Int,
        onRetry: (Int, Long) -> Unit,
        onSegment: suspend (index: Int, bytes: ByteArray) -> Unit,
    ) {
        fetchInParallelOrdered(urls.size, concurrency, { index -> fetchSegment(index, urls[index], onRetry) }) { index, bytes ->
            onSegment(index, bytes)
        }
    }

    /** 失败时补上分片序号和脱敏后的地址，方便看出是哪一段出了问题。 */
    private suspend fun fetchSegment(index: Int, url: String, onRetry: (Int, Long) -> Unit = { _, _ -> }): ByteArray = try {
        fetchHlsBytes(transport, url, retryDelayMillis, onRetry = onRetry)
    } catch (error: HlsDownloadException) {
        throw HlsDownloadException(error.reason, "segment ${index + 1}: ${redactUrlQuery(url)}")
    }
}

/** 平台提供的后台分片来源；没有时返回 null（用 [DirectHlsSegmentSource]）。 */
expect fun platformBackgroundSegmentSource(transport: SchoolHttpTransport): HlsSegmentSource?

/**
 * 读一路录播：先取播放列表，再把全部 MPEG-TS 分片交给 [sink]。
 * 只走 HTTPS 录播服务器（不需要学校 Cookie）。
 *
 * 分片默认并发抓取（同时最多 [concurrency] 个在飞），但仍按索引顺序写盘：
 * 进度口径（已完成分片数 / 已写字节数）与串行版完全一致。
 */
class HlsRecordingDownloader(
    private val transport: SchoolHttpTransport,
    private val retryDelayMillis: Long = 2_000,
    private val segmentSource: HlsSegmentSource = DirectHlsSegmentSource(transport, retryDelayMillis),
    /** 同时在飞的录播分片数；1 表示退回逐个抓。 */
    private val concurrency: Int = DEFAULT_SEGMENT_CONCURRENCY,
) {
    suspend fun resolvePlaylist(playlistUrl: String, onRetry: (Int, Long) -> Unit = { _, _ -> }): HlsMediaPlaylist {
        if (!isSchoolPlaybackStreamUrl(playlistUrl)) throw HlsDownloadException(HlsDownloadFailure.UNSUPPORTED)
        var url = playlistUrl
        repeat(2) {
            val body = try {
                fetchHlsBytes(transport, url, retryDelayMillis, onRetry = onRetry)
            } catch (error: HlsDownloadException) {
                throw HlsDownloadException(error.reason, "playlist: ${redactUrlQuery(url)}")
            }
            when (val parsed = parseHlsPlaylist(body.decodeToString(), url)) {
                is HlsParsedPlaylist.Master -> url = parsed.variantUrl
                is HlsParsedPlaylist.Media -> return parsed.playlist
            }
        }
        throw HlsDownloadException(HlsDownloadFailure.MALFORMED_PLAYLIST)
    }

    suspend fun download(
        playlistUrl: String,
        sink: StreamingFileSink,
        checkpoint: HlsSegmentCheckpoint? = null,
        onRetry: (Int, Long) -> Unit = { _, _ -> },
        onProgress: (HlsDownloadProgress) -> Unit,
    ) {
        val media = resolvePlaylist(playlistUrl, onRetry)
        val total = media.segmentUrls.size
        var written = 0L
        var transferred = 0L
        checkpoint?.prepare(media.segmentUrls)
        onProgress(HlsDownloadProgress(0, total, 0, media.durationSeconds))
        val restored = checkpoint?.completed ?: 0
        repeat(restored) { index ->
            val bytes = checkpoint!!.read(index)
            sink.write(bytes)
            written += bytes.size
            onProgress(HlsDownloadProgress(index + 1, total, written, media.durationSeconds, 0))
        }
        segmentSource.fetchConcurrently(media.segmentUrls.drop(restored), concurrency, onRetry) { relative, bytes ->
            val index = restored + relative
            checkpoint?.append(index, bytes)
            sink.write(bytes)
            written += bytes.size
            transferred += bytes.size
            onProgress(HlsDownloadProgress(index + 1, total, written, media.durationSeconds, transferred))
        }
    }
}

internal suspend fun fetchHlsBytes(
    transport: SchoolHttpTransport,
    url: String,
    retryDelayMillis: Long,
    waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
    onRetry: (Int, Long) -> Unit = { _, _ -> },
): ByteArray {
    var attempt = 0
    while (true) {
        attempt++
        val body = try {
            val response = transport.executePublic(
                SchoolHttpRequest(method = SchoolHttpMethod.GET, url = url, largeTransfer = true),
            )
            if (response.statusCode in 200..299 && response.body.isNotEmpty() &&
                isSchoolPlaybackStreamUrl(response.finalUrl)
            ) {
                response.body
            } else {
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (body != null) return body
        // 初次失败后再重连三次：默认等待 2、4、8 秒。取消不会当成网络失败重试。
        val delayMillis = hlsRetryDelay(attempt, retryDelayMillis)
            ?: throw HlsDownloadException(HlsDownloadFailure.NETWORK)
        onRetry(attempt, delayMillis)
        waitBeforeRetry(delayMillis)
    }
}

/** 首次失败后重试三次；供普通传输与 iOS 系统后台会话共用。 */
internal fun hlsRetryDelay(failedAttempt: Int, baseDelayMillis: Long = 2_000): Long? =
    if (failedAttempt in 1..3) baseDelayMillis * (1L shl (failedAttempt - 1)) else null

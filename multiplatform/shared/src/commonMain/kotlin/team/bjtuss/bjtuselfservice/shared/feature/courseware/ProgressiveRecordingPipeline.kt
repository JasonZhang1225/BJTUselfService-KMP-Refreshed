package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import team.bjtuss.bjtuselfservice.shared.data.courseware.*
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream
import team.bjtuss.bjtuselfservice.shared.files.StreamingFileSink
import team.bjtuss.bjtuselfservice.shared.media.MediaWorkDirectory
import team.bjtuss.bjtuselfservice.shared.media.ProgressiveVideoCompositor

/** 两路下载与原生编码并行，内存仍只容纳固定数量的分片；原始断点和已编码分段分别落盘。 */
internal class ProgressiveRecordingPipeline(private val downloader: HlsRecordingDownloader) {
    suspend fun run(
        streams: List<PlaybackStream>,
        work: MediaWorkDirectory,
        quality: PlaybackCompositeQuality,
        compositor: ProgressiveVideoCompositor,
        checkpoints: MutableMap<Int, HlsSegmentCheckpoint>,
        onDownload: (List<HlsDownloadProgress>) -> Unit,
        onInputsComplete: () -> Unit,
        onComposition: (Float) -> Unit,
        onFinalizing: (Float) -> Unit,
        onRetry: (Int, Long) -> Unit,
        onStatus: (String?) -> Unit,
        onDevice: (String?) -> Unit,
        onDuration: (Double) -> Unit = {},
    ) {
        val playlists = coroutineScope { streams.map { stream -> async { downloader.resolvePlaylist(stream.hlsUrl, onRetry) } }.awaitAll() }
        if (playlists.any { it.durationSeconds <= 0 })
            throw HlsDownloadException(HlsDownloadFailure.MALFORMED_PLAYLIST)
        val duration = playlists.maxOf { it.durationSeconds }
        onDuration(duration)
        val session = compositor.openProgressive(work, duration, quality)
        try {
            onComposition((session.resumeSeconds / duration).toFloat().coerceIn(0f, 1f))
            if (session.resumeSeconds > 0 && !session.isComplete)
                onStatus("从已保存的第 ${(session.resumeSeconds / 60).toInt()} 分钟继续，最多重做约 1 分钟")
            if (session.isComplete) {
                onInputsComplete()
                session.compose(onComposition, onStatus, onFinalizing, onDevice)
                return
            }
            // 恢复仍读原始时间轴；原生端只扫描已完成部分的压缩数据，不重新编码。
            // 不能用 EXTINF 累加值推测 AAC 的采样原点，否则段边界可能丢一帧音频。
            val progress = MutableStateFlow(playlists.map { HlsDownloadProgress(0, it.segmentUrls.size, 0, it.durationSeconds, 0) })
            val finishedInputs = MutableStateFlow(0)
            val limited = downloader.withConcurrency(2)
            // 两路 worker 可能在不同线程上启动，不能同时修改共享的普通 Map。
            val streamCheckpoints = playlists.indices.map { index ->
                checkpoints.getOrPut(index) { HlsSegmentCheckpoint(MediaWorkDirectory(work.file("segments-$index"))) }
            }
            coroutineScope {
                playlists.forEachIndexed { index, playlist ->
                    launch {
                        val checkpoint = streamCheckpoints[index]
                        limited.downloadResolved(playlist, StreamingFileSink { bytes ->
                            session.append(index, bytes)
                        }, checkpoint, onRetry) { latest ->
                            progress.update { previous -> previous.toMutableList().also { it[index] = latest } }
                            onDownload(progress.value)
                        }
                        session.finishInput(index)
                        finishedInputs.update { it + 1 }
                        if (finishedInputs.value == streams.size) onInputsComplete()
                    }
                }
                session.compose(onComposition, onStatus, onFinalizing, onDevice)
            }
        } finally { session.close() }
    }
}

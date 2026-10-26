package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsDownloadException
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsDownloadFailure
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestination
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingFileSink
import team.bjtuss.bjtuselfservice.shared.files.StreamingSinkIoException
import team.bjtuss.bjtuselfservice.shared.files.safeExportFileName
import team.bjtuss.bjtuselfservice.shared.media.AppendFile
import team.bjtuss.bjtuselfservice.shared.media.ByteArrayBuilder
import team.bjtuss.bjtuselfservice.shared.media.CompositionException
import team.bjtuss.bjtuselfservice.shared.media.MediaWorkDirectory
import team.bjtuss.bjtuselfservice.shared.media.PlaybackBackgroundHost
import team.bjtuss.bjtuselfservice.shared.media.RemuxException
import team.bjtuss.bjtuselfservice.shared.media.TsToMp4Remuxer
import team.bjtuss.bjtuselfservice.shared.media.VideoCompositor
import team.bjtuss.bjtuselfservice.shared.media.readFileInChunks

enum class PlaybackTaskPhase(val label: String) {
    QUEUED("排队中"),
    DOWNLOADING("下载中"),
    COMPOSITING("拼接中"),
    SAVING("保存中"),
    DONE("已完成"),
    FAILED("失败"),
    CANCELLED("已取消"),
    ;

    /** 终态：结果要短暂显示在通知/实时活动上。 */
    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

data class PlaybackTask(
    val id: String,
    val courseName: String,
    val lessonTitle: String,
    val streamLabels: List<String>,
    val composite: Boolean,
    val phase: PlaybackTaskPhase = PlaybackTaskPhase.QUEUED,
    /** 当前步骤说明，如“下载教师画面（第 1/2 路）”。 */
    val step: String = "",
    /** 0..1；未知时为 null（显示不确定进度条）。 */
    val progress: Float? = null,
    val outputs: List<String> = emptyList(),
    val message: String? = null,
    /** 需要用户注意但不是错误的提示（如 iOS 旧系统需回到前台继续合成）。 */
    val notice: String? = null,
    /** 已下载的源字节数。 */
    val bytesDownloaded: Long = 0L,
    /** 预计总大小；还估不出来时为 null。 */
    val bytesTotal: Long? = null,
    /** 当前下载速度（字节/秒）；还没有速度时为 null。 */
    val bytesPerSecond: Long? = null,
) {
    val isActive: Boolean get() = phase in setOf(PlaybackTaskPhase.QUEUED, PlaybackTaskPhase.DOWNLOADING, PlaybackTaskPhase.COMPOSITING, PlaybackTaskPhase.SAVING)

    /** “1.2 MB / 约 5.4 GB”；估不出总量时只显示已下载部分。 */
    val sizeLabel: String?
        get() = when {
            bytesDownloaded <= 0L -> null
            bytesTotal != null && bytesTotal > 0L -> "${formatSize(bytesDownloaded)} / 约 ${formatSize(bytesTotal)}"
            else -> formatSize(bytesDownloaded)
        }

    /** “9.1 MB/s”。 */
    val speedLabel: String?
        get() = bytesPerSecond?.takeIf { it > 0 }?.let { formatSpeed(it) }
}

sealed interface PlaybackEnqueueResult {
    data class Started(val taskId: String) : PlaybackEnqueueResult
    data object Cancelled : PlaybackEnqueueResult
    data class Failed(val message: String) : PlaybackEnqueueResult
}

/**
 * 回放下载任务：先在系统面板选好保存位置，然后在会话级作用域里排队执行
 * （下载 → 无损转 MP4 → 可选的左右并排合成 → 写到所选位置）。离开页面不影响；
 * 同一时间只跑一个任务，避免两个大任务抢带宽和编码器。
 */
class PlaybackTaskManager(
    private val scope: CoroutineScope,
    private val downloader: HlsRecordingDownloader,
    private val compositor: () -> VideoCompositor,
    private val backgroundHost: () -> PlaybackBackgroundHost?,
) {
    private val mutableTasks = MutableStateFlow<List<PlaybackTask>>(emptyList())
    val tasks: StateFlow<List<PlaybackTask>> = mutableTasks.asStateFlow()

    private val runMutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private var nextId = 0

    val canComposite: Boolean get() = compositor().isAvailable

    init {
        scope.launch { runCatching { MediaWorkDirectory.purgeStale() } }
    }

    /** 选位置（会弹系统面板）后入队。合成时只要一个输出文件，否则每一路一个。 */
    suspend fun enqueue(
        detail: PlaybackDetail,
        streams: List<PlaybackStream>,
        courseName: String,
        composite: Boolean,
        gateway: HomeworkFileGateway,
        quality: PlaybackCompositeQuality = PlaybackCompositeQuality.HIGH,
    ): PlaybackEnqueueResult {
        if (streams.isEmpty()) return PlaybackEnqueueResult.Failed("先选择画面")
        if (!gateway.isStreamingSaveAvailable) return PlaybackEnqueueResult.Failed("当前平台还不能保存大文件。")
        val ordered = streams.sortedBy { it.kind.ordinal }
        val doComposite = composite && ordered.size == 2 && compositor().isAvailable
        val names = if (doComposite) {
            listOf(playbackFileName(courseName, detail.lesson, ordered.joinToString("+") { it.kind.fileLabel }))
        } else {
            ordered.map { playbackFileName(courseName, detail.lesson, it.kind.fileLabel) }
        }
        val destinations = mutableListOf<StreamingDestination>()
        for (name in names) {
            when (val opened = gateway.openStreamingDestination(safeExportFileName(name), "video/mp4")) {
                is StreamingDestinationResult.Opened -> destinations += opened.destination
                StreamingDestinationResult.Cancelled -> {
                    destinations.forEach { it.abort() }
                    return PlaybackEnqueueResult.Cancelled
                }
                is StreamingDestinationResult.Failed -> {
                    destinations.forEach { it.abort() }
                    return PlaybackEnqueueResult.Failed(gatewayFailureMessage(opened.reason))
                }
            }
        }
        val id = newTaskId()
        val task = PlaybackTask(
            id = id,
            courseName = courseName,
            lessonTitle = detail.lesson.title,
            streamLabels = ordered.map { it.kind.label },
            composite = doComposite,
            outputs = destinations.map { it.displayName },
        )
        mutableTasks.value = listOf(task) + mutableTasks.value
        jobs[id] = scope.launch { run(id, ordered, doComposite, destinations, quality) }
        refreshBackground()
        return PlaybackEnqueueResult.Started(id)
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    fun dismiss(id: String) {
        if (mutableTasks.value.firstOrNull { it.id == id }?.isActive == true) return
        mutableTasks.value = mutableTasks.value.filterNot { it.id == id }
    }

    fun clearFinished() {
        mutableTasks.value = mutableTasks.value.filter { it.isActive }
    }

    private suspend fun run(
        id: String,
        streams: List<PlaybackStream>,
        composite: Boolean,
        destinations: List<StreamingDestination>,
        quality: PlaybackCompositeQuality,
    ) {
        val work = MediaWorkDirectory.forTask(id)
        val committed = BooleanArray(destinations.size)
        try {
            runMutex.withLock {
                // 先登记再落目录：清理残余目录时不会把这个正在跑的任务目录删掉。
                MediaWorkDirectory.markLive(id)
                work.create()
                val downloadShare = if (composite) 0.6f else 1f
                val perStream = downloadShare / streams.size
                val remuxed = mutableListOf<String>()
                // 体积与速度：已下载字节、各路预计总大小、当前速度。
                var downloadedBytes = 0L
                var estimatedTotalBytes = 0L
                var bytesPerSecond = 0L
                var durationSeconds = 0.0
                var finishedStreamsBytes = 0L
                var finishedStreamsCount = 0
                for ((index, stream) in streams.withIndex()) {
                    val base = perStream * index
                    val label = stream.kind.label
                    val which = if (streams.size > 1) "（第 ${index + 1}/${streams.size} 路）" else ""
                    update(id) { it.copy(phase = PlaybackTaskPhase.DOWNLOADING, step = "下载$label$which", progress = base) }
                    val dataPath = work.file("s$index.data")
                    var streamBytes = 0L
                    val header = AppendFile(dataPath).use { data ->
                        val pending = ByteArrayBuilder()
                        val remuxer = TsToMp4Remuxer { pending.append(it) }
                        suspend fun flush() {
                            if (pending.size > 0) {
                                data.append(pending.toByteArray())
                                pending.clear()
                            }
                        }
                        downloader.download(stream.hlsUrl, StreamingFileSink { segment ->
                            remuxer.feed(segment)
                            flush()
                        }) { progress ->
                            // 下载占这一路的 90%，剩下 10% 是写出 MP4。
                            val fraction = if (progress.totalSegments > 0) {
                                progress.completedSegments.toFloat() / progress.totalSegments
                            } else {
                                0f
                            }
                            streamBytes = progress.writtenBytes
                            downloadedBytes = finishedStreamsBytes + streamBytes
                            durationSeconds = progress.durationSeconds
                            // 速度按“已下载字节 ÷ 这些字节对应的录像时长”算：直接用字节÷墙钟时间会被
                            // 首个分片的突发下载放大（2.5 MB 一下到手，算出 6 MB/s，实际整路只有 250 KB/s）。
                            val segmentSeconds = if (progress.totalSegments > 0) {
                                progress.durationSeconds / progress.totalSegments
                            } else {
                                0.0
                            }
                            val mediaSeconds = segmentSeconds * progress.completedSegments
                            bytesPerSecond = if (mediaSeconds > 0.5) {
                                (streamBytes / mediaSeconds).toLong()
                            } else {
                                0L
                            }
                            // 总大小 = 已完成各路的实际字节 + 剩下各路按当前速率×录像时长估算。
                            estimatedTotalBytes = if (bytesPerSecond > 0 && durationSeconds > 0) {
                                finishedStreamsBytes +
                                    (streams.size - finishedStreamsCount) * (bytesPerSecond * durationSeconds).toLong()
                            } else {
                                0L
                            }
                            update(id) {
                                it.copy(
                                    progress = base + perStream * 0.9f * fraction,
                                    bytesDownloaded = downloadedBytes,
                                    bytesTotal = estimatedTotalBytes.takeIf { it > 0 },
                                    bytesPerSecond = bytesPerSecond,
                                )
                            }
                        }
                        remuxer.finish()
                        flush()
                        remuxer.buildHeader()
                    }
                    finishedStreamsBytes += streamBytes
                    finishedStreamsCount += 1
                    if (composite) {
                        val mp4 = work.file("s$index.mp4")
                        AppendFile(mp4).use { out ->
                            out.append(header)
                            readFileInChunks(dataPath) { out.append(it) }
                        }
                        remuxed += mp4.toString()
                    } else {
                        update(id) { it.copy(phase = PlaybackTaskPhase.SAVING, step = "保存$label$which") }
                        val destination = destinations[index]
                        destination.write(header)
                        readFileInChunks(dataPath) { destination.write(it) }
                        val saved = destination.commit()
                        if (saved != HomeworkFileSaveResult.Saved) throw SaveFailure(saved)
                        committed[index] = true
                    }
                    runCatching { kotlinx.io.files.SystemFileSystem.delete(dataPath, mustExist = false) }
                    update(id) { it.copy(progress = base + perStream) }
                }
                if (composite) {
                    // 合成输出的体积由码率和录像时长决定，下载阶段估的是源文件大小，这里换成输出估计。
                    val outputEstimate = if (durationSeconds > 0) {
                        (quality.bitRate / 8.0 * durationSeconds).toLong()
                    } else {
                        0L
                    }
                    update(id) {
                        it.copy(
                            phase = PlaybackTaskPhase.COMPOSITING,
                            step = "合成为左右并排视频",
                            progress = 0.6f,
                            bytesTotal = outputEstimate.takeIf { it > 0 },
                            bytesPerSecond = null,
                        )
                    }
                    val output = work.file("composite.mp4").toString()
                    compositor().composeSideBySide(
                        leftPath = remuxed[0],
                        rightPath = remuxed[1],
                        outputPath = output,
                        quality = quality,
                        onProgress = { fraction -> update(id) { it.copy(progress = 0.6f + 0.37f * fraction.coerceIn(0f, 1f)) } },
                        onStatus = { notice -> update(id) { it.copy(notice = notice) } },
                    )
                    update(id) { it.copy(phase = PlaybackTaskPhase.SAVING, step = "保存合成视频", progress = 0.97f, notice = null) }
                    val destination = destinations.single()
                    readFileInChunks(kotlinx.io.files.Path(output)) { destination.write(it) }
                    val saved = destination.commit()
                    if (saved != HomeworkFileSaveResult.Saved) throw SaveFailure(saved)
                    committed[0] = true
                }
            }
            update(id) {
                it.copy(
                    phase = PlaybackTaskPhase.DONE,
                    progress = 1f,
                    step = "",
                    notice = null,
                    outputs = destinations.map { d -> d.displayName },
                    message = if (it.composite) "已保存合成视频（左：${it.streamLabels[0]}，右：${it.streamLabels[1]}）。" else "已保存。",
                )
            }
        } catch (error: CancellationException) {
            update(id) { it.copy(phase = PlaybackTaskPhase.CANCELLED, notice = null, message = "已取消，未完成的文件已删除。") }
            throw error
        } catch (error: Exception) {
            update(id) { it.copy(phase = PlaybackTaskPhase.FAILED, notice = null, message = failureMessage(error)) }
        } finally {
            withContext(NonCancellable) {
                destinations.forEachIndexed { index, destination -> if (!committed[index]) runCatching { destination.abort() } }
                runCatching { work.deleteAll() }
                runCatching { MediaWorkDirectory.markFinished(id) }
                jobs.remove(id)
                refreshBackground()
            }
        }
    }

    private class SaveFailure(val result: HomeworkFileSaveResult) : Exception("save failed")

    private fun failureMessage(error: Exception): String = when (error) {
        is HlsDownloadException -> when (error.reason) {
            HlsDownloadFailure.NETWORK -> "下载中断：录播服务器连接失败，请检查网络后重试。"
            HlsDownloadFailure.MALFORMED_PLAYLIST -> "录播服务器返回的播放列表无法识别。"
            HlsDownloadFailure.UNSUPPORTED -> "这一路录像的地址或加密方式不受支持。"
        }
        is RemuxException -> "录像格式无法转换为 MP4：${error.message}"
        is CompositionException -> "合成失败：${error.message}。可以取消勾选“合成”后分别下载。"
        is StreamingSinkIoException -> "写入文件失败（空间不足或位置不可写）。"
        is SaveFailure -> when (val r = error.result) {
            is HomeworkFileSaveResult.Failed -> gatewayFailureMessage(r.reason)
            else -> "保存失败。"
        }
        else -> "处理失败（${error::class.simpleName}）。磁盘空间不足时也会出现，请清理后重试。"
    }

    private fun update(id: String, transform: (PlaybackTask) -> PlaybackTask) {
        val before = mutableTasks.value
        mutableTasks.value = before.map { if (it.id == id) transform(it) else it }
        refreshBackground()
    }

    private var lastBackgroundSignature: String? = null

    /** 任务状态转成宿主（通知/实时活动）用的内容。 */
    private fun PlaybackTask.toBackgroundState(queuedCount: Int): PlaybackBackgroundState = PlaybackBackgroundState(
        title = lessonTitle,
        phaseLabel = phase.label,
        detail = buildString {
            step.takeIf(String::isNotBlank)?.let(::append)
            if (queuedCount > 1) append("（另有 ${queuedCount - 1} 个任务排队）")
        },
        progress = progress,
        sizeLabel = sizeLabel,
        speedLabel = speedLabel,
    )

    private fun refreshBackground() {
        val host = backgroundHost() ?: return
        val all = mutableTasks.value
        val active = all.filter { it.isActive }
        val current = active.firstOrNull { it.phase != PlaybackTaskPhase.QUEUED } ?: active.firstOrNull()
        val state = current?.toBackgroundState(active.size)
        // 进度按 1% 去抖，避免频繁刷新通知/实时活动（合成时进度回调很密）。
        val signature = buildString {
            append(state?.title).append('|')
            append(state?.phaseLabel).append('|')
            append(state?.detail).append('|')
            append(state?.progress?.let { (it * 100).toInt() }).append('|')
            append(state?.sizeLabel).append('|')
            append(state?.speedLabel)
        }
        if (signature != lastBackgroundSignature) {
            lastBackgroundSignature = signature
            if (current != null) {
                host.update(true, state)
                return
            }
            // 没有进行中的任务了：刚结束的那个先把终态推给宿主（通知/实时活动短暂显示结果），再收掉。
            terminalPushed.retainAll(all.map { it.id }.toSet())
            val terminal = all.firstOrNull { it.phase.isTerminal && it.id !in terminalPushed }
            if (terminal != null) {
                terminalPushed += terminal.id
                host.update(true, terminal.toBackgroundState(0))
            }
        }
        host.update(false, null)
    }

    /** 已经把终态推给宿主的任务，避免每次刷新都重推导致实时活动反复重建。 */
    private val terminalPushed = mutableSetOf<String>()

    @OptIn(ExperimentalTime::class)
    private fun newTaskId(): String = "t${Clock.System.now().toEpochMilliseconds()}-${nextId++}"
}

internal fun gatewayFailureMessage(reason: HomeworkFileGatewayFailure): String = when (reason) {
    HomeworkFileGatewayFailure.PERMISSION_DENIED -> "没有写入所选位置的权限，请换个位置。"
    HomeworkFileGatewayFailure.UNAVAILABLE -> "保存面板暂时不可用，请稍后再试。"
    HomeworkFileGatewayFailure.IO -> "写入文件失败（空间不足或位置不可写）。"
}

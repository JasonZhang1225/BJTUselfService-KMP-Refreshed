package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlinx.coroutines.CancellationException
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsDownloadException
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsDownloadFailure
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsSegmentCheckpoint
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStreamKind
import team.bjtuss.bjtuselfservice.shared.data.homework.isSchoolPlaybackStreamUrl
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

@Serializable
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

@Serializable
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
    val canResume: Boolean = false,
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
        get() = bytesPerSecond?.let { formatSpeed(it) }
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
    private val taskStore: PlaybackTaskStore? = null,
    private val restoreDestination: (suspend (String, String) -> StreamingDestinationResult)? = null,
) {
    private val mutableTasks = MutableStateFlow<List<PlaybackTask>>(emptyList())
    val tasks: StateFlow<List<PlaybackTask>> = mutableTasks.asStateFlow()

    private val runMutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private var nextId = 0
    private class Request(
        val streams: List<PlaybackStream>, val composite: Boolean,
        val destinations: List<StreamingDestination>, val quality: PlaybackCompositeQuality,
    ) {
        val committed = BooleanArray(destinations.size)
        val checkpoints = mutableMapOf<Int, HlsSegmentCheckpoint>()
        val finished = mutableMapOf<Int, Pair<Long, Double>>()
    }
    private val requests = mutableMapOf<String, Request>()
    private val storedRequests = MutableStateFlow<Map<String, StoredPlaybackRequest>>(emptyMap())
    private val recordVersion = MutableStateFlow(0L)
    private val persistenceMutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val workRoot get() = taskStore?.workRoot ?: MediaWorkDirectory.root
    private var persistenceAvailable = true

    val canComposite: Boolean get() = compositor().isAvailable

    init {
        scope.launch {
            try {
                restoreTasks()
                MediaWorkDirectory.purgeStale(requests.keys.toSet(), workRoot)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                persistenceAvailable = false // 不用空记录覆盖可能损坏但尚可恢复的旧文件。
                mutableTasks.value = listOf(PlaybackTask("history-error", "", "下载记录", emptyList(), false,
                    phase = PlaybackTaskPhase.FAILED, message = "下载记录读取失败，原记录已保留，请稍后重试。"))
            } finally { initialized.complete(Unit) }
            if (taskStore != null && persistenceAvailable) recordVersion.collect {
                runCatching { persistNow() }
                delay(500) // 合成/速度回调很密，状态文件最多每秒写两次。
            }
        }
        scope.launch {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    jobs.values.toList().forEach { it.cancelAndJoin() }
                    // 退出账号时，已经失败并等待继续的任务也要关闭目标与清理分片。
                    requests.toMap().forEach { (id, request) -> discard(id, request) }
                    requests.clear()
                    storedRequests.value = emptyMap()
                    runCatching { persistNow() }
                }
            }
        }
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
        initialized.await()
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
        requests[id] = Request(ordered, doComposite, destinations, quality)
        rememberRequest(id, requests.getValue(id))
        try { persistNow() } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            requests.remove(id)?.let { discard(id, it) }
            storedRequests.update { it - id }
            update(id) { it.copy(phase = PlaybackTaskPhase.FAILED, message = "下载记录保存失败，请检查剩余空间后重试。") }
            return PlaybackEnqueueResult.Failed("下载记录保存失败，请检查剩余空间后重试。")
        }
        start(id)
        refreshBackground()
        return PlaybackEnqueueResult.Started(id)
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    fun dismiss(id: String) {
        if (mutableTasks.value.firstOrNull { it.id == id }?.isActive == true) return
        requests.remove(id)?.let { request -> scope.launch { discard(id, request) } }
        mutableTasks.value = mutableTasks.value.filterNot { it.id == id }
        storedRequests.update { it - id }
        recordVersion.update { it + 1 }
    }

    fun clearFinished() {
        mutableTasks.value.filterNot { it.isActive || it.canResume }.forEach { dismiss(it.id) }
    }

    /** 网络失败后沿用保存位置与分片，不重新选择或重下已完成的画面。 */
    fun retry(id: String) {
        if (mutableTasks.value.firstOrNull { it.id == id }?.canResume != true) return
        update(id) { it.copy(phase = PlaybackTaskPhase.QUEUED, canResume = false, message = null, bytesPerSecond = null) }
        start(id)
    }

    private fun start(id: String) {
        val request = requests[id] ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) { run(id, request) }
        jobs[id] = job
        job.start()
    }

    private suspend fun discard(id: String, request: Request) = withContext(NonCancellable) {
        request.destinations.forEachIndexed { index, destination ->
            if (!request.committed[index]) runCatching { destination.abort() }
        }
        runCatching { MediaWorkDirectory.forTask(id, workRoot).deleteAll() }
        MediaWorkDirectory.markFinished(id)
    }

    private suspend fun run(
        id: String,
        request: Request,
    ) {
        val streams = request.streams
        val composite = request.composite
        val destinations = request.destinations
        val quality = request.quality
        val work = MediaWorkDirectory.forTask(id, workRoot)
        val committed = request.committed
        var resumable = false
        try {
            runMutex.withLock {
                // 先登记再落目录：清理残余目录时不会把这个正在跑的任务目录删掉。
                MediaWorkDirectory.markLive(id, workRoot)
                work.create()
                val downloadShare = if (composite) 0.6f else 1f
                val perStream = downloadShare / streams.size
                val remuxed = mutableListOf<String>()
                // 体积与速度：已下载字节、各路预计总大小、当前速度。
                var downloadedBytes = 0L
                var estimatedTotalBytes = 0L
                var durationSeconds = 0.0
                var finishedStreamsBytes = 0L
                var finishedStreamsCount = 0
                for ((index, stream) in streams.withIndex()) {
                    val base = perStream * index
                    val label = stream.kind.label
                    val which = if (streams.size > 1) "（第 ${index + 1}/${streams.size} 路）" else ""
                    request.finished[index]?.let { (bytes, duration) ->
                        finishedStreamsBytes += bytes
                        finishedStreamsCount++
                        durationSeconds = duration
                    }
                    if (index in request.finished) {
                        if (composite) remuxed += work.file("s$index.mp4").toString()
                        continue
                    }
                    update(id) { it.copy(phase = PlaybackTaskPhase.DOWNLOADING, step = "下载$label$which", progress = maxOf(it.progress ?: base, base)) }
                    val dataPath = work.file("s$index.data")
                    var streamBytes = 0L
                    val checkpoint = request.checkpoints.getOrPut(index) {
                        HlsSegmentCheckpoint(MediaWorkDirectory(work.file("segments-$index")))
                    }
                    val header = AppendFile(dataPath).use { data ->
                        val pending = ByteArrayBuilder()
                        val remuxer = TsToMp4Remuxer { pending.append(it) }
                        suspend fun flush() {
                            if (pending.size > 0) {
                                data.append(pending.toByteArray())
                                pending.clear()
                            }
                        }
                        coroutineScope {
                        val speed = DownloadSpeedMeter()
                        var previousTransferred = 0L
                        val ticker = launch {
                            while (true) {
                                delay(500)
                                val sampled = speed.sample()
                                update(id) { it.copy(bytesPerSecond = sampled) }
                            }
                        }
                        try {
                        downloader.download(stream.hlsUrl, StreamingFileSink { segment ->
                            // TS 解析在大录像上耗 CPU；不能阻塞服务的主线程启动期限。
                            withContext(Dispatchers.Default) { remuxer.feed(segment) }
                            flush()
                        }, checkpoint, onRetry = { attempt, wait ->
                            update(id) { it.copy(notice = "连接中断，${wait / 1000} 秒后自动重连（第 $attempt/3 次）") }
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
                            speed.add(progress.transferredBytes - previousTransferred)
                            previousTransferred = progress.transferredBytes
                            // 总量估计仍按分片完成比例，与网络传输速度分开计算。
                            estimatedTotalBytes = if (fraction > 0) {
                                finishedStreamsBytes +
                                    (streams.size - finishedStreamsCount) * (streamBytes / fraction).toLong()
                            } else {
                                0L
                            }
                            update(id) {
                                it.copy(
                                    progress = maxOf(it.progress ?: 0f, base + perStream * 0.9f * fraction),
                                    bytesDownloaded = maxOf(it.bytesDownloaded, downloadedBytes),
                                    bytesTotal = estimatedTotalBytes.takeIf { it > 0 },
                                    notice = if (progress.transferredBytes > 0) null else it.notice,
                                )
                            }
                        }
                        } finally { ticker.cancel() }
                        }
                        withContext(Dispatchers.Default) { remuxer.finish() }
                        flush()
                        withContext(Dispatchers.Default) { remuxer.buildHeader() }
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
                    request.finished[index] = streamBytes to durationSeconds
                    rememberRequest(id, request)
                    persistNow() // 元数据先落盘，再清理这一整路已不需要的 TS 断点。
                    checkpoint.clear()
                    update(id) { it.copy(progress = base + perStream, bytesPerSecond = null) }
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
            resumable = error is HlsDownloadException && error.reason == HlsDownloadFailure.NETWORK
            update(id) { it.copy(phase = PlaybackTaskPhase.FAILED, notice = null, bytesPerSecond = null,
                message = if (resumable) "连接中断，已保留下载分片。恢复网络后点“继续下载”。" else failureMessage(error)) }
        } finally {
            withContext(NonCancellable) {
                jobs.remove(id)
                if (!resumable) {
                    requests.remove(id)
                    storedRequests.update { it - id }
                    discard(id, request)
                } else {
                    update(id) { it.copy(canResume = true) }
                }
                refreshBackground()
                runCatching { persistNow() }
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
        mutableTasks.update { before -> before.map { if (it.id == id) transform(it) else it } }
        recordVersion.update { it + 1 }
        refreshBackground()
    }

    private fun rememberRequest(id: String, request: Request) {
        // 当前学校录像是无 Cookie 的公开地址。带 query 的地址可能含 token，不落普通文件。
        if (request.streams.any { !isPersistablePlaybackUrl(it.hlsUrl) }) return
        val saved = StoredPlaybackRequest(
            request.streams.map { StoredPlaybackStream(it.kind.name, it.hlsUrl) }, request.composite,
            request.quality.name, request.destinations.map { it.recoveryToken }, request.committed.toList(),
            request.finished.mapValues { (_, result) -> StoredPlaybackStreamResult(result.first, result.second) },
        )
        storedRequests.update { it + (id to saved) }
        recordVersion.update { it + 1 }
    }

    private suspend fun persistNow() {
        val store = taskStore ?: return
        if (!persistenceAvailable) return
        persistenceMutex.withLock {
            val requestSnapshot = storedRequests.value
            store.save(mutableTasks.value.map { task ->
                StoredPlaybackTask(task.copy(bytesPerSecond = null, notice = null), requestSnapshot[task.id])
            })
        }
    }

    private suspend fun restoreTasks() {
        val store = taskStore ?: return
        val restored = mutableListOf<PlaybackTask>()
        for (saved in store.load()) {
            val task = saved.task
            if (!task.isActive && !task.canResume) { restored += task; continue }
            val request = saved.request
            var recovered: Request? = null
            if (request != null && Regex("t[0-9]+-[0-9]+").matches(task.id) &&
                request.streams.isNotEmpty() && request.streams.all { isPersistablePlaybackUrl(it.url) } &&
                request.destinations.size == task.outputs.size && request.committed.size == task.outputs.size) {
                val destinations = mutableListOf<StreamingDestination>()
                for ((index, token) in request.destinations.withIndex()) {
                    if (request.committed[index]) {
                        destinations += object : StreamingDestination {
                            override val displayName = task.outputs[index]
                            override val recoveryToken = token
                            override suspend fun write(bytes: ByteArray) = error("already saved")
                            override suspend fun commit() = HomeworkFileSaveResult.Saved
                            override suspend fun abort() = Unit
                        }
                    } else {
                        val result = token?.let { restoreDestination?.invoke(it, task.outputs[index]) }
                        if (result !is StreamingDestinationResult.Opened) break
                        destinations += result.destination
                    }
                }
                if (destinations.size == request.destinations.size) {
                    val resumed = Request(request.streams.map { PlaybackStream(PlaybackStreamKind.valueOf(it.kind), it.url) },
                        request.composite, destinations, PlaybackCompositeQuality.valueOf(request.quality))
                    request.committed.forEachIndexed { index, value -> resumed.committed[index] = value }
                    request.finished.forEach { (index, value) -> resumed.finished[index] = value.bytes to value.durationSeconds }
                    recovered = resumed
                    requests[task.id] = resumed
                    rememberRequest(task.id, resumed)
                    MediaWorkDirectory.markLive(task.id, workRoot)
                }
            }
            restored += task.copy(phase = PlaybackTaskPhase.FAILED, bytesPerSecond = null, notice = null,
                canResume = recovered != null,
                message = if (recovered != null) "上次运行中断，下载记录与分片已恢复。点“继续下载”接着处理。"
                    else "上次运行中断，下载记录已恢复；保存位置已不可用，请重新发起下载。")
        }
        mutableTasks.value = restored
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
        // 内容没变化仍然有活动任务，不能停掉服务。否则速度采样/重复进度会让
        // Android 的常驻通知反复撤销、重建，甚至触发前台服务启动超时崩溃。
        if (current != null) return
        host.update(false, null)
    }

    /** 已经把终态推给宿主的任务，避免每次刷新都重推导致实时活动反复重建。 */
    private val terminalPushed = mutableSetOf<String>()

    @OptIn(ExperimentalTime::class)
    private fun newTaskId(): String = "t${Clock.System.now().toEpochMilliseconds()}-${nextId++}"
}

private fun isPersistablePlaybackUrl(url: String): Boolean =
    '?' !in url && isSchoolPlaybackStreamUrl(url) && runCatching {
        val parsed = Url(url)
        parsed.user == null && parsed.password == null
    }.getOrDefault(false)

internal fun gatewayFailureMessage(reason: HomeworkFileGatewayFailure): String = when (reason) {
    HomeworkFileGatewayFailure.PERMISSION_DENIED -> "没有写入所选位置的权限，请换个位置。"
    HomeworkFileGatewayFailure.UNAVAILABLE -> "保存面板暂时不可用，请稍后再试。"
    HomeworkFileGatewayFailure.IO -> "写入文件失败（空间不足或位置不可写）。"
}

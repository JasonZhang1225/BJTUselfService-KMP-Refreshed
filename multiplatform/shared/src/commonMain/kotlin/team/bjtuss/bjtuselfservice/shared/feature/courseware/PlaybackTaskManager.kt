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
import kotlinx.coroutines.flow.getAndUpdate
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
import team.bjtuss.bjtuselfservice.shared.media.ProgressiveVideoCompositor
import team.bjtuss.bjtuselfservice.shared.media.readFileInChunks

@Serializable
enum class PlaybackTaskPhase(val label: String) {
    QUEUED("排队中"),
    PAUSED("已暂停"),
    DOWNLOADING("下载中"),
    PIPELINING("下载并合成"),
    COMPOSITING("拼接中"),
    FINALIZING("整理中"),
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
    /** 与 outputs 一一对应；旧记录和没有本地文件系统路径的平台可以为空。 */
    val outputPaths: List<String?> = emptyList(),
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
    val stageProgress: Float? = null,
    val remainingSeconds: Long? = null,
    val progressive: Boolean = false,
    val downloadProgress: Float? = null,
    /** 已决定丢弃中间文件，但清理尚未完成；记录必须保留以便重试。 */
    val cleanupPending: Boolean = false,
    /** 仅当前进程的合成设备；重启或恢复时由实际执行器重新报告。 */
    @kotlinx.serialization.Transient val compositionDevice: String? = null,
) {
    val isActive: Boolean get() = phase in setOf(PlaybackTaskPhase.QUEUED, PlaybackTaskPhase.DOWNLOADING, PlaybackTaskPhase.PIPELINING, PlaybackTaskPhase.COMPOSITING, PlaybackTaskPhase.FINALIZING, PlaybackTaskPhase.SAVING)
    val canPause: Boolean get() = phase == PlaybackTaskPhase.QUEUED || phase == PlaybackTaskPhase.DOWNLOADING ||
        phase == PlaybackTaskPhase.PIPELINING || phase == PlaybackTaskPhase.COMPOSITING ||
        (progressive && phase == PlaybackTaskPhase.FINALIZING)

    /** “1.2 MB / 约 5.4 GB”；估不出总量时只显示已下载部分。 */
    val sizeLabel: String?
        get() = when {
            bytesDownloaded <= 0L -> null
            progressive && phase in setOf(PlaybackTaskPhase.COMPOSITING, PlaybackTaskPhase.FINALIZING) -> "已下载 ${formatSize(bytesDownloaded)}"
            composite && (phase == PlaybackTaskPhase.COMPOSITING || phase == PlaybackTaskPhase.SAVING) ->
                "已下载 ${formatSize(bytesDownloaded)}" + (bytesTotal?.let { " · 预计输出 ${formatSize(it)}" } ?: "")
            bytesTotal != null && bytesTotal > 0L -> "${formatSize(bytesDownloaded)} / 约 ${formatSize(bytesTotal)}"
            else -> formatSize(bytesDownloaded)
        }

    /** “9.1 MB/s”。 */
    val speedLabel: String?
        get() = bytesPerSecond?.let { formatSpeed(it) }

    val remainingLabel: String? get() = if (!isActive || phase == PlaybackTaskPhase.QUEUED) null else {
        val estimate = remainingSeconds?.let { (if (it < 60) "" else "约 ") + formatRemainingSeconds(it) } ?: "估算中"
        when (phase) {
            PlaybackTaskPhase.PIPELINING -> {
                val encodePending = (stageProgress ?: 0f) < 0.01f
                if (encodePending) "预计剩余$estimate（含下载后合成）"
                else "预计剩余$estimate（下载与合成并行）"
            }
            PlaybackTaskPhase.DOWNLOADING -> "下载剩余$estimate" + if (composite) " · 合成时间另计" else ""
            PlaybackTaskPhase.COMPOSITING -> "合成剩余$estimate · 随后保存文件"
            PlaybackTaskPhase.FINALIZING -> "整理剩余$estimate · 随后保存文件"
            PlaybackTaskPhase.SAVING -> "保存剩余$estimate"
            else -> null
        }
    }
}

sealed interface PlaybackEnqueueResult {
    data class Started(val taskId: String) : PlaybackEnqueueResult
    data object Cancelled : PlaybackEnqueueResult
    data class Failed(val message: String) : PlaybackEnqueueResult
}

/**
 * 回放下载任务：先在系统面板选好保存位置，然后在宿主提供的长生命周期作用域里排队执行
 * （下载 → 无损转 MP4 → 可选的左右并排合成 → 写到所选位置）。离开页面不影响；
 * 同一时间只跑一个任务，避免两个大任务抢带宽和编码器。
 */
class PlaybackTaskManager(
    private val scope: CoroutineScope,
    private val downloader: HlsRecordingDownloader,
    private val compositor: () -> VideoCompositor,
    private val backgroundHost: () -> PlaybackBackgroundHost?,
    private val taskStore: PlaybackTaskStore? = null,
    /** 测试可覆盖；生产默认跟平台 `playbackRecordingDownloadAvailable`。须放在 restoreDestination 前，保留尾随 lambda。 */
    private val recordingDownloadsEnabled: Boolean = playbackRecordingDownloadAvailable,
    private val restoreDestination: (suspend (String, String) -> StreamingDestinationResult)? = null,
) {
    private val mutableTasks = MutableStateFlow<List<PlaybackTask>>(emptyList())
    val tasks: StateFlow<List<PlaybackTask>> = mutableTasks.asStateFlow()

    private val runMutex = Mutex()
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    // Mutex 只保证等待者的先后，不保证被调度的协程按入队顺序抵达锁。
    private val queueTail = MutableStateFlow<CompletableDeferred<Unit>?>(null)
    private enum class Stop { PAUSE, DISCARD }
    private val stops = MutableStateFlow<Map<String, Stop>>(emptyMap())
    private val nextId = MutableStateFlow(0)
    private class Request(
        val streams: List<PlaybackStream>, val composite: Boolean,
        val destinations: List<StreamingDestination>, val quality: PlaybackCompositeQuality,
        val progressive: Boolean = false,
    ) {
        val committed = BooleanArray(destinations.size)
        val checkpoints = mutableMapOf<Int, HlsSegmentCheckpoint>()
        val finished = mutableMapOf<Int, Pair<Long, Double>>()
        var compositionComplete = false
    }
    private val requests = MutableStateFlow<Map<String, Request>>(emptyMap())
    private val storedRequests = MutableStateFlow<Map<String, StoredPlaybackRequest>>(emptyMap())
    private val cleaning = MutableStateFlow<Set<String>>(emptySet())
    private val recordVersion = MutableStateFlow(0L)
    private val persistenceMutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val workRoot get() = taskStore?.workRoot ?: MediaWorkDirectory.root
    private var persistenceAvailable = true
    private var historyLoaded = false

    val canComposite: Boolean get() = compositor().isAvailable

    init {
        scope.launch {
            try {
                restoreTasks()
                historyLoaded = true
                // 持久目录中无法关联记录的数据可能是异常退出留下的断点，不能猜测归属后删除。
                if (taskStore == null) MediaWorkDirectory.purgeStale(requests.value.keys, workRoot)
            } catch (error: CancellationException) {
                persistenceAvailable = false // 读取未完成，不能用空列表覆盖上次运行的记录。
                throw error
            }
            catch (_: Exception) {
                persistenceAvailable = false // 不用空记录覆盖可能损坏但尚可恢复的旧文件。
                mutableTasks.value = listOf(PlaybackTask("history-error", "", "下载记录", emptyList(), false,
                    phase = PlaybackTaskPhase.FAILED, message = "下载记录读取失败，原记录和缓存已保留；请重启应用重试，仍失败时检查记录文件。"))
            } finally { initialized.complete(Unit) }
            if (persistenceAvailable) {
                mutableTasks.value.filter { it.cleanupPending }.forEach { launchCleanup(it.id, removeWhenDone = false) }
            }
            if (taskStore != null && persistenceAvailable) recordVersion.collect {
                while (true) {
                    try { persistNow(); break }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { delay(500) }
                }
                delay(500) // 合成/速度回调很密，状态文件最多每秒写两次。
            }
        }
        scope.launch {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    jobs.value.values.toList().forEach { it.cancelAndJoin() }
                    // 宿主销毁不是用户取消：保留断点。退出账号通过 cancelAllAndJoin 明确取消。
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
        if (taskStore != null && !persistenceAvailable) {
            return PlaybackEnqueueResult.Failed("下载记录不可用，已保留原记录和缓存；请先恢复记录后再开始新任务。")
        }
        if (streams.isEmpty()) return PlaybackEnqueueResult.Failed("先选择画面")
        if (!recordingDownloadsEnabled) {
            return PlaybackEnqueueResult.Failed("移动端仅支持在线播放，录像下载请使用 Windows 或 Mac。")
        }
        if (!gateway.isStreamingSaveAvailable) return PlaybackEnqueueResult.Failed("当前平台还不能保存大文件。")
        val ordered = streams.sortedBy { it.kind.ordinal }
        val doComposite = composite && ordered.size == 2 && compositor().isAvailable
        val doProgressive = doComposite && compositor() is ProgressiveVideoCompositor
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
            progressive = doProgressive,
            outputs = destinations.map { it.displayName },
            outputPaths = destinations.map { it.outputPath },
        )
        mutableTasks.update { listOf(task) + it }
        val request = Request(ordered, doComposite, destinations, quality, doProgressive)
        requests.update { it + (id to request) }
        rememberRequest(id, request)
        try { persistNow() } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            update(id) { it.copy(phase = PlaybackTaskPhase.FAILED, cleanupPending = true,
                message = "下载记录保存失败，请检查剩余空间后点“移除”重试清理。") }
            return PlaybackEnqueueResult.Failed("下载记录保存失败，请检查剩余空间后重试。")
        }
        start(id)
        refreshBackground()
        return PlaybackEnqueueResult.Started(id)
    }

    fun cancel(id: String) {
        stops.update { it + (id to Stop.DISCARD) }
        val job = jobs.value[id]
        if (job != null) job.cancel() else {
            if (id !in requests.value && id !in storedRequests.value) return
            val previous = mutableTasks.getAndUpdate { tasks -> tasks.map { task ->
                if (task.id == id && !task.isActive) task.copy(phase = PlaybackTaskPhase.CANCELLED,
                    canResume = false, remainingSeconds = null, cleanupPending = true,
                    message = "正在清理未完成的文件。") else task
            } }
            // 正在登记的排队任务会在 start 里读取 stop 并取消；不能同时启动清理。
            if (previous.none { it.id == id && !it.isActive }) return
            recordVersion.update { it + 1 }
            refreshBackground()
            launchCleanup(id, removeWhenDone = false)
        }
    }

    fun cancelAll() {
        requests.value.keys.toList().forEach(::cancel)
    }

    /** 清除账号数据前先停止写盘，避免清理结束后又被在途任务写回。 */
    suspend fun cancelAllAndJoin() = withContext(NonCancellable) {
        initialized.await()
        val running = jobs.value
        running.forEach { (id, job) -> stops.update { it + (id to Stop.DISCARD) }; job.cancel() }
        running.values.forEach { it.join() }
        requests.value.keys.toList().forEach { id ->
            update(id) { it.copy(phase = PlaybackTaskPhase.CANCELLED, canResume = false, remainingSeconds = null,
                cleanupPending = true, message = "正在清理未完成的文件。") }
            cleanupAndUpdate(id, removeWhenDone = false)
        }
        persistNow()
    }

    /** 暂停后保留输入；非渐进合成从头重新编码，渐进合成从已提交分段恢复。 */
    fun pause(id: String) {
        val task = tasks.value.firstOrNull { it.id == id } ?: return
        if (!task.canPause) return
        stops.update { it + (id to Stop.PAUSE) }
        jobs.value[id]?.cancel()
    }

    fun dismiss(id: String) {
        if (jobs.value[id] != null || mutableTasks.value.firstOrNull { it.id == id }?.isActive == true) return
        if (id in requests.value || id in storedRequests.value || mutableTasks.value.firstOrNull { it.id == id }?.cleanupPending == true) {
            val previous = mutableTasks.getAndUpdate { tasks -> tasks.map { task ->
                if (task.id == id && !task.isActive) task.copy(canResume = false, cleanupPending = true,
                    message = "正在清理临时文件。") else task
            } }
            if (previous.none { it.id == id && !it.isActive }) return
            recordVersion.update { it + 1 }
            refreshBackground()
            launchCleanup(id, removeWhenDone = true)
        } else {
            mutableTasks.update { tasks -> tasks.filterNot { it.id == id } }
            recordVersion.update { it + 1 }
        }
    }

    fun clearFinished() {
        mutableTasks.value.filterNot { it.isActive || it.canResume }.forEach { dismiss(it.id) }
    }

    /** 暂停/网络失败后沿用保存位置与分片，不重新选择或重下已完成的画面。 */
    fun retry(id: String) {
        if (!recordingDownloadsEnabled) return
        if (jobs.value[id] != null) return
        if (mutableTasks.value.firstOrNull { it.id == id }?.canResume != true) return
        stops.update { it - id }
        val previous = mutableTasks.getAndUpdate { tasks -> tasks.map { task ->
            if (task.id == id && task.canResume && !task.cleanupPending)
                task.copy(phase = PlaybackTaskPhase.QUEUED, canResume = false, message = null, bytesPerSecond = null,
                    remainingSeconds = null, compositionDevice = null) else task
        } }
        if (previous.none { it.id == id && it.canResume && !it.cleanupPending }) return
        recordVersion.update { it + 1 }
        refreshBackground()
        start(id)
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun start(id: String) {
        val request = requests.value[id] ?: return
        val turnFinished = CompletableDeferred<Unit>()
        val predecessor = queueTail.getAndUpdate { turnFinished }
        val registered = CompletableDeferred<Unit>()
        // 即使刚入队就暂停/退出，也必须进入 run 的 finally，不能留下不可继续的幽灵任务。
        val job = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                // 先登记 job 再允许执行，否则极快结束的任务可能与登记/重试交错。
                withContext(NonCancellable) { registered.await() }
                try { predecessor?.await() } finally { run(id, request) }
            } finally {
                // 排队任务被取消时也不能让后面的任务越过其尚未完成的前驱。
                withContext(NonCancellable) {
                    predecessor?.await()
                    turnFinished.complete(Unit)
                }
            }
        }
        jobs.update { it + (id to job) }
        if (stops.value[id] != null) job.cancel()
        registered.complete(Unit)
    }

    private suspend fun discard(id: String, request: Request): Boolean = withContext(NonCancellable) {
        var success = true
        request.destinations.forEachIndexed { index, destination ->
            if (!request.committed[index] && runCatching { destination.abortForCleanup() }.isFailure) success = false
        }
        if (runCatching { MediaWorkDirectory.forTask(id, workRoot).deleteAll() }.isFailure) success = false
        if (success) MediaWorkDirectory.markFinished(id)
        success
    }

    private suspend fun discardStored(id: String, task: PlaybackTask, saved: StoredPlaybackRequest?): Boolean = withContext(NonCancellable) {
        var success = true
        saved?.destinations?.forEachIndexed { index, token ->
            if (task.phase != PlaybackTaskPhase.DONE && saved.committed.getOrNull(index) != true) {
                val destination = runCatching { token?.let { restoreDestination?.invoke(it, task.outputs.getOrNull(index) ?: "") } }.getOrNull()
                if (destination !is StreamingDestinationResult.Opened || runCatching { destination.destination.abortForCleanup() }.isFailure) success = false
            }
        }
        if (runCatching { MediaWorkDirectory.forTask(id, workRoot).deleteAll() }.isFailure) success = false
        if (success) MediaWorkDirectory.markFinished(id)
        success
    }

    private fun launchCleanup(id: String, removeWhenDone: Boolean) {
        if (id in cleaning.getAndUpdate { it + id }) return
        scope.launch {
            try { cleanupAndUpdate(id, removeWhenDone) }
            finally { cleaning.update { it - id } }
        }
    }

    private suspend fun cleanupAndUpdate(id: String, removeWhenDone: Boolean): Boolean = withContext(NonCancellable) {
        val task = tasks.value.firstOrNull { it.id == id } ?: return@withContext true
        val request = requests.value[id]
        if (request != null) rememberRequest(id, request)
        update(id) { it.copy(cleanupPending = true, canResume = false, message = "正在清理临时文件。") }
        // 删除前持久化意图和所有权；否则异常退出后无法安全定位目标目录中的临时文件。
        val recorded = runCatching { persistNow() }.isSuccess
        val cleaned = recorded && if (request != null) discard(id, request) else discardStored(id, task, storedRequests.value[id])
        if (cleaned) {
            requests.update { it - id }
            storedRequests.update { it - id }
            if (removeWhenDone) {
                mutableTasks.update { tasks -> tasks.filterNot { it.id == id } }
                recordVersion.update { it + 1 }
            } else update(id) {
                it.copy(cleanupPending = false, message = when (it.phase) {
                    PlaybackTaskPhase.DONE -> "视频已保存，临时文件已清理。"
                    PlaybackTaskPhase.CANCELLED -> "已取消，未完成的文件已删除。"
                    else -> it.message
                })
            }
        } else update(id) { it.copy(cleanupPending = true,
            message = "部分临时文件或清理记录未能处理，请检查磁盘空间和权限后点“移除”重试。") }
        runCatching { persistNow() }
        cleaned
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
                if (request.progressive) {
                    val streaming = compositor() as? ProgressiveVideoCompositor
                        ?: throw CompositionException("当前平台不能继续此合成断点")
                    val outputExists = (kotlinx.io.files.SystemFileSystem.metadataOrNull(work.file("composite.mp4"))?.size ?: 0L) > 0L
                    if (!request.compositionComplete || !outputExists) {
                        if (request.compositionComplete) {
                            request.compositionComplete = false
                            rememberRequest(id, request)
                            persistNow()
                        }
                        runProgressive(id, request, work, streaming)
                        request.compositionComplete = true
                        rememberRequest(id, request)
                        persistNow()
                    }
                } else {
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
                        val eta = RemainingTimeEstimator()
                        var previousTransferred = 0L
                        val ticker = launch {
                            while (true) {
                                delay(500)
                                val sampled = speed.sample()
                                val task = tasks.value.first { it.id == id }
                                val remaining = task.bytesTotal?.let { (it - task.bytesDownloaded).coerceAtLeast(0).toDouble() }
                                val seconds = eta.sample(speed.totalTransferred.toDouble(), remaining ?: -1.0)
                                update(id) { it.copy(bytesPerSecond = sampled, remainingSeconds = seconds) }
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
                                    stageProgress = maxOf(it.stageProgress ?: 0f, (index + fraction) / streams.size),
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
                        rememberRequest(id, request)
                        persistNow() // 保存可能原子改名；先记录阶段，重启时才能识别提交结果不明。
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
                val compositePath = work.file("composite.mp4")
                val completedFileExists = request.compositionComplete &&
                    (kotlinx.io.files.SystemFileSystem.metadataOrNull(compositePath)?.size ?: 0L) > 0L
                if (composite && !completedFileExists) {
                    if (request.compositionComplete) {
                        request.compositionComplete = false
                        rememberRequest(id, request)
                        persistNow() // 防止重建过程中把半成品误认为上次已完成的文件。
                    }
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
                            stageProgress = 0f,
                            remainingSeconds = null,
                        )
                    }
                    val output = work.file("composite.mp4").toString()
                    val compositionEta = RemainingTimeEstimator()
                    compositor().composeSideBySide(
                        leftPath = remuxed[0],
                        rightPath = remuxed[1],
                        outputPath = output,
                        quality = quality,
                        onProgress = { fraction ->
                            val stage = fraction.coerceIn(0f, 1f)
                            val seconds = compositionEta.sample(stage.toDouble(), 1.0 - stage)
                            update(id) { it.copy(progress = 0.6f + 0.37f * stage, stageProgress = stage, remainingSeconds = seconds) }
                        },
                        onStatus = { notice -> update(id) { it.copy(notice = notice) } },
                    )
                    request.compositionComplete = true
                    rememberRequest(id, request)
                    persistNow() // 先记录完整合成，再开始写目标；恢复可跳过重新编码。
                }
                }
                if (composite) {
                    val output = work.file("composite.mp4").toString()
                    update(id) { it.copy(phase = PlaybackTaskPhase.SAVING, step = "保存合成视频", progress = 0.97f, notice = null,
                        stageProgress = 0f, remainingSeconds = null, compositionDevice = null) }
                    rememberRequest(id, request)
                    persistNow()
                    val destination = destinations.single()
                    val outputSize = kotlinx.io.files.SystemFileSystem.metadataOrNull(kotlinx.io.files.Path(output))?.size ?: 0L
                    update(id) { it.copy(bytesTotal = outputSize.takeIf { size -> size > 0 }) }
                    val saveEta = RemainingTimeEstimator()
                    var lastSaveProgress = kotlin.time.TimeSource.Monotonic.markNow()
                    var written = 0L
                    readFileInChunks(kotlinx.io.files.Path(output)) {
                        destination.write(it)
                        written += it.size
                        // 大文件每块都更新会反复刷新通知和状态流；最多每 250ms 通知一次。
                        if (lastSaveProgress.elapsedNow().inWholeMilliseconds >= 250 || (outputSize > 0 && written >= outputSize)) {
                            val stage = if (outputSize > 0) written.toFloat() / outputSize else 0f
                            val seconds = saveEta.sample(written.toDouble(), (outputSize - written).toDouble())
                            update(id) { task -> task.copy(stageProgress = stage, remainingSeconds = seconds) }
                            lastSaveProgress = kotlin.time.TimeSource.Monotonic.markNow()
                        }
                    }
                    update(id) { it.copy(stageProgress = 1f, remainingSeconds = null) }
                    val saved = destination.commit()
                    if (saved != HomeworkFileSaveResult.Saved) throw SaveFailure(saved)
                    committed[0] = true
                }
            }
            rememberRequest(id, request)
            update(id) {
                it.copy(
                    phase = PlaybackTaskPhase.DONE,
                    cleanupPending = true,
                    progress = 1f,
                    step = "",
                    notice = null,
                    compositionDevice = null,
                    outputs = destinations.map { d -> d.displayName },
                    outputPaths = destinations.mapIndexed { index, d -> d.outputPath ?: it.outputPaths.getOrNull(index) },
                    message = if (it.composite) "已保存合成视频（左：${it.streamLabels[0]}，右：${it.streamLabels[1]}）。" else "已保存。",
                )
            }
        } catch (error: CancellationException) {
            resumable = stops.getAndUpdate { it - id }[id] != Stop.DISCARD
            val resumeSave = resumable && request.composite && request.compositionComplete
            val restartComposition = resumable && request.composite && !request.progressive && !resumeSave && request.finished.size == streams.size
            val restartProgressive = resumable && request.progressive && !resumeSave &&
                (compositor() as? ProgressiveVideoCompositor)?.retainsEncodedSegmentsOnInterrupt == false
            update(id) { it.copy(phase = if (resumable) PlaybackTaskPhase.PAUSED else PlaybackTaskPhase.CANCELLED,
                notice = null, bytesPerSecond = null, remainingSeconds = null, compositionDevice = null,
                progress = if (restartProgressive) 0f else it.progress,
                stageProgress = if (restartComposition || restartProgressive || resumeSave) 0f else it.stageProgress,
                bytesTotal = if (restartComposition) null else it.bytesTotal,
                step = if (resumeSave) "等待保存" else if (restartComposition || restartProgressive) "等待重新合成" else it.step,
                message = if (resumeSave) "已合成的视频和保存位置已保留；继续时重新保存。"
                    else if (resumable && request.progressive && (compositor() as? ProgressiveVideoCompositor)?.retainsEncodedSegmentsOnInterrupt == true)
                        "下载和已合成分段均已保留。继续时最多重做约 1 分钟。"
                    else if (resumable && request.progressive) "已保留下载分片和保存位置；继续时从头合成。"
                    else if (restartComposition) "两路视频和保存位置已保留；继续时从头合成。"
                    else if (resumable) "已保留下载与保存位置，点“继续处理”恢复。" else "已取消，正在清理未完成的文件。") }
            throw error
        } catch (error: Exception) {
            // 磁盘写入、合成或网络失败都不能丢弃已有分片；用户可重试或主动移除。
            resumable = true
            update(id) { it.copy(phase = PlaybackTaskPhase.FAILED, notice = null, bytesPerSecond = null,
                remainingSeconds = null, compositionDevice = null,
                message = if (error is HlsDownloadException && error.reason == HlsDownloadFailure.NETWORK && request.progressive &&
                    (compositor() as? ProgressiveVideoCompositor)?.retainsEncodedSegmentsOnInterrupt == false)
                    "连接中断，已保留下载分片；继续时从头合成。"
                    else if (error is HlsDownloadException && error.reason == HlsDownloadFailure.NETWORK) "连接中断，已保留下载和已合成分段。恢复网络后点“继续处理”。"
                    else failureMessage(error) + " 已保留处理断点，可继续处理。") }
        } finally {
            withContext(NonCancellable) {
                if (!resumable) {
                    cleanupAndUpdate(id, removeWhenDone = false)
                    jobs.update { it - id }
                } else {
                    request.destinations.forEachIndexed { index, destination ->
                        if (!request.committed[index]) runCatching { destination.closeForRecovery() }
                    }
                    jobs.update { it - id }
                    update(id) { it.copy(canResume = true) }
                }
                refreshBackground()
                runCatching { persistNow() }
            }
        }
    }

    private suspend fun runProgressive(id: String, request: Request, work: MediaWorkDirectory,
        streaming: ProgressiveVideoCompositor) = coroutineScope {
        val speed = DownloadSpeedMeter()
        val downloadEta = RemainingTimeEstimator()
        val compositionEta = RemainingTimeEstimator()
        val finalizingEta = RemainingTimeEstimator()
        val compositionSeconds = MutableStateFlow<Long?>(null)
        val finalizingSeconds = MutableStateFlow<Long?>(null)
        val transferred = MutableStateFlow(0L)
        val contentDurationSeconds = MutableStateFlow(0.0)
        var initialFraction: Float? = null
        val overlaps = streaming.encodingOverlapsDownload
        update(id) { it.copy(phase = PlaybackTaskPhase.PIPELINING, step = "准备两路下载与合成", remainingSeconds = null) }
        val ticker = launch {
            while (true) {
                delay(500)
                val sampled = speed.sample()
                val current = tasks.value.first { it.id == id }
                val remaining = current.bytesTotal?.let { (it - current.bytesDownloaded).coerceAtLeast(0).toDouble() } ?: -1.0
                val downloadSeconds = downloadEta.sample(speed.totalTransferred.toDouble(), remaining)
                val compositeSeconds = compositionSeconds.value
                val provisional = streaming.provisionalEncodeSeconds(contentDurationSeconds.value, request.quality)
                val stage = (current.stageProgress ?: 0f).toDouble()
                val allSeconds = progressiveWallClockSeconds(
                    downloadSeconds, compositeSeconds, provisional, overlaps, stage)
                update(id) { it.copy(bytesPerSecond = sampled.takeIf { _ -> it.phase == PlaybackTaskPhase.PIPELINING },
                    remainingSeconds = when (it.phase) {
                        PlaybackTaskPhase.FINALIZING -> finalizingSeconds.value
                        PlaybackTaskPhase.COMPOSITING -> compositeSeconds
                        else -> allSeconds
                    }) }
            }
        }
        try {
            ProgressiveRecordingPipeline(downloader).run(request.streams, work, request.quality, streaming, request.checkpoints,
                onDownload = { values ->
                    val total = values.sumOf { it.transferredBytes }
                    val delta = (total - transferred.getAndUpdate { maxOf(it, total) }).coerceAtLeast(0)
                    speed.add(delta)
                    val known = values.mapNotNull { p -> if (p.completedSegments > 0) p.writtenBytes.toDouble() * p.totalSegments / p.completedSegments else null }
                    val estimate = if (known.isEmpty()) null else (known.sum() * values.size / known.size).toLong()
                    val fraction = values.sumOf { it.completedSegments }.toFloat() / values.sumOf { it.totalSegments }.coerceAtLeast(1)
                    val written = values.sumOf { it.writtenBytes }
                    update(id) { it.copy(downloadProgress = maxOf(it.downloadProgress ?: 0f, fraction),
                        bytesDownloaded = maxOf(it.bytesDownloaded, written), bytesTotal = estimate ?: it.bytesTotal,
                        step = if (total == 0L && written > 0) "恢复本地分片，继续已合成部分" else "两路下载与合成同时进行",
                        notice = if (delta > 0) null else it.notice) }
                },
                onInputsComplete = { update(id) { it.copy(phase = PlaybackTaskPhase.COMPOSITING, downloadProgress = 1f,
                    bytesPerSecond = null, step = "下载已完成，继续合成") } },
                onComposition = { fraction ->
                    val start = initialFraction ?: fraction.also { initialFraction = it }
                    val seconds = compositionEta.sample((fraction - start).coerceAtLeast(0f).toDouble(), (1.0 - fraction).coerceAtLeast(0.0))
                    compositionSeconds.value = seconds
                    update(id) { it.copy(stageProgress = fraction, progress = .94f * fraction,
                        remainingSeconds = if (it.phase == PlaybackTaskPhase.COMPOSITING) seconds else it.remainingSeconds) }
                },
                onFinalizing = { fraction ->
                    val seconds = finalizingEta.sample(fraction.toDouble(), (1.0 - fraction).coerceAtLeast(0.0))
                    finalizingSeconds.value = seconds
                    update(id) { it.copy(phase = PlaybackTaskPhase.FINALIZING, stageProgress = fraction,
                        progress = .94f + .03f * fraction, bytesPerSecond = null, remainingSeconds = seconds,
                        step = "正在整理合成视频", compositionDevice = null) }
                },
                onRetry = { attempt, wait -> update(id) { it.copy(notice = "连接中断，${wait / 1000} 秒后自动重连（第 $attempt/3 次）") } },
                onStatus = { notice -> update(id) { it.copy(notice = notice) } },
                onDevice = { device -> update(id) {
                    if (it.phase == PlaybackTaskPhase.PIPELINING || it.phase == PlaybackTaskPhase.COMPOSITING)
                        it.copy(compositionDevice = device) else it
                } },
                onDuration = { contentDurationSeconds.value = it },
            )
        } finally { ticker.cancel() }
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
        val streams = request.streams.takeIf { list -> list.all { isPersistablePlaybackUrl(it.hlsUrl) } }
            ?.map { StoredPlaybackStream(it.kind.name, it.hlsUrl) } ?: emptyList()
        val saved = StoredPlaybackRequest(
            streams, request.composite,
            request.quality.name, request.destinations.map { it.recoveryToken }, request.committed.toList(),
            request.finished.mapValues { (_, result) -> StoredPlaybackStreamResult(result.first, result.second) },
            request.progressive, request.compositionComplete,
        )
        storedRequests.update { it + (id to saved) }
        recordVersion.update { it + 1 }
    }

    private suspend fun persistNow() {
        val store = taskStore ?: return
        if (!persistenceAvailable || !historyLoaded) return
        persistenceMutex.withLock {
            val requestSnapshot = storedRequests.value
            store.save(mutableTasks.value.filterNot { it.id == "history-warning" }.map { task ->
                StoredPlaybackTask(task.copy(bytesPerSecond = null, notice = null, remainingSeconds = null), requestSnapshot[task.id])
            })
        }
    }

    private suspend fun restoreTasks() {
        val store = taskStore ?: return
        val restored = mutableListOf<PlaybackTask>()
        val records = store.load()
        require(records.all { Regex("t[0-9]+-[0-9]+").matches(it.task.id) })
        require(records.map { it.task.id }.toSet().size == records.size)
        require(records.all { record ->
            (!record.task.cleanupPending || !record.task.isActive) &&
                (record.request == null || (record.request.destinations.size == record.task.outputs.size &&
                    record.request.committed.size == record.task.outputs.size))
        })
        for (saved in records) {
            val task = saved.task
            val request = saved.request
            if (request != null) storedRequests.update { it + (task.id to request) }
            if (task.cleanupPending) { restored += task.copy(canResume = false); continue }
            if (!task.isActive && !task.canResume) {
                val hasWork = kotlinx.io.files.SystemFileSystem.exists(MediaWorkDirectory.forTask(task.id, workRoot).path)
                restored += if (task.phase in setOf(PlaybackTaskPhase.DONE, PlaybackTaskPhase.CANCELLED) && (request != null || hasWork)) task.copy(cleanupPending = true,
                    message = "正在清理上次遗留的任务缓存。") else task
                continue
            }
            var recovered: Request? = null
            var ambiguousCommit = false
            if (request != null && Regex("t[0-9]+-[0-9]+").matches(task.id) &&
                request.streams.isNotEmpty() && request.streams.all { isPersistablePlaybackUrl(it.url) } &&
                request.destinations.size == task.outputs.size && request.committed.size == task.outputs.size) {
                val destinations = mutableListOf<StreamingDestination>()
                for ((index, token) in request.destinations.withIndex()) {
                    if (request.committed[index]) {
                        destinations += object : StreamingDestination {
                            override val displayName = task.outputs[index]
                            override val outputPath = task.outputPaths.getOrNull(index)
                            override val recoveryToken = token
                            override suspend fun write(bytes: ByteArray) = error("already saved")
                            override suspend fun commit() = HomeworkFileSaveResult.Saved
                            override suspend fun abort() = Unit
                        }
                    } else {
                        val result = token?.let { restoreDestination?.invoke(it, task.outputs[index]) }
                        if (result !is StreamingDestinationResult.Opened) break
                        if (task.phase == PlaybackTaskPhase.SAVING &&
                            runCatching { result.destination.isRecoveryCommitAmbiguous() }.getOrDefault(true)) {
                            ambiguousCommit = true
                            break
                        }
                        destinations += result.destination
                    }
                }
                if (destinations.size == request.destinations.size) {
                    val resumed = Request(request.streams.map { PlaybackStream(PlaybackStreamKind.valueOf(it.kind), it.url) },
                        request.composite, destinations, PlaybackCompositeQuality.valueOf(request.quality), request.progressive)
                    request.committed.forEachIndexed { index, value -> resumed.committed[index] = value }
                    request.finished.forEach { (index, value) -> resumed.finished[index] = value.bytes to value.durationSeconds }
                    resumed.compositionComplete = request.compositionComplete
                    recovered = resumed
                    requests.update { it + (task.id to resumed) }
                    rememberRequest(task.id, resumed)
                    MediaWorkDirectory.markLive(task.id, workRoot)
                }
            }
            val resumeSave = recovered != null && recovered.composite && recovered.compositionComplete &&
                (kotlinx.io.files.SystemFileSystem.metadataOrNull(MediaWorkDirectory.forTask(task.id, workRoot).file("composite.mp4"))?.size ?: 0L) > 0L
            val restartComposition = recovered != null && recovered.composite && !recovered.progressive && !resumeSave &&
                recovered.finished.size == recovered.streams.size
            val restartProgressive = recovered != null && recovered.progressive && !resumeSave &&
                (compositor() as? ProgressiveVideoCompositor)?.retainsEncodedSegmentsOnInterrupt == false
            restored += task.copy(phase = if (recovered != null) PlaybackTaskPhase.PAUSED else PlaybackTaskPhase.FAILED,
                bytesPerSecond = null, notice = null, remainingSeconds = null,
                progress = if (restartProgressive) 0f else task.progress,
                stageProgress = if (restartComposition || restartProgressive || resumeSave) 0f else task.stageProgress,
                bytesTotal = if (restartComposition) null else task.bytesTotal,
                step = if (resumeSave) "等待保存" else if (restartComposition || restartProgressive) "等待重新合成" else task.step,
                canResume = recovered != null,
                message = if (resumeSave) "已合成的视频和保存位置已恢复；继续时重新保存。"
                    else if (restartComposition) "两路视频和保存位置已恢复；继续时从头合成。"
                    else if (restartProgressive) "下载分片和保存位置已恢复；继续时从头合成。"
                    else if (recovered != null) "上次运行中断，下载记录与分片已恢复。点“继续处理”接着处理。"
                    else if (ambiguousCommit) "上次保存的结果不明确，视频可能已保存；请先检查所选文件，缓存和记录已保留。"
                    else "上次运行中断，保存位置或录像地址不可恢复；缓存和原记录已保留，可点“移除”清理。")
        }
        val known = records.mapTo(mutableSetOf()) { it.task.id }
        val hasUnrecordedWork = kotlinx.io.files.SystemFileSystem.exists(workRoot) &&
            kotlinx.io.files.SystemFileSystem.list(workRoot).any { it.name !in known }
        if (hasUnrecordedWork) restored += PlaybackTask("history-warning", "", "未关联的下载缓存", emptyList(), false,
            phase = PlaybackTaskPhase.FAILED, message = "发现没有任务记录的缓存，已保留；无法自动恢复，请检查应用回放缓存。")
        // 移动端关闭录像下载后：遗留可继续任务不得静默重跑，标失败并保留卡片供移除。
        if (!recordingDownloadsEnabled) {
            for (index in restored.indices) {
                val task = restored[index]
                if (!task.isActive && !task.canResume) continue
                requests.update { it - task.id }
                restored[index] = task.copy(
                    phase = PlaybackTaskPhase.FAILED,
                    canResume = false,
                    bytesPerSecond = null,
                    remainingSeconds = null,
                    message = MOBILE_RECORDING_DOWNLOAD_DISABLED_MESSAGE,
                )
            }
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
            remainingLabel?.let { append(" · ").append(it) }
            if (queuedCount > 1) append("（另有 ${queuedCount - 1} 个任务排队）")
        },
        progress = stageProgress ?: progress,
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
    private fun newTaskId(): String = "t${Clock.System.now().toEpochMilliseconds()}-${nextId.getAndUpdate { it + 1 }}"

    companion object {
        internal const val MOBILE_RECORDING_DOWNLOAD_DISABLED_MESSAGE =
            "本端已不再支持录像下载；请移除遗留任务，或改用 Windows / Mac。"
    }
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

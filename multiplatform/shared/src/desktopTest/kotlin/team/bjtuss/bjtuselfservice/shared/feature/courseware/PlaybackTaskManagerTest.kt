package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlin.test.assertFalse

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsSegmentCheckpoint
import team.bjtuss.bjtuselfservice.shared.media.MediaWorkDirectory
import team.bjtuss.bjtuselfservice.shared.media.PlaybackBackgroundHost
import kotlinx.io.files.Path
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStreamKind
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePickResult
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestination
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult
import team.bjtuss.bjtuselfservice.shared.media.VideoCompositor
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class PlaybackTaskManagerTest {
    @Test
    fun rebuildingAuthenticatedGraphKeepsApplicationDownloadExecutor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = MemoryGateway()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        transport.segmentGate = gate
        val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { RecordingCompositor() }, { null })
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql.Schema.create(driver).value
        val cache = team.bjtuss.bjtuselfservice.shared.cache.CacheStore(driver)
        val recoveryGateway = object : HomeworkFileGateway by gateway, PlaybackRecoveryProvider {
            override val playbackRecovery = object : PlaybackRecoveryAccess {
                override fun retainedManager(accountScope: String): PlaybackTaskManager {
                    assertEquals("fixture-student", accountScope)
                    return manager
                }
                override fun storeFor(accountScope: String): PlaybackTaskStore = error("不能重建任务存储")
                override suspend fun restoreDestination(token: String, displayName: String): StreamingDestinationResult = error("不能重建保存目标")
            }
        }
        val preferences = object : team.bjtuss.bjtuselfservice.shared.security.AccountPreferences {
            override suspend fun shouldRememberCredentials() = false
            override suspend fun setShouldRememberCredentials(enabled: Boolean) = Unit
            override suspend fun clearRememberCredentialsSetting() = Unit
        }
        fun graph() = team.bjtuss.bjtuselfservice.shared.sync.AppDataGraph(
            team.bjtuss.bjtuselfservice.shared.auth.StudentProfile("测试", "fixture-student", "学生", "测试"),
            transport, team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol(transport), cache,
            team.bjtuss.bjtuselfservice.shared.PlatformFamily.Android,
            team.bjtuss.bjtuselfservice.shared.cache.AppPreferences(), { true },
            team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator(
                team.bjtuss.bjtuselfservice.shared.security.AccountSecurityStore(null, preferences)),
            team.bjtuss.bjtuselfservice.shared.auth.UnavailableCaptchaRecognizer, { null }, recoveryGateway, {}, Dispatchers.Default,
        )
        try {
            val detail = detail()
            val id = assertIs<PlaybackEnqueueResult.Started>(manager.enqueue(detail, detail.streams, "测试课程", true, gateway)).taskId
            withTimeout(5000) { while (manager.tasks.value.single().phase != PlaybackTaskPhase.DOWNLOADING) delay(10) }
            val first = graph()
            kotlin.test.assertSame(manager, first.playbackTaskManager)
            first.close()
            val second = graph()
            kotlin.test.assertSame(manager, second.playbackTaskManager)
            second.close()
            assertTrue(manager.tasks.value.single().isActive)
            assertTrue(gateway.aborted.isEmpty())
            gate.complete(Unit)
            assertEquals(PlaybackTaskPhase.DONE, manager.awaitFinished(id).phase)
        } finally { gate.complete(Unit); scope.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join(); cache.close() }
    }

    @Test
    fun progressiveTaskStartsEncodingBeforeBothInputsFinishAndRetainsFailureCheckpoints() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = MemoryGateway()
        val compositor = RecordingProgressive().apply { failEncoding = 1 }
        try {
            val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { compositor }, { null })
            val detail = detail()
            val id = assertIs<PlaybackEnqueueResult.Started>(manager.enqueue(detail, detail.streams, "测试课程", true, gateway)).taskId
            withTimeout(5000) { while (!manager.tasks.value.single().canResume) delay(10) }
            assertTrue(compositor.encodedBeforeDownloadCompleted)
            assertEquals(PlaybackTaskPhase.FAILED, manager.tasks.value.single().phase)
            assertTrue(gateway.aborted.isEmpty(), "编码中断不能删除已选保存目标")
            val first = transport.requested.count { it == "${base}tea/seg-0.ts" }
            manager.retry(id)
            assertEquals(PlaybackTaskPhase.DONE, manager.awaitFinished(id).phase)
            assertEquals(first, transport.requested.count { it == "${base}tea/seg-0.ts" }, "恢复流水线不重下已缓存的分片")
            assertEquals("progressive", gateway.saved.values.single().decodeToString())
        } finally { scope.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
    }

    @Test
    fun saveFailureReusesCompletedCompositionAndRewindsUncommittedDestination() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = MemoryGateway().apply { failCommits = 1 }
        val compositor = RecordingProgressive()
        try {
            val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { compositor }, { null })
            val detail = detail()
            val id = assertIs<PlaybackEnqueueResult.Started>(manager.enqueue(detail, detail.streams, "测试课程", true, gateway)).taskId
            withTimeout(5000) { while (!manager.tasks.value.single().canResume) delay(10) }
            assertEquals(1, compositor.encodedRuns)
            val downloads = transport.requested.count { it.endsWith(".ts") }
            manager.retry(id)
            assertEquals(PlaybackTaskPhase.DONE, manager.awaitFinished(id).phase)
            assertEquals(1, compositor.encodedRuns, "保存失败不能从头重编码")
            assertEquals(downloads, transport.requested.count { it.endsWith(".ts") })
            assertEquals("progressive", gateway.saved.values.single().decodeToString(), "重试保存不能把旧半成品追加两遍")
        } finally { scope.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
    }

    private class RecordingProgressive : team.bjtuss.bjtuselfservice.shared.media.ProgressiveVideoCompositor {
        var finalizingGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var failEncoding = 0
        var encodedRuns = 0
        var encodedBeforeDownloadCompleted = false
        private var completed = false
        override val isAvailable = true
        override suspend fun composeSideBySide(leftPath: String, rightPath: String, outputPath: String,
            quality: PlaybackCompositeQuality, onProgress: (Float) -> Unit, onStatus: (String?) -> Unit): Unit = error("必须走渐进管线")
        override suspend fun openProgressive(work: MediaWorkDirectory, durationSeconds: Double,
            quality: PlaybackCompositeQuality): team.bjtuss.bjtuselfservice.shared.media.ProgressiveComposition =
            object : team.bjtuss.bjtuselfservice.shared.media.ProgressiveComposition {
                val firstInput = kotlinx.coroutines.CompletableDeferred<Unit>()
                val encoding = kotlinx.coroutines.CompletableDeferred<Unit>()
                val inputsDone = kotlinx.coroutines.CompletableDeferred<Unit>()
                val finished = java.util.concurrent.atomic.AtomicInteger(0)
                override val resumeSeconds = 0.0
                override val isComplete get() = completed
                override suspend fun append(index: Int, bytes: ByteArray) {
                    if (firstInput.complete(Unit)) encoding.await()
                }
                override suspend fun finishInput(index: Int) { if (finished.incrementAndGet() == 2) inputsDone.complete(Unit) }
                override suspend fun compose(onProgress: (Float) -> Unit, onStatus: (String?) -> Unit,
                    onFinalizing: (Float) -> Unit) {
                    if (!completed) {
                        firstInput.await()
                        encodedBeforeDownloadCompleted = finished.get() < 2
                        encodedRuns++
                        onProgress(.1f)
                        encoding.complete(Unit)
                        if (failEncoding > 0) { failEncoding--; throw team.bjtuss.bjtuselfservice.shared.media.CompositionException("测试编码中断") }
                        inputsDone.await()
                        completed = true
                    }
                    onProgress(1f)
                    onFinalizing(.5f)
                    finalizingGate?.await()
                    File(work.file("composite.mp4").toString()).writeText("progressive")
                    onFinalizing(1f)
                }
                override suspend fun close() = Unit
            }
    }
    @Test
    fun finalizationShowsSeparateProgressAndCanResumeWithoutEncodingAgain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val compositor = RecordingProgressive().apply { finalizingGate = gate }
        try {
            val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { compositor }, { null })
            val detail = detail()
            val id = assertIs<PlaybackEnqueueResult.Started>(manager.enqueue(detail, detail.streams, "测试课程", true, MemoryGateway())).taskId
            withTimeout(5000) { while (manager.tasks.value.single().phase != PlaybackTaskPhase.FINALIZING) delay(10) }
            val task = manager.tasks.value.single()
            assertEquals(.5f, task.stageProgress)
            assertTrue(task.isActive && task.canPause)
            assertTrue(task.remainingLabel.orEmpty().startsWith("整理剩余"))
            manager.pause(id)
            withTimeout(5000) { while (!manager.tasks.value.single().canResume) delay(10) }
            assertEquals(PlaybackTaskPhase.PAUSED, manager.tasks.value.single().phase)
            gate.complete(Unit)
            manager.retry(id)
            assertEquals(PlaybackTaskPhase.DONE, manager.awaitFinished(id).phase)
            assertEquals(1, compositor.encodedRuns)
        } finally { gate.complete(Unit); scope.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
    }

    @Test
    fun closingWhileHistoryIsLoadingNeverOverwritesItWithEmptyRecords() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loading = kotlinx.coroutines.CompletableDeferred<Unit>()
        var saves = 0
        val directory = java.nio.file.Files.createTempDirectory("playback-load-cancel").toFile()
        val store = object : PlaybackTaskStore {
            override val workRoot = Path(directory.absolutePath)
            override suspend fun load(): List<StoredPlaybackTask> {
                loading.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun save(tasks: List<StoredPlaybackTask>) { saves++ }
        }
        try {
            PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { RecordingCompositor() }, { null }, store)
            loading.await()
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            assertEquals(0, saves)
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test
    fun logoutDiscardsPausedTaskAndWaitsForDestinationCleanup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = MemoryGateway()
        transport.segmentDelayMillis = 1000
        try {
            val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { RecordingCompositor() }, { null })
            val detail = detail()
            val id = assertIs<PlaybackEnqueueResult.Started>(manager.enqueue(detail, detail.streams, "测试课程", true, gateway)).taskId
            manager.pause(id) // 入队后立刻暂停也要经过收尾。
            withTimeout(5000) { while (!manager.tasks.value.single().canResume) delay(10) }
            assertTrue(gateway.aborted.isEmpty())
            manager.cancelAllAndJoin()
            assertEquals(PlaybackTaskPhase.CANCELLED, manager.tasks.value.single().phase)
            assertFalse(manager.tasks.value.single().canResume)
            assertEquals(1, gateway.aborted.size)
        } finally { scope.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
    }
    @Test
    fun pausingKeepsDestinationAndResumesInsteadOfCancelling() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = MemoryGateway()
        transport.segmentDelayMillis = 500
        try {
            val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0, concurrency = 1), { RecordingCompositor() }, { null })
            val detail = detail()
            val id = assertIs<PlaybackEnqueueResult.Started>(manager.enqueue(detail, detail.streams, "测试课程", true, gateway)).taskId
            withTimeout(5000) { while (manager.tasks.value.first().bytesDownloaded == 0L) delay(10) }
            manager.pause(id)
            withTimeout(5000) { while (!manager.tasks.value.first().canResume) delay(10) }
            assertEquals(PlaybackTaskPhase.PAUSED, manager.tasks.value.first().phase)
            assertTrue(gateway.aborted.isEmpty())
            val first = transport.requested.count { it == "${base}tea/seg-0.ts" }
            manager.clearFinished()
            assertEquals(1, manager.tasks.value.size)
            transport.segmentDelayMillis = 0
            manager.retry(id)
            assertEquals(PlaybackTaskPhase.DONE, manager.awaitFinished(id).phase)
            assertEquals(first, transport.requested.count { it == "${base}tea/seg-0.ts" })
        } finally { scope.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
    }

    @Test
    fun disposingOwnerPreservesInterruptedDownloadInsteadOfDeletingIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = MemoryGateway()
        transport.segmentDelayMillis = 2000
        val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { RecordingCompositor() }, { null })
        val detail = detail()
        manager.enqueue(detail, detail.streams, "测试课程", true, gateway)
        delay(150)
        scope.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        assertEquals(PlaybackTaskPhase.PAUSED, manager.tasks.value.single().phase)
        assertTrue(manager.tasks.value.single().canResume)
        assertTrue(gateway.aborted.isEmpty(), "宿主销毁不能触发删除用户保存目标")
    }
    private val base = "https://lmt6.bjtu.edu.cn:8880/vod/a/"
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/media/$name")).use { it.readBytes() }

    private val transport = object : SchoolHttpTransport {
        val bodies = mutableMapOf<String, ByteArray>()
        var segmentDelayMillis = 0L
        var segmentGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            requested += request.url
            if (request.url.endsWith(".ts")) { segmentGate?.await(); delay(segmentDelayMillis) }
            val body = bodies[request.url]
            return SchoolHttpResponse(if (body == null) 404 else 200, request.url, body = body ?: ByteArray(0))
        }
        override suspend fun executeWithoutRedirects(request: SchoolHttpRequest) = execute(request)
        override fun clearSession() = Unit
    }

    init {
        for (stream in listOf("tea", "vga")) {
            transport.bodies["${base}$stream/playlist.m3u8"] =
                "#EXTM3U\n#EXTINF:2,\nseg-0.ts\n#EXTINF:2,\nseg-1.ts\n#EXT-X-ENDLIST\n".encodeToByteArray()
            transport.bodies["${base}$stream/seg-0.ts"] = fixture("synthetic-0.ts")
            transport.bodies["${base}$stream/seg-1.ts"] = fixture("synthetic-1.ts")
        }
    }

    private class MemoryGateway : HomeworkFileGateway {
        var failCommits = 0
        val saved = mutableMapOf<String, ByteArray>()
        val aborted = mutableListOf<String>()
        override val isAvailable = true
        override val isStreamingSaveAvailable = true
        override suspend fun pickFiles() = HomeworkFilePickResult.Cancelled
        override suspend fun saveFile(file: HomeworkFileContent) = HomeworkFileSaveResult.Cancelled
        override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult =
            StreamingDestinationResult.Opened(object : StreamingDestination {
                val data = java.io.ByteArrayOutputStream()
                override val displayName = fileName
                override suspend fun write(bytes: ByteArray) = data.write(bytes)
                override suspend fun commit(): HomeworkFileSaveResult {
                    if (failCommits > 0) { failCommits--; return HomeworkFileSaveResult.Failed(team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGatewayFailure.IO) }
                    saved[fileName] = data.toByteArray(); return HomeworkFileSaveResult.Saved
                }
                override suspend fun closeForRecovery() { data.reset() }
                override suspend fun abort() { aborted += fileName }
            })
    }

    private class RecordingCompositor : VideoCompositor {
        var inputs: List<String> = emptyList()
        var quality: PlaybackCompositeQuality? = null
        override val isAvailable = true
        override suspend fun composeSideBySide(
            leftPath: String, rightPath: String, outputPath: String, quality: PlaybackCompositeQuality,
            onProgress: (Float) -> Unit, onStatus: (String?) -> Unit,
        ) {
            inputs = listOf(File(leftPath).readBytes().copyOfRange(4, 8).decodeToString(), File(rightPath).readBytes().copyOfRange(4, 8).decodeToString())
            this.quality = quality
            onProgress(0.5f)
            File(outputPath).writeText("composite ${quality.width} x ${quality.height} @${quality.bitRate}")
            onProgress(1f)
        }
    }

    private fun detail(): PlaybackDetail {
        val lesson = PlaybackLesson(1, 17, "", "2026-10-20 16:20-18:10", "YF207", "", true)
        val streams = listOf(
            PlaybackStream(PlaybackStreamKind.SCREEN, "${base}vga/playlist.m3u8"),
            PlaybackStream(PlaybackStreamKind.TEACHER, "${base}tea/playlist.m3u8"),
        )
        return PlaybackDetail(lesson, true, streams, listOf(PlaybackStreamKind.TEACHER, PlaybackStreamKind.SCREEN), "")
    }

    private suspend fun PlaybackTaskManager.awaitFinished(id: String): PlaybackTask = withTimeout(20_000) {
        while (true) {
            val task = tasks.value.first { it.id == id }
            if (!task.isActive) return@withTimeout task
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test
    fun unchangedActiveProgressNeverStopsBackgroundHost() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val host = object : PlaybackBackgroundHost {
            override fun update(active: Boolean, state: PlaybackBackgroundState?) { calls += active }
        }
        transport.segmentDelayMillis = 4000
        try {
            val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { RecordingCompositor() }, { host })
            val detail = detail()
            manager.enqueue(detail, detail.streams, "测试课程", true, MemoryGateway())
            delay(1800) // 跨过几次相同的速度/进度采样。
            assertTrue(calls.isNotEmpty())
            assertTrue(calls.all { it }, "有任务时，相同状态不能通知宿主停止")
        } finally { scope.cancel() }
    }

    @Test
    fun persistedPartialDownloadRestoresInNewManagerAndKeepsFinishedHistory() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("playback-recovery-test").toFile()
        val root = Path(File(directory, "work").absolutePath)
        var records = emptyList<StoredPlaybackTask>()
        val store = object : PlaybackTaskStore {
            override val workRoot = root
            override suspend fun load() = decodePlaybackTaskRecords(encodePlaybackTaskRecords(records))
            override suspend fun save(tasks: List<StoredPlaybackTask>) { records = decodePlaybackTaskRecords(encodePlaybackTaskRecords(tasks)) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val detail = detail()
            val id = "t123456789-0"
            val work = MediaWorkDirectory.forTask(id, root)
            val urls = listOf("${base}tea/seg-0.ts", "${base}tea/seg-1.ts")
            HlsSegmentCheckpoint(MediaWorkDirectory(work.file("segments-0"))).apply {
                prepare(urls)
                append(0, fixture("synthetic-0.ts"))
            }
            // 模拟被杀时写了一半的下一片：不能当作完整断点读回。
            File(work.file("segments-0").toString(), "1.part").writeText("partial")
            val output = "recovered.mp4"
            records = listOf(StoredPlaybackTask(
                PlaybackTask(id, "测试课程", "测试录像", listOf("教师画面", "屏幕/课件"), true,
                    phase = PlaybackTaskPhase.DOWNLOADING, outputs = listOf(output)),
                StoredPlaybackRequest(detail.streams.sortedBy { it.kind.ordinal }.map { StoredPlaybackStream(it.kind.name, it.hlsUrl) },
                    true, "HIGH", listOf("test:$output"), listOf(false))))
            val gateway = MemoryGateway()
            val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0, concurrency = 1),
                { RecordingCompositor() }, { null }, store,
                restoreDestination = { _, name -> gateway.openStreamingDestination(name, "video/mp4") })
            withTimeout(5000) { while (manager.tasks.value.isEmpty()) delay(10) }
            assertTrue(manager.tasks.value.single().canResume)
            manager.clearFinished()
            assertEquals(1, manager.tasks.value.size, "清除已结束不能删除可继续的断点")
            manager.retry(id)
            assertEquals(PlaybackTaskPhase.DONE, manager.awaitFinished(id).phase)
            withTimeout(5000) { while (records.single().task.phase != PlaybackTaskPhase.DONE) delay(10) }
            assertEquals(0, transport.requested.count { it == urls[0] }, "新对象从磁盘恢复，不重下第一片")
            assertEquals(1, transport.requested.count { it == urls[1] }, "半片必须重新下载")
            assertEquals(1, gateway.saved.size)
            assertEquals(PlaybackTaskPhase.DONE, store.load().single().task.phase)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            directory.deleteRecursively()
        }
    }

    @Test
    fun networkFailureCanResumeCachedSegmentsAndThenSaveAValidMp4() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gateway = MemoryGateway()
            val missing = "${base}tea/seg-1.ts"
            val saved = transport.bodies.remove(missing)!!
            val downloader = HlsRecordingDownloader(transport, retryDelayMillis = 0, concurrency = 1)
            val manager = PlaybackTaskManager(scope, downloader, { RecordingCompositor() }, { null })
            val detail = detail()
            val id = assertIs<PlaybackEnqueueResult.Started>(manager.enqueue(
                detail, detail.streams, "测试课程", composite = true, gateway = gateway,
            )).taskId
            withTimeout(5000) { while (manager.tasks.value.first { it.id == id }.canResume != true) delay(10) }
            assertEquals(PlaybackTaskPhase.FAILED, manager.tasks.value.first().phase)
            assertTrue(gateway.aborted.isEmpty(), "可继续的失败不能删除保存目标")
            val completedUrl = "${base}tea/seg-0.ts"
            assertEquals(1, transport.requested.count { it == completedUrl })
            transport.bodies[missing] = saved
            manager.retry(id)
            assertEquals(PlaybackTaskPhase.DONE, manager.awaitFinished(id).phase)
            assertEquals(1, transport.requested.count { it == completedUrl }, "已经下载的片不能再请求")
            assertEquals(1, gateway.saved.size)
        } finally { scope.cancel() }
    }

    @Test
    fun resumingSecondStreamDoesNotRedownloadOrSaveTheFirstStreamTwice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gateway = MemoryGateway()
            val missing = "${base}vga/seg-1.ts"
            val saved = transport.bodies.remove(missing)!!
            val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0, concurrency = 1), { RecordingCompositor() }, { null })
            val detail = detail()
            val id = assertIs<PlaybackEnqueueResult.Started>(manager.enqueue(detail, detail.streams, "测试课程", false, gateway)).taskId
            withTimeout(5000) { while (!manager.tasks.value.first().canResume) delay(10) }
            assertEquals(1, gateway.saved.size)
            transport.bodies[missing] = saved
            manager.retry(id)
            assertEquals(PlaybackTaskPhase.DONE, manager.awaitFinished(id).phase)
            assertEquals(2, gateway.saved.size)
            assertEquals(1, transport.requested.count { it == "${base}tea/seg-0.ts" })
            assertEquals(1, transport.requested.count { it == "${base}tea/seg-1.ts" })
        } finally { scope.cancel() }
    }

    @Test
    fun dismissingResumableFailureAbortsThePendingDestination() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gateway = MemoryGateway()
            transport.bodies.remove("${base}tea/seg-1.ts")
            val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0, concurrency = 1), { RecordingCompositor() }, { null })
            val detail = detail()
            val id = assertIs<PlaybackEnqueueResult.Started>(manager.enqueue(detail, detail.streams, "测试课程", true, gateway)).taskId
            withTimeout(5000) { while (!manager.tasks.value.first().canResume) delay(10) }
            manager.dismiss(id)
            withTimeout(5000) { while (gateway.aborted.isEmpty()) delay(10) }
            assertEquals(1, gateway.aborted.size)
            assertTrue(manager.tasks.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test
    fun compositeTaskRemuxesBothStreamsTeacherLeftAndSavesOneFile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val compositor = RecordingCompositor()
        val gateway = MemoryGateway()
        val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { compositor }, { null })
        val detail = detail()
        val result = manager.enqueue(detail, detail.streams, "程序设计", composite = true, gateway = gateway)
        val task = manager.awaitFinished(assertIs<PlaybackEnqueueResult.Started>(result).taskId)
        assertEquals(PlaybackTaskPhase.DONE, task.phase, task.message)
        assertEquals(listOf("教师画面", "屏幕/课件"), task.streamLabels)
        assertEquals(listOf("ftyp", "ftyp"), compositor.inputs, "合成器拿到的是转好的 MP4")
        val (name, bytes) = gateway.saved.entries.single()
        assertTrue(name.endsWith("教师+课件.mp4"), name)
        assertEquals("composite 1920 x 540 @2500000", bytes.decodeToString())
        scope.cancel()
    }

    @Test
    fun compositeTaskUsesTheRequestedQuality() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val compositor = RecordingCompositor()
        val gateway = MemoryGateway()
        val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { compositor }, { null })
        val detail = detail()
        val result = manager.enqueue(
            detail, detail.streams, "程序设计",
            composite = true, gateway = gateway, quality = PlaybackCompositeQuality.LOW,
        )
        val task = manager.awaitFinished(assertIs<PlaybackEnqueueResult.Started>(result).taskId)
        assertEquals(PlaybackTaskPhase.DONE, task.phase, task.message)
        assertEquals(PlaybackCompositeQuality.LOW, compositor.quality)
        assertEquals("composite 960 x 270 @600000", gateway.saved.values.single().decodeToString())
        scope.cancel()
    }

    @Test
    fun separateTaskSavesEachStreamAsMp4WhenCompositeIsOff() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = MemoryGateway()
        val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { RecordingCompositor() }, { null })
        val detail = detail()
        val result = manager.enqueue(detail, detail.streams, "程序设计", composite = false, gateway = gateway)
        val task = manager.awaitFinished(assertIs<PlaybackEnqueueResult.Started>(result).taskId)
        assertEquals(PlaybackTaskPhase.DONE, task.phase, task.message)
        assertEquals(2, gateway.saved.size)
        gateway.saved.forEach { (name, bytes) ->
            assertTrue(name.endsWith(".mp4"))
            assertEquals("ftyp", bytes.copyOfRange(4, 8).decodeToString())
        }
        scope.cancel()
    }

    @Test
    fun cancellingAbortsDestinationsAndMarksTaskCancelled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = MemoryGateway()
        transport.segmentDelayMillis = 2_000
        val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(transport, 0), { RecordingCompositor() }, { null })
        val detail = detail()
        val id = assertIs<PlaybackEnqueueResult.Started>(
            manager.enqueue(detail, detail.streams, "程序设计", composite = true, gateway = gateway),
        ).taskId
        delay(300)
        manager.cancel(id)
        val task = manager.awaitFinished(id)
        assertEquals(PlaybackTaskPhase.CANCELLED, task.phase)
        assertTrue(gateway.saved.isEmpty())
        // 任务先标记为已取消，收尾（删除未完成的保存目标）随后才跑完，等它真的发生。
        withTimeout(5_000) {
            while (gateway.aborted.isEmpty()) delay(20)
        }
        assertEquals(1, gateway.aborted.size)
        transport.segmentDelayMillis = 0
        scope.cancel()
    }
}

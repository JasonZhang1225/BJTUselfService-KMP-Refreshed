package team.bjtuss.bjtuselfservice.windows

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTask
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTaskPhase
import team.bjtuss.bjtuselfservice.shared.feature.courseware.StoredPlaybackTask
import team.bjtuss.bjtuselfservice.shared.feature.courseware.StoredPlaybackRequest
import team.bjtuss.bjtuselfservice.shared.feature.courseware.StoredPlaybackStream
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTaskManager
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTaskStore
import team.bjtuss.bjtuselfservice.shared.media.UnavailableVideoCompositor
import team.bjtuss.bjtuselfservice.shared.media.MediaWorkDirectory
import kotlinx.io.files.Path
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult

class WindowsPlaybackRecoveryTest {
    @Test fun completedPathRemainsAfterCacheCleanupAndManagerRestart() = runBlocking {
        withDirectory { directory ->
            val target = File(directory, "课程 & 视频.mp4")
            val recovery = WindowsPlaybackRecovery(File(directory, "state"))
            val store = recovery.storeFor("path-test")
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val gateway = object : team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway {
                override val isAvailable = true
                override val isStreamingSaveAvailable = true
                override suspend fun pickFiles() = team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePickResult.Cancelled
                override suspend fun saveFile(file: team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent) = HomeworkFileSaveResult.Cancelled
                override suspend fun openStreamingDestination(fileName: String, contentType: String) =
                    StreamingDestinationResult.Opened(prepareFileStreamingDestination(target))
            }
            val streams = listOf("TEACHER", "SCREEN").map { kind ->
                team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream(
                    team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStreamKind.valueOf(kind),
                    "https://lmt6.bjtu.edu.cn:8880/vod/a/${kind.lowercase()}/playlist.m3u8")
            }
            val detail = team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail(
                team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson(1, 1, "", "课节", "", "", true),
                true, streams, streams.map { it.kind }, "")
            try {
                val manager = PlaybackTaskManager(scope, HlsRecordingDownloader(PlaybackCrashFixture.transport, 0),
                    { PlaybackCrashFixture.compositor }, { null }, store, restoreDestination = recovery::restoreDestination)
                assertIs<team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackEnqueueResult.Started>(
                    manager.enqueue(detail, streams, "课程", true, gateway))
                withTimeout(10_000) {
                    while (store.load().singleOrNull()?.let { it.task.phase == PlaybackTaskPhase.DONE && it.request == null } != true) delay(20)
                }
                assertEquals(listOf(target.absolutePath), store.load().single().task.outputPaths)
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            }
            val restartedScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            try {
                val restarted = manager(restartedScope, store, recovery)
                withTimeout(5_000) { while (restarted.tasks.value.isEmpty()) delay(10) }
                assertEquals(PlaybackTaskPhase.DONE, restarted.tasks.value.single().phase)
                assertEquals(listOf(target.absolutePath), restarted.tasks.value.single().outputPaths)
                assertEquals("合成结果", target.readText())
            } finally {
                restartedScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                restartedScope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            }
        }
    }

    private val noNetwork = object : SchoolHttpTransport {
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = error("恢复阶段不能访问网络")
        override fun clearSession() = Unit
    }

    private fun manager(scope: kotlinx.coroutines.CoroutineScope, store: PlaybackTaskStore, recovery: WindowsPlaybackRecovery) =
        PlaybackTaskManager(scope, HlsRecordingDownloader(noNetwork, 0), { UnavailableVideoCompositor }, { null },
            store, restoreDestination = recovery::restoreDestination)

    private fun interrupted(taskId: String, destination: FileStreamingDestination, phase: PlaybackTaskPhase,
        cleanupPending: Boolean = false): StoredPlaybackTask = StoredPlaybackTask(
        PlaybackTask(taskId, "测试", "测试课节", listOf("教师"), false, phase = phase,
            outputs = listOf(destination.displayName), cleanupPending = cleanupPending),
        StoredPlaybackRequest(listOf(StoredPlaybackStream("TEACHER", "https://lmt2.bjtu.edu.cn:8880/test/playlist.m3u8")),
            false, "HIGH", listOf(destination.recoveryToken), listOf(false)))

    private suspend fun withDirectory(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("bjtu-playback-test-").toFile().canonicalFile
        try { block(directory) } finally {
            val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
            check(directory.parentFile == tempRoot && directory.name.startsWith("bjtu-playback-test-"))
            directory.deleteRecursively()
        }
    }

    @Test fun destinationIsRecordedBeforeAnyTemporaryFileIsCreated() = runBlocking {
        withDirectory { directory ->
            val target = File(directory, "output.mp4").apply { writeText("existing video") }
            val destination = prepareFileStreamingDestination(target)
            assertTrue(!Files.exists(destination.temporary), "选择保存位置后不应立刻创建临时文件")
            val restored = assertIs<StreamingDestinationResult.Opened>(
                WindowsPlaybackRecovery(directory).restoreDestination(destination.recoveryToken, target.name)).destination
            restored.write("new video".toByteArray())
            assertTrue(Files.exists(destination.temporary))
            assertEquals("existing video", target.readText())
            restored.abort()
            assertTrue(!Files.exists(destination.temporary))
            assertEquals("existing video", target.readText(), "取消不得删除已存在的视频")
        }
    }

    @Test fun abortAfterCommitCannotDeleteAFileCreatedAtTheOldTemporaryPath() = runBlocking {
        withDirectory { directory ->
            val target = File(directory, "output.mp4")
            val destination = prepareFileStreamingDestination(target)
            destination.write("保存的视频".toByteArray())
            assertEquals(HomeworkFileSaveResult.Saved, destination.commit())
            Files.writeString(destination.temporary, "之后创建的其他文件")
            destination.abort()
            assertEquals("保存的视频", target.readText())
            assertEquals("之后创建的其他文件", Files.readString(destination.temporary))
        }
    }

    @Test fun unopenedDestinationDoesNotDeleteAnUnexpectedFileAtItsReservedName() = runBlocking {
        withDirectory { directory ->
            val target = File(directory, "output.mp4")
            val destination = prepareFileStreamingDestination(target)
            Files.writeString(destination.temporary, "belongs to someone else")
            destination.abort()
            assertEquals("belongs to someone else", Files.readString(destination.temporary))
        }
    }

    @Test fun abortReportsAnUndeletableOwnedTemporary() = runBlocking {
        withDirectory { directory ->
            val target = File(directory, "output.mp4")
            val temporary = File(directory, ".bjtu-save-owned.tmp").apply { mkdir(); resolve("locked").writeText("x") }
            val destination = FileStreamingDestination(target, temporary.toPath())
            assertFailsWith<java.io.IOException> { destination.abortForCleanup() }
            assertTrue(temporary.isDirectory, "删除失败必须保留文件并告知调用者")
        }
    }

    @Test fun workCleanupDoesNotFollowWindowsJunctionIntoAnotherDirectory() = runBlocking {
        withDirectory { directory ->
            val work = File(directory, "work").apply { mkdir() }
            val outside = File(directory, "outside").apply { mkdir() }
            val video = File(outside, "user-video.mp4").apply { writeText("用户视频") }
            val junction = File(work, "linked-directory")
            val command = ProcessBuilder(System.getenv("ComSpec") ?: "cmd.exe", "/c", "mklink", "/J",
                junction.absolutePath, outside.absolutePath).redirectErrorStream(true).start()
            val output = command.inputStream.bufferedReader().readText()
            assertEquals(0, command.waitFor(), "创建隔离 junction 失败：$output")
            MediaWorkDirectory(Path(work.absolutePath)).deleteAll()
            assertTrue(!work.exists())
            assertEquals("用户视频", video.readText(), "清理不能沿 junction 删除目标目录里的视频")
        }
    }

    @Test fun interruptedCommitWithMissingTemporaryPreservesExistingVideoAndCachedData() = runBlocking {
        withDirectory { directory ->
            val recovery = WindowsPlaybackRecovery(File(directory, "state"))
            val store = recovery.storeFor("commit-test")
            val target = File(directory, "saved.mp4").apply { writeText("已保存的视频") }
            val orphan = File(directory, ".bjtu-save-unrelated.tmp").apply { writeText("其他文件") }
            val destination = prepareFileStreamingDestination(target)
            val id = "t12345-0"
            val cache = File(store.workRoot.toString(), "$id/segments-0/0.ts").apply { parentFile.mkdirs(); writeText("cached") }
            store.save(listOf(interrupted(id, destination, PlaybackTaskPhase.SAVING)))
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            try {
                val manager = manager(scope, store, recovery)
                withTimeout(5000) { while (manager.tasks.value.isEmpty()) delay(10) }
                assertEquals(PlaybackTaskPhase.FAILED, manager.tasks.value.single().phase)
                assertTrue(!manager.tasks.value.single().canResume)
                assertTrue(manager.tasks.value.single().message!!.contains("可能已保存"))
                assertTrue(cache.isFile, "保存结果不明确时必须保留断点")
                withTimeout(5000) { while (store.load().single().task.phase != PlaybackTaskPhase.FAILED) delay(100) }
                assertTrue(store.load().single().request != null, "不能抹掉清理所需的所有权记录")
                manager.dismiss(id)
                withTimeout(5000) { while (manager.tasks.value.isNotEmpty()) delay(10) }
                assertEquals("已保存的视频", target.readText())
                assertEquals("其他文件", orphan.readText(), "不能扫描或删除其他相似文件")
                assertTrue(!cache.exists())
            } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
    }

    @Test fun inaccessibleDestinationKeepsCachedDataAndRecordUntilCleanupCanBeRetried() = runBlocking {
        withDirectory { directory ->
            val recovery = WindowsPlaybackRecovery(File(directory, "state"))
            val store = recovery.storeFor("unavailable-test")
            val outputDirectory = File(directory, "output").apply { mkdir() }
            val target = File(outputDirectory, "saved.mp4").apply { writeText("原视频") }
            val temporary = Files.createTempFile(outputDirectory.toPath(), ".bjtu-save-", ".tmp")
            Files.writeString(temporary, "未完成")
            val destination = FileStreamingDestination(target, temporary)
            val id = "t23456-0"
            val cache = File(store.workRoot.toString(), "$id/segments-0/0.ts").apply { parentFile.mkdirs(); writeText("cached") }
            store.save(listOf(interrupted(id, destination, PlaybackTaskPhase.DOWNLOADING)))
            val offline = File(directory, "offline")
            assertTrue(outputDirectory.renameTo(offline))
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            try {
                val manager = manager(scope, store, recovery)
                withTimeout(5000) { while (manager.tasks.value.isEmpty()) delay(10) }
                assertEquals(PlaybackTaskPhase.FAILED, manager.tasks.value.single().phase)
                assertTrue(cache.isFile, "目标不可用不能删除缓存")
                withTimeout(5000) { while (store.load().single().task.phase != PlaybackTaskPhase.FAILED) delay(100) }
                assertTrue(store.load().single().request != null)
                manager.dismiss(id)
                withTimeout(5000) { while (manager.tasks.value.single().message?.contains("未能处理") != true) delay(10) }
                assertTrue(store.load().single().request != null, "目标不可达时不能丢弃清理记录")
                assertTrue(File(offline, temporary.fileName.toString()).isFile)
                assertTrue(offline.renameTo(outputDirectory))
                withTimeout(5000) { while (manager.tasks.value.isNotEmpty()) { manager.dismiss(id); delay(10) } }
                assertTrue(!Files.exists(temporary))
                assertTrue(!cache.exists())
                assertEquals("原视频", target.readText())
            } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
    }

    @Test fun restartRetriesRecordedCleanupWithoutTouchingUnrelatedTemporaryFiles() = runBlocking {
        withDirectory { directory ->
            val recovery = WindowsPlaybackRecovery(File(directory, "state"))
            val store = recovery.storeFor("cleanup-test")
            val target = File(directory, "saved.mp4").apply { writeText("用户视频") }
            val temporary = Files.createTempFile(directory.toPath(), ".bjtu-save-", ".tmp")
            Files.writeString(temporary, "未完成")
            val unrelated = File(directory, ".bjtu-save-unrelated.tmp").apply { writeText("其他数据") }
            val id = "t34567-0"
            val taskWork = File(store.workRoot.toString(), id).apply { mkdirs() }
            val cache = File(taskWork, "composite.partial.mp4").apply { writeText("部分视频") }
            listOf("segments-0/0.ts", "segments-0/1.part", "windows-input-0/samples.data",
                "windows-input-0/video.idx", "windows-input-0/audio.idx", "windows-input-0/video-config.bin.tmp",
                "composite.mp4", "windows-composite-complete.tmp").forEach { name ->
                File(taskWork, name).apply { parentFile.mkdirs(); writeText("测试缓存") }
            }
            store.save(listOf(interrupted(id, FileStreamingDestination(target, temporary), PlaybackTaskPhase.CANCELLED, true)))
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            try {
                val manager = manager(scope, store, recovery)
                withTimeout(5000) { while (manager.tasks.value.isEmpty() || manager.tasks.value.single().cleanupPending ||
                    Files.exists(temporary) || cache.exists()) delay(10) }
                assertEquals("用户视频", target.readText())
                assertEquals("其他数据", unrelated.readText())
                assertTrue(!taskWork.exists(), "分片、索引、合成文件和临时文件应一起清理")
                withTimeout(5000) { while (store.load().single().request != null) delay(10) }
            } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
    }

    @Test fun restoredDestinationRewindsAndPreservesExistingTargetUntilCommit() = runBlocking {
        withDirectory { directory ->
            val target = File(directory, "output.mp4").apply { writeText("original") }
            val temporary = Files.createTempFile(directory.toPath(), ".bjtu-save-", ".tmp")
            val destination = FileStreamingDestination(target, temporary)
            destination.write("unfinished".toByteArray())
            val token = destination.recoveryToken
            destination.closeForRecovery()
            assertEquals("original", target.readText())
            val restored = assertIs<StreamingDestinationResult.Opened>(
                WindowsPlaybackRecovery(directory).restoreDestination(token, target.name)).destination
            restored.write("complete".toByteArray())
            assertEquals("original", target.readText())
            assertEquals(HomeworkFileSaveResult.Saved, restored.commit())
            assertEquals("complete", target.readText())
            assertTrue(!Files.exists(temporary))
        }
    }

    @Test fun destinationTokenRejectsDifferentDirectoryAndName() = runBlocking {
        withDirectory { directory ->
            val target = File(directory, "output.mp4")
            val other = File(directory, "other").apply { mkdir() }
            val temporary = Files.createTempFile(other.toPath(), ".bjtu-save-", ".tmp").toFile()
            val recovery = WindowsPlaybackRecovery(directory)
            assertIs<StreamingDestinationResult.Failed>(
                recovery.restoreDestination(windowsDestinationToken(target, temporary), target.name))
            val valid = Files.createTempFile(directory.toPath(), ".bjtu-save-", ".tmp").toFile()
            assertIs<StreamingDestinationResult.Failed>(
                recovery.restoreDestination(windowsDestinationToken(target, valid), "different.mp4"))
            assertIs<StreamingDestinationResult.Failed>(
                recovery.restoreDestination(windowsDestinationToken(valid, valid), valid.name),
                "损坏记录不能把最终文件当成临时文件删除")
            assertTrue(valid.exists())
            val nonFile = File(directory, ".bjtu-save-directory.tmp").apply { mkdir(); resolve("keep").writeText("保留") }
            assertIs<StreamingDestinationResult.Failed>(
                recovery.restoreDestination(windowsDestinationToken(target, nonFile), target.name))
            assertEquals("保留", File(nonFile, "keep").readText())
        }
    }

    @Test fun recordsPersistAtomicallyAndAccountsAreIsolated() = runBlocking {
        withDirectory { directory ->
            val recovery = WindowsPlaybackRecovery(directory)
            val first = recovery.storeFor("account-one")
            val second = recovery.storeFor("account-two")
            assertNotEquals(first.workRoot, second.workRoot)
            val task = StoredPlaybackTask(PlaybackTask("t123-0", "课程", "课节", listOf("教师"), false,
                phase = PlaybackTaskPhase.PAUSED, canResume = true))
            first.save(listOf(task))
            assertEquals(listOf(task), recovery.storeFor("account-one").load())
            assertEquals(emptyList(), second.load())
            assertFailsWith<IllegalArgumentException> { first.save(listOf(task, task)) }
            assertEquals(listOf(task), first.load(), "非法写入不能破坏原记录")
        }
    }

    @Test fun staleRecordHardLinkCannotTruncateAnotherFile() = runBlocking {
        withDirectory { directory ->
            val recovery = WindowsPlaybackRecovery(File(directory, "state"))
            val store = recovery.storeFor("hardlink-test")
            val task = StoredPlaybackTask(PlaybackTask("t987-0", "课程", "课节", emptyList(), false,
                phase = PlaybackTaskPhase.DONE))
            store.save(listOf(task))
            val accountDirectory = checkNotNull(File(store.workRoot.toString()).parentFile)
            val userVideo = File(directory, "user-video.mp4").apply { writeText("用户视频") }
            val temporary = File(accountDirectory, "tasks.json.new")
            Files.createLink(temporary.toPath(), userVideo.toPath())
            store.save(listOf(task))
            assertEquals("用户视频", userVideo.readText())
            assertTrue(!temporary.exists())
            assertEquals(listOf(task), store.load())
        }
    }

    @Test fun damagedHistoryIsNotOverwrittenWhenManagerStarts() = runBlocking {
        withDirectory { directory ->
            val recovery = WindowsPlaybackRecovery(directory)
            val store = recovery.storeFor("damaged-test")
            val work = File(store.workRoot.toString())
            val accountDirectory = checkNotNull(work.parentFile)
            check(accountDirectory.mkdirs() || accountDirectory.isDirectory)
            val record = File(accountDirectory, "tasks.json").apply { writeText("{broken") }
            val cache = File(work, "t123-0/segments-0/0.ts").apply { parentFile.mkdirs(); writeText("cached") }
            val orphan = File(directory, ".bjtu-save-unknown.tmp").apply { writeText("未知文件") }
            val manager = recovery.retainedManager("damaged-test")
            withTimeout(5000) { while (manager.tasks.value.isEmpty()) delay(10) }
            assertEquals("history-error", manager.tasks.value.single().id)
            delay(700)
            assertEquals("{broken", record.readText(), "读取失败不能用空历史覆盖原记录")
            assertEquals("cached", cache.readText(), "记录损坏时不能清理可能需要恢复的数据")
            assertEquals("未知文件", orphan.readText(), "无法确定归属的目标目录文件不能自动删除")
        }
    }

    @Test fun nonFileHistoryPathIsReportedWithoutDeletingExistingData() = runBlocking {
        withDirectory { directory ->
            val recovery = WindowsPlaybackRecovery(File(directory, "state"))
            val store = recovery.storeFor("history-directory-test")
            val work = File(store.workRoot.toString())
            val record = File(checkNotNull(work.parentFile), "tasks.json").apply { mkdirs() }
            val sentinel = File(record, "keep").apply { writeText("保留") }
            val cache = File(work, "t111-0/segments-0/0.ts").apply { parentFile.mkdirs(); writeText("cached") }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            try {
                val manager = manager(scope, store, recovery)
                withTimeout(5000) { while (manager.tasks.value.isEmpty()) delay(10) }
                assertEquals("history-error", manager.tasks.value.single().id)
                delay(600)
                assertEquals("保留", sentinel.readText())
                assertEquals("cached", cache.readText())
            } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
    }

    @Test fun interruptedInitialRecordWriteIsPreservedInsteadOfReplacedByEmptyHistory() = runBlocking {
        withDirectory { directory ->
            val recovery = WindowsPlaybackRecovery(File(directory, "state"))
            val store = recovery.storeFor("interrupted-record-test")
            val work = File(store.workRoot.toString())
            val accountDirectory = checkNotNull(work.parentFile).apply { mkdirs() }
            val pending = File(accountDirectory, "tasks.json.new").apply { writeText("{部分记录") }
            val cache = File(work, "t222-0/segments-0/0.ts").apply { parentFile.mkdirs(); writeText("cached") }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            try {
                val manager = manager(scope, store, recovery)
                withTimeout(5000) { while (manager.tasks.value.isEmpty()) delay(10) }
                assertEquals("history-error", manager.tasks.value.single().id)
                delay(600)
                assertEquals("{部分记录", pending.readText())
                assertEquals("cached", cache.readText())
                assertTrue(!File(accountDirectory, "tasks.json").exists())
            } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
    }

    @Test fun unrecordedWorkIsPreservedAndReportedInsteadOfPurged() = runBlocking {
        withDirectory { directory ->
            val recovery = WindowsPlaybackRecovery(File(directory, "state"))
            val store = recovery.storeFor("orphan-work-test")
            val cache = File(store.workRoot.toString(), "t56789-0/segments-0/0.ts").apply { parentFile.mkdirs(); writeText("cached") }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            try {
                val manager = manager(scope, store, recovery)
                withTimeout(5000) { while (manager.tasks.value.isEmpty()) delay(10) }
                assertEquals("history-warning", manager.tasks.value.single().id)
                assertTrue(manager.tasks.value.single().message!!.contains("无法自动恢复"))
                delay(700)
                assertEquals("cached", cache.readText())
                assertTrue(store.load().isEmpty(), "临时提示不应写入任务记录")
            } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
    }
}

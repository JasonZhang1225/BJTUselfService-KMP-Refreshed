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
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult

class WindowsPlaybackRecoveryTest {
    private suspend fun withDirectory(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("bjtu-playback-test-").toFile().canonicalFile
        try { block(directory) } finally {
            val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
            check(directory.parentFile == tempRoot && directory.name.startsWith("bjtu-playback-test-"))
            directory.deleteRecursively()
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

    @Test fun damagedHistoryIsNotOverwrittenWhenManagerStarts() = runBlocking {
        withDirectory { directory ->
            val recovery = WindowsPlaybackRecovery(directory)
            val store = recovery.storeFor("damaged-test")
            val work = File(store.workRoot.toString())
            val accountDirectory = checkNotNull(work.parentFile)
            check(accountDirectory.mkdirs() || accountDirectory.isDirectory)
            val record = File(accountDirectory, "tasks.json").apply { writeText("{broken") }
            val manager = recovery.retainedManager("damaged-test")
            withTimeout(5000) { while (manager.tasks.value.isEmpty()) delay(10) }
            assertEquals("history-error", manager.tasks.value.single().id)
            delay(700)
            assertEquals("{broken", record.readText(), "读取失败不能用空历史覆盖原记录")
        }
    }
}

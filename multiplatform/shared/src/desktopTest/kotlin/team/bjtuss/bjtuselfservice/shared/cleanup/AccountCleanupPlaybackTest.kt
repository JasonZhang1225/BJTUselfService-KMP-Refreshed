package team.bjtuss.bjtuselfservice.shared.cleanup

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.feature.courseware.*
import team.bjtuss.bjtuselfservice.shared.files.*
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault
import team.bjtuss.bjtuselfservice.shared.agent.remote.VaultRemoteSecretStore

class AccountCleanupPlaybackTest {
    private fun record(id: String, phase: PlaybackTaskPhase, committed: Boolean = false) = StoredPlaybackTask(
        PlaybackTask(id, "fixture-course", "fixture-lesson", listOf("video"), false, phase, outputs = listOf("export.mp4")),
        StoredPlaybackRequest(emptyList(), false, "HIGH", listOf("fixture-token"), listOf(committed)),
    )
    private class Store(override val workRoot: Path, initial: List<StoredPlaybackTask>) : PlaybackTaskStore {
        private val state = MutableStateFlow(initial)
        var records: List<StoredPlaybackTask>
            get() = state.value
            set(value) { state.value = value }
        override suspend fun load() = records
        override suspend fun save(tasks: List<StoredPlaybackTask>) { records = tasks }
    }
    private class Destination(private val ambiguous: Boolean = false, override val recoveredOutputMayBeCommitted: Boolean = false) : StreamingDestination {
        override val displayName = "export.mp4"
        var aborted = false
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun commit(): FileSaveResult = error("Cleanup must never commit exports")
        override suspend fun abort() { aborted = true }
        override suspend fun isRecoveryCommitAmbiguous() = ambiguous
    }

    @Test fun completedAndCommittedExportsAreNeverOpenedOrDeleted() = runBlocking {
        val root = Files.createTempDirectory("cleanup-exports-fixture-")
        val exported = root.resolve("user-export.mp4")
        Files.writeString(exported, "fixture-export")
        try {
            val store = Store(Path(root.resolve("work").toString()), listOf(record("done", PlaybackTaskPhase.DONE), record("committed", PlaybackTaskPhase.PAUSED, true)))
            assertTrue(clearStoredPlaybackData(store) { _, _ -> error("Export must not be opened") })
            assertTrue(store.records.isEmpty())
            assertEquals("fixture-export", Files.readString(exported))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun ambiguousFinalExportSurvivesWhileItsApplicationRecordIsRemoved() = runBlocking {
        val root = Files.createTempDirectory("cleanup-ambiguous-fixture-")
        try {
            val store = Store(Path(root.toString()), listOf(record("ambiguous", PlaybackTaskPhase.PAUSED)))
            val destination = Destination(ambiguous = true)
            assertTrue(clearStoredPlaybackData(store) { _, _ -> StreamingDestinationResult.Opened(destination) })
            assertFalse(destination.aborted)
            assertTrue(store.records.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unfinishedOutputIsAbortedAndFailuresRetainOnlyRetryableRecords() = runBlocking {
        val root = Files.createTempDirectory("cleanup-unfinished-fixture-")
        try {
            val failed = record("failed", PlaybackTaskPhase.PAUSED)
            val store = Store(Path(root.toString()), listOf(record("unfinished", PlaybackTaskPhase.PAUSED), failed))
            val destination = Destination()
            var calls = 0
            assertFalse(clearStoredPlaybackData(store) { _, _ ->
                if (++calls == 1) StreamingDestinationResult.Opened(destination) else StreamingDestinationResult.Failed(FileGatewayFailure.UNAVAILABLE)
            })
            assertTrue(destination.aborted)
            assertEquals(listOf(failed), store.records)
            assertTrue(clearStoredPlaybackData(store) { _, _ -> StreamingDestinationResult.Opened(Destination()) })
            assertTrue(store.records.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun providerWithoutCommitEvidenceRetainsRecoveredUserFile() = runBlocking {
        val root = Files.createTempDirectory("cleanup-provider-fixture-")
        try {
            val store = Store(Path(root.toString()), listOf(record("provider", PlaybackTaskPhase.PAUSED)))
            val destination = Destination(recoveredOutputMayBeCommitted = true)
            assertTrue(clearStoredPlaybackData(store) { _, _ -> StreamingDestinationResult.Opened(destination) })
            assertFalse(destination.aborted)
            assertTrue(store.records.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun corruptTaskIdCannotEscapeManagedDirectory() = runBlocking {
        val root = Files.createTempDirectory("cleanup-invalid-fixture-")
        val protected = root.resolve("protected.txt")
        Files.writeString(protected, "keep")
        try {
            val store = Store(Path(root.resolve("work").toString()), listOf(record("../protected.txt", PlaybackTaskPhase.DONE)))
            assertFalse(clearStoredPlaybackData(store) { _, _ -> error("Invalid record must not resolve an export") })
            assertEquals(1, store.records.size)
            assertEquals("keep", Files.readString(protected))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun allRemoteTokenSlotsAreAttemptedEvenWhenOneVaultFails() = runBlocking {
        val attempted = mutableListOf<String>()
        val secrets = VaultRemoteSecretStore { key -> object : CredentialVault {
            override suspend fun load(): Credentials? = null
            override suspend fun save(credentials: Credentials) = Unit
            override suspend fun clear() { attempted += key; check(key != "device_token") { "Fixture failure" } }
        } }
        assertFails { secrets.clear() }
        assertEquals(listOf("device_token", "platform_access", "platform_site"), attempted)
    }

    @Test fun managerCacheClearKeepsPausedWorkAndAccountClearCannotResurrectRecordsInFinalizer() = runBlocking {
        val root = Files.createTempDirectory("cleanup-manager-fixture-").toFile().canonicalFile
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val taskWork = java.io.File(root, "work/t123-0").apply { mkdirs() }
        val segment = java.io.File(taskWork, "segment.data").apply { writeText("paused-work") }
        val stale = java.io.File(root, "work/stale").apply { mkdirs() }
        java.io.File(stale, "old.tmp").writeText("stale")
        val exported = java.io.File(root, "export.mp4").apply { writeText("user-export") }
        val store = Store(Path(java.io.File(root, "work").path), listOf(record("t123-0", PlaybackTaskPhase.PAUSED)))
        val destination = Destination(recoveredOutputMayBeCommitted = true)
        val transport = object : team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport {
            override suspend fun execute(request: team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest): team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse = error("No network in cleanup fixture")
            override fun clearSession() = Unit
            override fun close() = Unit
        }
        try {
            val manager = PlaybackTaskManager(scope, team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader(transport),
                { team.bjtuss.bjtuselfservice.shared.media.PlaybackPlatformHooks.effectiveCompositor }, { null }, store,
                restoreDestination = { _, _ -> StreamingDestinationResult.Opened(destination) })
            assertTrue(manager.clearStaleTemporaryFiles())
            assertTrue(segment.isFile)
            assertTrue(stale.exists(), "Unrecorded directories are not proven stale during a cache clear")
            assertTrue(manager.tasks.value.any { it.id == "t123-0" })
            assertTrue(manager.clearAccountData())
            assertTrue(manager.tasks.value.isEmpty())
            assertFalse(taskWork.exists())
            assertFalse(stale.exists(), "Account-owned work can be removed during explicit account deletion")
            assertFalse(destination.aborted)
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            assertTrue(store.records.isEmpty())
            assertEquals("user-export", exported.readText())
        } finally {
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            check(root.parentFile == java.io.File(System.getProperty("java.io.tmpdir")).canonicalFile && root.name.startsWith("cleanup-manager-fixture-"))
            root.deleteRecursively()
        }
    }
}

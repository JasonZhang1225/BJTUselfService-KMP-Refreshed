package team.bjtuss.bjtuselfservice.shared.runtime

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.calendar.*
import team.bjtuss.bjtuselfservice.shared.files.FileContent
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackRecoveryAccess
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTaskStore
import team.bjtuss.bjtuselfservice.shared.files.*

class RuntimeHostServicesTest {
    @Test
    fun replacingHostRedirectsAllUiPortsAndOldDisposalCannotDetachIt() = runBlocking<Unit> {
        val first = Host()
        val second = Host()
        val services = RuntimeHostServices(null)
        val releaseFirst = services.attach(first, first, first)
        services.files.pickFiles()
        assertEquals(1, first.calls)
        val releaseSecond = services.attach(second, second, second)
        releaseFirst()
        services.files.pickFiles()
        services.files.saveFile(file)
        services.files.previewFile(file)
        services.files.saveStreamingFile("fixture", "application/octet-stream") { }
        services.directories.openDirectory("fixture")
        services.calendar.install(emptyList())
        assertEquals(1, first.calls)
        assertEquals(6, second.calls)
        releaseSecond()
        assertFalse(services.files.isAvailable)
        assertFalse(services.files.isPreviewAvailable)
        assertFalse(services.files.isStreamingSaveAvailable)
        assertFalse(services.directories.isDirectoryExportAvailable)
        assertFalse(services.calendar.isAvailable)
        assertEquals(FilePickResult.Failed(FileGatewayFailure.UNAVAILABLE), services.files.pickFiles())
        services.close()
        assertFailsWith<IllegalStateException> { services.attach(first, first, first) }
    }

    @Test
    fun selectedDestinationRemainsUsableAfterItsPickerHostIsDetached() = runBlocking<Unit> {
        val host = Host()
        val services = RuntimeHostServices(null)
        val release = services.attach(host, host, host)
        val selected = services.files.openStreamingDestination("fixture", "application/octet-stream") as StreamingDestinationResult.Opened
        release()
        selected.destination.write(byteArrayOf(1, 2))
        assertEquals(FileSaveResult.Saved, selected.destination.commit())
        assertEquals(2, host.writtenBytes)
        assertEquals(StreamingDestinationResult.Failed(FileGatewayFailure.UNAVAILABLE),
            services.files.openStreamingDestination("fixture", "application/octet-stream"))
    }

    @Test
    fun applicationRecoverySurvivesHostDetachmentWithoutInventingSupportOnOtherPlatforms() = runBlocking<Unit> {
        var restores = 0
        val recovery = object : PlaybackRecoveryAccess {
            override fun storeFor(accountScope: String): PlaybackTaskStore = error("No disk access in this fixture")
            override suspend fun restoreDestination(token: String, displayName: String): StreamingDestinationResult {
                restores++
                return StreamingDestinationResult.Cancelled
            }
        }
        val services = RuntimeHostServices(recovery)
        val release = services.attach(UnavailableFileGateway, UnavailableDirectoryGateway, UnavailableSystemCalendarGateway)
        release()
        val recoveryAccess = assertNotNull(services.playbackRecovery)
        assertSame(recovery, recoveryAccess)
        recoveryAccess.restoreDestination("fixture", "fixture")
        assertEquals(1, restores)
        assertNull(RuntimeHostServices(null).playbackRecovery)
    }

    private val file = FileContent("fixture.txt", "text/plain", byteArrayOf())

    private class Host : FileGateway, DirectoryGateway, SystemCalendarGateway {
        var calls = 0
        var writtenBytes = 0
        override val isAvailable = true
        override val isPreviewAvailable = true
        override val isStreamingSaveAvailable = true
        override val isDirectoryExportAvailable = true
        override suspend fun pickFiles(): FilePickResult { calls++; return FilePickResult.Cancelled }
        override suspend fun saveFile(file: FileContent): FileSaveResult { calls++; return FileSaveResult.Saved }
        override suspend fun previewFile(file: FileContent): FilePreviewResult { calls++; return FilePreviewResult.Opened }
        override suspend fun saveStreamingFile(fileName: String, contentType: String, produce: suspend (StreamingFileSink) -> Unit): FileSaveResult {
            calls++
            return FileSaveResult.Saved
        }
        override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult =
            StreamingDestinationResult.Opened(object : StreamingDestination {
                override val displayName = fileName
                override suspend fun write(bytes: ByteArray) { writtenBytes += bytes.size }
                override suspend fun commit(): FileSaveResult = FileSaveResult.Saved
                override suspend fun abort() = Unit
            })
        override suspend fun openDirectory(directoryName: String): DirectoryOpenResult {
            calls++
            return DirectoryOpenResult.Cancelled
        }
        override suspend fun install(calendars: List<SystemCalendarBatch>): SystemCalendarInstallResult {
            calls++
            return SystemCalendarInstallResult.Cancelled
        }
    }
}

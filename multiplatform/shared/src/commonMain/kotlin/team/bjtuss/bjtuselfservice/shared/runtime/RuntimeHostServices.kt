package team.bjtuss.bjtuselfservice.shared.runtime

import kotlin.concurrent.Volatile
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarBatch
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarGateway
import team.bjtuss.bjtuselfservice.shared.calendar.UnavailableSystemCalendarGateway
import team.bjtuss.bjtuselfservice.shared.files.FileContent
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackRecoveryAccess
import team.bjtuss.bjtuselfservice.shared.files.*

/** Account resources keep these stable ports, while platform UI adapters belong to the current host. */
internal class RuntimeHostServices(val playbackRecovery: PlaybackRecoveryAccess?) {
    private class Binding(
        val files: FileGateway,
        val directories: DirectoryGateway,
        val calendar: SystemCalendarGateway,
    )

    @Volatile private var binding: Binding? = null
    private var closed = false

    val files: FileGateway = Files()
    val directories = object : DirectoryGateway {
        private val delegate get() = binding?.directories ?: UnavailableDirectoryGateway
        override val isDirectoryExportAvailable get() = delegate.isDirectoryExportAvailable
        override suspend fun openDirectory(directoryName: String) = delegate.openDirectory(directoryName)
    }
    val calendar = object : SystemCalendarGateway {
        private val delegate get() = binding?.calendar ?: UnavailableSystemCalendarGateway
        override val isAvailable get() = delegate.isAvailable
        override suspend fun install(calendars: List<SystemCalendarBatch>) = delegate.install(calendars)
    }

    fun attach(files: FileGateway, directories: DirectoryGateway, calendar: SystemCalendarGateway): () -> Unit {
        check(!closed) { "App runtime is closed" }
        val attached = Binding(files, directories, calendar)
        binding = attached
        // Disposing an older root cannot detach a newly created host.
        return { if (binding === attached) binding = null }
    }

    fun close() {
        closed = true
        binding = null
    }

    private inner class Files : FileGateway {
        private val delegate get() = binding?.files ?: UnavailableFileGateway
        override val isAvailable get() = delegate.isAvailable
        override val isPreviewAvailable get() = delegate.isPreviewAvailable
        override val isStreamingSaveAvailable get() = delegate.isStreamingSaveAvailable
        override suspend fun pickFiles() = delegate.pickFiles()
        override suspend fun saveFile(file: FileContent) = delegate.saveFile(file)
        override suspend fun previewFile(file: FileContent) = delegate.previewFile(file)
        override suspend fun openStreamingDestination(fileName: String, contentType: String) =
            delegate.openStreamingDestination(fileName, contentType)
        override suspend fun saveStreamingFile(fileName: String, contentType: String, produce: suspend (StreamingFileSink) -> Unit) =
            delegate.saveStreamingFile(fileName, contentType, produce)
    }

}

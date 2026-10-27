package team.bjtuss.bjtuselfservice.windows

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import team.bjtuss.bjtuselfservice.shared.feature.courseware.*
import team.bjtuss.bjtuselfservice.shared.files.*

/** 只持有公开媒体 transport、任务目录和保存路径，不持有窗口或登录会话。 */
class WindowsPlaybackRecovery(private val root: File) : PlaybackRecoveryAccess {
    override fun retainedManager(accountScope: String): PlaybackTaskManager = synchronized(managers) {
        val key = File(root, digest(accountScope)).canonicalPath
        managers.getOrPut(key) {
            PlaybackTaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Main),
                team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader(
                    team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport()),
                { team.bjtuss.bjtuselfservice.shared.media.PlaybackPlatformHooks.effectiveCompositor },
                { null }, storeFor(accountScope), ::restoreDestination)
        }
    }
    override fun storeFor(accountScope: String): PlaybackTaskStore = WindowsPlaybackTaskStore(File(root, digest(accountScope)))
    override suspend fun restoreDestination(token: String, displayName: String): StreamingDestinationResult = withContext(Dispatchers.IO) {
        try {
            require(token.length < 256 * 1024)
            val parts = token.split(':')
            require(parts.size == 3 && parts[0] == "v1")
            val rawTarget = File(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
            val rawTemporary = File(String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8))
            require(rawTarget.isAbsolute && rawTemporary.isAbsolute)
            require(!Files.isSymbolicLink(rawTarget.toPath()) && !Files.isSymbolicLink(rawTemporary.toPath()))
            val target = rawTarget.canonicalFile
            val temporary = rawTemporary.canonicalFile
            require(target.name == displayName && target.parentFile.isDirectory)
            require(temporary.parentFile == target.parentFile && temporary.name.startsWith(".bjtu-save-") && temporary.name.endsWith(".tmp"))
            require(!temporary.isDirectory && !Files.isSymbolicLink(temporary.toPath()))
            StreamingDestinationResult.Opened(FileStreamingDestination(target, temporary.toPath()))
        } catch (_: Exception) { StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.IO) }
    }
    private companion object {
        val managers = mutableMapOf<String, PlaybackTaskManager>()
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

internal fun windowsDestinationToken(target: File, temporary: File): String {
    fun encode(file: File) = Base64.getUrlEncoder().withoutPadding().encodeToString(file.canonicalPath.toByteArray(Charsets.UTF_8))
    return "v1:${encode(target)}:${encode(temporary)}"
}

internal class WindowsPlaybackTaskStore(private val directory: File) : PlaybackTaskStore {
    override val workRoot = Path(File(directory, "work").absolutePath)
    override suspend fun load(): List<StoredPlaybackTask> = withContext(Dispatchers.IO) {
        val file = File(directory, "tasks.json")
        if (!file.isFile) emptyList() else decodePlaybackTaskRecords(file.readText()).also { validate(it) }
    }
    override suspend fun save(tasks: List<StoredPlaybackTask>) = withContext(Dispatchers.IO) {
        validate(tasks)
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "tasks.json").toPath()
        val temporary = File(directory, "tasks.json.new")
        FileOutputStream(temporary).use { stream ->
            stream.write(encodePlaybackTaskRecords(tasks).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try { Files.move(temporary.toPath(), file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file, StandardCopyOption.REPLACE_EXISTING) }
        Unit
    }
    private fun validate(tasks: List<StoredPlaybackTask>) {
        require(tasks.all { Regex("t[0-9]+-[0-9]+").matches(it.task.id) })
        require(tasks.map { it.task.id }.toSet().size == tasks.size)
    }
}

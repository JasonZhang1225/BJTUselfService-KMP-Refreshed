package team.bjtuss.bjtuselfservice.kmp.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.files.Path
import team.bjtuss.bjtuselfservice.kmp.ContentStreamingDestination
import team.bjtuss.bjtuselfservice.shared.feature.courseware.*
import team.bjtuss.bjtuselfservice.shared.files.*

/** 不持有 Activity；任务记录和原始分片按账号摘要隔离在应用私有 files 下。 */
class AndroidPlaybackRecovery(context: Context) : PlaybackRecoveryAccess {
    private val app = context.applicationContext
    override fun retainedManager(accountScope: String): PlaybackTaskManager = managerFor(accountScope)

    /** 只保存 applicationContext、公开媒体 transport 与目标 URI，不保存 Activity/登录会话。 */
    fun managerFor(accountScope: String, transport: team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport? = null): PlaybackTaskManager =
        synchronized(managers) {
            managers.getOrPut(accountScope) {
                val publicTransport = transport ?: team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport()
                val host = AndroidPlaybackBackgroundHost(app)
                PlaybackTaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Main),
                    team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader(publicTransport),
                    { team.bjtuss.bjtuselfservice.shared.media.PlaybackPlatformHooks.effectiveCompositor },
                    { host }, storeFor(accountScope), restoreDestination = ::restoreDestination)
            }
        }

    private companion object {
        val managers = mutableMapOf<String, PlaybackTaskManager>()
    }
    override fun storeFor(accountScope: String): PlaybackTaskStore {
        val owner = MessageDigest.getInstance("SHA-256").digest(accountScope.toByteArray()).joinToString("") { "%02x".format(it) }
        return AndroidPlaybackTaskStore(File(app.filesDir, "playback-tasks/$owner"))
    }

    override suspend fun restoreDestination(token: String, displayName: String): StreamingDestinationResult = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(token) }.getOrNull()
        if (uri?.scheme != "content" || uri.query != null) return@withContext StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        val granted = app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!granted) return@withContext StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
        // 保存时才打开并截断目标；重进 App 不会重写或删除用户文件。
        StreamingDestinationResult.Opened(ContentStreamingDestination(app.contentResolver, uri, null, displayName))
    }
}

class AndroidPlaybackTaskStore(private val directory: File) : PlaybackTaskStore {
    override val workRoot: Path = Path(File(directory, "work").absolutePath)
    private val recordFile get() = AtomicFile(File(directory, "tasks.json"))
    private var lastText: String? = null

    override suspend fun load(): List<StoredPlaybackTask> = withContext(Dispatchers.IO) {
        if (!File(directory, "tasks.json").exists() && !File(directory, "tasks.json.bak").exists()) return@withContext emptyList()
        val text = recordFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val tasks = decodePlaybackTaskRecords(text)
        lastText = text
        tasks
    }

    override suspend fun save(tasks: List<StoredPlaybackTask>) = withContext(Dispatchers.IO) {
        val text = encodePlaybackTaskRecords(tasks)
        if (text == lastText) return@withContext
        check(directory.isDirectory || directory.mkdirs()) { "Task storage unavailable" }
        val atomic = recordFile
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            lastText = text
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}

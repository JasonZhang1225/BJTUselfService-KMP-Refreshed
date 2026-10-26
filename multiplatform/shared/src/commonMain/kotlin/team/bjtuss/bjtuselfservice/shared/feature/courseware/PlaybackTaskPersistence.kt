package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult

/** 平台提供原子记录文件和持久工作目录；内容只含任务、公开媒体地址与保存位置句柄。 */
interface PlaybackTaskStore {
    val workRoot: Path
    suspend fun load(): List<StoredPlaybackTask>
    suspend fun save(tasks: List<StoredPlaybackTask>)
}

/** 保存面板的宿主与恢复能力分开；Android 实现只持有 applicationContext。 */
interface PlaybackRecoveryAccess {
    fun storeFor(accountScope: String): PlaybackTaskStore
    suspend fun restoreDestination(token: String, displayName: String): StreamingDestinationResult
}

interface PlaybackRecoveryProvider {
    val playbackRecovery: PlaybackRecoveryAccess
}

@Serializable
data class StoredPlaybackStream(val kind: String, val url: String)

@Serializable
data class StoredPlaybackRequest(
    val streams: List<StoredPlaybackStream>,
    val composite: Boolean,
    val quality: String,
    val destinations: List<String?>,
    val committed: List<Boolean>,
    val finished: Map<Int, StoredPlaybackStreamResult> = emptyMap(),
)

@Serializable
data class StoredPlaybackStreamResult(val bytes: Long, val durationSeconds: Double)

@Serializable
data class StoredPlaybackTask(val task: PlaybackTask, val request: StoredPlaybackRequest? = null)

private val taskRecordJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun encodePlaybackTaskRecords(tasks: List<StoredPlaybackTask>): String = taskRecordJson.encodeToString(tasks)
fun decodePlaybackTaskRecords(text: String): List<StoredPlaybackTask> = taskRecordJson.decodeFromString(text)

package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 私人 Site 远程协议（v1）的 DTO，与 `multiplatform/remote-site-template/src/protocol.js` 一一对应。
 * 两端共用 `remote-site-template/fixtures/` 的虚构样例做契约测试；改字段必须同时改两边。
 *
 * 快照与任务只携带获准上云的业务数据，绝不包含学校密码、Cookie、令牌或数据库密钥。
 */
const val REMOTE_PROTOCOL_VERSION = 1
const val REMOTE_SNAPSHOT_SCHEMA_VERSION = 1

/** 允许上云的数据范围；成绩与邮箱默认不上传。 */
enum class RemoteScope(val wire: String, val label: String) {
    SCHEDULE("schedule", "课表与日程"),
    EXAMS("exams", "考试"),
    ASSIGNMENTS("assignments", "作业与实验"),
    GRADES("grades", "成绩"),
    MAIL("mail", "邮箱未读数"),
    ;

    companion object {
        val DEFAULT: Set<RemoteScope> = setOf(SCHEDULE, EXAMS, ASSIGNMENTS)
        fun fromWire(wire: String): RemoteScope? = entries.firstOrNull { it.wire == wire }
    }
}

/** 即时任务操作白名单。 */
object RemoteOperation {
    const val QUERY = "query"
    const val REFRESH_ALL = "refresh_all"
    const val CITEL_PROBLEM = "citel_problem"
}

object RemoteTaskStatus {
    const val SUCCEEDED = "succeeded"
    const val PARTIAL = "partial"
    const val FAILED = "failed"
}

/** 设备端的可判定错误码（Site 返回或本地判定）。 */
object RemoteErrorCode {
    const val UNAUTHORIZED = "unauthorized"
    const val PROTOCOL_MISMATCH = "protocol_mismatch"
    const val STALE_SNAPSHOT = "stale_snapshot"
    const val STALE_LEASE = "stale_lease"
    const val BINDING_MISMATCH = "binding_mismatch"
    const val UNSUPPORTED_OPERATION = "unsupported_operation"
    const val SCOPE_DENIED = "scope_denied"
}

@Serializable
data class RemotePairRequest(
    val protocolVersion: Int = REMOTE_PROTOCOL_VERSION,
    val pairingCode: String,
    val deviceName: String,
    val accountBindingId: String,
    val scopes: List<String>,
)

@Serializable
data class RemotePairResponse(
    val protocolVersion: Int,
    val deviceId: String,
    val deviceToken: String,
    val accountBindingId: String,
    val scopes: List<String>,
    val templateVersion: String? = null,
) {
    override fun toString(): String = "RemotePairResponse(deviceId=$deviceId, deviceToken=<redacted>, scopes=$scopes)"
}

@Serializable
data class RemoteSourceState(
    /** success / failed / skipped / needs_user_action（同 RefreshStatus.wire）。 */
    val status: String,
    /** 该来源数据的真实同步时间；失败保留旧时间，null 表示没有记录。 */
    val fetchedAt: Long? = null,
    val reason: String? = null,
)

@Serializable
data class RemoteSnapshot(
    val schemaVersion: Int = REMOTE_SNAPSHOT_SCHEMA_VERSION,
    val deviceId: String,
    val accountBindingId: String,
    val snapshotId: String,
    /** 同一绑定代次内单调递增；旧快照不能覆盖新数据。 */
    val sequence: Long,
    val generatedAt: Long,
    val uploadedAt: Long,
    val scopes: List<String>,
    val sources: Map<String, RemoteSourceState>,
    /** 键只能是 [scopes] 中的范围。 */
    val data: JsonObject,
)

@Serializable
data class RemoteSnapshotAck(
    val snapshotId: String,
    val sequence: Long,
    val stored: Boolean,
    val duplicate: Boolean,
)

@Serializable
data class RemoteScopesUpdate(val scopes: List<String>)

@Serializable
data class RemoteTask(
    val requestId: String,
    val deviceId: String,
    val accountBindingId: String,
    val operation: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val createdAt: Long,
    val expiresAt: Long,
    val status: String,
    val attempt: Int,
    val leaseId: String? = null,
    val leaseUntil: Long? = null,
)

@Serializable
data class RemotePollResponse(
    val protocolVersion: Int,
    val serverTime: Long,
    val scopesRevision: Int = 0,
    val task: RemoteTask? = null,
)

@Serializable
data class RemoteLeaseRenewal(val leaseId: String, val leaseSeconds: Int)

@Serializable
data class RemoteError(val code: String, val message: String? = null)

@Serializable
data class RemoteTaskResult(
    val leaseId: String,
    /** succeeded / partial / failed */
    val status: String,
    val result: JsonObject = JsonObject(emptyMap()),
    val error: RemoteError? = null,
)

@Serializable
data class RemoteTaskResultAck(val requestId: String, val accepted: Boolean, val duplicate: Boolean)

@Serializable
data class RemoteErrorBody(val error: RemoteError)

/** 协议 JSON：忽略新版 Site 的额外字段，不省略默认值，避免两端对“缺省”的理解不一致。 */
val remoteJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

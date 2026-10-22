package team.bjtuss.bjtuselfservice.shared.agent.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

enum class RemoteFailureKind {
    /** 401：设备令牌无效或已撤销，需要重新配对。 */
    UNAUTHORIZED,
    /** 403：平台门槛或权限拒绝（如范围未授权、设备不匹配）。 */
    FORBIDDEN,
    /** 426：协议版本不一致，需要更新电脑端或 Site 模板。 */
    PROTOCOL_MISMATCH,
    /** 409：旧快照、旧租约、绑定变化等，按 code 区分。 */
    CONFLICT,
    /** 429：遵守服务器的 Retry-After。 */
    RATE_LIMITED,
    CLIENT_ERROR,
    SERVER_ERROR,
    NETWORK,
}

sealed interface RemoteCall<out T> {
    data class Ok<T>(val value: T) : RemoteCall<T>
    data class Failure(
        val kind: RemoteFailureKind,
        val code: String? = null,
        val message: String? = null,
        val retryAfterSeconds: Long? = null,
    ) : RemoteCall<Nothing>
}

/** 设备一侧调用私人 Site 的接口；Windows 主动连接，Site 不连回电脑。 */
interface RemoteSiteClient {
    suspend fun pair(request: RemotePairRequest): RemoteCall<RemotePairResponse>
    /** 不需要设备令牌的版本探测；旧 Site 没有该接口时按失败处理。 */
    suspend fun health(): RemoteCall<RemoteHealth> = RemoteCall.Failure(RemoteFailureKind.CLIENT_ERROR, "unsupported")
    suspend fun putSnapshot(snapshot: RemoteSnapshot): RemoteCall<RemoteSnapshotAck>
    suspend fun putScopes(scopes: List<String>): RemoteCall<Unit>
    suspend fun putScopes(scopes: List<String>, scopesRevision: Long): RemoteCall<Unit> = putScopes(scopes)
    suspend fun poll(): RemoteCall<RemotePollResponse>
    suspend fun renewLease(requestId: String, leaseId: String, leaseSeconds: Int): RemoteCall<Unit>
    suspend fun submitResult(requestId: String, result: RemoteTaskResult): RemoteCall<RemoteTaskResultAck>
}

/**
 * Ktor 实现。只接受 [normalizeSiteUrl] 校验过的 https 地址；令牌在每次请求时从安全存储读取，
 * 不缓存在长生命周期字段里，也不出现在异常信息中。
 */
class KtorRemoteSiteClient(
    siteUrl: String,
    private val deviceToken: suspend () -> String?,
    private val platformAccess: suspend () -> String? = { null },
    private val client: HttpClient = defaultRemoteHttpClient(),
) : RemoteSiteClient {
    private val base: String = requireNotNull(normalizeSiteUrl(siteUrl)) { "Site 地址必须是 https" }

    override suspend fun pair(request: RemotePairRequest) =
        call(HttpMethod.Post, "/api/v1/device/pair", request, RemotePairRequest.serializer(), RemotePairResponse.serializer(), device = false)

    override suspend fun health() =
        call(HttpMethod.Get, "/api/v1/health", "", String.serializer(), RemoteHealth.serializer(), device = false, raw = true)

    override suspend fun putSnapshot(snapshot: RemoteSnapshot) =
        call(HttpMethod.Put, "/api/v1/device/snapshot", snapshot, RemoteSnapshot.serializer(), RemoteSnapshotAck.serializer())

    override suspend fun putScopes(scopes: List<String>): RemoteCall<Unit> =
        call(HttpMethod.Put, "/api/v1/device/scopes", RemoteScopesUpdate(scopes), RemoteScopesUpdate.serializer(), null)

    override suspend fun putScopes(scopes: List<String>, scopesRevision: Long): RemoteCall<Unit> =
        call(HttpMethod.Put, "/api/v1/device/scopes", RemoteScopesUpdate(scopes, scopesRevision), RemoteScopesUpdate.serializer(), null)

    override suspend fun poll() =
        call(HttpMethod.Post, "/api/v1/device/poll", "{}", String.serializer(), RemotePollResponse.serializer(), raw = true)

    override suspend fun renewLease(requestId: String, leaseId: String, leaseSeconds: Int): RemoteCall<Unit> =
        call(HttpMethod.Post, "/api/v1/device/tasks/${pathId(requestId)}/renew", RemoteLeaseRenewal(leaseId, leaseSeconds),
            RemoteLeaseRenewal.serializer(), null)

    override suspend fun submitResult(requestId: String, result: RemoteTaskResult) =
        call(HttpMethod.Post, "/api/v1/device/tasks/${pathId(requestId)}/result", result, RemoteTaskResult.serializer(),
            RemoteTaskResultAck.serializer())

    private fun pathId(id: String): String {
        require(Regex("^[A-Za-z0-9_-]{8,64}$").matches(id)) { "非法任务 ID" }
        return id
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <B, R> call(
        method: HttpMethod,
        path: String,
        body: B,
        bodySerializer: KSerializer<B>,
        responseSerializer: KSerializer<R>?,
        device: Boolean = true,
        raw: Boolean = false,
    ): RemoteCall<R> {
        val token = if (device) deviceToken() ?: return RemoteCall.Failure(RemoteFailureKind.UNAUTHORIZED, "no_token", "尚未配对") else null
        val access = platformAccess()
        return try {
            val response = client.request("$base$path") {
                this.method = method
                header("X-BJTU-Protocol", REMOTE_PROTOCOL_VERSION.toString())
                if (token != null) header("Authorization", "Bearer $token")
                if (access != null) header("OAI-Sites-Authorization", remotePlatformAuthorization(access))
                val text = if (raw) body as String else remoteJson.encodeToString(bodySerializer, body)
                if (method != HttpMethod.Get) setBody(TextContent(text, ContentType.Application.Json))
            }
            val status = response.status.value
            val text = response.bodyAsText()
            if (status in 200..299) {
                if (responseSerializer == null) return RemoteCall.Ok(Unit as R)
                val decoded = runCatching { remoteJson.decodeFromString(responseSerializer, text) }.getOrNull()
                    ?: return RemoteCall.Failure(RemoteFailureKind.SERVER_ERROR, "bad_response", "Site 返回了无法识别的内容")
                return RemoteCall.Ok(decoded)
            }
            val error = runCatching { remoteJson.decodeFromString(RemoteErrorBody.serializer(), text).error }.getOrNull()
            if (status in listOf(401, 403) && error == null) {
                return RemoteCall.Failure(RemoteFailureKind.FORBIDDEN, "platform_auth_required",
                    "本站连接授权无效，请登录 Site 重新生成并复制完整配对信息。")
            }
            val kind = when (status) {
                401 -> RemoteFailureKind.UNAUTHORIZED
                403 -> RemoteFailureKind.FORBIDDEN
                409 -> RemoteFailureKind.CONFLICT
                426 -> RemoteFailureKind.PROTOCOL_MISMATCH
                429 -> RemoteFailureKind.RATE_LIMITED
                in 400..499 -> RemoteFailureKind.CLIENT_ERROR
                else -> RemoteFailureKind.SERVER_ERROR
            }
            RemoteCall.Failure(kind, error?.code, error?.message?.take(200), response.headers["Retry-After"]?.trim()?.toLongOrNull())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            RemoteCall.Failure(RemoteFailureKind.NETWORK, "network", "无法连接 Site（${failure::class.simpleName}）")
        }
    }
}

/** Sites 的 service access 使用 Bearer 方案；同时兼容已填入完整头值的旧设置。 */
internal fun remotePlatformAuthorization(value: String): String {
    require('\r' !in value && '\n' !in value) { "Invalid platform access credential" }
    val token = value.trim().replaceFirst(Regex("^Bearer +", RegexOption.IGNORE_CASE), "").trim()
    require(token.isNotEmpty() && token.none(Char::isWhitespace)) { "Invalid platform access credential" }
    return "Bearer $token"
}

fun defaultRemoteHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
}

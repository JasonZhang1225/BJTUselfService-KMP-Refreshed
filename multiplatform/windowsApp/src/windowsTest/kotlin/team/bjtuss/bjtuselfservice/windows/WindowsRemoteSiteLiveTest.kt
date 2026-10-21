package team.bjtuss.bjtuselfservice.windows

import java.io.File
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.TextContent
import org.junit.Assume.assumeTrue
import team.bjtuss.bjtuselfservice.shared.agent.remote.*

/** 仅显式注入独立私人测试 Site 时运行。凭据只经当前进程环境传入，不进入参数/文件/测试输出。 */
class WindowsRemoteSiteLiveTest {
    @Test fun privateSiteMachineProtocolRoundTrip() = runBlocking {
        val input = System.getenv("BJTU_REMOTE_PROBE_CONFIG")
        assumeTrue("No private test Site configured", !input.isNullOrBlank())
        val config = remoteJson.parseToJsonElement(input!!).jsonObject
        val base = config.getValue("url").jsonPrimitive.content
        require(normalizeSiteUrl(base) == base)
        val access = config.getValue("platformAccess").jsonPrimitive.content
        val ownerToken = config.getValue("ownerToken").jsonPrimitive.content
        val http = defaultRemoteHttpClient()
        var pairedDevice: String? = null
        suspend fun owner(path: String, method: HttpMethod = HttpMethod.Get, body: JsonObject? = null): JsonObject {
            val response = http.request("$base$path") {
                this.method = method
                header("OAI-Sites-Authorization", "Bearer $access")
                header("Authorization", "Bearer $ownerToken")
                body?.let { setBody(TextContent(it.toString(), ContentType.Application.Json)) }
            }
            val text = response.bodyAsText()
            val decoded = runCatching { remoteJson.parseToJsonElement(text).jsonObject }.getOrNull()
            val code = (decoded?.get("error") as? JsonObject)?.get("code")?.jsonPrimitive?.content?.takeIf { Regex("^[a-z0-9_-]{1,64}$").matches(it) }
            assertTrue(response.status.value in 200..299, "Private fixture endpoint $path rejected: ${response.status.value}; code=${code ?: "platform_or_non_json"}")
            return requireNotNull(decoded)
        }
        try {
            assertNotEquals(200, http.get("$base/api/v1/health").status.value, "私人平台边界不能允许匿名直接进入应用")
            val status = owner("/api/v1/owner/status")
            println("REMOTE_PROBE_STEP: owner_access")
            require(status.getValue("devices").jsonArray.none { it.jsonObject["revokedAt"] is JsonNull }) {
                "Refusing to replace an active user device; use an empty private test Site"
            }
            val code = owner("/api/v1/owner/pairings", HttpMethod.Post).getValue("pairingCode").jsonPrimitive.content
            var token: String? = null
            val device = KtorRemoteSiteClient(base, { token }, { access })
            val pair = assertIs<RemoteCall.Ok<RemotePairResponse>>(device.pair(RemotePairRequest(
                pairingCode = code, deviceName = "Windows 虚构协议验证", accountBindingId = "bind_windowsProbe01",
                scopes = RemoteScope.DEFAULT.map { it.wire },
            ))).value
            pairedDevice = pair.deviceId
            println("REMOTE_PROBE_STEP: pairing")
            token = pair.deviceToken
            val fixture = remoteJson.decodeFromString(RemoteSnapshot.serializer(), File("../remote-site-template/fixtures/snapshot-v1.json").readText())
            val snapshot = fixture.copy(deviceId = pair.deviceId, accountBindingId = pair.accountBindingId, scopesRevision = pair.scopesRevision)
            assertIs<RemoteCall.Ok<Unit>>(device.putScopes(snapshot.scopes, snapshot.scopesRevision))
            assertTrue(assertIs<RemoteCall.Ok<RemoteSnapshotAck>>(device.putSnapshot(snapshot)).value.stored)
            println("REMOTE_PROBE_STEP: snapshot")
            val queued = owner("/api/v1/owner/tasks", HttpMethod.Post, buildJsonObject {
                put("operation", "query"); put("idempotencyKey", "probe_${System.nanoTime()}")
                put("arguments", buildJsonObject { put("sources", buildJsonArray { add(JsonPrimitive("courses")) }); put("freshness", "local") })
            })
            val leased = assertIs<RemoteCall.Ok<RemotePollResponse>>(device.poll()).value.task!!
            assertEquals(queued.getValue("requestId").jsonPrimitive.content, leased.requestId)
            assertIs<RemoteCall.Ok<Unit>>(device.renewLease(leased.requestId, leased.leaseId!!, 120))
            val result = RemoteTaskResult(leased.leaseId!!, RemoteTaskStatus.SUCCEEDED, buildJsonObject { put("fixtureVerified", true) })
            assertIs<RemoteCall.Ok<RemoteTaskResultAck>>(device.submitResult(leased.requestId, result))
            assertTrue(assertIs<RemoteCall.Ok<RemoteTaskResultAck>>(device.submitResult(leased.requestId, result)).value.duplicate)
            assertEquals("succeeded", owner("/api/v1/owner/tasks/${leased.requestId}").getValue("status").jsonPrimitive.content)
            println("REMOTE_PROBE_STEP: task_result")
            // 机器访问与插件 OAuth 是独立边界；OWNER_TOKEN 不可冒充平台 MCP 凭据。
            val mcp = http.post("$base/mcp") {
                header("OAI-Sites-Authorization", "Bearer $access")
                header("Authorization", "Bearer $ownerToken")
                setBody(TextContent("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"query_snapshot","arguments":{}}}""", ContentType.Application.Json))
            }
            assertEquals(401, mcp.status.value)
            owner("/api/v1/owner/devices/${pair.deviceId}/revoke", HttpMethod.Post)
            pairedDevice = null
            assertEquals(RemoteFailureKind.UNAUTHORIZED, assertIs<RemoteCall.Failure>(device.poll()).kind)
            println("REMOTE_PROBE_PASS: private_access,pairing,snapshot,lease,result,MCP_auth_boundary,revocation (fictional data)")
        } finally {
            pairedDevice?.let { owner("/api/v1/owner/devices/$it/revoke", HttpMethod.Post) }
            http.close()
        }
    }
}

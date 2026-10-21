package team.bjtuss.bjtuselfservice.shared.agent.remote

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

/** 与 Site 模板共用 fixtures：两端对同一份虚构样例的理解必须一致。 */
class RemoteProtocolContractTest {
    private val fixtures = File("../remote-site-template/fixtures")

    private fun fixture(name: String) = File(fixtures, name).readText()

    @Test
    fun snapshotFixtureRoundTripsWithoutLosingFields() {
        val text = fixture("snapshot-v2.json")
        val snapshot = remoteJson.decodeFromString(RemoteSnapshot.serializer(), text)
        assertEquals(REMOTE_SNAPSHOT_SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals(42, snapshot.sequence)
        assertEquals(1792530000, snapshot.sources.getValue("homework").fetchedAt)
        assertNull(snapshot.sources.getValue("citel").fetchedAt)
        assertTrue(snapshot.data.keys.all { it in snapshot.scopes }, "data 只含已授权范围")
        val encoded = remoteJson.encodeToJsonElement(RemoteSnapshot.serializer(), snapshot).jsonObject
        assertEquals(Json.parseToJsonElement(text).jsonObject.keys, encoded.keys)
    }

    @Test
    fun wireNamesMatchTheTemplate() {
        val protocol = fixture("../src/protocol.js")
        RefreshSource.entries.forEach { assertTrue("\"${it.wire}\"" in protocol, "Site 模板缺少来源 ${it.wire}") }
        RefreshStatus.entries.forEach { assertTrue("\"${it.wire}\"" in protocol, "Site 模板缺少状态 ${it.wire}") }
        RemoteScope.entries.forEach { assertTrue("\"${it.wire}\"" in protocol, "Site 模板缺少范围 ${it.wire}") }
        listOf(RemoteOperation.QUERY, RemoteOperation.REFRESH_ALL, RemoteOperation.CITEL_PROBLEM, RemoteOperation.MAIL_MESSAGE)
            .forEach { assertTrue("\"$it\"" in protocol) }
        assertTrue("PROTOCOL_VERSION = $REMOTE_PROTOCOL_VERSION;" in protocol)
        assertTrue("SNAPSHOT_SCHEMA_VERSION = $REMOTE_SNAPSHOT_SCHEMA_VERSION;" in protocol)
        assertEquals(setOf("schedule", "exams", "assignments"), RemoteScope.DEFAULT.map { it.wire }.toSet())
    }

    @Test
    fun pairRequestAndTaskResultFixturesDecode() {
        val pair = remoteJson.decodeFromString(RemotePairRequest.serializer(), fixture("pair-request-v1.json"))
        assertEquals(REMOTE_PROTOCOL_VERSION, pair.protocolVersion)
        assertEquals(RemoteScope.DEFAULT.map { it.wire }, pair.scopes)
        val result = remoteJson.decodeFromString(RemoteTaskResult.serializer(), fixture("task-result-v1.json"))
        assertEquals(RemoteTaskStatus.PARTIAL, result.status)
        assertNull(result.error)
        assertTrue("refresh" in result.result)
    }

    @Test
    fun pollResponseToleratesNewFieldsAndEmptyTask() {
        val poll = remoteJson.decodeFromString(RemotePollResponse.serializer(),
            """{"protocolVersion":1,"serverTime":5,"scopesRevision":2,"task":null,"futureField":true}""")
        assertNull(poll.task)
        val task = remoteJson.decodeFromString(RemotePollResponse.serializer(),
            """{"protocolVersion":1,"serverTime":5,"task":{"requestId":"req_x","deviceId":"dev_x","accountBindingId":"bind_x",
               "operation":"refresh_all","arguments":{},"createdAt":1,"expiresAt":2,"status":"running","attempt":1,
               "leaseId":"lease_x","leaseUntil":3,"result":null,"resultCleared":false}}""").task!!
        assertEquals("lease_x", task.leaseId)
        assertEquals(JsonObject(emptyMap()), task.arguments)
    }

    @Test
    fun pairResponseNeverPrintsTheToken() {
        val response = RemotePairResponse(1, "dev_x", "bjtud_secretsecretsecret", "bind_x", listOf("schedule"))
        assertFalse("bjtud_secret" in response.toString())
    }
}

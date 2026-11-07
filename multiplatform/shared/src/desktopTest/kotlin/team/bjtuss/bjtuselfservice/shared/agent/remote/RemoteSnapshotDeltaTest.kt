package team.bjtuss.bjtuselfservice.shared.agent.remote

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import team.bjtuss.bjtuselfservice.shared.agent.AgentAssignmentDetail
import team.bjtuss.bjtuselfservice.shared.agent.AgentAttachmentInfo
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openDesktopCacheStore
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

private fun item(id: String, title: String) = buildJsonObject { put("id", id); put("title", title) }
private fun section(vararg items: JsonObject, sources: String = "a") = buildJsonObject {
    put("sources", sources); put("items", JsonArray(items.toList()))
}
private fun snapshot(data: Map<String, kotlinx.serialization.json.JsonElement>, sequence: Long = 1, scopes: List<String> = data.keys.toList()) =
    RemoteSnapshot(deviceId = "dev_testDevice01", accountBindingId = "bind_testBinding01", snapshotId = "snap_$sequence", sequence = sequence,
        generatedAt = sequence, uploadedAt = sequence, scopes = scopes, sources = emptyMap(), data = JsonObject(data))

class RemoteSnapshotDiffTest {
    @Test
    fun unchangedSnapshotProducesEmptyPatch() {
        val data = mapOf("assignments" to section(item("a", "一")))
        val patch = diffRemoteSnapshot(snapshot(data), snapshot(data, 2))!!
        assertTrue(patch.set.isEmpty() && patch.items.isEmpty() && patch.remove.isEmpty())
        assertEquals("snap_1", patch.baseSnapshotId)
        assertEquals(1, patch.baseSequence)
    }

    @Test
    fun changedItemsAreSentByIdAndOrderKeepsDeletions() {
        val base = snapshot(mapOf("assignments" to section(item("a", "一"), item("b", "二"), item("c", "三"))))
        val next = snapshot(mapOf("assignments" to section(item("d", "四"), item("a", "一改"), item("c", "三"))), 2)
        val change = diffRemoteSnapshot(base, next)!!.items.getValue("assignments")
        assertEquals(listOf("d", "a"), change.upsert.map { (it as JsonObject)["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("d", "a", "c"), change.order)
    }

    @Test
    fun sectionIsReplacedWholeWhenMoreThanItemsChanged() {
        val base = snapshot(mapOf("assignments" to section(item("a", "一"))))
        val next = snapshot(mapOf("assignments" to section(item("a", "一"), sources = "b")), 2)
        val patch = diffRemoteSnapshot(base, next)!!
        assertTrue(patch.items.isEmpty())
        assertNotNull(patch.set["assignments"])
    }

    @Test
    fun sectionWithoutIdsIsReplacedWhole() {
        val noIds = buildJsonObject { put("items", JsonArray(listOf(JsonPrimitive(1)))) }
        val patch = diffRemoteSnapshot(snapshot(mapOf("exams" to JsonObject(emptyMap()))), snapshot(mapOf("exams" to noIds), 2))!!
        assertEquals(noIds, patch.set["exams"])
    }

    @Test
    fun changedScopesOrBindingNeedFullUpload() {
        val a = snapshot(mapOf("exams" to JsonObject(emptyMap())))
        assertNull(diffRemoteSnapshot(a, snapshot(mapOf("mail" to JsonObject(emptyMap())), 2)))
        assertNull(diffRemoteSnapshot(a, a.copy(accountBindingId = "bind_other000001")))
    }

    @Test
    fun patchFixtureSharedWithSiteDecodes() {
        val patch = remoteJson.decodeFromString(RemoteSnapshotPatch.serializer(), File("../remote-site-template/fixtures/snapshot-patch-v1.json").readText())
        assertEquals("snap_fixture00001", patch.baseSnapshotId)
        assertEquals(listOf("phyvlab:3", "phyvlab:1"), patch.items.getValue("assignments").order)
    }

    @Test
    fun attachmentPlanUploadsOnlySmallPendingOnes() {
        val big = AgentAttachmentInfo("big", "大.zip", 6L * 1024 * 1024)
        val small = AgentAttachmentInfo("small", "小.pdf", 1024)
        val unknown = AgentAttachmentInfo("unknown", "未知.png")
        val details = mapOf(
            "phyvlab:1" to AgentAssignmentDetail("phyvlab:1", attachments = listOf(big, small, unknown), fetchedAt = 1),
            "phyvlab:2" to AgentAssignmentDetail("phyvlab:2", attachments = listOf(small), fetchedAt = 1),
            "phyvlab:3" to AgentAssignmentDetail("phyvlab:3", attachments = listOf(small), fetchedAt = 1),
        )
        val plan = buildRemoteAttachmentPlan(details, setOf("phyvlab:1", "phyvlab:2"), setOf("phyvlab:1"))
        assertEquals(listOf("small", "unknown"), plan.upload.map { it.attachmentId })
        assertEquals(4, plan.keep.size, "已交作业的附件保留，消失的作业不保留")
        assertTrue(remoteAttachmentRef("phyvlab:3", small).key !in plan.keep)
    }

    @Test
    fun assignmentIdArgumentIsWhitelisted() {
        fun args(value: String) = JsonObject(mapOf("assignmentId" to JsonPrimitive(value)))
        assertEquals("phyvlab:12", parseRemoteAssignmentId(args("phyvlab:12")))
        listOf("phyvlab:0", "shell:1", "phyvlab:1;rm", "").forEach { bad ->
            assertTrue(runCatching { parseRemoteAssignmentId(args(bad)) }.isFailure, bad)
        }
    }
}

private class DeltaClient(private val inner: FakeSiteClient = FakeSiteClient()) : RemoteSiteClient by inner {
    val fulls get() = inner.snapshots
    val patches = CopyOnWriteArrayList<RemoteSnapshotPatch>()
    var patchResult: RemoteCall.Failure? = null
    val uploads = CopyOnWriteArrayList<RemoteAttachmentMeta>()
    val chunks = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    val deleted = CopyOnWriteArrayList<String>()
    var listed = listOf<RemoteAttachmentEntry>()

    override suspend fun patchSnapshot(patch: RemoteSnapshotPatch): RemoteCall<RemoteSnapshotAck> {
        patchResult?.let { return it }
        patches += patch
        return RemoteCall.Ok(RemoteSnapshotAck(patch.snapshotId, patch.sequence, stored = true, duplicate = false))
    }
    override suspend fun listAttachments() = RemoteCall.Ok(RemoteAttachmentList(listed))
    override suspend fun putAttachment(meta: RemoteAttachmentMeta): RemoteCall<Unit> { uploads += meta; return RemoteCall.Ok(Unit) }
    override suspend fun putAttachmentChunk(key: String, index: Int, bytes: ByteArray): RemoteCall<Unit> { chunks += key to bytes; return RemoteCall.Ok(Unit) }
    override suspend fun deleteAttachment(key: String): RemoteCall<Unit> { deleted += key; return RemoteCall.Ok(Unit) }
}

class RemoteSnapshotDeltaUploadTest {
    private val directory: File = Files.createTempDirectory("remote-delta").toFile()
    private val cache: team.bjtuss.bjtuselfservice.shared.cache.CacheStore = openDesktopCacheStore(directory, JvmAesCacheValueProtector(ByteArray(32) { it.toByte() })).store
    private val store = RemoteAgentStore(cache)
    private val client = DeltaClient()
    private var plan: RemoteAttachmentPlan? = null
    private val files = mutableMapOf<String, ByteArray>()
    private val prepared = mutableListOf<Set<RemoteScope>>()
    private val executor = object : RemoteRefreshExecutor by FakeExecutor() {
        override suspend fun prepareAssignmentDetails(scopes: Set<RemoteScope>) { prepared += scopes }
        override suspend fun attachmentPlan(scopes: Set<RemoteScope>) = plan
        override suspend fun readAttachment(ref: RemoteAttachmentRef) = files[ref.attachmentId]?.let { HomeworkFileContent("a.pdf", "application/pdf", it) }
    }
    private var now = 1_800_000_000L
    private val config = MutableStateFlow(RemoteAgentConfig(enabled = true, siteUrl = "https://site.example", deviceId = "dev_testDevice01",
        accountBindingId = "bind_testBinding01", accountScope = "20990001", consentAtEpochSeconds = 1))
    private val runner = RemoteAgentRunner(config, { client }, executor, store, { now }, Random(7), secondMillis = 10)

    @AfterTest
    fun cleanUp() {
        cache.close()
        directory.deleteRecursively()
    }

    private suspend fun cycle() {
        assertIs<RemoteUploadOutcome.Uploaded>(runner.runUploadCycle(refreshFirst = true, remote = false))
        now += config.value.uploadIntervalSeconds + 1
    }

    @Test
    fun firstUploadIsFullThenDiffsAgainstOwnBaseline() = runBlocking {
        cycle()
        assertEquals(1, client.fulls.size)
        assertNull(client.patches.firstOrNull())
        val first = store.loadBaseline()!!
        cycle()
        assertEquals(1, client.fulls.size, "第二次不传全量")
        assertEquals(first.snapshotId, client.patches.single().baseSnapshotId)
        assertEquals(first.sequence, client.patches.single().baseSequence)
        assertEquals(client.patches.single().snapshotId, store.loadBaseline()!!.snapshotId, "确认后基线前移")
    }

    @Test
    fun baseMismatchFallsBackToFullUpload() = runBlocking {
        cycle()
        client.patchResult = RemoteCall.Failure(RemoteFailureKind.CONFLICT, "base_mismatch")
        cycle()
        assertEquals(2, client.fulls.size)
        client.patchResult = null
        cycle()
        assertEquals(client.fulls.last().snapshotId, client.patches.single().baseSnapshotId, "全量之后以它为新基线")
    }

    @Test
    fun oldTemplateWithoutPatchEndpointKeepsWorking() = runBlocking {
        cycle()
        client.patchResult = RemoteCall.Failure(RemoteFailureKind.CLIENT_ERROR, "not_found")
        cycle()
        assertEquals(2, client.fulls.size)
    }

    @Test
    fun networkFailureDoesNotFallBackOrMoveBaseline() = runBlocking {
        cycle()
        val base = store.loadBaseline()!!
        client.patchResult = RemoteCall.Failure(RemoteFailureKind.NETWORK)
        assertIs<RemoteUploadOutcome.Failed>(runner.runUploadCycle(refreshFirst = true, remote = false))
        assertEquals(1, client.fulls.size)
        assertEquals(base.snapshotId, store.loadBaseline()!!.snapshotId)
    }

    @Test
    fun resetBindingForgetsBaseline() = runBlocking {
        cycle()
        store.resetBinding()
        assertNull(store.loadBaseline())
    }

    @Test
    fun detailsArePreparedBeforeSnapshot() = runBlocking {
        cycle()
        assertEquals(1, prepared.size)
    }

    @Test
    fun attachmentsAreIncrementalAndChunked() = runBlocking {
        val new = RemoteAttachmentRef("att_newnewnew01", "phyvlab:1", "new", null)
        val done = RemoteAttachmentRef("att_donedone001", "phyvlab:1", "done", 3)
        val partial = RemoteAttachmentRef("att_partial0001", "phyvlab:1", "partial", 3)
        files["new"] = ByteArray(REMOTE_ATTACHMENT_CHUNK_BYTES + 5) { 1 }
        files["partial"] = byteArrayOf(1, 2, 3)
        plan = RemoteAttachmentPlan(listOf(new, done, partial), setOf(new.key, done.key, partial.key))
        client.listed = listOf(
            RemoteAttachmentEntry(done.key, 3, complete = true),
            RemoteAttachmentEntry(partial.key, 3, complete = false),
            RemoteAttachmentEntry("att_goneassign1", 9, complete = true),
        )
        cycle()
        assertEquals(setOf(new.key, partial.key), client.uploads.map { it.key }.toSet(), "已完整的跳过，半截的重传")
        assertEquals(listOf("att_goneassign1"), client.deleted.toList())
        val newChunks = client.chunks.filter { it.first == new.key }.map { it.second.size }
        assertEquals(listOf(REMOTE_ATTACHMENT_CHUNK_BYTES, 5), newChunks)
        assertEquals(2, client.uploads.first { it.key == new.key }.chunkCount)
    }

    @Test
    fun oversizedAttachmentIsNotUploaded() = runBlocking {
        plan = RemoteAttachmentPlan(listOf(RemoteAttachmentRef("att_toobig00001", "phyvlab:1", "missing", null)), emptySet())
        cycle()
        assertTrue(client.uploads.isEmpty())
    }

    @Test
    fun attachmentFailureDoesNotFailTheUpload(): Unit = runBlocking {
        plan = RemoteAttachmentPlan(listOf(RemoteAttachmentRef("att_boomboom001", "phyvlab:1", "boom", null)), emptySet())
        val failing = object : RemoteRefreshExecutor by executor {
            override suspend fun readAttachment(ref: RemoteAttachmentRef): HomeworkFileContent? = throw IllegalStateException("x")
        }
        val other = RemoteAgentRunner(config, { client }, failing, store, { now }, Random(7), secondMillis = 10)
        assertIs<RemoteUploadOutcome.Uploaded>(other.runUploadCycle(refreshFirst = true, remote = false))
    }
}

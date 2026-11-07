package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 增量快照：只描述本设备这份快照相对“本设备上次被 Site 确认的快照”的变化。
 * 基线按设备保存，Site 只合并到这台设备自己那一行，不跟踪、不比较其他设备的数据。
 * 改字段时同时改 `remote-site-template/src/core.js` 的 `applySnapshotPatch`。
 */
@Serializable
data class RemoteItemsPatch(
    val upsert: List<JsonElement> = emptyList(),
    /** 合并后的完整 id 顺序；不在其中的旧项即被删除。 */
    val order: List<String>,
)

@Serializable
data class RemoteSnapshotPatch(
    val schemaVersion: Int = REMOTE_SNAPSHOT_SCHEMA_VERSION,
    val deviceId: String,
    val accountBindingId: String,
    val snapshotId: String,
    val sequence: Long,
    /** Site 上这台设备的当前快照必须正好是它，否则回 base_mismatch，设备改传全量。 */
    val baseSnapshotId: String,
    val baseSequence: Long,
    val generatedAt: Long,
    val uploadedAt: Long,
    val scopes: List<String>,
    val sources: Map<String, RemoteSourceState>,
    val scopesRevision: Long,
    /** 整段替换的数据范围。 */
    val set: Map<String, JsonElement> = emptyMap(),
    val remove: List<String> = emptyList(),
    /** 只有 items 变化的数据范围：按 id 增量。 */
    val items: Map<String, RemoteItemsPatch> = emptyMap(),
)

private fun JsonElement.itemId(): String? = ((this as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun identifiedItems(section: JsonElement?): Map<String, JsonElement>? {
    val items = ((section as? JsonObject)?.get("items") as? JsonArray) ?: return null
    val byId = items.associateBy { it.itemId() ?: return null }
    return byId.takeIf { it.size == items.size }
}

/** 基线不可用（范围变了、换了绑定）时返回 null，调用方传全量。 */
fun diffRemoteSnapshot(base: RemoteSnapshot, next: RemoteSnapshot): RemoteSnapshotPatch? {
    if (base.deviceId != next.deviceId || base.accountBindingId != next.accountBindingId ||
        base.schemaVersion != next.schemaVersion || base.scopes.toSet() != next.scopes.toSet()
    ) return null
    val set = mutableMapOf<String, JsonElement>()
    val items = mutableMapOf<String, RemoteItemsPatch>()
    for ((key, value) in next.data) {
        val previous = base.data[key]
        if (previous == value) continue
        val oldItems = identifiedItems(previous)
        val newItems = identifiedItems(value)
        val sameOutside = oldItems != null && newItems != null &&
            JsonObject((previous as JsonObject) - "items") == JsonObject((value as JsonObject) - "items")
        if (sameOutside) {
            items[key] = RemoteItemsPatch(
                upsert = newItems!!.filter { (id, item) -> oldItems!![id] != item }.values.toList(),
                order = newItems.keys.toList(),
            )
        } else {
            set[key] = value
        }
    }
    return RemoteSnapshotPatch(
        deviceId = next.deviceId, accountBindingId = next.accountBindingId, snapshotId = next.snapshotId,
        sequence = next.sequence, baseSnapshotId = base.snapshotId, baseSequence = base.sequence,
        generatedAt = next.generatedAt, uploadedAt = next.uploadedAt, scopes = next.scopes, sources = next.sources,
        scopesRevision = next.scopesRevision, set = set, remove = (base.data.keys - next.data.keys).toList(), items = items,
    )
}

package team.bjtuss.bjtuselfservice.shared.cleanup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault

/** Only deletion ownership and progress. Passwords, cookies and remote tokens never enter this record. */
@Serializable
data class CleanupInventory(
    val accounts: Set<String> = emptySet(),
    val playbackTaskIds: Set<String> = emptySet(),
    val playbackTemporaryDestinations: Set<String> = emptySet(),
    val ownsRemote: Boolean = false,
    val remoteBindingId: String? = null,
) {
    override fun toString() = "CleanupInventory(<redacted>)"
}

internal class CleanupContext(var inventory: CleanupInventory = CleanupInventory(), val recovering: Boolean = false)

@Serializable
data class CleanupRecord(
    val id: String,
    val request: CleanupRequest,
    val inventory: CleanupInventory,
    val items: List<CleanupItem> = emptyList(),
) {
    override fun toString() = "CleanupRecord(operation=${request.operation}, <redacted>)"
}

interface CleanupJournal {
    suspend fun load(): List<CleanupRecord>
    suspend fun save(records: List<CleanupRecord>)
}

class MemoryCleanupJournal : CleanupJournal {
    private var records = emptyList<CleanupRecord>()
    override suspend fun load() = records
    override suspend fun save(records: List<CleanupRecord>) { this.records = records.toList() }
}

/** A dedicated system-vault slot survives both the business database wipe and service-vault cleanup. */
class VaultCleanupJournal(private val vault: CredentialVault?) : CleanupJournal {
    @Serializable private data class Document(val version: Int = 1, val records: List<CleanupRecord>)
    private val json = Json { encodeDefaults = true }

    override suspend fun load(): List<CleanupRecord> {
        val slot = vault ?: return emptyList()
        val payload = slot.load()?.password ?: return emptyList()
        val document = json.decodeFromString<Document>(payload)
        require(document.version == 1)
        require(document.records.map { it.id }.distinct().size == document.records.size)
        document.records.forEach { record ->
            require(record.id.isNotBlank() && record.request.accountScope.isNotBlank())
            require(record.items.map { it.step }.distinct().size == record.items.size)
            require(record.items.all { it.step in AccountCleanupCoordinator.steps(record.request.operation) })
            require(record.items.none { it.status == CleanupStatus.SKIPPED && it.step != CleanupStep.SCHOOL_SESSION })
            require(record.inventory.playbackTaskIds.all { it.matches(Regex("t[0-9]+-[0-9]+")) })
        }
        return document.records
    }

    override suspend fun save(records: List<CleanupRecord>) {
        val slot = checkNotNull(vault) { "Cleanup journal unavailable" }
        if (records.isEmpty()) slot.clear()
        else slot.save(Credentials("cleanup-journal-v1", json.encodeToString(Document(records = records))))
    }
}

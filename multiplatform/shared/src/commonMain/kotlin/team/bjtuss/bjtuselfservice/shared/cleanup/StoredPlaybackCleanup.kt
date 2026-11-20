package team.bjtuss.bjtuselfservice.shared.cleanup

import team.bjtuss.bjtuselfservice.shared.feature.courseware.*
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult
import team.bjtuss.bjtuselfservice.shared.media.MediaWorkDirectory

/** Removes an inactive application-owned store. Export destinations are never inferred from paths. */
suspend fun clearStoredPlaybackData(store: PlaybackTaskStore, restore: suspend (String, String) -> StreamingDestinationResult): Boolean {
    val records = try { store.load() } catch (_: Exception) { return false }
    val failed = mutableListOf<StoredPlaybackTask>()
    for (record in records) {
        var ok = record.task.id.matches(Regex("[A-Za-z0-9_-]{1,160}"))
        val request = record.request
        if (ok && record.task.phase != PlaybackTaskPhase.DONE && request != null) {
            for ((index, token) in request.destinations.withIndex()) {
                if (request.committed.getOrNull(index) == true) continue
                if (token == null) { ok = false; continue }
                try {
                    val opened = restore(token, record.task.outputs.getOrNull(index).orEmpty())
                    if (opened !is StreamingDestinationResult.Opened) ok = false
                    else if (!opened.destination.recoveredOutputMayBeCommitted && !opened.destination.isRecoveryCommitAmbiguous()) opened.destination.abortForCleanup()
                } catch (_: Exception) { ok = false }
            }
        }
        if (ok && runCatching { MediaWorkDirectory.forTask(record.task.id, store.workRoot).deleteAll() }.isFailure) ok = false
        if (!ok) failed += record
    }
    return try {
        store.save(failed)
        if (failed.isEmpty()) MediaWorkDirectory.purgeStale(workRoot = store.workRoot)
        failed.isEmpty()
    } catch (_: Exception) { false }
}

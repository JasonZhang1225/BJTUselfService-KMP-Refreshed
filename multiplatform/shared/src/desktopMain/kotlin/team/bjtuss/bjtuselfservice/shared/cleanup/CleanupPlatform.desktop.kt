package team.bjtuss.bjtuselfservice.shared.cleanup

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import team.bjtuss.bjtuselfservice.shared.media.deleteWorkTree
import team.bjtuss.bjtuselfservice.shared.feature.settings.platformLocalDataCleaner

actual fun platformCleanup(): CleanupPlatform = object : CleanupPlatform {
    override suspend fun clearPlaybackTemporaryDestinations(tokens: Set<String>): Boolean = withContext(Dispatchers.IO) {
        tokens.map { token -> runCatching { clearDesktopPlaybackTemporaryDestination(token) }.isSuccess }.all { it }
    }
    override suspend fun clearPresentation(allLocalData: Boolean) = true // No application-owned desktop widget or Live Activity.
    override suspend fun clearManagedTemporaryFiles(): Boolean = withContext(Dispatchers.IO) {
        runCatching { deleteWorkTree(Path(File(System.getProperty("java.io.tmpdir"), "BJTUselfService-preview").absolutePath)) }.isSuccess
    }
    override suspend fun clearDeviceSettings(removeAgent: Boolean): Boolean =
        platformLocalDataCleaner()?.clearSettings(removeAgent) ?: !removeAgent
}

/** Opaque ownership token for an application's staging file, never permission to delete the export. */
fun desktopPlaybackTemporaryToken(target: File, temporary: java.nio.file.Path): String {
    fun encode(path: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
    return "desktop-temp-v1:${encode(target.canonicalPath)}:${encode(temporary.toFile().canonicalPath)}"
}

internal fun clearDesktopPlaybackTemporaryDestination(token: String) {
    val parts = token.split(':')
    require(parts.size == 3 && parts[0] == "desktop-temp-v1")
    fun decode(value: String) = java.nio.file.Path.of(String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8))
    val target = decode(parts[1]); val temporary = decode(parts[2])
    require(target.isAbsolute && temporary.isAbsolute && target.normalize() == target && temporary.normalize() == temporary)
    require(target.parent == temporary.parent && temporary != target)
    require(temporary.fileName.toString().startsWith(".bjtu-save-") && temporary.fileName.toString().endsWith(".tmp"))
    require(temporary.parent.toRealPath() == temporary.parent)
    java.nio.file.Files.deleteIfExists(temporary)
}

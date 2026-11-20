package team.bjtuss.bjtuselfservice.shared.cleanup

/** System operations only. The coordinator decides whether/when to invoke them. */
interface CleanupPlatform {
    suspend fun clearPlaybackTemporaryDestinations(tokens: Set<String>): Boolean = tokens.isEmpty()
    suspend fun clearPresentation(allLocalData: Boolean): Boolean
    suspend fun clearManagedTemporaryFiles(): Boolean
    suspend fun clearDeviceSettings(removeAgent: Boolean): Boolean
}

expect fun platformCleanup(): CleanupPlatform

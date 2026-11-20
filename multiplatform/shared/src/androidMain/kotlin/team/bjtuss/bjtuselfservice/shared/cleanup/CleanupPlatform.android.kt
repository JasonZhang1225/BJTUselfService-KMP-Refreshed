package team.bjtuss.bjtuselfservice.shared.cleanup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import team.bjtuss.bjtuselfservice.shared.feature.home.clearAndroidLiveActivityData
import team.bjtuss.bjtuselfservice.shared.feature.widget.androidWidgetCleanupContext
import team.bjtuss.bjtuselfservice.shared.feature.widget.androidCourseWidgetSnapshotFile
import team.bjtuss.bjtuselfservice.shared.feature.widget.writeCourseWidgetSnapshot
import team.bjtuss.bjtuselfservice.shared.media.deleteWorkTree
import java.io.File

actual fun platformCleanup(): CleanupPlatform = object : CleanupPlatform {
    override suspend fun clearPresentation(allLocalData: Boolean): Boolean = withContext(Dispatchers.Main.immediate) {
        val context = androidWidgetCleanupContext() ?: return@withContext false
        writeCourseWidgetSnapshot(null)
        val live = clearAndroidLiveActivityData(allLocalData)
        live && !androidCourseWidgetSnapshotFile(context).exists()
    }
    override suspend fun clearManagedTemporaryFiles(): Boolean = withContext(Dispatchers.IO) {
        val context = androidWidgetCleanupContext() ?: return@withContext false
        runCatching { deleteWorkTree(Path(File(context.cacheDir, "previews").absolutePath)) }.isSuccess
    }
    override suspend fun clearDeviceSettings(removeAgent: Boolean) = true
}

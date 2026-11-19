package team.bjtuss.bjtuselfservice.shared.cleanup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSFileManager
import team.bjtuss.bjtuselfservice.shared.feature.widget.clearIosCourseWidgetSnapshot

private var cleanupHandler: ((String, (String) -> Unit) -> Unit)? = null
internal fun installIosCleanupHandler(handler: (String, (String) -> Unit) -> Unit) { cleanupHandler = handler }

actual fun platformCleanup(): CleanupPlatform = object : CleanupPlatform {
    override suspend fun clearPresentation(allLocalData: Boolean): Boolean = withContext(Dispatchers.Main.immediate) {
        val widget = clearIosCourseWidgetSnapshot()
        val handler = cleanupHandler ?: return@withContext false
        val live = suspendCancellableCoroutine<Boolean> { continuation ->
            handler(if (allLocalData) "all" else "account") { result ->
                if (continuation.isActive) continuation.resume(result == "ok")
            }
        }
        widget && live
    }
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    override suspend fun clearManagedTemporaryFiles(): Boolean = withContext(Dispatchers.Default) {
        val root = NSTemporaryDirectory().trimEnd('/')
        val manager = NSFileManager.defaultManager
        val children = manager.contentsOfDirectoryAtPath(root, null)?.filterIsInstance<String>() ?: return@withContext false
        var ok = true
        for (name in children.filter { it.startsWith("bjtu-preview-") || it.startsWith("bjtu-homework-") }) {
            if (!manager.removeItemAtPath("$root/$name", null)) ok = false
        }
        ok
    }
    override suspend fun clearDeviceSettings(removeAgent: Boolean) = true
}

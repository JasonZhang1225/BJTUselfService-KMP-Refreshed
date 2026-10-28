package team.bjtuss.bjtuselfservice.shared.feature.courseware

import java.awt.Desktop
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.currentPlatform

internal actual fun playbackSavedFileActions(): PlaybackSavedFileActions? =
    if (currentPlatform().isWindows) DesktopPlaybackSavedFileActions() else null

internal class DesktopPlaybackSavedFileActions(
    private val openFile: (File) -> Unit = { file ->
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
        Desktop.getDesktop().open(file)
    },
) : PlaybackSavedFileActions {
    override suspend fun open(path: String, folder: Boolean): String? = withContext(Dispatchers.IO) {
        try {
            val video = File(path)
            if (!video.isAbsolute || !video.extension.equals("mp4", ignoreCase = true)) {
                return@withContext "保存路径无效，无法打开视频。"
            }
            val target = if (folder) video.parentFile else video
            if (target == null || (if (folder) !target.isDirectory else !target.isFile)) {
                return@withContext if (folder) "保存文件夹不存在，可能已被移动或删除。" else "视频文件不存在，可能已被移动或删除。"
            }
            openFile(target)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (folder) "无法打开文件夹，请检查访问权限。" else "无法打开视频，请检查访问权限和默认播放器。"
        }
    }
}

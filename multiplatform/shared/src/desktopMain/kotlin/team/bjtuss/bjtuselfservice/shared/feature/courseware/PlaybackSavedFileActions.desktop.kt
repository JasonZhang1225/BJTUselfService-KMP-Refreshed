package team.bjtuss.bjtuselfservice.shared.feature.courseware

import java.awt.Desktop
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.currentPlatform

internal actual fun playbackSavedFileActions(): PlaybackSavedFileActions? =
    DesktopPlaybackSavedFileActions()

/**
 * 桌面端打开已保存录像：Windows 走 [Desktop.open]；macOS 用 `open` / `open -R` 定位到 Finder。
 */
internal class DesktopPlaybackSavedFileActions(
    private val openVideo: (File) -> Unit = ::defaultOpenVideo,
    private val revealVideo: (File) -> Unit = ::defaultRevealVideo,
    private val openDirectory: (File) -> Unit = ::defaultOpenDirectory,
) : PlaybackSavedFileActions {
    override suspend fun open(path: String, folder: Boolean): String? = withContext(Dispatchers.IO) {
        try {
            val video = File(path)
            if (!video.isAbsolute || !video.extension.equals("mp4", ignoreCase = true)) {
                return@withContext "保存路径无效，无法打开视频。"
            }
            if (folder) {
                val parent = video.parentFile
                if (parent == null || !parent.isDirectory) {
                    return@withContext "保存文件夹不存在，可能已被移动或删除。"
                }
                // 视频还在时优先选中文件；已删则仍打开原文件夹。
                if (video.isFile) revealVideo(video) else openDirectory(parent)
            } else {
                if (!video.isFile) {
                    return@withContext "视频文件不存在，可能已被移动或删除。"
                }
                openVideo(video)
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (folder) "无法打开文件夹，请检查访问权限。" else "无法打开视频，请检查访问权限和默认播放器。"
        }
    }
}

private fun defaultOpenVideo(file: File) {
    if (currentPlatform().isWindows) {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
        Desktop.getDesktop().open(file)
    } else {
        runOpenProcess("open", file.absolutePath)
    }
}

private fun defaultRevealVideo(file: File) {
    if (currentPlatform().isWindows) {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
        Desktop.getDesktop().open(checkNotNull(file.parentFile))
    } else {
        runOpenProcess("open", "-R", file.absolutePath)
    }
}

private fun defaultOpenDirectory(directory: File) {
    if (currentPlatform().isWindows) {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
        Desktop.getDesktop().open(directory)
    } else {
        runOpenProcess("open", directory.absolutePath)
    }
}

private fun runOpenProcess(vararg command: String) {
    val code = ProcessBuilder(*command).start().waitFor()
    check(code == 0) { "open exited $code" }
}

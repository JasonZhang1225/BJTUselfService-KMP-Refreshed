package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality

/**
 * Windows：调用随 App 打包的合成小程序 `BJTUPlaybackCompositor.exe`
 * （Media Foundation 解码 → 等比缩放 → 左右并排 → H.264 编码 → MP4）。
 *
 * 小程序以资源形式放在 jar 里（`native/BJTUPlaybackCompositor.exe`），首次使用时释放到
 * `%LOCALAPPDATA%\BJTUselfServiceKMP\tools\`；没编译过（打包机没有 C++ 工具链）时
 * [isAvailable] 为 false，任务改为分别保存两路视频。
 */
internal object WindowsPlaybackCompositor : ProgressiveVideoCompositor {

    override val isAvailable: Boolean get() = locateHelper() != null
    override val retainsEncodedSegmentsOnInterrupt: Boolean get() = false

    override suspend fun openProgressive(work: MediaWorkDirectory, durationSeconds: Double,
        quality: PlaybackCompositeQuality): ProgressiveComposition = withContext(Dispatchers.IO) {
        WindowsProgressiveComposition(work, durationSeconds, quality)
    }

    override suspend fun composeSideBySide(
        leftPath: String,
        rightPath: String,
        outputPath: String,
        quality: PlaybackCompositeQuality,
        onProgress: (Float) -> Unit,
        onStatus: (String?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val helper = locateHelper() ?: throw CompositionException("缺少合成组件")
        val process = ProcessBuilder(
            helper.absolutePath,
            "compose",
            leftPath,
            rightPath,
            outputPath,
            quality.width.toString(),
            quality.height.toString(),
            quality.bitRate.toString(),
            "--parent-pid",
            ProcessHandle.current().pid().toString(),
        )
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        try {
            val result = awaitPlaybackHelper(process, onProgress, onStatus) { device ->
                onStatus(device?.let { "合成设备：$it" })
            }
            if (result.code != 0) {
                val reason = result.diagnostic.ifBlank { "退出码 ${result.code}" }
                File(outputPath).delete()
                throw CompositionException(reason)
            }
        } catch (error: CancellationException) {
            process.destroyForcibly()
            File(outputPath).delete()
            throw error
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** 允许用系统属性指定小程序路径，便于本地排查。 */
private const val HELPER_PROPERTY = "bjtu.playback.compositor.helper"

private const val HELPER_RESOURCE = "/native/BJTUPlaybackCompositor.exe"

/** 合成小程序所在的工具目录（MSI 卸载时会连带清掉 `%LOCALAPPDATA%\BJTUselfServiceKMP`）。 */
internal fun windowsHelperDirectory(): File {
    val root = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
    return File(root, "BJTUselfServiceKMP/tools")
}

internal fun locateHelper(): File? {
    System.getProperty(HELPER_PROPERTY)
        ?.let(::File)
        ?.takeIf { it.isFile }
        ?.let { return it }
    return extractHelper()
}

/**
 * 把 jar 里的合成小程序释放到 `%LOCALAPPDATA%\BJTUselfServiceKMP\tools\`。
 *
 * 文件名带内容摘要：版本变化时不会覆盖正在运行的旧文件，也不用反复释放。
 */
@Synchronized
internal fun extractHelper(): File? {
    val bytes = WindowsPlaybackCompositor::class.java.getResourceAsStream(HELPER_RESOURCE)
        ?.use { it.readBytes() }
        ?: return null
    return writeHelperFile(windowsHelperDirectory(), bytes)
}

/**
 * 把小程序字节写成可执行文件；文件名带内容摘要。
 *
 * 摘要来自文件内容：同一个版本反复调用不会重复写盘（正在运行的那份也不会被覆盖）。
 */
internal fun writeHelperFile(directory: File, bytes: ByteArray): File? {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val tag = digest.take(4).joinToString("") { "%02x".format(it) }
    if (!directory.isDirectory && !directory.mkdirs()) return null
    val target = File(directory, "BJTUPlaybackCompositor-$tag.exe")
    if (target.isFile && target.length() == bytes.size.toLong()) return target
    val temporary = File(directory, "BJTUPlaybackCompositor-$tag.exe.tmp")
    try {
        temporary.writeBytes(bytes)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } catch (error: IOException) {
        temporary.delete()
        return null
    }
    // 顺手清掉旧版本，目录里只留当前这一个。
    directory.listFiles()
        ?.filter { it.name.startsWith("BJTUPlaybackCompositor-") && it.name != target.name }
        ?.forEach { it.delete() }
    return target
}

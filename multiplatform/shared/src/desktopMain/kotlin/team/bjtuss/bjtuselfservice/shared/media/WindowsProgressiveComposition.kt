package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality

/** 两路 TS 同时落索引，独立原生进程在输入增长时解码、并排和编码。 */
internal class WindowsProgressiveComposition(
    work: MediaWorkDirectory,
    private val durationSeconds: Double,
    private val quality: PlaybackCompositeQuality,
) : ProgressiveComposition {
    private val root = File(work.path.toString()).canonicalFile
    private val output = File(root, "composite.mp4")
    private val partial = File(root, "composite.partial.mp4")
    private val completeMarker = File(root, "windows-composite-complete")
    override val isComplete: Boolean = runCatching {
        completeMarker.readText() == "v1:${quality.name}" && output.isFile && output.length() > 0
    }.getOrDefault(false)
    override val resumeSeconds: Double get() = if (isComplete) durationSeconds else 0.0
    private val sources: List<WindowsIndexedTsInput>
    private val directories: List<File>
    @Volatile private var process: Process? = null

    init {
        check(root.isDirectory || root.mkdirs())
        directories = List(2) { index ->
            File(root, "windows-input-$index").canonicalFile.also { dir ->
                check(dir.parentFile == root && dir.name == "windows-input-$index")
            }
        }
        if (isComplete) {
            sources = emptyList()
        } else {
            completeMarker.delete()
            output.delete()
            partial.delete()
            val opened = mutableListOf<WindowsIndexedTsInput>()
            try {
                directories.forEachIndexed { index, dir -> opened += WindowsIndexedTsInput(dir, needsAudio = index == 0) }
            } catch (error: Throwable) {
                opened.forEach { it.close() }
                throw error
            }
            sources = opened
        }
    }

    override suspend fun append(index: Int, bytes: ByteArray) = sources[index].append(bytes)
    override suspend fun finishInput(index: Int) = sources[index].finish()

    override suspend fun compose(onProgress: (Float) -> Unit, onStatus: (String?) -> Unit,
        onFinalizing: (Float) -> Unit, onDevice: (String?) -> Unit) = withContext(Dispatchers.IO) {
        if (isComplete) {
            onProgress(1f)
            onFinalizing(1f)
            return@withContext
        }
        onStatus("等待两路录像的首批数据")
        while (!sources.all { it.videoReady } || !sources[0].audioReady) {
            coroutineContext.ensureActive()
            delay(50)
        }
        val helper = locateHelper() ?: throw CompositionException("缺少合成组件")
        val child = ProcessBuilder(helper.absolutePath, "compose-indexed", directories[0].absolutePath,
            directories[1].absolutePath, partial.absolutePath, quality.width.toString(), quality.height.toString(),
            quality.bitRate.toString(), "--duration", durationSeconds.toLong().coerceAtLeast(1).toString(),
            "--parent-pid", ProcessHandle.current().pid().toString()).start()
        process = child
        try {
            onStatus(null)
            val result = awaitPlaybackHelper(child, onProgress, onStatus, onDevice)
            if (result.code != 0) throw CompositionException(result.diagnostic.ifBlank { "退出码 ${result.code}" })
            coroutineContext.ensureActive()
            if (!partial.isFile || partial.length() <= 0) throw CompositionException("合成组件没有生成视频")
            onFinalizing(0f)
            moveAtomically(partial, output)
            val markerTemp = File(root, "windows-composite-complete.tmp")
            markerTemp.writeText("v1:${quality.name}")
            moveAtomically(markerTemp, completeMarker)
            onFinalizing(1f)
        } finally {
            if (child.isAlive) child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
            process = null
            if (!completeMarker.isFile) partial.delete()
        }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        process?.let { child ->
            if (child.isAlive) child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
        sources.forEach { it.close() }
    }

    private fun moveAtomically(source: File, destination: File) {
        try { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

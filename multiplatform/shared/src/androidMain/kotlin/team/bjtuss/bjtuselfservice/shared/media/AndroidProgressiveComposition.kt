package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import team.bjtuss.bjtuselfservice.shared.logging.AppLog

/** TS 帧流直接供硬件解码；成品在关键帧处定期提交，恢复时保留已编码部分。 */
internal class AndroidProgressiveComposition(
    private val work: MediaWorkDirectory,
    private val durationSeconds: Double,
    private val quality: PlaybackCompositeQuality,
) : ProgressiveComposition {
    private val inputDirectory = File(work.file("inputs").toString())
    private val directories = List(2) { File(inputDirectory, "stream-$it") }
    private val sources = directories.map { AndroidIndexedTsInput(it, (durationSeconds * 1_000_000).toLong()) }
    private val journal = AndroidCompositionJournal(File(work.file("encoded").toString()), quality.name)
    override val resumeSeconds: Double get() = journal.resumeUs / 1_000_000.0
    override val isComplete: Boolean get() = journal.complete

    override suspend fun append(index: Int, bytes: ByteArray) = sources[index].append(bytes)
    override suspend fun finishInput(index: Int) = sources[index].finish()
    override suspend fun compose(onProgress: (Float) -> Unit, onStatus: (String?) -> Unit,
        onFinalizing: (Float) -> Unit) {
        sources.forEach { it.readerJob = coroutineContext[Job] }
        val factory: (String, String) -> AndroidMediaSamples? = { path, kind ->
            sources[directories.indexOfFirst { it.absolutePath == path }].open(kind)
        }
        try {
            withContext(Dispatchers.IO) {
                if (!journal.complete) {
                    AppLog.d("PlaybackCompose", "progressive resumeUs=${journal.resumeUs} audioFrames=${journal.resumeAudioFrames}")
                    AndroidMediaCompositor(directories[0].absolutePath, directories[1].absolutePath,
                        work.file("composite.mp4").toString(), quality, factory, (durationSeconds * 1_000_000).toLong(), journal)
                        .run(onProgress, onStatus)
                }
                onStatus("正在无损整理已合成的分段")
                journal.concatenate(File(work.file("composite.mp4").toString()), coroutineContext, onFinalizing)
                onStatus(null)
            }
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            throw error
        }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        sources.forEach { it.cancel() }
        MediaWorkDirectory(kotlinx.io.files.Path(inputDirectory.absolutePath)).deleteAll()
    }
}

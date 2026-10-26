package team.bjtuss.bjtuselfservice.shared.data.courseware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import team.bjtuss.bjtuselfservice.shared.media.AppendFile
import team.bjtuss.bjtuselfservice.shared.media.MediaWorkDirectory

/** 任务内的分片断点。只保存媒体字节，地址与可能的 token 只留在内存。 */
class HlsSegmentCheckpoint(private val directory: MediaWorkDirectory) {
    private var urls: List<String> = emptyList()
    var completed: Int = 0
        private set

    suspend fun prepare(segmentUrls: List<String>) {
        if (urls != segmentUrls) {
            clear()
            urls = segmentUrls.toList()
        }
        directory.create()
    }

    suspend fun append(index: Int, bytes: ByteArray) {
        check(index == completed)
        AppendFile(file(index)).use { it.append(bytes) }
        completed++ // 完整落盘后才推进断点；中断的半片会被覆盖。
    }

    suspend fun read(index: Int): ByteArray = withContext(Dispatchers.IO) {
        SystemFileSystem.source(file(index)).buffered().use { it.readByteArray() }
    }

    suspend fun clear() {
        directory.deleteAll()
        completed = 0
        urls = emptyList()
    }

    private fun file(index: Int): Path = directory.file("$index.ts")
}

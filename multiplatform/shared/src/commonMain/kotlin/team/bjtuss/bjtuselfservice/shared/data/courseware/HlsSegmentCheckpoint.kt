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

/** 可跨进程读取的分片断点。仅保存媒体和列表指纹，不落盘 URL 或 token。 */
class HlsSegmentCheckpoint(private val directory: MediaWorkDirectory) {
    var completed: Int = 0
        private set

    suspend fun prepare(segmentUrls: List<String>) {
        val identity = playlistFingerprint(segmentUrls)
        val storedIdentity = withContext(Dispatchers.IO) {
            if (SystemFileSystem.exists(directory.file("playlist.id"))) {
                SystemFileSystem.source(directory.file("playlist.id")).buffered().use { it.readByteArray().decodeToString() }
            } else null
        }
        if (storedIdentity != identity) {
            clear()
        }
        directory.create()
        AppendFile(directory.file("playlist.id.part")).use { it.append(identity.encodeToByteArray()) }
        withContext(Dispatchers.IO) {
            SystemFileSystem.atomicMove(directory.file("playlist.id.part"), directory.file("playlist.id"))
            completed = 0
            while (completed < segmentUrls.size && SystemFileSystem.exists(file(completed))) completed++
        }
    }

    suspend fun append(index: Int, bytes: ByteArray) {
        check(index == completed)
        val partial = directory.file("$index.part")
        AppendFile(partial).use { it.append(bytes) }
        withContext(Dispatchers.IO) { SystemFileSystem.atomicMove(partial, file(index)) }
        completed++ // 完整落盘后才推进断点；中断的半片会被覆盖。
    }

    suspend fun read(index: Int): ByteArray = withContext(Dispatchers.IO) {
        SystemFileSystem.source(file(index)).buffered().use { it.readByteArray() }
    }

    suspend fun clear() {
        directory.deleteAll()
        completed = 0
    }

    private fun file(index: Int): Path = directory.file("$index.ts")
}

/** 只用作播放列表身份，不保存 URL/token；双 64 位滚动指纹不用于认证或密码。 */
private fun playlistFingerprint(urls: List<String>): String {
    var a = -3750763034362895579L
    var b = 0x6a09e667f3bcc909L
    urls.forEach { url ->
        (url + "\n").encodeToByteArray().forEach { byte ->
            a = (a xor (byte.toLong() and 255)) * 1099511628211L
            b = (b xor (byte.toLong() and 255)) * 6364136223846793005L
        }
    }
    return "$a:$b:${urls.size}"
}

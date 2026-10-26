package team.bjtuss.bjtuselfservice.shared.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * 回放任务的工作目录；默认系统临时目录，支持恢复的平台传入应用私有持久目录。
 * 成功、取消或移除时删除；网络失败保留分片，启动时仅清理没有记录的残余目录。
 */
class MediaWorkDirectory(val path: Path) {
    fun file(name: String): Path = Path(path, name)

    suspend fun create() = withContext(Dispatchers.IO) { SystemFileSystem.createDirectories(path) }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { deleteRecursively(path) }

    companion object {
        val root: Path get() = Path(SystemTemporaryDirectory, "bjtu-playback-tasks")

        fun forTask(id: String, workRoot: Path = root): MediaWorkDirectory = MediaWorkDirectory(Path(workRoot, id))

        /**
         * 目录根的锁：登记任务、创建任务目录、清理残余目录三件事都在这把锁里，
         * 否则“清理上次进程留下的目录”可能把正在跑的任务目录删掉，
         * 任务随后写文件时报 FileNotFoundException（“处理失败”）。
         */
        private val rootMutex = Mutex()

        /** 本进程正在使用的任务目录；清理时必须保留。 */
        private val liveTasks = mutableSetOf<String>()

        /** 任务开始前登记，保证清理目录时不会误删。 */
        suspend fun markLive(id: String, workRoot: Path = root) = withContext(Dispatchers.IO) {
            rootMutex.withLock {
                liveTasks += id
                SystemFileSystem.createDirectories(Path(workRoot, id))
            }
        }

        /** 任务结束（成功、失败、取消）后注销。 */
        suspend fun markFinished(id: String) = withContext(Dispatchers.IO) {
            rootMutex.withLock { liveTasks -= id }
        }

        /** 启动时清掉上次进程留下的残余（被系统杀掉的任务）；本进程的任务目录不动。 */
        suspend fun purgeStale(keep: Set<String> = emptySet(), workRoot: Path = root) = withContext(Dispatchers.IO) {
            rootMutex.withLock {
                if (!SystemFileSystem.exists(workRoot)) return@withLock
                val protected = liveTasks + keep
                SystemFileSystem.list(workRoot).filter { it.name !in protected }.forEach(::deleteRecursively)
            }
        }
    }
}

private fun deleteRecursively(path: Path) {
    if (!SystemFileSystem.exists(path)) return
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach(::deleteRecursively)
    }
    runCatching { SystemFileSystem.delete(path, mustExist = false) }
}

/** 顺序追加写入的文件。 */
class AppendFile(private val path: Path) : AutoCloseable {
    private var sink: RawSink? = SystemFileSystem.sink(path, append = false)
    var size = 0L
        private set

    suspend fun append(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = sink ?: error("closed")
        val buffer = Buffer().apply { write(bytes) }
        target.write(buffer, buffer.size)
        size += bytes.size
    }

    override fun close() {
        val target = sink ?: return
        sink = null
        try { target.flush() } finally { target.close() }
    }
}

suspend fun fileSize(path: Path): Long = withContext(Dispatchers.IO) { SystemFileSystem.metadataOrNull(path)?.size ?: -1L }

/** 分块读出整个文件，交给 [consumer]（写入保存目标或另一个文件）。 */
suspend fun readFileInChunks(path: Path, chunkSize: Int = 1 shl 20, consumer: suspend (ByteArray) -> Unit) {
    val source = withContext(Dispatchers.IO) { SystemFileSystem.source(path).buffered() }
    try {
        while (true) {
            val chunk = withContext(Dispatchers.IO) {
                val buffer = ByteArray(chunkSize)
                var filled = 0
                while (filled < chunkSize) {
                    val read = source.readAtMostTo(buffer, filled, chunkSize)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == 0) null else if (filled == chunkSize) buffer else buffer.copyOf(filled)
            } ?: break
            consumer(chunk)
        }
    } finally {
        withContext(Dispatchers.IO) { source.close() }
    }
}

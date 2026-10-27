package team.bjtuss.bjtuselfservice.shared.media

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class PlaybackHelperExit(val code: Int, val diagnostic: String)

/** 两条管道同时读取；取消不依赖子进程是否已经输出过进度。 */
internal suspend fun awaitPlaybackHelper(
    process: Process,
    onProgress: (Float) -> Unit,
    onStatus: (String?) -> Unit = {},
    onDevice: (String?) -> Unit = {},
): PlaybackHelperExit = coroutineScope {
    val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (process.isAlive) process.destroyForcibly() }
    }
    val diagnostic = async(Dispatchers.IO) {
        process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val tail = StringBuilder()
            val buffer = CharArray(2048)
            while (true) {
                val size = reader.read(buffer)
                if (size < 0) break
                tail.append(buffer, 0, size)
                if (tail.length > 8192) tail.delete(0, tail.length - 8192)
            }
            tail.toString().trim()
        }
    }
    try {
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                coroutineContext.ensureActive()
                when {
                    line.startsWith("progress ") -> line.removePrefix("progress ").toFloatOrNull()?.let(onProgress)
                    line.startsWith("status ") -> onStatus(line.removePrefix("status "))
                    line.startsWith("device ") -> onDevice(line.removePrefix("device ").trim().takeIf(String::isNotEmpty))
                }
            }
        }
        coroutineContext.ensureActive()
        PlaybackHelperExit(process.waitFor(), diagnostic.await())
    } finally {
        if (process.isAlive) process.destroyForcibly()
        withContext(NonCancellable) { cancellation.cancelAndJoin(); diagnostic.join() }
    }
}

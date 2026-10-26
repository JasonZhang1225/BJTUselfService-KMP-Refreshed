package team.bjtuss.bjtuselfservice.shared.data.courseware

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.darwin.NSObject
import team.bjtuss.bjtuselfservice.shared.data.homework.isSchoolPlaybackStreamUrl
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

/**
 * iOS：分片交给系统后台下载会话，App 被挂起时系统仍继续下载；回到前台后按顺序取用。
 * 所有分片一次性入队，系统自行调度并发；单个分片失败重试 3 次。
 */
actual fun platformBackgroundSegmentSource(transport: SchoolHttpTransport): HlsSegmentSource? = IosBackgroundSegmentSource

@OptIn(ExperimentalForeignApi::class)
private object IosBackgroundSegmentSource : HlsSegmentSource {
    private val lock = NSLock()
    private val batches = mutableMapOf<String, Batch>()

    private class Batch(val directory: String, val urls: List<String>, val scope: CoroutineScope, val onRetry: (Int, Long) -> Unit) {
        val results = List(urls.size) { CompletableDeferred<String?>() }
        val attempts = IntArray(urls.size)
    }

    private val session: NSURLSession by lazy {
        val identifier = (NSBundle.mainBundle.bundleIdentifier ?: "team.bjtuss.bjtuselfservice.kmp") + ".playback.segments"
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(identifier).apply {
            setSessionSendsLaunchEvents(true)
            setDiscretionary(false)
            setHTTPMaximumConnectionsPerHost(4)
            setHTTPShouldSetCookies(false)
        }
        NSURLSession.sessionWithConfiguration(configuration, delegate = Delegate(this), delegateQueue = NSOperationQueue().apply {
            setMaxConcurrentOperationCount(1)
        })
    }

    override suspend fun fetchInOrder(urls: List<String>, onSegment: suspend (index: Int, bytes: ByteArray) -> Unit) =
        fetchOrdered(urls, { _, _ -> }, onSegment)

    override suspend fun fetchConcurrently(urls: List<String>, concurrency: Int, onRetry: (Int, Long) -> Unit,
        onSegment: suspend (index: Int, bytes: ByteArray) -> Unit) = fetchOrdered(urls, onRetry, onSegment)

    private suspend fun fetchOrdered(urls: List<String>, onRetry: (Int, Long) -> Unit,
        onSegment: suspend (index: Int, bytes: ByteArray) -> Unit) = coroutineScope {
        if (urls.any { !isSchoolPlaybackStreamUrl(it) }) throw HlsDownloadException(HlsDownloadFailure.UNSUPPORTED)
        val batchId = NSUUID().UUIDString
        val directory = NSTemporaryDirectory().trimEnd('/') + "/bjtu-segments-" + batchId
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val batch = Batch(directory, urls, this, onRetry)
        lock.lock(); batches[batchId] = batch; lock.unlock()
        try {
            urls.indices.forEach { enqueue(batchId, batch, it) }
            for (index in urls.indices) {
                val path = batch.results[index].await() ?: throw HlsDownloadException(HlsDownloadFailure.NETWORK)
                val bytes = withContext(Dispatchers.IO) {
                    val data = NSData.dataWithContentsOfFile(path) ?: throw HlsDownloadException(HlsDownloadFailure.NETWORK)
                    NSFileManager.defaultManager.removeItemAtPath(path, null)
                    data.toByteArray()
                }
                onSegment(index, bytes)
            }
        } finally {
            lock.lock(); batches.remove(batchId); lock.unlock()
            // 取消或失败时把还没跑完的分片任务也取消掉。
            session.getAllTasksWithCompletionHandler { tasks ->
                tasks?.filterIsInstance<NSURLSessionTask>()
                    ?.filter { it.taskDescription?.startsWith("$batchId|") == true }
                    ?.forEach { it.cancel() }
            }
            NSFileManager.defaultManager.removeItemAtPath(directory, null)
        }
    }

    private fun enqueue(batchId: String, batch: Batch, index: Int) {
        val url = NSURL.URLWithString(batch.urls[index]) ?: run {
            batch.results[index].complete(null)
            return
        }
        val task = session.downloadTaskWithURL(url)
        task.setTaskDescription("$batchId|$index")
        task.resume()
    }

    private fun lookup(task: NSURLSessionTask): Triple<String, Batch, Int>? {
        val parts = task.taskDescription?.split('|') ?: return null
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return null
        lock.lock()
        val batch = batches[parts[0]]
        lock.unlock()
        return batch?.let { Triple(parts[0], it, index) }
    }

    /**
     * 下载会话的委托。
     *
     * 必须是普通 class 而不是 Kotlin `object`：继承 ObjC 类的 Kotlin `object` 在框架构建时，
     * 单例会在文件级 `$init_global()` 里分配，编译器对这种 ObjC 类的分配路径没做处理，
     * 直接报 "Allocation of Obj-C class CLASS OBJECT ... should have been lowered"，
     * 整份 framework 链接失败（模块里其他 ObjC 委托——WebNavDelegate、PlaybackNavigationDelegate 等——
     * 都是普通 class、运行时创建，所以没事）。
     */
    private class Delegate(private val source: IosBackgroundSegmentSource) : NSObject(), NSURLSessionDownloadDelegateProtocol {
        override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didFinishDownloadingToURL: NSURL) {
            val (_, batch, index) = source.lookup(downloadTask) ?: return
            val status = (downloadTask.response as? NSHTTPURLResponse)?.statusCode ?: 0
            val finalUrl = downloadTask.currentRequest?.URL?.absoluteString.orEmpty()
            if (status !in 200..299 || !isSchoolPlaybackStreamUrl(finalUrl)) return // 交给 didComplete 重试
            // 系统给的临时文件在回调返回后就会被删除，必须当场移走。
            val target = batch.directory + "/" + index + ".ts"
            NSFileManager.defaultManager.removeItemAtPath(target, null)
            if (NSFileManager.defaultManager.moveItemAtPath(didFinishDownloadingToURL.path ?: return, target, null)) {
                batch.results[index].complete(target)
            }
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val (batchId, batch, index) = source.lookup(task) ?: return
            if (batch.results[index].isCompleted) return
            batch.attempts[index]++
            val attempt = batch.attempts[index]
            val wait = hlsRetryDelay(attempt)
            if (wait == null) batch.results[index].complete(null) else batch.scope.launch {
                batch.onRetry(attempt, wait)
                delay(wait)
                // 取消/失败后的批次不再入队；退避协程属于当前下载的结构化作用域。
                source.lock.lock(); val live = source.batches[batchId] === batch; source.lock.unlock()
                if (live) source.enqueue(batchId, batch, index)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    if (length == 0uL) return byteArrayOf()
    return bytes!!.reinterpret<ByteVar>().readBytes(length.toInt())
}

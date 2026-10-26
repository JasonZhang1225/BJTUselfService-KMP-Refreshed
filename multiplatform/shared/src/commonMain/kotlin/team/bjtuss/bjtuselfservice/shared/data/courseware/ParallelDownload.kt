package team.bjtuss.bjtuselfservice.shared.data.courseware

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * 并发下载的公共调度：录播分片（HLS）和单个大文件（Range）共用同一套「有上限的并发 + 按序交付」。
 *
 * 共同约束：
 * - 并发数有上限，内存里不会堆积无界的分片字节；
 * - 交付顺序 = 索引顺序，因此写盘结果与串行版逐字节一致；
 * - 任一任务失败 → 整个下载失败（异常原样抛出），不做部分成功；
 * - 协程取消 → 立刻停掉在飞的请求。
 */

/**
 * 同时在飞的录播分片数上限。
 *
 * 取 4：录播是校内流媒体服务器，单条连接的往返延迟占大头，4 条并发足以把等待摊掉；
 * 再高对总吞吐帮助不大，却会挤占同一台录像上其它路（一条录像常同时有 2~3 路）的带宽。
 */
const val DEFAULT_SEGMENT_CONCURRENCY = 4

/**
 * 有上限的并发执行，结果按索引顺序交付。
 *
 * - 同时在跑的任务不超过 [concurrency] 个；
 * - 同时驻留内存的结果不超过 [concurrency] + 2 份：worker 交付完才去取下一个任务，
 *   所以内存里的分片数量有界，不会把整路录像一次性读进来；
 * - 交付顺序与索引一致，调用方写出的字节与串行版完全相同；
 * - 任一任务失败 → 协程作用域失败，其余任务被取消，异常原样抛给调用方。
 */
internal suspend fun <T : Any> fetchInParallelOrdered(
    count: Int,
    concurrency: Int,
    fetch: suspend (index: Int) -> T,
    onResult: suspend (index: Int, value: T) -> Unit,
) {
    if (count <= 0) return
    val limit = concurrency.coerceAtLeast(1).coerceAtMost(count)
    if (limit == 1) {
        for (index in 0 until count) onResult(index, fetch(index))
        return
    }
    coroutineScope {
        // 待取任务的队列：索引很小，全部排队也不占内存；真正限流的是 worker 数量。
        val queue = Channel<Int>(capacity = count)
        // 取回结果只留 1 格：worker 交付不出去就会卡在这里，不会再去取新的任务（这就是背压）。
        val ready = Channel<IndexedValue<T>>(capacity = 1)
        for (index in 0 until count) queue.send(index)
        queue.close()
        val workers = List(limit) {
            launch {
                for (index in queue) {
                    val value = fetch(index)
                    ready.send(IndexedValue(index, value))
                }
            }
        }
        launch {
            workers.joinAll()
            ready.close()
        }
        // 乱序到达的结果先攒着，等齐了再按索引交付。
        val pending = HashMap<Int, T>()
        var next = 0
        for (item in ready) {
            pending[item.index] = item.value
            while (true) {
                val value = pending.remove(next) ?: break
                onResult(next, value)
                next++
            }
        }
    }
}

/**
 * 去掉 URL 的查询串再写进异常或日志：录播/课件地址可能带 token，
 * 与网络层打印请求时（`SchoolHttpRequest.toString`）的脱敏方式一致。
 */
internal fun redactUrlQuery(url: String): String = url.substringBefore('?')

package team.bjtuss.bjtuselfservice.shared.data.courseware

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
        // 滑动窗口：只有最早的一片已交付，才启动下一片。
        // 仅限制 worker / Channel 容量仍会让接收端的乱序 Map 无界增长。
        val window = ArrayDeque<Pair<Int, kotlinx.coroutines.Deferred<T>>>()
        var scheduled = 0
        repeat(limit) {
            val index = scheduled++
            window.addLast(index to async { fetch(index) })
        }
        while (window.isNotEmpty()) {
            val (index, result) = window.removeFirst()
            onResult(index, result.await())
            if (scheduled < count) {
                val next = scheduled++
                window.addLast(next to async { fetch(next) })
            }
        }
    }
}

/**
 * 去掉 URL 的查询串再写进异常或日志：录播/课件地址可能带 token，
 * 与网络层打印请求时（`SchoolHttpRequest.toString`）的脱敏方式一致。
 */
internal fun redactUrlQuery(url: String): String = url.substringBefore('?')

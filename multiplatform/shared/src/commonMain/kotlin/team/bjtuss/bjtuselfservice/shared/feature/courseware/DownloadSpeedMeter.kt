package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 按单调时钟的最近 4 秒计速；无新字节时自然降到 0，不受系统时间调整影响。 */
internal class DownloadSpeedMeter(private val millis: () -> Long = monotonicMillis()) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private val transferred = MutableStateFlow(0L)

    init { samples.addLast(millis() to 0L) }

    fun add(bytes: Long) { transferred.update { it + bytes.coerceAtLeast(0) } }
    val totalTransferred: Long get() = transferred.value

    fun sample(): Long? {
        val now = millis()
        val total = transferred.value
        samples.addLast(now to total)
        while (samples.size > 2 && samples[1].first <= now - 4_000) samples.removeFirst()
        val (start, bytes) = samples.first()
        val elapsed = now - start
        return if (elapsed < 500) null else ((total - bytes) * 1_000 / elapsed.coerceAtLeast(1))
    }
}

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

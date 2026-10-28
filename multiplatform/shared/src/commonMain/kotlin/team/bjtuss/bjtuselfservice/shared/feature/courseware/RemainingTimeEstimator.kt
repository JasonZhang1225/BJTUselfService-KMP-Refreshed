package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.time.TimeSource

/** 单调时钟、20 秒时间常数的指数平均；不把瞬时速度或总体进度权重当作时间。 */
internal class RemainingTimeEstimator(private val millis: () -> Long = run {
    val start = TimeSource.Monotonic.markNow()
    val clock: () -> Long = { start.elapsedNow().inWholeMilliseconds }
    clock
}) {
    private val started = millis()
    private var previousTime = started
    private var previousWork = 0.0
    private var rate = 0.0
    private var lastAdvance = started
    private var firstAdvance: Long? = null

    fun sample(completed: Double, remaining: Double): Long? {
        val now = millis()
        val elapsed = now - previousTime
        if (elapsed > 0) {
            val advanced = (completed - previousWork).coerceAtLeast(0.0)
            if (advanced > 0) {
                lastAdvance = now
                if (firstAdvance == null) firstAdvance = previousTime
            }
            val instant = advanced * 1000 / elapsed
            val weight = 1 - exp(-elapsed / 20_000.0)
            rate = if (rate <= 0 && instant > 0) instant else rate + weight * (instant - rate)
            previousTime = now
            previousWork = completed
        }
        if (now - (firstAdvance ?: now) < 5_000 || now - lastAdvance > 10_000 || rate <= 0 || remaining < 0) return null
        return ceil(remaining / rate).toLong().coerceAtLeast(1)
    }
}

internal fun formatRemainingSeconds(seconds: Long): String = when {
    seconds < 60 -> "不足 1 分钟"
    seconds < 3600 -> "${(seconds + 59) / 60} 分钟"
    else -> ((seconds + 59) / 60).let { "${it / 60} 小时 ${it % 60} 分钟" }
}

package team.bjtuss.bjtuselfservice.shared

import kotlin.time.Clock

/** Repeated focus notifications do not restart the background interval or request another sync. */
internal class AppForegroundRefreshPolicy(
    private val thresholdMillis: Long = 15 * 60 * 1000L,
    // Wall time includes lock-screen and laptop sleep, not just CPU-awake time.
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var backgroundAt: Long? = null

    fun background() {
        if (backgroundAt == null) backgroundAt = nowMillis()
    }

    fun foreground(): Boolean {
        val start = backgroundAt ?: return false
        backgroundAt = null
        return nowMillis() - start >= thresholdMillis
    }
}

/** Windows has no background timer: only a completed click can request refresh. */
internal class AppIdleClickRefreshPolicy(
    private val thresholdMillis: Long = 15 * 60 * 1000L,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var lastClickAt = nowMillis()

    fun clicked(): Boolean {
        val now = nowMillis()
        val expired = now - lastClickAt >= thresholdMillis
        lastClickAt = now
        return expired
    }
}

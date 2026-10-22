package team.bjtuss.bjtuselfservice.shared.feature.home

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetSnapshot
import team.bjtuss.bjtuselfservice.shared.feature.widget.minuteOfDay

/**
 * 实时活动“该显示哪一项、什么时候再看一次”的规则（Android 实时动态用）。
 * 与 iOS `CourseLiveActivityCoordinator.swift` 的 desiredState / nextBoundary 一一对应，改一边要同步另一边：
 *
 * - 课前 N 分钟（10 或 20，卡片菜单里选）自动出现，到这一项结束为止；
 * - 已经在显示时，课间不超过 30 分钟就直接接上下一项；
 * - 被用户关掉（划掉或在卡片里关闭）的那一项不再自动出现；
 * - 手动开启的那一项优先显示（可早于自动出现的时间），直到它结束。
 */

/** 课前多久自动出现，与 Swift `courseLiveActivityLeadMinuteOptions` / `courseLiveActivityDefaultLeadMinutes` 一致。 */
val LIVE_ACTIVITY_LEAD_MINUTE_OPTIONS = listOf(10, 20)
const val LIVE_ACTIVITY_DEFAULT_LEAD_MINUTES = 10

/** 已经在显示时，课间不超过这么久就直接接上下一项。 */
private const val HANDOFF_WINDOW_MINUTES = 30

enum class LiveActivityPhase { UPCOMING, ONGOING, FINISHED }

fun LiveActivityTarget.phase(now: LocalDateTime): LiveActivityPhase {
    if (now.date != date) return if (now.date < date) LiveActivityPhase.UPCOMING else LiveActivityPhase.FINISHED
    val minute = now.minuteOfDay
    return when {
        minute < startMinute -> LiveActivityPhase.UPCOMING
        minute < endMinute -> LiveActivityPhase.ONGOING
        else -> LiveActivityPhase.FINISHED
    }
}

val LiveActivityTarget.isLab: Boolean get() = kind == "lab"
val LiveActivityTarget.isExam: Boolean get() = kind == "exam"

/** 与 Swift `statusText(at:)` 同一套说法。 */
fun LiveActivityTarget.statusText(now: LocalDateTime): String = when (phase(now)) {
    LiveActivityPhase.UPCOMING -> if (isExam) "即将考试" else if (isLab) "即将开始" else "即将上课"
    LiveActivityPhase.ONGOING -> if (isExam) "考试中" else if (isLab) "实验中" else "正在上课"
    LiveActivityPhase.FINISHED -> if (isExam) "考试结束" else if (isLab) "实验结束" else "已下课"
}

/** 快照里某一天的全部日程，转成与卡片同一格式的 [LiveActivityTarget]（id 相同才能对上）。 */
fun CourseWidgetSnapshot.liveActivityTargets(date: LocalDate): List<LiveActivityTarget> {
    val day = days.firstOrNull { it.date == date.toString() } ?: return emptyList()
    return day.sessions.map { session ->
        LiveActivityTarget(
            id = liveActivityOccurrenceId(date, session.startMinute, session.endMinute, session.name),
            date = date,
            name = session.name,
            place = session.place,
            teacher = session.teacher,
            startMinute = session.startMinute,
            endMinute = session.endMinute,
            color = session.color,
            kind = session.kind,
        )
    }
}

data class LiveActivityPlan(
    /** 该显示的那一项；null 表示收起。 */
    val target: LiveActivityTarget?,
    /** 同一天紧接着的下一项，只显示名字和开始时间。 */
    val next: LiveActivityTarget?,
    /** 手动开启的那一项已经结束或找不到了，宿主应忘掉它。 */
    val manualExpired: Boolean,
)

/**
 * @param today 今天的日程（[liveActivityTargets]）。
 * @param manualFallback 卡片开启时带来的内容；快照里找不到这一项时兜底。
 * @param hasRunning 当前是否已经在显示，决定能否跨课间接上下一项。
 */
fun planLiveActivity(
    today: List<LiveActivityTarget>,
    now: LocalDateTime,
    manualId: String?,
    manualFallback: LiveActivityTarget?,
    dismissedIds: Set<String>,
    leadMinutes: Int,
    hasRunning: Boolean,
): LiveActivityPlan {
    fun plan(target: LiveActivityTarget, manualExpired: Boolean = false) =
        LiveActivityPlan(target, nextAfter(target, today), manualExpired)

    var manualExpired = false
    if (manualId != null) {
        today.firstOrNull { it.id == manualId && it.phase(now) != LiveActivityPhase.FINISHED }?.let { return plan(it) }
        manualFallback?.takeIf { it.id == manualId && it.phase(now) != LiveActivityPhase.FINISHED }?.let { return plan(it) }
        manualExpired = true
    }
    val lead = if (hasRunning) maxOf(leadMinutes, HANDOFF_WINDOW_MINUTES) else leadMinutes
    val nowMinute = now.minuteOfDay
    val candidate = today
        .filter {
            it.date == now.date && it.endMinute > nowMinute && it.startMinute - lead <= nowMinute && it.id !in dismissedIds
        }
        .minWithOrNull(compareBy({ it.startMinute }, { it.endMinute }))
    return candidate?.let { plan(it, manualExpired) } ?: LiveActivityPlan(null, null, manualExpired)
}

private fun nextAfter(target: LiveActivityTarget, today: List<LiveActivityTarget>): LiveActivityTarget? =
    today.filter { it.date == target.date && it.startMinute >= target.endMinute }.minByOrNull { it.startMinute }

/** 下一个关键时刻（课前 N 分钟、开始、结束），最晚到明天零点，换到新一天的日程。 */
fun liveActivityNextBoundary(today: List<LiveActivityTarget>, now: LocalDateTime, leadMinutes: Int): LocalDateTime {
    val nowMinute = now.minuteOfDay
    val next = today.filter { it.date == now.date }
        .flatMap { listOf(it.startMinute - leadMinutes, it.startMinute, it.endMinute) }
        .filter { it > nowMinute }
        .minOrNull()
    val date = now.date
    if (next == null) {
        val tomorrow = date.plus(1, DateTimeUnit.DAY)
        return LocalDateTime(tomorrow.year, tomorrow.month, tomorrow.day, 0, 0)
    }
    return LocalDateTime(date.year, date.month, date.day, next / 60, next % 60)
}

/** 关掉的记录只留今天及以后的，id 以 yyyy-MM-dd 开头。 */
fun pruneLiveActivityDismissed(ids: Set<String>, today: LocalDate): Set<String> {
    val key = today.toString()
    return ids.filterTo(mutableSetOf()) { it.take(10) >= key }
}

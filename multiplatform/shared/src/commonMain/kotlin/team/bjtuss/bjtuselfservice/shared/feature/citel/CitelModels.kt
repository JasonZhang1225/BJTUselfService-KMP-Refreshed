package team.bjtuss.bjtuselfservice.shared.feature.citel

import kotlinx.serialization.Serializable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind

const val CITEL_BASE = "https://citel.bjtu.edu.cn/mlsv3"
val citelTimeZone = TimeZone.of("Asia/Shanghai")

@Serializable
data class CitelCourse(val id: Int, val name: String)

@Serializable
data class CitelTask(
    val id: Int,
    val courseId: Int,
    val courseName: String,
    val title: String,
    val url: String,
    val programming: Boolean,
    val openTime: Long? = null,
    val dueTime: Long? = null,
    val discountTime: Long? = null,
    /** 平台当前显示的倍率；不推测未来折扣公式。 */
    val discount: Double? = null,
    val allowLate: Boolean? = null,
    val status: String = "",
    val submitted: Boolean = false,
    val grade: String? = null,
)

fun citelDateText(seconds: Long): String =
    Instant.fromEpochSeconds(seconds).toLocalDateTime(citelTimeZone).toString().replace('T', ' ').take(16)

fun CitelTask.deadlineStatus(now: Long): String = when {
    dueTime == null -> "未公布截止时间"
    now >= dueTime && allowLate == true -> "已到截止时间 · 允许迟交"
    now >= dueTime -> "已到截止时间"
    else -> {
        val minutes = ((dueTime - now + 59) / 60)
        when {
            minutes >= 1440 -> "距截止 ${minutes / 1440} 天 ${(minutes % 1440) / 60} 小时"
            minutes >= 60 -> "距截止 ${minutes / 60} 小时 ${minutes % 60} 分钟"
            else -> "距截止 $minutes 分钟"
        }
    }
}

fun citelAgendaEvents(tasks: List<CitelTask>): List<PhyVlabEvent> = tasks.flatMap { task ->
    buildList {
        task.openTime?.let { time ->
            add(PhyVlabEvent("citel-start-${task.id}", "CITEL · ${task.title} · 开始",
                citelDateText(time), time, task.url, kind = PhyVlabEventKind.START, submitted = task.submitted))
        }
        task.discountTime?.takeIf { it != task.dueTime }?.let { time ->
            add(PhyVlabEvent("citel-discount-${task.id}", "CITEL · ${task.title} · 开始折扣",
                citelDateText(time), time, task.url, kind = PhyVlabEventKind.DISCOUNT, submitted = task.submitted))
        }
        task.dueTime?.let { time ->
            add(PhyVlabEvent("citel-due-${task.id}", "CITEL · ${task.title} · 截止" +
                if (time == task.discountTime) " / 开始折扣" else "",
                citelDateText(time), time, task.url, submitted = task.submitted))
        }
    }
}

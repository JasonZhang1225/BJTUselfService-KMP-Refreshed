package team.bjtuss.bjtuselfservice.kmp

import android.app.Activity
import android.os.Bundle
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import team.bjtuss.bjtuselfservice.kmp.live.CourseLiveUpdates
import team.bjtuss.bjtuselfservice.shared.feature.home.LiveActivityTarget
import team.bjtuss.bjtuselfservice.shared.feature.home.liveActivityOccurrenceId

/**
 * 仅 debug：不等真实课表，用一节虚构课程验证实时动态的样式与系统推广。
 * `adb shell am start -n team.bjtuss.bjtuselfservice.kmp/.LiveUpdateSmokeActivity --ei start -5 --ei length 30`
 * start 为相对现在的开始分钟（负数表示已开课），`--es action stop` 关闭。
 */
class LiveUpdateSmokeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val minute = now.hour * 60 + now.minute
        val start = (minute + intent.getIntExtra("start", -5)).coerceIn(0, 24 * 60 - 2)
        val end = (start + intent.getIntExtra("length", 30)).coerceAtMost(24 * 60 - 1)
        val name = "测试课程"
        val target = LiveActivityTarget(
            id = liveActivityOccurrenceId(now.date, start, end, name),
            date = now.date,
            name = name,
            place = "思源楼 SY101",
            teacher = "测试教师",
            startMinute = start,
            endMinute = end,
            color = intent.getStringExtra("color")?.toLongOrNull(16) ?: 0x4F6FD8,
            kind = intent.getStringExtra("kind") ?: "required",
        )
        CourseLiveUpdates.install(this)
        CourseLiveUpdates.command(this, intent.getStringExtra("action") ?: "start", target)
        finish()
    }
}

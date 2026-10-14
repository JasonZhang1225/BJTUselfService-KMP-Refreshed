package team.bjtuss.bjtuselfservice.kmp.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetDay
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetSession
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetSnapshot
import java.time.ZonedDateTime

/** Android 15+ 生成真实布局预览；仅用演示数据，不把用户课表放进组件选择器。 */
internal object CourseWidgetPreviews {
    fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val manager = AppWidgetManager.getInstance(context)
        val actual = ZonedDateTime.now()
        val now = actual.withHour(7).withMinute(0).withSecond(0)
        val monday = now.toLocalDate().minusDays(now.dayOfWeek.value - 1L)
        val snapshot = CourseWidgetSnapshot(generatedAt = now.toEpochSecond(), days = (0..13).map { offset ->
            val date = monday.plusDays(offset.toLong())
            val courses = if (date.dayOfWeek.value >= 6) emptyList() else listOf(
                CourseWidgetSession("示例交流课程", "教学楼 A101", "", 480, 590, color = 0xC9558A, kind = "limited"),
                CourseWidgetSession("示例物理课程", "教学楼 B202", "", 610, 720, color = 0xD9574E, kind = "required"),
                CourseWidgetSession("示例编程课程", "教学楼 C303", "", 980, 1090, color = 0xD9574E, kind = "required"),
            )
            CourseWidgetDay(date.toString(), 5, courses)
        })
        for (provider in CourseWidgetUpdates.providers) {
            val component = ComponentName(context, provider.javaClass)
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, provider.defaultWidth.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, provider.defaultWidth.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, provider.defaultHeight.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, provider.defaultHeight.toInt())
            }
            // 系统限流时保留 XML/previewImage 兜底，不反复重试。
            runCatching {
                manager.setWidgetPreview(component, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                    CourseWidgetRenderer.render(context, provider, -1, options, snapshot, now))
            }
        }
    }
}

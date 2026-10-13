package team.bjtuss.bjtuselfservice.kmp.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

enum class WidgetKind { NEXT, TODAY, WEEK }
enum class WidgetHost { ANDROID, XIAOMI, VIVO }

/** 类名是桌面保存的小组件身份，发布后保持稳定。所有 provider 均运行在 :widgetProvider。 */
open class CourseWidgetProvider(
    val kind: WidgetKind = WidgetKind.NEXT,
    val host: WidgetHost = WidgetHost.ANDROID,
    val defaultWidth: Float = 150f,
    val defaultHeight: Float = 150f,
) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        CourseWidgetUpdates.enqueue(this, context) { CourseWidgetUpdates.updateProvider(context, this, ids) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // 这套组件不存每实例配置；小米迁移只需确认新 ID 并重画，避免反复触发迁移。
        if (host == WidgetHost.XIAOMI && options.getBoolean("miuiIdChanged") && !options.getBoolean("miuiIdChangedComplete")) {
            val newIds = options.getIntArray("miuiNewIds") ?: intArrayOf(id)
            for (newId in newIds) manager.updateAppWidgetOptions(newId, Bundle(options).apply {
                putBoolean("miuiIdChangedComplete", true)
            })
            onUpdate(context, manager, newIds)
        } else onUpdate(context, manager, intArrayOf(id))
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        onUpdate(context, AppWidgetManager.getInstance(context), newWidgetIds)
    }

    override fun onDisabled(context: Context) {
        CourseWidgetUpdates.enqueue(this, context) { CourseWidgetUpdates.schedule(context) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (host == WidgetHost.XIAOMI && intent.action == "miui.appwidget.action.APPWIDGET_UPDATE") {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, manager.getAppWidgetIds(android.content.ComponentName(context, javaClass)))
        } else super.onReceive(context, intent)
    }
}

class NextCourseWidget : CourseWidgetProvider()
class TodayCourseWidget : CourseWidgetProvider(WidgetKind.TODAY, defaultWidth = 300f, defaultHeight = 150f)
class WeekCourseWidget : CourseWidgetProvider(WidgetKind.WEEK, defaultWidth = 300f, defaultHeight = 320f)

class XiaomiNextSmallWidget : CourseWidgetProvider(WidgetKind.NEXT, WidgetHost.XIAOMI, 150f, 150f)
class XiaomiNextWideWidget : CourseWidgetProvider(WidgetKind.NEXT, WidgetHost.XIAOMI, 300f, 150f)
class XiaomiTodayWideWidget : CourseWidgetProvider(WidgetKind.TODAY, WidgetHost.XIAOMI, 300f, 150f)
class XiaomiTodayLargeWidget : CourseWidgetProvider(WidgetKind.TODAY, WidgetHost.XIAOMI, 300f, 320f)
class XiaomiWeekWidget : CourseWidgetProvider(WidgetKind.WEEK, WidgetHost.XIAOMI, 300f, 320f)

class VivoNextSmallWidget : CourseWidgetProvider(WidgetKind.NEXT, WidgetHost.VIVO, 150f, 150f)
class VivoNextWideWidget : CourseWidgetProvider(WidgetKind.NEXT, WidgetHost.VIVO, 300f, 150f)
class VivoTodayWideWidget : CourseWidgetProvider(WidgetKind.TODAY, WidgetHost.VIVO, 300f, 150f)
class VivoTodayLargeWidget : CourseWidgetProvider(WidgetKind.TODAY, WidgetHost.VIVO, 300f, 320f)
class VivoWeekWidget : CourseWidgetProvider(WidgetKind.WEEK, WidgetHost.VIVO, 300f, 320f)

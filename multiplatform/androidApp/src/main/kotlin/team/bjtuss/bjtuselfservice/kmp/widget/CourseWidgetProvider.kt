package team.bjtuss.bjtuselfservice.kmp.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

enum class WidgetKind { NEXT, TODAY, WEEK }

/** 类名是桌面保存的小组件身份，发布后保持稳定。所有 provider 均运行在 :widgetProvider。 */
open class CourseWidgetProvider(
    val kind: WidgetKind = WidgetKind.NEXT,
    val defaultWidth: Float = 150f,
    val defaultHeight: Float = 150f,
) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        CourseWidgetUpdates.enqueue(this, context) { CourseWidgetUpdates.updateProvider(context, this, ids) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        onUpdate(context, AppWidgetManager.getInstance(context), newWidgetIds)
    }

    override fun onDisabled(context: Context) {
        CourseWidgetUpdates.enqueue(this, context) { CourseWidgetUpdates.schedule(context) }
    }
}

class NextCourseWidget : CourseWidgetProvider()
class TodayCourseWidget : CourseWidgetProvider(WidgetKind.TODAY, defaultWidth = 300f, defaultHeight = 150f)
class WeekCourseWidget : CourseWidgetProvider(WidgetKind.WEEK, defaultWidth = 300f, defaultHeight = 320f)

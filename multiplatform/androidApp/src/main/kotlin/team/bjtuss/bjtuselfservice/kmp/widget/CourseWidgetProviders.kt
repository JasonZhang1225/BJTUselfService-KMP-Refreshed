package team.bjtuss.bjtuselfservice.kmp.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import team.bjtuss.bjtuselfservice.kmp.MainActivity
import team.bjtuss.bjtuselfservice.kmp.R
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetState
import team.bjtuss.bjtuselfservice.shared.feature.widget.courseWidgetNextRefresh
import team.bjtuss.bjtuselfservice.shared.feature.widget.courseWidgetState
import team.bjtuss.bjtuselfservice.shared.feature.widget.readAndroidCourseWidgetSnapshot

/** 三种小组件，与 iOS 的 UpNext / Today / Week 一一对应。 */
enum class CourseWidgetKind { UP_NEXT, TODAY, WEEK }

abstract class CourseWidgetProvider : AppWidgetProvider() {
    abstract val kind: CourseWidgetKind

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        CourseWidgetUpdater.updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        CourseWidgetUpdater.update(context, manager, kind, id)
    }

    override fun onDisabled(context: Context) {
        // 最后一个同类小组件被移除；其它种类还在时闹钟由 updateAll 继续维护。
        CourseWidgetUpdater.updateAll(context)
    }
}

class UpNextCourseWidgetProvider : CourseWidgetProvider() {
    override val kind = CourseWidgetKind.UP_NEXT
}

class TodayCourseWidgetProvider : CourseWidgetProvider() {
    override val kind = CourseWidgetKind.TODAY
}

class WeekCourseWidgetProvider : CourseWidgetProvider() {
    override val kind = CourseWidgetKind.WEEK
}

/** 定时重排、系统时间/时区变化、应用更新后都重新排版一次。 */
class CourseWidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CourseWidgetUpdater.updateAll(context)
    }
}

object CourseWidgetUpdater {
    private const val ACTION_REFRESH = "team.bjtuss.bjtuselfservice.kmp.widget.REFRESH"
    /** 只记录桌面上报的尺寸（无课表、无账号信息），`adb logcat -s CourseWidget` 可查。 */
    private const val TAG = "CourseWidget"

    private fun providerClass(kind: CourseWidgetKind): Class<out CourseWidgetProvider> = when (kind) {
        CourseWidgetKind.UP_NEXT -> UpNextCourseWidgetProvider::class.java
        CourseWidgetKind.TODAY -> TodayCourseWidgetProvider::class.java
        CourseWidgetKind.WEEK -> WeekCourseWidgetProvider::class.java
    }

    /** 快照变化、闹钟到点时调用：所有种类全部重排，并安排下一次重排。 */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        var any = false
        CourseWidgetKind.entries.forEach { kind ->
            val ids = manager.getAppWidgetIds(ComponentName(context, providerClass(kind)))
            ids.forEach { update(context, manager, kind, it) }
            any = any || ids.isNotEmpty()
        }
        scheduleNext(context, any)
    }

    fun update(context: Context, manager: AppWidgetManager, kind: CourseWidgetKind, id: Int) {
        val views = runCatching { build(context, kind, manager.getAppWidgetOptions(id)) }.getOrNull() ?: return
        runCatching { manager.updateAppWidget(id, views) }
    }

    private fun now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun build(context: Context, kind: CourseWidgetKind, options: Bundle): RemoteViews {
        val renderer = CourseWidgetRenderer(context)
        val state = courseWidgetState(readAndroidCourseWidgetSnapshot(context), now())
        val openApp = PendingIntent.getActivity(
            context,
            kind.ordinal,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun render(size: CourseWidgetSize, widthDp: Float, heightDp: Float): RemoteViews {
            val views = renderer.message(state) ?: run {
                val model = (state as CourseWidgetState.Ready).model
                when (kind) {
                    CourseWidgetKind.UP_NEXT -> renderer.upNext(model, size, widthDp, heightDp)
                    CourseWidgetKind.TODAY -> renderer.today(model, size, widthDp, heightDp)
                    CourseWidgetKind.WEEK -> renderer.week(model, widthDp, heightDp)
                }
            }
            views.setOnClickPendingIntent(android.R.id.background, openApp)
            return views
        }
        val sizes = reportedSizes(context, kind, options)
        Log.i(TAG, "${kind.name} sdk=${Build.VERSION.SDK_INT} source=${sizes.source} sizes=${sizes.sizes}")
        // 桌面上报了多个尺寸（竖屏/横屏、荣耀折叠屏展开态等）：各排一份，桌面按当前格子挑；
        // 只有一个尺寸时直接排这一份。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sizes.sizes.size > 1) {
            return RemoteViews(sizes.sizes.associateWith { render(family(kind, it.width, it.height), it.width, it.height) })
        }
        val size = sizes.sizes.first()
        return render(family(kind, size.width, size.height), size.width, size.height)
    }

    private class ReportedSizes(val source: String, val sizes: List<SizeF>)

    /**
     * 小组件实际尺寸，按可靠程度依次取：
     * 1. Android 12 的尺寸列表；
     * 2. 老式 MIN/MAX 宽高（竖屏取最小宽×最大高，横屏取最大宽×最小高）——部分桌面（推测含 HarmonyOS 4）不报列表；以前这时用写死的兜底尺寸，大卡片按小尺寸排、只显示一两条；
     * 3. 都没有时按屏幕宽度和默认格数估算，宁小勿大：少放一行留白，也不让内容被裁掉。
     */
    private fun reportedSizes(context: Context, kind: CourseWidgetKind, options: Bundle): ReportedSizes {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val list = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.filter { it.width > 0f && it.height > 0f }
                ?.distinct()
            if (!list.isNullOrEmpty()) return ReportedSizes("sizes", list)
        }
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val fromInts = if (landscape) {
            SizeF((maxWidth.takeIf { it > 0 } ?: minWidth).toFloat(), (minHeight.takeIf { it > 0 } ?: maxHeight).toFloat())
        } else {
            SizeF((minWidth.takeIf { it > 0 } ?: maxWidth).toFloat(), (maxHeight.takeIf { it > 0 } ?: minHeight).toFloat())
        }
        if (fromInts.width > 0f && fromInts.height > 0f) {
            return ReportedSizes("minmax(${minWidth}x$minHeight..${maxWidth}x$maxHeight)", listOf(fromInts))
        }
        return ReportedSizes("estimate", listOf(estimatedSize(context, kind)))
    }

    /** 按 4 列桌面、正方形格子估算默认格数下的尺寸，并限制在小组件声明的尺寸范围内。 */
    private fun estimatedSize(context: Context, kind: CourseWidgetKind): SizeF {
        val (columns, rows, maxWidth, maxHeight) = when (kind) {
            CourseWidgetKind.UP_NEXT -> listOf(2, 2, 530, 260)
            CourseWidgetKind.TODAY -> listOf(4, 2, 530, 530)
            CourseWidgetKind.WEEK -> listOf(4, 4, 530, 600)
        }
        val cell = (context.resources.configuration.screenWidthDp - 32f) / 4f
        val width = (columns * cell - 16f).coerceIn(110f, maxWidth.toFloat())
        val height = (rows * cell - 16f).coerceIn(110f, maxHeight.toFloat())
        return SizeF(width, height)
    }

    /** 宽不到 200dp 的临近课程用小号；高不到 230dp 的今日课程用中号。 */
    private fun family(kind: CourseWidgetKind, width: Float, height: Float): CourseWidgetSize = when (kind) {
        CourseWidgetKind.UP_NEXT -> if (width < 200f) CourseWidgetSize.SMALL else CourseWidgetSize.MEDIUM
        CourseWidgetKind.TODAY -> if (height < 230f) CourseWidgetSize.MEDIUM else CourseWidgetSize.LARGE
        CourseWidgetKind.WEEK -> CourseWidgetSize.LARGE
    }

    /**
     * 不申请精确闹钟权限：用非唤醒的 RTC 闹钟，息屏时不叫醒手机，亮屏后到期的那次立即补上。
     * 上课时段逐分钟（“还剩 N 分钟”），其它时候按下一次课程起止，最多 15 分钟一次。
     */
    private fun scheduleNext(context: Context, hasWidgets: Boolean) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CourseWidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (!hasWidgets) {
            alarm.cancel(intent)
            return
        }
        val zone = TimeZone.currentSystemDefault()
        val next = courseWidgetNextRefresh(readAndroidCourseWidgetSnapshot(context), now())
        alarm.set(AlarmManager.RTC, next.toInstant(zone).toEpochMilliseconds(), intent)
    }
}

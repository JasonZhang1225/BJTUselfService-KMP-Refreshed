package team.bjtuss.bjtuselfservice.kmp.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import team.bjtuss.bjtuselfservice.shared.feature.widget.AndroidCourseWidgetStore
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetSnapshot
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/** 只读取主应用写入的本地快照；不初始化主应用、数据库、网络、验证码模型。 */
object CourseWidgetUpdates {
    const val SNAPSHOT_CHANGED = "team.bjtuss.bjtuselfservice.kmp.widget.SNAPSHOT_CHANGED"
    const val REFRESH = "team.bjtuss.bjtuselfservice.kmp.widget.REFRESH"
    const val BOUNDARY = "team.bjtuss.bjtuselfservice.kmp.widget.BOUNDARY"
    const val OPEN_SCHEDULE = "open_widget_schedule"

    val providers: List<CourseWidgetProvider> = listOf(
        NextCourseWidget(), TodayCourseWidget(), WeekCourseWidget(),
        XiaomiNextSmallWidget(), XiaomiNextWideWidget(), XiaomiTodayWideWidget(), XiaomiTodayLargeWidget(), XiaomiWeekWidget(),
        VivoNextSmallWidget(), VivoNextWideWidget(), VivoTodayWideWidget(), VivoTodayLargeWidget(), VivoWeekWidget(),
    )
    private val executor = Executors.newSingleThreadExecutor()

    fun install(context: Context) {
        val appContext = context.applicationContext
        // OEM 专用 provider 只在对应品牌开启；通用组件始终保留，未审核或旧系统可回退。
        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        for (provider in providers.filter { it.host != WidgetHost.ANDROID }) {
            val enabled = when (provider.host) {
                WidgetHost.XIAOMI -> manufacturer in setOf("xiaomi", "redmi", "poco")
                WidgetHost.VIVO -> manufacturer in setOf("vivo", "iqoo")
                else -> false
            }
            val component = ComponentName(appContext, provider.javaClass)
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (appContext.packageManager.getComponentEnabledSetting(component) != state) {
                appContext.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
        AndroidCourseWidgetStore.install(appContext) {
            appContext.sendBroadcast(Intent(appContext, CourseWidgetRefreshReceiver::class.java).setAction(SNAPSHOT_CHANGED))
        }
        appContext.sendBroadcast(Intent(appContext, CourseWidgetRefreshReceiver::class.java).setAction(SNAPSHOT_CHANGED))
        executor.execute { CourseWidgetPreviews.refresh(appContext) }
    }

    fun enqueue(receiver: BroadcastReceiver, context: Context, block: () -> Unit) {
        val pending = receiver.goAsync()
        executor.execute {
            try { block() } finally { pending.finish() }
        }
    }

    fun updateProvider(context: Context, provider: CourseWidgetProvider, ids: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        val snapshot = AndroidCourseWidgetStore.read(context)
        val now = ZonedDateTime.now()
        for (id in ids) update(context, manager, provider, id, snapshot, now)
        schedule(context, snapshot, now)
    }

    private fun update(context: Context, manager: AppWidgetManager, provider: CourseWidgetProvider, id: Int,
                       snapshot: CourseWidgetSnapshot?, now: ZonedDateTime) {
        // 个别桌面删除组件和广播同时发生时会给出已失效 ID。
        if (manager.getAppWidgetInfo(id)?.provider != ComponentName(context, provider.javaClass)) return
        manager.updateAppWidget(id, CourseWidgetRenderer.render(context, provider, id, manager.getAppWidgetOptions(id), snapshot, now))
    }

    fun updateAll(context: Context, includeVivo: Boolean = true) {
        val manager = AppWidgetManager.getInstance(context)
        val snapshot = AndroidCourseWidgetStore.read(context)
        val now = ZonedDateTime.now()
        for (provider in providers) {
            if (!includeVivo && provider.host == WidgetHost.VIVO) continue
            for (id in manager.getAppWidgetIds(ComponentName(context, provider.javaClass))) {
                update(context, manager, provider, id, snapshot, now)
            }
        }
        schedule(context, snapshot, now)
    }

    /** 原生 Android / 小米按课程边界安排一次非精确刷新；不申请精确闹钟或后台常驻权限。 */
    fun schedule(context: Context, snapshot: CourseWidgetSnapshot? = AndroidCourseWidgetStore.read(context), now: ZonedDateTime = ZonedDateTime.now()) {
        val manager = AppWidgetManager.getInstance(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val callback = PendingIntent.getBroadcast(context, 0,
            Intent(context, CourseWidgetRefreshReceiver::class.java).setAction(BOUNDARY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hasScheduledWidgets = providers.filter { it.host != WidgetHost.VIVO }.any {
            manager.getAppWidgetIds(ComponentName(context, it.javaClass)).isNotEmpty()
        }
        if (!hasScheduledWidgets || snapshot == null) {
            alarm.cancel(callback)
            return
        }
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        val next = snapshot.days.flatMap { day ->
            val date = LocalDate.parse(day.date)
            day.sessions.flatMap { session -> listOf(session.startMinute, session.endMinute).map { minute ->
                date.atStartOfDay().plusMinutes(minute.toLong()).atZone(now.zone)
            } }
        }.filter { it.isAfter(now) && it.isBefore(midnight) }.minOrNull() ?: midnight
        alarm.setWindow(AlarmManager.RTC, next.toInstant().toEpochMilli(), 10 * 60 * 1000L, callback)
    }
}

class CourseWidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accepted = setOf(CourseWidgetUpdates.SNAPSHOT_CHANGED, CourseWidgetUpdates.REFRESH, CourseWidgetUpdates.BOUNDARY,
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED)
        if (intent.action !in accepted) return
        CourseWidgetUpdates.enqueue(this, context) {
            // vivo 被动间隔 >=12h；不借边界闹钟绕过厂商刷新规范。
            CourseWidgetUpdates.updateAll(context.applicationContext, includeVivo = intent.action != CourseWidgetUpdates.BOUNDARY)
        }
    }
}

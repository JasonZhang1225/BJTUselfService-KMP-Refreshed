package team.bjtuss.bjtuselfservice.kmp.live

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import team.bjtuss.bjtuselfservice.kmp.MainActivity
import team.bjtuss.bjtuselfservice.kmp.R
import team.bjtuss.bjtuselfservice.shared.feature.home.AndroidLiveActivityHost
import team.bjtuss.bjtuselfservice.shared.feature.home.AndroidLiveActivityStatus
import team.bjtuss.bjtuselfservice.shared.feature.home.LIVE_ACTIVITY_DEFAULT_LEAD_MINUTES
import team.bjtuss.bjtuselfservice.shared.feature.home.LIVE_ACTIVITY_LEAD_MINUTE_OPTIONS
import team.bjtuss.bjtuselfservice.shared.feature.home.LiveActivityPhase
import team.bjtuss.bjtuselfservice.shared.feature.home.LiveActivityTarget
import team.bjtuss.bjtuselfservice.shared.feature.home.installAndroidLiveActivityHost
import team.bjtuss.bjtuselfservice.shared.feature.home.isExam
import team.bjtuss.bjtuselfservice.shared.feature.home.isLab
import team.bjtuss.bjtuselfservice.shared.feature.home.liveActivityNextBoundary
import team.bjtuss.bjtuselfservice.shared.feature.home.liveActivityTargets
import team.bjtuss.bjtuselfservice.shared.feature.home.phase
import team.bjtuss.bjtuselfservice.shared.feature.home.planLiveActivity
import team.bjtuss.bjtuselfservice.shared.feature.home.pruneLiveActivityDismissed
import team.bjtuss.bjtuselfservice.shared.feature.home.statusText
import team.bjtuss.bjtuselfservice.shared.feature.widget.readAndroidCourseWidgetSnapshot
import team.bjtuss.bjtuselfservice.shared.feature.widget.widgetClock
import team.bjtuss.bjtuselfservice.shared.feature.widget.widgetRemainingText

/**
 * 日程实时动态（Android 16 Live Updates）的唯一所有者，对应 iOS `CourseLiveActivityCoordinator`：
 * 课前 10 或 20 分钟（卡片菜单里选）自动出现，到这一项结束为止。规则在 shared 的 `planLiveActivity`。
 *
 * 只用系统原生接口：常驻通知 + `setRequestPromotedOngoing`，系统（含 HyperOS）把它放到状态栏胶囊/灵动岛。
 * 不用厂商私有通知接口；Android 16 以下不显示 Live 按钮，也不发通知。
 *
 * - 用户划掉或在卡片、通知里关闭的那一项不再自动出现；卡片上的 Live 菜单可以重新开启。
 * - 手动开启的那一项优先显示（可早于自动出现的时间），直到它结束。
 * - 只读课程小组件快照（课名、地点、教师、时间），不含账号信息。
 */
object CourseLiveUpdates : AndroidLiveActivityHost {
    private const val TAG = "CourseLiveUpdate"
    private const val CHANNEL_ID = "course_live_update"
    private const val NOTIFICATION_ID = 2101
    private const val PREFS = "course_live_update"
    private const val KEY_LEAD = "leadMinutes"
    private const val KEY_MANUAL = "manualId"
    private const val KEY_PRESENTED = "presentedId"
    private const val KEY_DISMISSED = "dismissedIds"
    private const val KEY_ASKED_PERMISSION = "askedNotificationPermission"

    internal const val ACTION_REFRESH = "team.bjtuss.bjtuselfservice.kmp.live.REFRESH"
    internal const val ACTION_STOP = "team.bjtuss.bjtuselfservice.kmp.live.STOP"
    internal const val ACTION_DISMISSED = "team.bjtuss.bjtuselfservice.kmp.live.DISMISSED"
    internal const val EXTRA_ID = "id"
    /** `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`。 */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    private val statusFlow = MutableStateFlow<AndroidLiveActivityStatus?>(null)
    override val status: StateFlow<AndroidLiveActivityStatus?> = statusFlow.asStateFlow()

    private var appContext: Context? = null
    /** 卡片开启时带来的内容；快照里找不到这一项时兜底（不持久化）。 */
    private var manualFallback: LiveActivityTarget? = null
    /** 等通知权限或设置页回来后要开启的那一项。 */
    private var pendingEnable: LiveActivityTarget? = null
    /** 前台 Activity 注册的权限请求；Activity 销毁时清掉，这里不持有 Activity。 */
    private var permissionRequester: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { appContext?.let(::reconcile) }

    /** 由 MainActivity 创建时调用，重复调用无副作用。Live Updates 从 Android 16（API 36）开始。 */
    fun install(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        appContext = context.applicationContext
        installAndroidLiveActivityHost(this)
        reconcile(context)
    }

    fun attachPermissionRequester(requester: (() -> Unit)?) {
        permissionRequester = requester
    }

    fun onPermissionResult(context: Context, granted: Boolean) {
        val target = pendingEnable
        if (!granted) pendingEnable = null
        if (granted && target != null) command(context, "start", target) else reconcile(context)
    }

    // MARK: 卡片命令

    override fun command(action: String, target: LiveActivityTarget) {
        appContext?.let { command(it, action, target) }
    }

    override fun setLeadMinutes(minutes: Int) {
        val context = appContext ?: return
        if (minutes in LIVE_ACTIVITY_LEAD_MINUTE_OPTIONS) prefs(context).edit().putInt(KEY_LEAD, minutes).apply()
    }

    override fun requestEnable(target: LiveActivityTarget) {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        pendingEnable = target
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val prefs = prefs(context)
        val requester = permissionRequester
        when {
            // 第一次先弹系统权限框；之后再被拒绝系统不会再弹，只能去设置里打开。
            !granted && requester != null && !prefs.getBoolean(KEY_ASKED_PERMISSION, false) -> {
                prefs.edit().putBoolean(KEY_ASKED_PERMISSION, true).apply()
                requester()
            }
            !granted || !manager.areNotificationsEnabled() || channelBlocked(manager) ->
                openSettings(context, Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            !manager.canPostPromotedNotifications() ->
                openSettings(context, Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
            else -> command(context, "start", target)
        }
    }

    internal fun command(context: Context, action: String, target: LiveActivityTarget) {
        val prefs = prefs(context)
        val dismissed = dismissedIds(context).toMutableSet()
        val editor = prefs.edit()
        when (action) {
            "start" -> {
                dismissed -= target.id
                editor.putString(KEY_MANUAL, target.id)
                manualFallback = target
                pendingEnable = null
            }
            // 只改了提前时间（已写入），重新对齐即可。
            "lead" -> Unit
            // 恢复“课前 N 分钟自动显示”，不提前出现。
            "arm" -> {
                dismissed -= target.id
                if (prefs.getString(KEY_MANUAL, null) == target.id) clearManual(editor)
            }
            "stop" -> {
                dismissed += target.id
                if (prefs.getString(KEY_MANUAL, null) == target.id) clearManual(editor)
            }
            else -> return
        }
        editor.putStringSet(KEY_DISMISSED, dismissed).apply()
        reconcile(context)
    }

    /** 通知上的“关闭”或用户划掉通知：这一项不再自动出现。 */
    internal fun dismiss(context: Context, id: String) {
        val prefs = prefs(context)
        val editor = prefs.edit().putStringSet(KEY_DISMISSED, dismissedIds(context) + id)
        if (prefs.getString(KEY_MANUAL, null) == id) clearManual(editor)
        if (prefs.getString(KEY_PRESENTED, null) == id) editor.remove(KEY_PRESENTED)
        editor.apply()
        reconcile(context)
    }

    private fun clearManual(editor: android.content.SharedPreferences.Editor) {
        editor.remove(KEY_MANUAL)
        manualFallback = null
    }

    // MARK: 对齐期望状态

    /** 快照变化、回到前台、闹钟到点、系统时间变化时调用。 */
    fun reconcile(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        runCatching { reconcileOnce(context) }.onFailure { Log.w(TAG, "reconcile failed: ${it.javaClass.simpleName}") }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun reconcileOnce(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val now = now()
        val prefs = prefs(context)
        val dismissed = pruneLiveActivityDismissed(dismissedIds(context), now.date)
        val lead = leadMinutes(context)
        val enabled = isEnabled(context, manager)
        val today = readAndroidCourseWidgetSnapshot(context)?.liveActivityTargets(now.date)
        val running = manager.activeNotifications.any { it.id == NOTIFICATION_ID }
        val plan = today?.let {
            planLiveActivity(it, now, prefs.getString(KEY_MANUAL, null), manualFallback, dismissed, lead, running)
        }
        val editor = prefs.edit().putStringSet(KEY_DISMISSED, dismissed)
        if (plan?.manualExpired == true) clearManual(editor)
        val target = plan?.target
        if (target == null || !enabled) {
            // 没有快照（未登录或已退出）、今天没有该显示的，或通知被关：收起。
            manager.cancel(NOTIFICATION_ID)
            editor.remove(KEY_PRESENTED)
        } else {
            manager.notify(NOTIFICATION_ID, buildNotification(context, target, plan.next, now))
            editor.putString(KEY_PRESENTED, target.id)
        }
        editor.apply()
        // 设置页回来后通知已允许：开启当时点的那一项。
        val pending = pendingEnable
        if (enabled && pending != null) {
            pendingEnable = null
            command(context, "start", pending)
            return
        }
        statusFlow.value = AndroidLiveActivityStatus(enabled, target?.id?.takeIf { enabled }, lead)
        scheduleNext(context, today.orEmpty(), now, lead, showing = target != null && enabled)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildNotification(
        context: Context,
        target: LiveActivityTarget,
        next: LiveActivityTarget?,
        now: LocalDateTime,
    ): Notification {
        val zone = TimeZone.currentSystemDefault()
        val accent = (0xFF000000 or target.color).toInt()
        val phase = target.phase(now)
        val endAt = LocalDateTime(target.date.year, target.date.month, target.date.day, target.endMinute / 60, target.endMinute % 60)
        val endMillis = endAt.toInstant(zone).toEpochMilliseconds()
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        val time = "${widgetClock(target.startMinute)}–${widgetClock(target.endMinute)}"
        val detail = listOf(time, target.place, target.teacher).filter(String::isNotEmpty).joinToString(" · ")
        val nextLine = next?.let { "接下来 ${widgetClock(it.startMinute)} ${it.name}" }
        val glyph = kindIcon(target)
        val progress = progressStyle(target, phase, now, leadMinutes(context), accent)

        return Notification.Builder(context, CHANNEL_ID)
            // 状态栏胶囊和灵动岛展开态左侧用课程类别图标（课本/烧瓶/试卷/跑步），与 iOS 一致。
            // 不设大图标：HyperOS 灵动岛展开时会在右侧再画一个，与左侧重复；通知中心左侧固定是 App 图标。
            .setSmallIcon(glyph)
            .setColor(accent)
            .setContentTitle(target.name)
            // 第二行是状态与剩余时间，第三行是时间、地点、教师。推广通知的文字样式会被系统去掉，只能靠行序区分主次。
            .setSubText(statusLine(target, phase, now))
            .setContentText(listOfNotNull(detail, nextLine).joinToString("  ·  "))
            .setStyle(progress)
            .setCategory(Notification.CATEGORY_EVENT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // 不用系统秒级计时（标题旁会出现跳秒的“1:24:14”）；剩余时间只到分钟，随进度条逐分钟更新。
            .setShowWhen(false)
            // 状态栏胶囊里的短文字（约 7 个字）。不写时 HyperOS 显示课名（会被截断），也不显示计时：
            // 开课前写上课时刻，上课时写下课时刻，与 iOS 收起态“下课 16:00”一致。
            .setShortCriticalText(chipText(target, phase))
            // 等同 setRequestPromotedOngoing(true)：该方法只是写这个 extra，但 CI 镜像里的 android-36 平台缺这个方法，
            // 直接写 extra 不依赖 SDK 修订版本，运行时效果相同（平板实测仍为 PROMOTED_ONGOING）。
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            // 闹钟没按时到也会在下课时自己消失。
            .setTimeoutAfter((endMillis - nowMillis).coerceAtLeast(1_000L))
            .setContentIntent(openAppIntent(context))
            .setDeleteIntent(broadcast(context, ACTION_DISMISSED, target.id, requestCode = 1))
            .addAction(
                Notification.Action.Builder(null, "关闭", broadcast(context, ACTION_STOP, target.id, requestCode = 2)).build(),
            )
            .build()
    }

    private fun kindIcon(target: LiveActivityTarget): Int = when (target.kind) {
        "lab" -> R.drawable.ic_live_kind_lab
        "exam" -> R.drawable.ic_live_kind_exam
        "pe" -> R.drawable.ic_live_kind_pe
        else -> R.drawable.ic_live_kind_course
    }

    /** “正在上课 · 距下课 1 小时 24 分钟”。 */
    private fun statusLine(target: LiveActivityTarget, phase: LiveActivityPhase, now: LocalDateTime): String {
        val status = target.statusText(now)
        val minute = now.hour * 60 + now.minute
        val remaining = when (phase) {
            LiveActivityPhase.UPCOMING ->
                (if (target.isExam) "距考试 " else if (target.isLab) "距开始 " else "距上课 ") +
                    widgetRemainingText(target.startMinute - minute)
            LiveActivityPhase.ONGOING ->
                (if (target.isLab || target.isExam) "距结束 " else "距下课 ") + widgetRemainingText(target.endMinute - minute)
            LiveActivityPhase.FINISHED -> null
        }
        return listOfNotNull(status, remaining).joinToString(" · ")
    }

    private fun chipText(target: LiveActivityTarget, phase: LiveActivityPhase): String {
        val plain = !target.isLab && !target.isExam
        return when (phase) {
            LiveActivityPhase.UPCOMING ->
                widgetClock(target.startMinute) + if (target.isExam) "考试" else if (target.isLab) "开始" else "上课"
            LiveActivityPhase.ONGOING, LiveActivityPhase.FINISHED ->
                widgetClock(target.endMinute) + if (plain) "下课" else "结束"
        }
    }

    /** 开课前进度条在课前 N 分钟内逐渐走满；上课后从空开始，走过的部分填色，与 iOS 进度环同向。 */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun progressStyle(
        target: LiveActivityTarget,
        phase: LiveActivityPhase,
        now: LocalDateTime,
        lead: Int,
        accent: Int,
    ): Notification.ProgressStyle {
        val minute = now.hour * 60 + now.minute
        val (max, value) = when (phase) {
            LiveActivityPhase.UPCOMING -> {
                // 手动提前开启时从开启那一刻算起。
                val from = minOf(target.startMinute - lead, minute)
                maxOf(1, target.startMinute - from) to (minute - from).coerceAtLeast(0)
            }
            LiveActivityPhase.ONGOING -> {
                val length = maxOf(1, target.endMinute - target.startMinute)
                length to (minute - target.startMinute).coerceIn(0, length)
            }
            LiveActivityPhase.FINISHED -> 1 to 1
        }
        return Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(max).setColor(accent)))
            .setProgress(value)
    }

    // MARK: 定时

    /**
     * 显示中逐分钟更新进度；否则在下一个关键时刻（课前 N 分钟、开始、结束、零点）再看一次。
     * 与小组件一样不申请精确闹钟权限：非唤醒 RTC 闹钟，息屏时不叫醒手机，亮屏后补上；
     * 进程还在时另用主线程定时器按时对齐。
     */
    private fun scheduleNext(context: Context, today: List<LiveActivityTarget>, now: LocalDateTime, lead: Int, showing: Boolean) {
        val zone = TimeZone.currentSystemDefault()
        val boundary = liveActivityNextBoundary(today, now, lead).toInstant(zone).toEpochMilliseconds()
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        val nextMinute = (nowMillis / 60_000L + 1) * 60_000L
        val fireAt = if (showing) minOf(boundary, nextMinute) else boundary
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm?.set(AlarmManager.RTC, fireAt, broadcast(context, ACTION_REFRESH, null, requestCode = 0))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, (fireAt - nowMillis).coerceAtLeast(1_000L) + 500L)
    }

    // MARK: 状态

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun isEnabled(context: Context, manager: NotificationManager): Boolean {
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled() && !channelBlocked(manager) && manager.canPostPromotedNotifications()
    }

    private fun channelBlocked(manager: NotificationManager): Boolean =
        manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // 低重要性：不响铃、不弹横幅；实时动态仍会出现在状态栏胶囊和通知顶部。
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.live_update_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = context.getString(R.string.live_update_channel_description)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun openSettings(context: Context, action: String) {
        val intent = Intent(action)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(context: Context, action: String, id: String?, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, CourseLiveUpdateReceiver::class.java).setAction(action).apply { id?.let { putExtra(EXTRA_ID, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun leadMinutes(context: Context): Int =
        prefs(context).getInt(KEY_LEAD, LIVE_ACTIVITY_DEFAULT_LEAD_MINUTES)
            .takeIf { it in LIVE_ACTIVITY_LEAD_MINUTE_OPTIONS } ?: LIVE_ACTIVITY_DEFAULT_LEAD_MINUTES

    private fun dismissedIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISMISSED, null)?.toSet().orEmpty()

    private fun now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
}

/** 定时刷新、通知上的“关闭”、用户划掉通知。 */
class CourseLiveUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(CourseLiveUpdates.EXTRA_ID)
        when (intent.action) {
            CourseLiveUpdates.ACTION_STOP, CourseLiveUpdates.ACTION_DISMISSED -> id?.let { CourseLiveUpdates.dismiss(context, it) }
            else -> CourseLiveUpdates.reconcile(context)
        }
    }
}

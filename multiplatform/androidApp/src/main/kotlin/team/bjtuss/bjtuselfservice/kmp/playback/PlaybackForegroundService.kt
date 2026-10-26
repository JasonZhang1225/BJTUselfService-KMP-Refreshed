package team.bjtuss.bjtuselfservice.kmp.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import team.bjtuss.bjtuselfservice.kmp.MainActivity
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackBackgroundState
import team.bjtuss.bjtuselfservice.shared.media.PlaybackBackgroundHost

private const val TAG = "PlaybackForeground"

/**
 * 通知强调色：App 蓝色系（与 res/values/widget_accents.xml 里的课程蓝一致），
 * 浅色通知栏和深色锁屏上都看得清。
 */
private const val PLAYBACK_ACCENT = 0xFF4F6FD8.toInt()

/**
 * `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`。
 * 本机 SDK 的 android-36 平台里没有 `Notification.Builder.setRequestPromotedOngoing`，
 * 直接写这个 extra 效果相同（与 live/CourseLiveUpdates.kt 一致）。
 */
private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

/**
 * 课程回放下载/合成的保活前台服务。
 *
 * 任务本身跑在会话级作用域里（`PlaybackTaskManager`），这里只负责让系统不要在下载、
 * 合成期间把 App 挂起或杀掉，并在通知栏显示进度。任务全部结束后服务自己停掉。
 *
 * Android 16（API 36）起这条常驻通知就是「实时动态（Live Updates）」：系统按
 * `Notification.ProgressStyle` 画进度（紧凑态是环形），并把它提升到状态栏胶囊。
 *
 * 进程被杀后任务不会恢复（与 iOS/macOS 一致），任务卡片显示失败即可。
 */
class PlaybackForegroundService : Service() {
    /** 已经 startForeground 成功过：再次收到命令只刷新通知，不重复 startForeground。 */
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = intent?.readState() ?: defaultState()
        val notification = buildPlaybackNotification(this, state)
        if (foregroundStarted) {
            // 服务已在跑：只刷新通知。后台重复启动前台服务会被系统拒绝。
            notify(notification)
            return START_NOT_STICKY
        }
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground failed: ${it.javaClass.simpleName}") }.isSuccess
        foregroundStarted = started
        isRunning = started
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregroundStarted = false
        isRunning = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed: ${it.javaClass.simpleName}") }
    }

    companion object {
        const val CHANNEL_ID = "playback_download"
        const val NOTIFICATION_ID = 2102
        const val ACTION_UPDATE = "team.bjtuss.bjtuselfservice.kmp.playback.UPDATE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_SIZE = "size"
        const val EXTRA_SPEED = "speed"

        /** 服务是否正在前台运行；用来避免在后台反复启动前台服务。 */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}

/** 通知渠道“课程回放下载”：低优先级、不打扰，任务结束后自动撤掉。 */
private fun ensurePlaybackChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(PlaybackForegroundService.CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        PlaybackForegroundService.CHANNEL_ID,
        "课程回放下载",
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = "下载或合成课程回放时的进度"
        setShowBadge(false)
    }
    manager.createNotificationChannel(channel)
}

/**
 * 回放任务的常驻通知。
 *
 * - Android 16（API 36）起：实时动态。`Notification.ProgressStyle` 画进度，系统据此在状态栏胶囊
 *   （灵动岛那类常驻位置）里画环形进度；通知同时被提升为 promoted ongoing。
 * - 更早的版本：退回普通进度条通知（`setProgress`）。
 */
internal fun buildPlaybackNotification(
    context: Context,
    state: PlaybackBackgroundState,
): Notification {
    ensurePlaybackChannel(context)
    val phase = state.phaseLabel.ifBlank { "课程回放" }
    val body = listOfNotNull(
        state.detail.takeIf { it.isNotBlank() },
        state.sizeLabel,
        state.speedLabel,
    ).joinToString(" · ")
    val builder = Notification.Builder(context, PlaybackForegroundService.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setColor(PLAYBACK_ACCENT)
        // 标题是阶段（下载中/合成中/保存中），课节标题放副标题。
        .setContentTitle(phase)
        .setSubText(state.title)
        // 正文是当前步骤 + 体积 + 速度。
        .setContentText(body.ifBlank { "正在处理课程回放" })
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setShowWhen(false)
        .setContentIntent(openAppIntent(context))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        applyLiveUpdates(builder, state)
    } else {
        applyLegacyProgress(builder, state)
    }
    return builder.build()
}

/**
 * Android 16（API 36）起的实时动态。
 *
 * `Notification.ProgressStyle` 的进度单位跟 `Segment.getLength()` 一致：这里用一段长度 100 的
 * 进度段，所以 `setProgress` 的值就是百分比。进度未知时用不确定进度（系统按唯一的进度段上色）。
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private fun applyLiveUpdates(builder: Notification.Builder, state: PlaybackBackgroundState) {
    val style = Notification.ProgressStyle()
        .setStyledByProgress(true)
        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(PLAYBACK_ACCENT)))
    val progress = state.progress
    if (progress != null) {
        style.setProgress((progress * 100).toInt().coerceIn(0, 100))
    } else {
        style.setProgressIndeterminate(true)
    }
    builder.setStyle(style)
    // 状态栏胶囊里的短文字：知道进度就写百分比，不知道就写阶段名（都很短）。
    builder.setShortCriticalText(state.shortCriticalText())
    // 等同 setRequestPromotedOngoing(true)：这个 extra 就是系统提升常驻实时动态的开关。
    builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
}

/** API 36 以下：普通进度条通知。 */
private fun applyLegacyProgress(builder: Notification.Builder, state: PlaybackBackgroundState) {
    val progress = state.progress
    if (progress != null) {
        builder.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
    } else {
        builder.setProgress(0, 0, true)
    }
}

/** 胶囊里的短文字：百分比优先，其次阶段名（最多 4 个字）。 */
private fun PlaybackBackgroundState.shortCriticalText(): String {
    val progress = progress ?: return phaseLabel.take(4)
    return "${(progress * 100).toInt().coerceIn(0, 100)}%"
}

private fun openAppIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** 从 Intent 里读回任务状态；进度用 -1 表示未知。 */
private fun Intent.readState(): PlaybackBackgroundState {
    val progress = getFloatExtra(PlaybackForegroundService.EXTRA_PROGRESS, -1f)
    return PlaybackBackgroundState(
        title = getStringExtra(PlaybackForegroundService.EXTRA_TITLE).orEmpty(),
        phaseLabel = getStringExtra(PlaybackForegroundService.EXTRA_PHASE).orEmpty(),
        detail = getStringExtra(PlaybackForegroundService.EXTRA_DETAIL).orEmpty(),
        progress = if (progress in 0f..1f) progress else null,
        sizeLabel = getStringExtra(PlaybackForegroundService.EXTRA_SIZE),
        speedLabel = getStringExtra(PlaybackForegroundService.EXTRA_SPEED),
    )
}

/** 兜底状态：服务被空 Intent 拉起时也要给出一条说得清的通知。 */
private fun defaultState(): PlaybackBackgroundState = PlaybackBackgroundState(
    title = "课程回放",
    phaseLabel = "处理中",
    detail = "正在处理课程回放",
    progress = null,
)

/**
 * 把 shared 的 [PlaybackBackgroundHost] 接到 Android 前台服务：
 * 有任务时开前台服务并更新通知，任务全部结束后停服务。
 */
class AndroidPlaybackBackgroundHost(private val context: Context) : PlaybackBackgroundHost {
    private val handler = Handler(Looper.getMainLooper())
    /** 最近一次推来的状态；用来判断结束时是不是终态（终态要多留几秒让用户看到结果）。 */
    private var lastState: PlaybackBackgroundState? = null
    private val stopRunnable = Runnable {
        runCatching { context.stopService(Intent(context, PlaybackForegroundService::class.java)) }
            .onFailure { Log.w(TAG, "stopService failed: ${it.javaClass.simpleName}") }
    }

    override fun update(active: Boolean, state: PlaybackBackgroundState?) {
        if (!active) {
            handler.removeCallbacks(stopRunnable)
            val last = lastState
            if (last != null && last.phaseLabel in TERMINAL_PHASES) {
                // 终态（已完成/失败/已取消）先在通知上停几秒，再撤掉。
                handler.postDelayed(stopRunnable, TERMINAL_LINGER_MILLIS)
            } else {
                stopRunnable.run()
            }
            return
        }
        handler.removeCallbacks(stopRunnable)
        lastState = state
        val payload = state ?: return
        val intent = newIntent(context, payload)
        if (PlaybackForegroundService.isRunning) {
            // 服务已在跑：不能重复启动前台服务（后台启动会被系统拒绝），
            // 改发一条 startService，让服务收到后只刷新通知。
            val delivered = runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "startService rejected: ${it.javaClass.simpleName}") }
                .isSuccess
            if (!delivered) {
                // 后台连 startService 都不允许时，直接改通知，保证进度照样更新。
                notifyDirectly(context, payload)
            }
            return
        }
        runCatching { context.startForegroundService(intent) }
            .onFailure {
                // 已经在后台时系统不允许再启动前台服务；任务在会话作用域里继续跑，不因此失败。
                Log.w(TAG, "startForegroundService rejected: ${it.javaClass.simpleName}")
            }
    }

    private fun notifyDirectly(context: Context, state: PlaybackBackgroundState) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(
                PlaybackForegroundService.NOTIFICATION_ID,
                buildPlaybackNotification(context, state),
            )
        }.onFailure { Log.w(TAG, "notify failed: ${it.javaClass.simpleName}") }
    }

    companion object {
        /** 启动/更新服务用的 Intent：把任务状态放进 extras。 */
        fun newIntent(context: Context, state: PlaybackBackgroundState): Intent =
            Intent(context, PlaybackForegroundService::class.java).apply {
                action = PlaybackForegroundService.ACTION_UPDATE
                putExtra(PlaybackForegroundService.EXTRA_TITLE, state.title)
                putExtra(PlaybackForegroundService.EXTRA_PHASE, state.phaseLabel)
                putExtra(PlaybackForegroundService.EXTRA_DETAIL, state.detail)
                putExtra(PlaybackForegroundService.EXTRA_PROGRESS, state.progress ?: -1f)
                putExtra(PlaybackForegroundService.EXTRA_SIZE, state.sizeLabel)
                putExtra(PlaybackForegroundService.EXTRA_SPEED, state.speedLabel)
            }
    }
}

/** 终态阶段：通知先留着让用户看到结果，再撤掉。 */
private val TERMINAL_PHASES = setOf("已完成", "失败", "已取消")

/** 终态在通知栏停留多久再撤掉。 */
private const val TERMINAL_LINGER_MILLIS = 5_000L

package team.bjtuss.bjtuselfservice.shared.media

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlin.concurrent.Volatile
import platform.BackgroundTasks.BGContinuedProcessingTask
import platform.BackgroundTasks.BGContinuedProcessingTaskRequest
import platform.BackgroundTasks.BGContinuedProcessingTaskRequestSubmissionStrategy
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.dispatch_async
import platform.darwin.dispatch_after
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_get_main_queue
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackBackgroundState

/** App 前后台状态（主线程通知维护，后台线程只读）。 */
internal object IosAppState {
    @Volatile var inBackground = false
        private set
    @Volatile var backgroundTransitions = 0
        private set
    @Volatile var continuedProcessingActive = false

    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            inBackground = true
            backgroundTransitions++
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            inBackground = false
        }
    }
}

/**
 * iOS 的回放任务保活：
 * - iOS 26 起申请系统“继续处理任务”（BGContinuedProcessingTask），系统在灵动岛/锁屏显示进度，
 *   切到后台时下载与合成都能继续；
 * - 更早的系统申请普通后台时间（约 30 秒），下载另由系统后台下载会话继续，合成需回到前台。
 *
 * 系统继续处理任务已带进度活动，不再同时创建自绘活动；申请失败或旧系统才由 Swift 回退。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosPlaybackBackground : PlaybackBackgroundHost {
    /**
     * 只用 Info.plist 里登记过的标识：登记未声明的标识会直接抛异常崩溃；
     * SideStore 重签会改 bundle id，所以不能拿 bundle id 现拼。
     */
    private val identifier: String? =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("BGTaskSchedulerPermittedIdentifiers") as? List<*>)
            ?.filterIsInstance<String>()
            ?.firstOrNull { it.endsWith(".playback.task") }
    private var registered = false
    private var continuedTask: BGContinuedProcessingTask? = null
    private var submitted = false
    private var backgroundTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private var lastState: PlaybackBackgroundState? = null
    private var attemptedForRun = false
    private var activeRun = false
    private var generation = 0
    private var flushScheduled = false
    private var lastPublishedAt = Double.NEGATIVE_INFINITY
    private var lastPublishedPhase: String? = null

    override fun update(active: Boolean, state: PlaybackBackgroundState?) {
        dispatch_async(dispatch_get_main_queue()) { updateOnMain(active, state) }
    }

    private fun updateOnMain(active: Boolean, state: PlaybackBackgroundState?) {
        IosAppState.install()
        if (!active) {
            val succeeded = lastState?.phaseLabel == "已完成"
            activeRun = false
            generation++ // 已安排的旧进度不能在结束后复活实时活动。
            flushScheduled = false
            continuedTask?.let { task ->
                if (succeeded) task.progress.setCompletedUnitCount(1000)
                task.setTaskCompletedWithSuccess(succeeded)
            }
            if (submitted && continuedTask == null) identifier?.let { BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(it) }
            continuedTask = null
            submitted = false
            attemptedForRun = false
            lastState = null
            lastPublishedPhase = null
            lastPublishedAt = Double.NEGATIVE_INFINITY
            IosAppState.continuedProcessingActive = false
            postActivityState(null)
            endBackgroundTask()
            return
        }
        activeRun = true
        lastState = state
        val terminal = state?.phaseLabel in TERMINAL_PHASES
        if (supportsContinuedProcessing()) {
            if (!terminal && !attemptedForRun && !IosAppState.inBackground) {
                attemptedForRun = true // 提交失败或过期后不按每个分片重新申请。
                submitContinuedProcessing(state)
            }
        } else if (backgroundTask == UIBackgroundTaskInvalid) {
            backgroundTask = UIApplication.sharedApplication.beginBackgroundTaskWithName("playback") { endBackgroundTask() }
        }
        val elapsed = NSProcessInfo.processInfo.systemUptime - lastPublishedAt
        if (terminal || state?.phaseLabel != lastPublishedPhase || elapsed >= 2.0) {
            publishLatest()
        } else if (!flushScheduled) {
            flushScheduled = true
            val token = generation
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, ((2.0 - elapsed) * 1_000_000_000).toLong()), dispatch_get_main_queue()) {
                if (token == generation) {
                    flushScheduled = false
                    if (activeRun) updateOnMain(true, lastState)
                }
            }
        }
    }

    private fun publishLatest() {
        val state = lastState ?: return
        lastPublishedAt = NSProcessInfo.processInfo.systemUptime
        lastPublishedPhase = state.phaseLabel
        continuedTask?.let { task ->
            task.updateTitle(state.title, subtitle = listOfNotNull(state.detail.takeIf { it.isNotBlank() }, state.sizeLabel, state.speedLabel).joinToString(" · "))
            // 100% 留到整个任务真正结束；跨路、重试与合成不会另建系统任务。
            state.progress?.let { task.progress.setCompletedUnitCount((it.coerceIn(0f, 0.999f) * 1000).toLong()) }
        }
        postActivityState(state)
    }

    private fun postActivityState(state: PlaybackBackgroundState?) {
        NSNotificationCenter.defaultCenter.postNotificationName(PLAYBACK_LIVE_ACTIVITY_NOTIFICATION, `object` = null,
            userInfo = state?.toUserInfo()?.plus("systemActivity" to (submitted || continuedTask != null)))
    }

    private fun supportsContinuedProcessing(): Boolean =
        NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion } >= 26

    private fun submitContinuedProcessing(state: PlaybackBackgroundState?) {
        val identifier = identifier ?: return
        val scheduler = BGTaskScheduler.sharedScheduler
        if (!registered) {
            registered = scheduler.registerForTaskWithIdentifier(identifier, usingQueue = dispatch_get_main_queue()) { task ->
                val continued = task as? BGContinuedProcessingTask ?: return@registerForTaskWithIdentifier
                if (!activeRun) {
                    continued.setTaskCompletedWithSuccess(false)
                    return@registerForTaskWithIdentifier
                }
                continuedTask = continued
                IosAppState.continuedProcessingActive = true
                continued.progress.setTotalUnitCount(1000)
                lastState?.progress?.let { continued.progress.setCompletedUnitCount((it * 1000).toLong()) }
                continued.updateTitle(lastState?.title.orEmpty(), subtitle = lastState?.detail.orEmpty())
                continued.setExpirationHandler {
                    dispatch_async(dispatch_get_main_queue()) {
                        if (continuedTask !== continued) return@dispatch_async
                        // 系统收回后台时间：任务本身不取消，回到前台后继续（合成会从头重来）。
                        IosAppState.continuedProcessingActive = false
                        continued.setTaskCompletedWithSuccess(false)
                        continuedTask = null
                        // 不在随后每次进度里再次提交；使用自绘活动回退，等待下一次明确新任务。
                        submitted = false
                        postActivityState(lastState)
                    }
                }
                publishLatest()
            }
        }
        val request = BGContinuedProcessingTaskRequest(
            identifier = identifier,
            title = state?.title?.ifBlank { "课程回放" } ?: "课程回放",
            subtitle = state?.detail.orEmpty(),
        )
        request.setStrategy(BGContinuedProcessingTaskRequestSubmissionStrategy.BGContinuedProcessingTaskRequestSubmissionStrategyFail)
        submitted = scheduler.submitTaskRequest(request, error = null)
    }

    private fun endBackgroundTask() {
        if (backgroundTask != UIBackgroundTaskInvalid) {
            UIApplication.sharedApplication.endBackgroundTask(backgroundTask)
            backgroundTask = UIBackgroundTaskInvalid
        }
    }
}

private val TERMINAL_PHASES = setOf("已完成", "失败", "已取消")

/** Swift 宿主监听此通知更新灵动岛/锁屏实时活动；名称与 Swift 侧一致。 */
const val PLAYBACK_LIVE_ACTIVITY_NOTIFICATION = "BJTUPlaybackLiveActivityDidChange"

/** 把共享状态转成通知的 userInfo（Swift 侧按同样的键读取）。 */
private fun PlaybackBackgroundState.toUserInfo(): Map<Any?, Any?> = mapOf(
    "title" to title,
    "phase" to phaseLabel,
    "detail" to detail,
    "progress" to (progress ?: -1f),
    "size" to (sizeLabel ?: ""),
    "speed" to (speedLabel ?: ""),
)

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
 * 同时把进度发给 Swift 宿主，由它更新灵动岛/锁屏实时活动（ActivityKit 只有 Swift 接口）。
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

    override fun update(active: Boolean, state: PlaybackBackgroundState?) {
        dispatch_async(dispatch_get_main_queue()) { updateOnMain(active, state) }
    }

    private fun updateOnMain(active: Boolean, state: PlaybackBackgroundState?) {
        IosAppState.install()
        lastState = if (active) state else null
        // 实时活动由 Swift 宿主绘制：把状态发过去即可（App 退到后台后这条通知仍会送达）。
        NSNotificationCenter.defaultCenter.postNotificationName(
            PLAYBACK_LIVE_ACTIVITY_NOTIFICATION,
            `object` = null,
            userInfo = if (active) state?.toUserInfo() else null,
        )
        if (!active) {
            continuedTask?.let { task ->
                task.progress.setCompletedUnitCount(1000)
                task.setTaskCompletedWithSuccess(true)
            }
            continuedTask = null
            submitted = false
            IosAppState.continuedProcessingActive = false
            endBackgroundTask()
            return
        }
        if (supportsContinuedProcessing()) {
            if (!submitted && !IosAppState.inBackground) submitContinuedProcessing(state)
            continuedTask?.let { task ->
                task.updateTitle(state?.title.orEmpty(), subtitle = state?.detail.orEmpty())
                state?.progress?.let { task.progress.setCompletedUnitCount((it * 1000).toLong()) }
            }
        } else if (backgroundTask == UIBackgroundTaskInvalid) {
            backgroundTask = UIApplication.sharedApplication.beginBackgroundTaskWithName("playback") { endBackgroundTask() }
        }
    }

    private fun supportsContinuedProcessing(): Boolean =
        NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion } >= 26

    private fun submitContinuedProcessing(state: PlaybackBackgroundState?) {
        val identifier = identifier ?: return
        val scheduler = BGTaskScheduler.sharedScheduler
        if (!registered) {
            registered = scheduler.registerForTaskWithIdentifier(identifier, usingQueue = null) { task ->
                val continued = task as? BGContinuedProcessingTask ?: return@registerForTaskWithIdentifier
                continuedTask = continued
                IosAppState.continuedProcessingActive = true
                continued.progress.setTotalUnitCount(1000)
                lastState?.progress?.let { continued.progress.setCompletedUnitCount((it * 1000).toLong()) }
                continued.updateTitle(lastState?.title.orEmpty(), subtitle = lastState?.detail.orEmpty())
                continued.setExpirationHandler {
                    // 系统收回后台时间：任务本身不取消，回到前台后继续（合成会从头重来）。
                    IosAppState.continuedProcessingActive = false
                    continued.setTaskCompletedWithSuccess(false)
                    continuedTask = null
                    submitted = false
                }
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

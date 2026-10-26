import ActivityKit
import Foundation
import UIKit

/// 共享层（Kotlin）发状态通知，Swift 宿主把它转成 ActivityKit 实时活动。
enum PlaybackDownloadBridge {
    /// 与 shared/.../media/IosPlaybackBackground.kt 的 PLAYBACK_LIVE_ACTIVITY_NOTIFICATION 一致。
    static let stateNotification = Notification.Name("BJTUPlaybackLiveActivityDidChange")
}

/// 回放下载实时活动的唯一所有者。
///
/// 共享层每次任务状态变化都会发一条通知（userInfo 里带标题、阶段、步骤、进度、体积、速度）：
/// - 有任务时开一个活动并持续更新；
/// - 任务结束后先把终态（已完成/失败/已取消）留在锁屏上，几十秒后再消失。
///
/// 实时活动只能在 App 在前台时新建：后台只更新已有活动。后台收到状态时先记下来，
/// 回到前台再补建，不会漏掉第一次进度。
@MainActor
final class PlaybackDownloadCoordinator {
    static let shared = PlaybackDownloadCoordinator()

    private var activity: Activity<PlaybackDownloadAttributes>?
    private var lastState: PlaybackDownloadAttributes.ContentState?
    private var desiredState: PlaybackDownloadAttributes.ContentState?
    private var revision = 0
    private var appliedRevision = -1
    private var worker: Task<Void, Never>?
    private var lastPublishedAt = -Double.infinity
    private var dismissedByUser = false
    private var observers: [NSObjectProtocol] = []

    private init() {}

    /// App 启动时调用一次。
    func start() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: PlaybackDownloadBridge.stateNotification, object: nil, queue: .main) { [weak self] note in
                let info = note.userInfo ?? [:]
                // queue: .main 保证主线程；同步收下事件，避免 Task 的调度顺序反转结束/更新。
                MainActor.assumeIsolated {
                    let usesSystemActivity = (info["systemActivity"] as? NSNumber)?.boolValue ?? false
                    self?.receive(usesSystemActivity ? nil : Self.state(from: info))
                }
            },
            // 后台收到的状态在回前台时补上。
            center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self, let desired = self.desiredState else { return }
                    self.receive(desired)
                }
            },
        ]
        receive(nil) // 清理上次进程留下的自绘活动；不影响上课活动或系统后台任务。
    }

    /// 进程重启后可能还有活动悬着（App 被杀而活动没结束），接管它。
    private func adoptExisting() async {
        for existing in Activity<PlaybackDownloadAttributes>.activities {
            if activity == nil && (existing.activityState == .active || existing.activityState == .stale) {
                activity = existing
            } else if existing.id != activity?.id {
                await existing.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    private func receive(_ state: PlaybackDownloadAttributes.ContentState?) {
        desiredState = state // nil 也清掉后台暂存状态，回前台不会复活已结束活动。
        revision &+= 1
        guard worker == nil else { return }
        worker = Task { await self.drain() }
    }

    /// 单个 worker 串行 update/end；密集进度只交付最新值。
    private func drain() async {
        while appliedRevision != revision {
            let currentRevision = revision
            let state = desiredState
            if let state, !state.isTerminal, state.phase == lastState?.phase {
                let remaining = 2 - (ProcessInfo.processInfo.systemUptime - lastPublishedAt)
                if remaining > 0 {
                    try? await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
                    if currentRevision != revision { continue }
                }
            }
            await apply(state)
            appliedRevision = currentRevision
            lastPublishedAt = ProcessInfo.processInfo.systemUptime
        }
        worker = nil
    }

    private func apply(_ state: PlaybackDownloadAttributes.ContentState?) async {
        // 终态要短暂留在锁屏上，所以先记住上一个状态是不是终态，再覆盖。
        let wasTerminal = lastState?.isTerminal ?? false
        lastState = state
        guard let state else {
            // 没有任务了：终态短暂停留后消失，其他情况立刻收起。
            let policy: ActivityUIDismissalPolicy = wasTerminal
                ? .after(Date().addingTimeInterval(45))
                : .immediate
            activity = nil
            dismissedByUser = false
            for existing in Activity<PlaybackDownloadAttributes>.activities {
                await existing.end(nil, dismissalPolicy: policy)
            }
            return
        }
        if activity?.activityState == .dismissed { dismissedByUser = true; activity = nil }
        guard !dismissedByUser else { return }
        await adoptExisting()
        // 清理重复活动期间可能收到结束或系统接管事件，不能再申请旧活动。
        guard desiredState != nil else { return }
        if let activity {
            await activity.update(content(for: state))
            return
        }
        // 新建活动只能在 App 在前台时做；后台先把状态存着，回前台再补。
        guard UIApplication.shared.applicationState == .active else { return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        do {
            let created = try Activity.request(
                attributes: PlaybackDownloadAttributes(),
                content: content(for: state),
                pushType: nil
            )
            activity = created
        } catch {
            // 实时活动开不出来（例如用户关掉了）不影响下载本身。
            NSLog("BJTUPlaybackLiveActivity request failed: \(error.localizedDescription)")
        }
    }

    private func content(for state: PlaybackDownloadAttributes.ContentState) -> ActivityContent<PlaybackDownloadAttributes.ContentState> {
        ActivityContent(state: state, staleDate: Date().addingTimeInterval(600))
    }

    /// 解析共享层发来的状态；不碰实例状态，标成 nonisolated 供通知回调直接调用。
    nonisolated private static func state(from info: [AnyHashable: Any]) -> PlaybackDownloadAttributes.ContentState? {
        guard let title = info["title"] as? String, !title.isEmpty else { return nil }
        let phase = info["phase"] as? String ?? "下载中"
        let detail = info["detail"] as? String ?? ""
        // Kotlin 侧发的是 Float（-1 表示未知），按 NSNumber 取，避免类型变了悄悄退化成"未知"。
        let progress = (info["progress"] as? NSNumber)?.doubleValue ?? -1
        let size = info["size"] as? String ?? ""
        let speed = info["speed"] as? String ?? ""
        return PlaybackDownloadAttributes.ContentState(
            title: title,
            phase: phase,
            detail: detail,
            progress: progress,
            size: size,
            speed: speed
        )
    }
}

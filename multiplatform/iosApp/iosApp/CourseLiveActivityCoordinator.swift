import ActivityKit
import BackgroundTasks
import Foundation
import os
import UIKit

/// 与 shared/.../feature/home/LiveActivityButton.ios.kt 的常量一一对应。
/// Kotlin 卡片发命令通知、读 UserDefaults 状态；ActivityKit 只有 Swift 接口，由宿主代办。
enum CourseLiveActivityBridge {
    static let commandNotification = Notification.Name("BJTULiveActivityCommand")
    static let statusNotification = Notification.Name("BJTULiveActivityStatusDidChange")
    static let snapshotNotification = Notification.Name("BJTUCourseWidgetSnapshotDidChange")
    static let supportedKey = "BJTULiveActivity.supported"
    static let enabledKey = "BJTULiveActivity.enabled"
    static let activeIdKey = "BJTULiveActivity.activeId"
    /// 课前多少分钟自动出现（10 或 20），由卡片菜单写入。
    static let leadMinutesKey = "BJTULiveActivity.leadMinutes"
    /// 必须同时写在 Info.plist 的 BGTaskSchedulerPermittedIdentifiers 里，否则注册时崩溃。
    static let refreshTaskId = "team.bjtuss.bjtuselfservice.kmp.ios.liveactivity"
}

/// 日程实时活动的唯一所有者：课前 10 或 20 分钟（卡片菜单里选）自动出现，到这一项结束为止。
///
/// - 只用一个活动实例，换课时更新内容：后台只能更新、不能新建实时活动。
/// - 用户在锁屏划掉或在卡片里关闭的那一项不再自动打开；卡片上的 Live 菜单可以重新开启。
/// - 手动开启的那一项优先显示（可早于自动出现的时间），直到它结束。
/// - iOS 26 起，前台时再预约“下一项需要新开实时活动的日程”，由系统在课前 N 分钟准点启动，
///   App 被挂起或被杀也不影响（见 `reconcileScheduled`）。iOS 18–25 仍要课前打开过 App 才出现。
@MainActor
final class CourseLiveActivityCoordinator {
    static let shared = CourseLiveActivityCoordinator()

    /// 已经在显示时，课间不超过这么久就直接接上下一项。
    private static let handoffWindow: TimeInterval = 30 * 60

    private let defaults = UserDefaults.standard
    private let dismissedKey = "BJTULiveActivity.dismissedIds"
    private let manualKey = "BJTULiveActivity.manualId"
    /// 已预约（尚未开始）的那一项及其出现时刻；过了出现时刻视同已在显示，划掉也算关闭。
    private let scheduledKey = "BJTULiveActivity.scheduledId"
    private let scheduledAppearKey = "BJTULiveActivity.scheduledAppear"

    private var observers: [NSObjectProtocol] = []
    private var watchedActivities = Set<String>()
    private var boundaryTimer: Timer?
    private var reconciling = false
    private var pendingReconcile = false
    /// 卡片开启时带来的内容；快照里找不到这一项时兜底（不持久化）。
    private var manualFallback: CourseLiveActivityAttributes.ContentState?

    private init() {}

    func start() {
        guard observers.isEmpty else { return }
        #if DEBUG
        // 仅 debug：`-BJTULiveActivityScheduleTest <秒>` 预约一节虚构课程在 N 秒后由系统启动，
        // 用来验证 App 被杀后系统是否准点开出实时活动；不跑正常对齐（否则会取消这条预约）。
        // 系统启动它时会在后台拉起 App，正常对齐发现课表里没有这节课，几秒后会把它收起，属预期；
        // 看系统日志 liveactivitiesd 的 "Starting delayed activity" 即可确认准点启动。
        let arguments = ProcessInfo.processInfo.arguments
        if let index = arguments.firstIndex(of: "-BJTULiveActivityScheduleTest"),
           index + 1 < arguments.count, let seconds = TimeInterval(arguments[index + 1]) {
            scheduleTestActivity(after: seconds)
            return
        }
        #endif
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: CourseLiveActivityBridge.commandNotification, object: nil, queue: .main) { [weak self] note in
                let info = (note.userInfo ?? [:]).reduce(into: [String: String]()) { result, pair in
                    if let key = pair.key as? String, let value = pair.value as? String { result[key] = value }
                }
                Task { @MainActor in self?.handle(command: info) }
            },
            center.addObserver(forName: CourseLiveActivityBridge.snapshotNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.reconcileSoon() }
            },
            center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.reconcileSoon() }
            },
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in
                    self?.boundaryTimer?.invalidate()
                    self?.scheduleBackgroundRefresh()
                }
            },
        ]
        Task { @MainActor [weak self] in
            for await _ in ActivityAuthorizationInfo().activityEnablementUpdates {
                self?.reconcileSoon()
            }
        }
        for activity in Activity<CourseLiveActivityAttributes>.activities { watch(activity) }
        reconcileSoon()
    }

    // MARK: 卡片命令

    private func handle(command info: [String: String]) {
        guard let action = info["action"], let id = info["id"] else { return }
        absorbExternalDismissal()
        switch action {
        case "start":
            var dismissed = dismissedIds
            dismissed.remove(id)
            dismissedIds = dismissed
            manualId = id
            manualFallback = fallbackState(from: info, id: id)
        case "lead":
            // 只改了提前时间（已写入 UserDefaults），重新对齐即可。
            break
        case "arm":
            // 恢复“课前 N 分钟自动显示”，不提前出现。
            var dismissed = dismissedIds
            dismissed.remove(id)
            dismissedIds = dismissed
            if manualId == id {
                manualId = nil
                manualFallback = nil
            }
        case "stop":
            var dismissed = dismissedIds
            dismissed.insert(id)
            dismissedIds = dismissed
            if manualId == id {
                manualId = nil
                manualFallback = nil
            }
        default:
            return
        }
        reconcileSoon()
    }

    private func fallbackState(from info: [String: String], id: String) -> CourseLiveActivityAttributes.ContentState? {
        guard let dayKey = info["day"], let day = CourseWidgetCalendar.date(fromKey: dayKey),
              let startMinute = info["startMinute"].flatMap(Int.init),
              let endMinute = info["endMinute"].flatMap(Int.init), endMinute > startMinute else { return nil }
        return CourseLiveActivityAttributes.ContentState(
            occurrenceId: id,
            name: info["name"] ?? "",
            place: info["place"] ?? "",
            teacher: info["teacher"] ?? "",
            kind: info["kind"] ?? "unknown",
            color: info["color"].flatMap { Int64($0) } ?? 0x4F6FD8,
            start: day.addingTimeInterval(TimeInterval(startMinute * 60)),
            end: day.addingTimeInterval(TimeInterval(endMinute * 60)),
            nextName: nil,
            nextStart: nil,
            leadMinutes: leadMinutes
        )
    }

    // MARK: 对齐期望状态

    func reconcileSoon() {
        Task { @MainActor in await reconcile() }
    }

    func reconcile() async {
        // 等待 ActivityKit 时可能重入；合并成一轮跑完。
        if reconciling {
            pendingReconcile = true
            return
        }
        reconciling = true
        repeat {
            pendingReconcile = false
            await reconcileOnce()
        } while pendingReconcile
        reconciling = false
        publishStatus()
        armBoundaryTimer()
    }

    private func reconcileOnce() async {
        let now = Date()
        adoptStartedSchedule(now: now)
        absorbExternalDismissal()
        pruneDismissed(now: now)
        let running = runningActivities()
        guard let occurrences = todayOccurrences(now: now) else {
            // 没有快照（未登录或已退出）：收起所有实时活动，取消预约。
            presentedId = nil
            for activity in running { await activity.end(nil, dismissalPolicy: .immediate) }
            await reconcileScheduled(now: now, desired: nil, cancelOnly: true)
            return
        }
        let desired = desiredState(now: now, occurrences: occurrences, hasRunning: !running.isEmpty)
        await reconcileScheduled(now: now, desired: desired, cancelOnly: false)
        guard let desired else {
            presentedId = nil
            for activity in running { await activity.end(nil, dismissalPolicy: .immediate) }
            return
        }
        let content = ActivityContent(state: desired, staleDate: desired.staleDate(after: now))
        if let current = running.first {
            if current.content.state != desired || current.content.staleDate != content.staleDate {
                await current.update(content)
            }
            presentedId = desired.occurrenceId
            for extra in running.dropFirst() { await extra.end(nil, dismissalPolicy: .immediate) }
        } else if UIApplication.shared.applicationState != .background,
                  ActivityAuthorizationInfo().areActivitiesEnabled {
            do {
                let activity = try Activity.request(
                    attributes: CourseLiveActivityAttributes(),
                    content: content,
                    pushType: nil
                )
                presentedId = desired.occurrenceId
                watch(activity)
            } catch {
                NSLog("CourseLiveActivity request failed: %@", String(describing: error))
            }
        }
    }

    private func desiredState(now: Date, occurrences: [CourseOccurrence], hasRunning: Bool) -> CourseLiveActivityAttributes.ContentState? {
        if let manual = manualId {
            if let occurrence = occurrences.first(where: { occurrenceId($0) == manual }), occurrence.end > now {
                return state(for: occurrence, in: occurrences)
            }
            if let fallback = manualFallback, fallback.occurrenceId == manual, fallback.end > now {
                return fallback
            }
            manualId = nil
            manualFallback = nil
        }
        let dismissed = dismissedIds
        let leadTime = TimeInterval(leadMinutes * 60)
        let lead = hasRunning ? max(leadTime, Self.handoffWindow) : leadTime
        let candidate = occurrences
            .filter { $0.end > now && $0.start.addingTimeInterval(-lead) <= now && !dismissed.contains(occurrenceId($0)) }
            .min { ($0.start, $0.end) < ($1.start, $1.end) }
        return candidate.map { state(for: $0, in: occurrences) }
    }

    private func state(for occurrence: CourseOccurrence, in occurrences: [CourseOccurrence]) -> CourseLiveActivityAttributes.ContentState {
        let next = occurrences.filter { $0.start >= occurrence.end }.min { $0.start < $1.start }
        let session = occurrence.session
        return CourseLiveActivityAttributes.ContentState(
            occurrenceId: occurrenceId(occurrence),
            name: session.name,
            place: session.place,
            teacher: session.teacher,
            kind: session.kind ?? (session.isLab ? "lab" : "unknown"),
            color: session.color,
            start: occurrence.start,
            end: occurrence.end,
            nextName: next?.session.name,
            nextStart: next?.start,
            leadMinutes: leadMinutes
        )
    }

    /// 今天的日程；没有快照（未登录或已退出）时为 nil，此时收起所有实时活动。
    private func todayOccurrences(now: Date) -> [CourseOccurrence]? {
        guard case .loaded(let snapshot) = CourseWidgetStore.load() else { return nil }
        let key = CourseWidgetCalendar.dayKey(now)
        guard let day = snapshot.days.first(where: { $0.date == key }) else { return [] }
        return CourseWidgetCalendar.plan(for: day)?.occurrences ?? []
    }

    private func occurrenceId(_ occurrence: CourseOccurrence) -> String {
        courseLiveActivityOccurrenceId(
            day: CourseWidgetCalendar.dayKey(occurrence.start),
            startMinute: occurrence.session.startMinute,
            endMinute: occurrence.session.endMinute,
            name: occurrence.session.name
        )
    }

    // MARK: 预约下一项（iOS 26+）

    /// 下一项“需要新开实时活动”的日程：今天和明天里，出现时刻（课前 N 分钟）还没到、没被关掉，
    /// 且不会被正在显示的那一项接上的第一项。
    ///
    /// 重叠日程：与正在显示的那一项时间重叠、或出现时刻落在它结束后 30 分钟接力窗口内的，
    /// 由同一个实时活动接上（与 `desiredState` 的接力规则一致），不另外预约，避免两个同时出现；
    /// 接上的那一项再往后接，沿着这条接力链看到底。同一时刻开始的几项只看最早结束的那一项。
    private func scheduleCandidate(now: Date, desired: CourseLiveActivityAttributes.ContentState?) -> CourseOccurrence? {
        let dismissed = dismissedIds
        let lead = TimeInterval(leadMinutes * 60)
        let handoffLead = max(lead, Self.handoffWindow)
        var chainEnd = desired?.end
        for occurrence in upcomingOccurrences(now: now) where occurrence.end > now {
            let id = occurrenceId(occurrence)
            if id == desired?.occurrenceId { continue }
            if let end = chainEnd, occurrence.start.addingTimeInterval(-handoffLead) <= end {
                if !dismissed.contains(id) { chainEnd = max(end, occurrence.end) }
                continue
            }
            // 被关掉的不预约；出现时刻已过的由 desiredState 当场显示，不必预约。
            if dismissed.contains(id) || occurrence.start.addingTimeInterval(-lead) <= now { continue }
            return occurrence
        }
        return nil
    }

    /// 保持“至多一个预约”与 [scheduleCandidate] 一致：内容或出现时刻变了就取消重约。
    /// 预约只能在前台发起；后台（后台刷新）只做取消，回到前台再补约。
    private func reconcileScheduled(now: Date, desired: CourseLiveActivityAttributes.ContentState?, cancelOnly: Bool) async {
        guard #available(iOS 26.0, *) else { return }
        let pending = Activity<CourseLiveActivityAttributes>.activities.filter { $0.activityState == .pending }
        let candidate = cancelOnly ? nil : scheduleCandidate(now: now, desired: desired)
        guard let candidate, ActivityAuthorizationInfo().areActivitiesEnabled else {
            for activity in pending { await activity.end(nil, dismissalPolicy: .immediate) }
            clearSchedule()
            return
        }
        let sameDay = upcomingOccurrences(now: now).filter {
            CourseWidgetCalendar.dayKey($0.start) == CourseWidgetCalendar.dayKey(candidate.start)
        }
        let state = state(for: candidate, in: sameDay)
        let appear = candidate.start.addingTimeInterval(-TimeInterval(leadMinutes * 60))
        if pending.count == 1, pending[0].content.state == state, scheduledId == state.occurrenceId, scheduledAppear == appear {
            return
        }
        for activity in pending { await activity.end(nil, dismissalPolicy: .immediate) }
        clearSchedule()
        guard UIApplication.shared.applicationState != .background else { return }
        do {
            let activity = try Activity.request(
                attributes: CourseLiveActivityAttributes(),
                content: ActivityContent(state: state, staleDate: state.start),
                pushType: nil,
                style: .standard,
                alertConfiguration: startAlert(for: state),
                start: appear
            )
            scheduledId = state.occurrenceId
            scheduledAppear = appear
            watch(activity)
        } catch {
            NSLog("CourseLiveActivity schedule failed: %@", String(describing: error))
        }
    }

    /// 系统准点启动预约时的提醒（必填）：默认提示音；标题、正文用在无法显示实时活动界面的地方（如手表）。
    @available(iOS 26.0, *)
    private func startAlert(for state: CourseLiveActivityAttributes.ContentState) -> AlertConfiguration {
        let title = state.statusText(at: state.start.addingTimeInterval(-1))
        let body = [Self.clock(state.start) + " " + state.name, state.place].filter { !$0.isEmpty }.joined(separator: " · ")
        return AlertConfiguration(title: "\(title)", body: "\(body)", sound: .default)
    }

    /// 预约的出现时刻已过：系统已经替我们开了（或用户已划掉），记作“正在显示”，以后按同一套规则对齐。
    /// 出现时刻刚到、系统还没把它从 pending 切到 active 时不算：否则会被误当成“已开过又被划掉”。
    /// 这时前台的 [reconcileScheduled] 会取消这条预约，由 desiredState 当场开出同一项。
    private func adoptStartedSchedule(now: Date) {
        guard let id = scheduledId, let appear = scheduledAppear, appear <= now else { return }
        if #available(iOS 26.0, *), Activity<CourseLiveActivityAttributes>.activities.contains(where: {
            $0.activityState == .pending && $0.content.state.occurrenceId == id
        }) {
            return
        }
        if presentedId == nil { presentedId = id }
        clearSchedule()
    }

    private func clearSchedule() {
        scheduledId = nil
        scheduledAppear = nil
    }

    /// 今天和明天的日程，按开始、结束排序。明天的第一节可以在前一晚打开 App 时就约好。
    private func upcomingOccurrences(now: Date) -> [CourseOccurrence] {
        guard case .loaded(let snapshot) = CourseWidgetStore.load() else { return [] }
        let today = CourseWidgetCalendar.dayKey(now)
        return snapshot.days
            .filter { $0.date >= today }
            .prefix(2)
            .flatMap { CourseWidgetCalendar.plan(for: $0)?.occurrences ?? [] }
            .sorted { ($0.start, $0.end) < ($1.start, $1.end) }
    }

    #if DEBUG
    private func scheduleTestActivity(after seconds: TimeInterval) {
        guard #available(iOS 26.0, *) else { return }
        Task { @MainActor in
            for activity in Activity<CourseLiveActivityAttributes>.activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
            let appear = Date().addingTimeInterval(seconds)
            let start = appear.addingTimeInterval(TimeInterval(leadMinutes * 60))
            let state = CourseLiveActivityAttributes.ContentState(
                occurrenceId: "test", name: "测试课程", place: "思源楼 SY101", teacher: "测试教师",
                kind: "required", color: 0x4F6FD8, start: start, end: start.addingTimeInterval(40 * 60),
                nextName: nil, nextStart: nil, leadMinutes: leadMinutes
            )
            do {
                _ = try Activity.request(
                    attributes: CourseLiveActivityAttributes(),
                    content: ActivityContent(state: state, staleDate: start),
                    pushType: nil, style: .standard, alertConfiguration: startAlert(for: state), start: appear
                )
                NSLog("CourseLiveActivity test scheduled at %@", appear as NSDate)
            } catch {
                NSLog("CourseLiveActivity test schedule failed: %@", String(describing: error))
            }
        }
    }
    #endif

    private static func clock(_ date: Date) -> String {
        let parts = CourseWidgetCalendar.calendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0)
    }

    private func runningActivities() -> [Activity<CourseLiveActivityAttributes>] {
        Activity<CourseLiveActivityAttributes>.activities.filter {
            $0.activityState == .active || $0.activityState == .stale
        }
    }

    /// 记着在显示、系统里却已经没有了：用户在锁屏划掉或长按关闭。这一项不再自动打开。
    private func absorbExternalDismissal() {
        guard let presented = presentedId, runningActivities().isEmpty else { return }
        var dismissed = dismissedIds
        dismissed.insert(presented)
        dismissedIds = dismissed
        if manualId == presented {
            manualId = nil
            manualFallback = nil
        }
        presentedId = nil
    }

    private func pruneDismissed(now: Date) {
        let today = CourseWidgetCalendar.dayKey(now)
        let kept = dismissedIds.filter { String($0.prefix(10)) >= today }
        if kept.count != dismissedIds.count { dismissedIds = kept }
    }

    private func watch(_ activity: Activity<CourseLiveActivityAttributes>) {
        guard watchedActivities.insert(activity.id).inserted else { return }
        Task { @MainActor [weak self] in
            for await state in activity.activityStateUpdates {
                // 预约的那一项被系统准点启动（pending → active）：App 恰好在运行时立即对齐一次。
                self?.reconcileSoon()
                if state == .dismissed || state == .ended { break }
            }
        }
    }

    // MARK: 定时

    /// 前台时在下一个关键时刻（课前 N 分钟、开始、结束）醒来再对齐一次。
    private func armBoundaryTimer() {
        boundaryTimer?.invalidate()
        guard UIApplication.shared.applicationState == .active,
              let fire = nextBoundary(after: Date()) else { return }
        boundaryTimer = Timer.scheduledTimer(withTimeInterval: max(1, fire.timeIntervalSinceNow + 1), repeats: false) { _ in
            Task { @MainActor in CourseLiveActivityCoordinator.shared.reconcileSoon() }
        }
    }

    private func nextBoundary(after now: Date) -> Date? {
        let occurrences = todayOccurrences(now: now) ?? []
        let lead = TimeInterval(leadMinutes * 60)
        let moments = occurrences.flatMap { [$0.start.addingTimeInterval(-lead), $0.start, $0.end] }
        let next = moments.filter { $0 > now }.min()
        // 跨过零点也要醒一次，换到新一天的日程。
        let midnight = CourseWidgetCalendar.calendar.date(
            byAdding: .day, value: 1, to: CourseWidgetCalendar.calendar.startOfDay(for: now)
        )
        return [next, midnight].compactMap { $0 }.min()
    }

    /// 在后台尽力按时换课或收起；系统不保证准点，回到前台时总会再对齐。
    func scheduleBackgroundRefresh() {
        guard presentedId != nil, let date = nextBoundary(after: Date()) else { return }
        let request = BGAppRefreshTaskRequest(identifier: CourseLiveActivityBridge.refreshTaskId)
        request.earliestBeginDate = date
        try? BGTaskScheduler.shared.submit(request)
    }

    /// 启动处理在后台队列被调用，不能继承主线程隔离。
    nonisolated static func registerBackgroundRefresh() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: CourseLiveActivityBridge.refreshTaskId, using: nil) { task in
            // 正常完成与到期都必须报告完成，且只报一次；到期不报会被系统结束并降低以后的唤醒频率。
            let completed = OSAllocatedUnfairLock(initialState: false)
            let finish = { (success: Bool) in
                let first = completed.withLock { (done: inout Bool) -> Bool in
                    defer { done = true }
                    return !done
                }
                if first { task.setTaskCompleted(success: success) }
            }
            let work = Task { @MainActor in
                // 先预约下一次：等待 ActivityKit 时到期，也不会断掉后台换课。
                CourseLiveActivityCoordinator.shared.scheduleBackgroundRefresh()
                await CourseLiveActivityCoordinator.shared.reconcile()
                CourseLiveActivityCoordinator.shared.scheduleBackgroundRefresh()
                finish(true)
            }
            // ActivityKit 的等待不响应取消，到期时直接报告失败，不等它跑完。
            task.expirationHandler = {
                work.cancel()
                finish(false)
            }
        }
    }

    // MARK: 状态

    private func publishStatus() {
        defaults.set(true, forKey: CourseLiveActivityBridge.supportedKey)
        defaults.set(ActivityAuthorizationInfo().areActivitiesEnabled, forKey: CourseLiveActivityBridge.enabledKey)
        NotificationCenter.default.post(name: CourseLiveActivityBridge.statusNotification, object: nil)
    }

    /// 卡片菜单选的提前分钟数；没选过或值不认识时用默认值。
    private var leadMinutes: Int {
        let stored = defaults.integer(forKey: CourseLiveActivityBridge.leadMinutesKey)
        return courseLiveActivityLeadMinuteOptions.contains(stored) ? stored : courseLiveActivityDefaultLeadMinutes
    }

    private var presentedId: String? {
        get { defaults.string(forKey: CourseLiveActivityBridge.activeIdKey) }
        set { defaults.set(newValue, forKey: CourseLiveActivityBridge.activeIdKey) }
    }

    private var manualId: String? {
        get { defaults.string(forKey: manualKey) }
        set { defaults.set(newValue, forKey: manualKey) }
    }

    private var scheduledId: String? {
        get { defaults.string(forKey: scheduledKey) }
        set { defaults.set(newValue, forKey: scheduledKey) }
    }

    private var scheduledAppear: Date? {
        get { defaults.object(forKey: scheduledAppearKey) as? Date }
        set { defaults.set(newValue, forKey: scheduledAppearKey) }
    }

    private var dismissedIds: Set<String> {
        get { Set(defaults.stringArray(forKey: dismissedKey) ?? []) }
        set { defaults.set(Array(newValue), forKey: dismissedKey) }
    }
}

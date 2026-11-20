import SwiftUI
import WidgetKit
import BJTUShared

private final class AppRuntimeOwner: ObservableObject {
    let runtime = IosAppRuntimeOwner()
    init() {
        runtime.setSystemCleanupHandler { mode, completed in
            Task { @MainActor in
                let courses = await CourseLiveActivityCoordinator.shared.clearAccountData(allLocalData: mode == "all")
                let playback = await PlaybackDownloadCoordinator.shared.clearAccountData()
                completed(courses && playback ? "ok" : "failed")
            }
        }
    }
    deinit { runtime.close() }
}

@main
struct iOSApp: App {
    @StateObject private var runtimeOwner = AppRuntimeOwner()

    init() {
        // 共享层写完课程小组件快照后发此通知；WidgetKit 只有 Swift 接口，由宿主代为刷新。
        // 名称与 CourseWidgetStore.ios.kt 的 COURSE_WIDGET_DID_CHANGE_NOTIFICATION 一致。
        NotificationCenter.default.addObserver(
            forName: Notification.Name("BJTUCourseWidgetSnapshotDidChange"),
            object: nil,
            queue: .main
        ) { _ in
            WidgetCenter.shared.reloadAllTimelines()
        }
        // 日程实时活动（灵动岛）：后台刷新任务必须在启动完成前注册。
        CourseLiveActivityCoordinator.registerBackgroundRefresh()
        Task { @MainActor in CourseLiveActivityCoordinator.shared.start() }
        // 回放下载实时活动（灵动岛）：共享层发状态通知，这里开/更新活动。
        Task { @MainActor in PlaybackDownloadCoordinator.shared.start() }
    }

    var body: some Scene {
        WindowGroup {
            ContentView(runtimeOwner: runtimeOwner.runtime)
        }
    }
}

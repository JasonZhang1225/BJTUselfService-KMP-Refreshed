import BJTUShared
import Foundation
import SwiftUI
import UIKit

// 应用入口：SwiftUI ContentView 承载全屏 ComposeView，并装配 iOS 26+ 玻璃壳
// 或 M10 回退壳。壳控制器只做宿主装配，不画业务界面。

private final class LiquidGlassShellController: UIViewController {
    private var authenticatedSession: AuthenticatedSession?
    private var tabController: (UIViewController & NativeTabsHosting)?
    private var appActiveObserver: NSObjectProtocol?
    private var appInactiveObserver: NSObjectProtocol?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(appBackgroundColor)
        let root = MainViewControllerKt.NativeMainViewController(
            onAuthenticatedSessionChanged: { [weak self] session in
                self?.handle(session: session)
            },
            onOpenNativeRoute: { _ in },
            nativeTabBarEnabled: true
        )
        configureComposeHost(root)
        embed(root)
        appInactiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.authenticatedSession?.notifyAppBecameInactive()
        }
        appActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.authenticatedSession?.notifyAppBecameActive()
        }
    }

    deinit {
        if let appInactiveObserver { NotificationCenter.default.removeObserver(appInactiveObserver) }
        if let appActiveObserver {
            NotificationCenter.default.removeObserver(appActiveObserver)
        }
    }

    /// Kotlin 的 SideEffect 会在每次重组时重复上报同一会话，所以只在实例变化时换引用、拆装 tab 控制器；
    /// 但每次上报都要过一遍 reloadTabs——偏好变化（物理在线开关）会在同一个会话实例上
    /// 长出或收起一级入口，只按实例去重会让设置里的开关看起来「没有反应」。
    private func handle(session: AuthenticatedSession?) {
        if session !== authenticatedSession {
            authenticatedSession = session
            if session != nil && tabController == nil {
                let tabs = makeNativeTabs(session: session!)
                tabController = tabs
                embed(tabs)

            } else if session == nil, let tabs = tabController {
                tabs.willMove(toParent: nil)
                tabs.view.removeFromSuperview()
                tabs.removeFromParent()
                tabController = nil
            }
        }
        if let session, let tabs = tabController {
            tabs.reloadTabs(session: session)
        }
    }

    private func embed(_ child: UIViewController) {
        addChild(child)
        let alreadyVisible = view.window != nil
        if alreadyVisible { child.beginAppearanceTransition(true, animated: false) }
        child.view.frame = view.bounds
        child.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(child.view)
        child.didMove(toParent: self)
        if alreadyVisible { child.endAppearanceTransition() }
    }
}

#if DEBUG
/// Uses the production navigation containers with an isolated, synthetic session.
private final class OfflineLayoutShellController: UIViewController {
    private var bootstrap: UIViewController?
    override func viewDidLoad() {
        super.viewDidLoad()
        let bootstrap = OfflineLayoutProbeKt.OfflineLayoutBootstrap { [weak self] session in
            DispatchQueue.main.async { self?.show(session) }
        }
        self.bootstrap = bootstrap
        embed(bootstrap)
    }
    private func show(_ session: AuthenticatedSession) {
        // Keep the source composition attached, as in the production login shell.
        // Removing the first Compose host can retire its scene's render lifecycle.
        guard children.count == 1 else { return }
        if #available(iOS 26.0, *), UIDevice.current.userInterfaceIdiom == .phone,
           !ProcessInfo.processInfo.arguments.contains("--fallback-shell") {
            embed(makeNativeTabs(session: session))
        } else {
            embed(NativeNavigationController(offlineSession: session))
        }
    }
    private func embed(_ child: UIViewController) {
        addChild(child)
        let alreadyVisible = view.window != nil
        if alreadyVisible { child.beginAppearanceTransition(true, animated: false) }
        child.view.frame = view.bounds
        child.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(child.view)
        child.didMove(toParent: self)
        if alreadyVisible { child.endAppearanceTransition() }
    }
}
#endif

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--layout-smoke") {
            return OfflineLayoutShellController()
        }
        if ProcessInfo.processInfo.arguments.contains("--courseware-picker-smoke") {
            return CoursewarePickerProbeViewControllerKt.CoursewarePickerProbeViewController()
        }
        if ProcessInfo.processInfo.arguments.contains("--new-migration-smoke") {
            return NewMigrationProbeViewControllerKt.NewMigrationProbeViewController()
        }
        if ProcessInfo.processInfo.arguments.contains("--security-smoke") {
            return SecuritySmokeViewControllerKt.SecuritySmokeViewController()
        }
        if ProcessInfo.processInfo.arguments.contains("--physicslab-sheet-smoke") {
            return NativeSheetSmokeViewControllerKt.NativeSheetSmokeViewController(physicsLab: true, updateNotes: false)
        }
        if ProcessInfo.processInfo.arguments.contains("--update-notes-smoke") {
            return NativeSheetSmokeViewControllerKt.NativeSheetSmokeViewController(physicsLab: false, updateNotes: true)
        }
        if ProcessInfo.processInfo.arguments.contains("--sheet-smoke") {
            return NativeSheetSmokeViewControllerKt.NativeSheetSmokeViewController(physicsLab: false, updateNotes: false)
        }
#endif
        // iOS 26 起把导航壳交给系统容器，由渲染栈自动应用 Liquid Glass；更低版本原样保留
        // M10 的 Compose 自绘壳层，行为不变。
        // 只接管 iPhone 紧凑端：iPad/宽窗口继续走共享三栏 NavDisplay 路径。用 idiom 而不是
        // size class 判定，是因为 size class 会随旋转变化，而壳层只能在装配时选一次。
        if #available(iOS 26.0, *), UIDevice.current.userInterfaceIdiom == .phone {
            return LiquidGlassShellController()
        }
        return NativeNavigationController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

struct ContentView: View {
    var body: some View {
        ZStack {
            // Keep the hosting surface continuous behind every system area.
            appBackgroundColor
                .ignoresSafeArea()
            ComposeView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // Keep the Compose host at a stable full-screen size on every edge. A pushed
                // destination must also cover the status-bar region, or a native push transition
                // leaves a static strip above the moving card. Compose owns the login form's
                // IME padding only while fields are editable; SwiftUI must not retain a stale
                // keyboard safe area after Password AutoFill or foreground transitions.
                .ignoresSafeArea(.all)
        }
        // Apply edge-to-edge at the hosting boundary so neither the container nor keyboard safe
        // area can resize the root and expose a strip of the UIWindow background.
        .background(appBackgroundColor)
        .ignoresSafeArea(.all)
    }
}

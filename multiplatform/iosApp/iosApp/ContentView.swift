import BJTUShared
import Foundation
import SwiftUI
import UIKit

// Keep this pair aligned with LightColors/DarkColors.background in shared App.kt.
private let appBackgroundColor = Color(
    uiColor: UIColor { traits in
        if traits.userInterfaceStyle == .dark {
            return UIColor(red: 16.0 / 255.0, green: 18.0 / 255.0, blue: 22.0 / 255.0, alpha: 1)
        }
        return UIColor(red: 244.0 / 255.0, green: 245.0 / 255.0, blue: 249.0 / 255.0, alpha: 1)
    }
)

private final class NativeNavigationController: UINavigationController, UINavigationControllerDelegate, UIGestureRecognizerDelegate {
    private var authenticatedSession: AuthenticatedSession?
    private var appActiveObserver: NSObjectProtocol?

    /// 本项目暂不开放实验性的 Compose iOS 无障碍语义树。iOS 26 的辅助功能客户端（含
    /// 各类自动化查询）会在原生 push/pop 移除宿主控制器后继续查询已失效的 Compose
    /// AccessibilityElement，在框架 cachedProperties 内崩溃。在 Swift 侧对整个 Compose
    /// 宿主视图隐藏无障碍子树即可完全跳过该路径，视觉界面与原生导航手势不受影响。
    private func hideComposeAccessibilitySubtree(in controller: UIViewController) {
        controller.view.accessibilityElementsHidden = true
        // Compose 首帧之前 UIKit 会先显示宿主底色；与页面背景保持一致可避免深色模式闪白。
        controller.view.backgroundColor = UIColor(appBackgroundColor)
    }

    /// 导航栏隐藏后，UIKit 默认会关掉 interactivePopGestureRecognizer（内部 delegate
    /// 认为没有返回按钮就不该 pop）。二级页仍用 Compose 顶栏返回，因此必须自己接管
    /// 手势：仅在栈深 > 1 时允许开始，并优先于 Compose 的滚动/拖动手势。
    private func configureInteractivePopGesture() {
        interactivePopGestureRecognizer?.isEnabled = true
        interactivePopGestureRecognizer?.delegate = self
    }

    private func updateInteractivePopEnabled() {
        interactivePopGestureRecognizer?.isEnabled = viewControllers.count > 1
    }

    init() {
        super.init(nibName: nil, bundle: nil)
        delegate = self
        setNavigationBarHidden(true, animated: false)
        configureInteractivePopGesture()
        appActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.authenticatedSession?.notifyAppBecameActive()
        }
        let rootController = MainViewControllerKt.NativeMainViewController(
            onAuthenticatedSessionChanged: { [weak self] session in
                self?.authenticatedSession = session
                if session == nil, (self?.viewControllers.count ?? 0) > 1 {
                    self?.popToRootViewController(animated: false)
                    self?.updateInteractivePopEnabled()
                }
            },
            onOpenNativeRoute: { [weak self] routeId in
                self?.openNativeRoute(routeId)
            }
        )
        hideComposeAccessibilitySubtree(in: rootController)
        setViewControllers([rootController], animated: false)
        updateInteractivePopEnabled()
    }

    deinit {
        if let appActiveObserver {
            NotificationCenter.default.removeObserver(appActiveObserver)
        }
    }

    @available(*, unavailable)
    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func openNativeRoute(_ routeId: String) {
        guard let session = authenticatedSession else { return }
        guard topViewController?.restorationIdentifier != routeId else { return }
        let destination = MainViewControllerKt.NativeDestinationViewController(
            session: session,
            routeId: routeId,
            onOpenNativeRoute: { [weak self] childRouteId in
                self?.openNativeRoute(childRouteId)
            },
            onCloseNativeRoute: { [weak self] in
                self?.popViewController(animated: true)
            }
        )
        destination.restorationIdentifier = routeId
        hideComposeAccessibilitySubtree(in: destination)
        pushViewController(destination, animated: true)
    }

    // MARK: - UINavigationControllerDelegate

    func navigationController(
        _ navigationController: UINavigationController,
        didShow viewController: UIViewController,
        animated: Bool
    ) {
        // push/pop 动画结束后再同步开关，避免根页仍能半截手势卡住导航栈。
        updateInteractivePopEnabled()
        // 部分系统版本在 didShow 后会把 delegate 重置；每次确认仍由本类接管。
        if interactivePopGestureRecognizer?.delegate !== self {
            configureInteractivePopGesture()
            updateInteractivePopEnabled()
        }
    }

    // MARK: - UIGestureRecognizerDelegate

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === interactivePopGestureRecognizer else { return true }
        // 根页禁止 pop；二级及以上（更多→设置/考试/课件…）允许 leading-edge 跟手返回。
        return viewControllers.count > 1
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        // 边缘返回优先：Compose 列表滚动/水平拖动须等 pop 手势失败后再开始，
        // 否则 Skia 层会吃掉左缘触摸，导致“更多”子页无法侧滑返回。
        gestureRecognizer === interactivePopGestureRecognizer
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--security-smoke") {
            return SecuritySmokeViewControllerKt.SecuritySmokeViewController()
        }
#endif
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

import BJTUShared
import Foundation
import UIKit

// M10 回退壳（iOS 26 以下，以及 iPad/宽窗口）：Compose 自绘顶栏，
// UIKit UINavigationController 负责二/三级页 push 与边缘返回。

// MARK: - M10 回退壳（iOS 26 以下；iPad/宽窗口同样走这条 Compose 自绘顶栏的壳）

final class NativeNavigationController: UINavigationController, UINavigationControllerDelegate, UIGestureRecognizerDelegate {
    private var authenticatedSession: AuthenticatedSession?
    private var appActiveObserver: NSObjectProtocol?
    private var appInactiveObserver: NSObjectProtocol?

    private func updateInteractivePopEnabled() {
        interactivePopGestureRecognizer?.isEnabled = viewControllers.count > 1
    }

    init(offlineSession: AuthenticatedSession? = nil) {
        super.init(nibName: nil, bundle: nil)
        delegate = self
        view.backgroundColor = appBackgroundUIColor
        setNavigationBarHidden(true, animated: false)
        installInteractivePopGesture(on: self)
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
        let rootController: UIViewController
        if let offlineSession {
            authenticatedSession = offlineSession
            rootController = OfflineLayoutProbeKt.OfflineLayoutRootViewController(session: offlineSession, onOpenRoute: { [weak self] routeId in
                self?.openNativeRoute(routeId)
            })
        } else {
        rootController = MainViewControllerKt.NativeMainViewController(
            onAuthenticatedSessionChanged: { [weak self] session in
                self?.authenticatedSession = session
                if session == nil, (self?.viewControllers.count ?? 0) > 1 {
                    self?.popToRootViewController(animated: false)
                    self?.updateInteractivePopEnabled()
                }
            },
            onOpenNativeRoute: { [weak self] routeId in
                self?.openNativeRoute(routeId)
            },
            nativeTabBarEnabled: false
        )
        }
        configureComposeHost(rootController)
        setViewControllers([rootController], animated: false)
        updateInteractivePopEnabled()
#if DEBUG
        if offlineSession != nil, let route = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("--route=") }) {
            DispatchQueue.main.async { [weak self] in self?.openNativeRoute(String(route.dropFirst(8))) }
        }
#endif
    }

    deinit {
        if let appInactiveObserver { NotificationCenter.default.removeObserver(appInactiveObserver) }
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
            nativeHostState: NativeHostState(),
            useNativeTitleBar: false,
            onOpenNativeRoute: { [weak self] childRouteId in
                self?.openNativeRoute(childRouteId)
            },
            onCloseNativeRoute: { [weak self] in
                self?.popViewController(animated: true)
            },
            // 回退壳不显示系统导航栏：标题回调留空，页面继续自绘顶栏，保持 M10 验收语义。
            onTitleChanged: { _ in },
            onActionChanged: { _ in },
            onSelectNativeTab: { _ in }
        )
        destination.restorationIdentifier = routeId
        configureComposeHost(destination)
        destination.loadViewIfNeeded()
        destination.view.frame = view.bounds
        destination.view.setNeedsLayout()
        destination.view.layoutIfNeeded()
        pinComposeSurfaceColor(destination.view)
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
        installInteractivePopGesture(on: navigationController)
        updateInteractivePopEnabled()
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

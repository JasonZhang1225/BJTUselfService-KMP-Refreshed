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

/// 每个 Compose 宿主管线的公共约定（M10 回退壳与 M17 玻璃壳共用，行为必须一致）。
///
/// 本项目暂不开放实验性的 Compose iOS 无障碍语义树。iOS 26 的辅助功能客户端（含
/// 各类自动化查询）会在原生 push/pop 移除宿主控制器后继续查询已失效的 Compose
/// AccessibilityElement，在框架 cachedProperties 内崩溃。在 Swift 侧对整个 Compose
/// 宿主视图隐藏无障碍子树即可完全跳过该路径，视觉界面与原生导航手势不受影响。
///
/// Compose 首帧之前 UIKit 会先显示宿主底色；与页面背景保持一致可避免深色模式闪白。
private func configureComposeHost(_ controller: UIViewController) {
    controller.view.accessibilityElementsHidden = true
    controller.view.backgroundColor = UIColor(appBackgroundColor)
}

/// 边缘返回手势接管：Compose 的 Skia 层会吃掉左缘触摸，pop 手势必须优先于列表滚动/横滑。
/// 导航栏隐藏时 UIKit 默认会关掉 interactivePopGestureRecognizer（内部 delegate 认为
/// 没有返回按钮就不该 pop），因此栈深判定也要自己接管。
private func installInteractivePopGesture(
    on navigationController: UINavigationController
) {
    navigationController.interactivePopGestureRecognizer?.isEnabled = true
    navigationController.interactivePopGestureRecognizer?.delegate =
        navigationController as? UIGestureRecognizerDelegate
}

// MARK: - M10 回退壳（iOS 26 以下）

private final class NativeNavigationController: UINavigationController, UINavigationControllerDelegate, UIGestureRecognizerDelegate {
    private var authenticatedSession: AuthenticatedSession?
    private var appActiveObserver: NSObjectProtocol?

    private func updateInteractivePopEnabled() {
        interactivePopGestureRecognizer?.isEnabled = viewControllers.count > 1
    }

    init() {
        super.init(nibName: nil, bundle: nil)
        delegate = self
        setNavigationBarHidden(true, animated: false)
        installInteractivePopGesture(on: self)
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
            },
            nativeTabBarEnabled: false
        )
        configureComposeHost(rootController)
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

// MARK: - M17 液态玻璃壳（iOS 26+）

/// 宿主导航栏绑定：Kotlin 首帧之后才回报动态标题（教学楼名等）与右侧动作，需要回填到已入栈的控制器，
/// 并让宿主重算导航栏显隐——空标题代表「本页自绘顶栏」，系统玻璃栏不该出现。
private final class NativeChromeBinding {
    weak var controller: UIViewController?

    func apply(_ title: String) {
        guard let controller, !title.isEmpty else { return }
        controller.navigationItem.title = title
        (controller.navigationController as? NativeChromeHosting)?.refreshNavigationBarVisibility()
    }

    /// 同步状态与刷新由 Compose 声明、由系统导航栏渲染：省掉页内那条独占一行的胶囊，
    /// 刷新进行中换成转圈，页面自己不再画顶栏工具行。
    func apply(action: NativeBarAction?) {
        guard let controller else { return }
        guard let action else {
            controller.navigationItem.rightBarButtonItems = nil
            return
        }
        var items: [UIBarButtonItem] = []
        if action.canRefresh {
            if action.busy {
                let spinner = UIActivityIndicatorView(style: .medium)
                spinner.startAnimating()
                items.append(UIBarButtonItem(customView: spinner))
            } else {
                let item = UIBarButtonItem(
                    image: UIImage(systemName: "arrow.clockwise"),
                    primaryAction: UIAction { _ in action.onClick() }
                )
                item.accessibilityLabel = action.label
                items.append(item)
            }
        }
        // UIKit 把 rightBarButtonItems 的第 0 项放在最右边：主操作（刷新）靠右，状态文字在它左边。
        if !action.status.isEmpty {
            let label = UILabel()
            label.text = action.status
            label.font = .preferredFont(forTextStyle: .subheadline)
            label.textColor = .secondaryLabel
            items.append(UIBarButtonItem(customView: label))
        }
        controller.navigationItem.rightBarButtonItems = items.isEmpty ? nil : items
    }
}

private protocol NativeChromeHosting: AnyObject {
    func refreshNavigationBarVisibility()
}

/// 单个一级入口的原生导航栈：根页面为对应 tab 的 Compose 内容（保留页内自绘顶栏与同步胶囊），
/// 二/三级页 push 后由系统玻璃导航栏提供标题与返回。
private final class TabRootNavigationController: UINavigationController, UINavigationControllerDelegate, UIGestureRecognizerDelegate, NativeChromeHosting {
    private let session: AuthenticatedSession
    private let tabRouteId: String
    private let selectTab: (String) -> Void
    /// 根页 ↔ 被压入的二级页切换时通知宿主：底栏被 push 藏起来时，悬浮入口必须跟着消失。
    var onBarVisibilityChanged: ((Bool) -> Void)?

    init(
        session: AuthenticatedSession,
        tabRouteId: String,
        selectTab: @escaping (String) -> Void
    ) {
        self.session = session
        self.tabRouteId = tabRouteId
        self.selectTab = selectTab
        super.init(nibName: nil, bundle: nil)
        delegate = self
        setNavigationBarHidden(true, animated: false)
        installInteractivePopGesture(on: self)
    }

    @available(*, unavailable)
    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// UITabBarController 会一次性装配五个 tab，但只有被选中的那个加载视图：
    /// Compose 根控制器推迟到这里创建，冷启动不必同时付五份组合与五块 Metal 层的成本。
    override func viewDidLoad() {
        super.viewDidLoad()
        guard viewControllers.isEmpty else { return }
        let root = MainViewControllerKt.NativeTabRootViewController(
            session: session,
            routeId: tabRouteId,
            onOpenNativeRoute: { [weak self] routeId in
                self?.openNativeRoute(routeId)
            },
            onCloseNativeRoute: { [weak self] in
                self?.popViewController(animated: true)
            },
            // tab 根页面保留页内顶栏（标题与同步胶囊同一条），不向系统栏要标题。
            onTitleChanged: { _ in },
            onSelectNativeTab: { [weak self] routeId in
                self?.selectTab(routeId)
            }
        )
        root.restorationIdentifier = tabRouteId
        configureComposeHost(root)
        setViewControllers([root], animated: false)
        updateInteractivePopEnabled()
    }

    func openNativeRouteFromHost(_ routeId: String) {
        openNativeRoute(routeId)
    }

    private func openNativeRoute(_ routeId: String) {
        guard topViewController?.restorationIdentifier != routeId else { return }
        let binding = NativeChromeBinding()
        let destination = MainViewControllerKt.NativeDestinationViewController(
            session: session,
            routeId: routeId,
            useNativeTitleBar: true,
            onOpenNativeRoute: { [weak self] childRouteId in
                self?.openNativeRoute(childRouteId)
            },
            onCloseNativeRoute: { [weak self] in
                self?.popViewController(animated: true)
            },
            onTitleChanged: { title in
                DispatchQueue.main.async { binding.apply(title) }
            },
            onActionChanged: { action in
                DispatchQueue.main.async { binding.apply(action: action) }
            },
            onSelectNativeTab: { [weak self] targetRouteId in
                self?.selectTab(targetRouteId)
            }
        )
        destination.restorationIdentifier = routeId
        destination.navigationItem.title = NativeShellKt.nativeRouteTitle(routeId: routeId)
        configureComposeHost(destination)
        binding.controller = destination
        destination.hidesBottomBarWhenPushed = true
        pushViewController(destination, animated: true)
    }

    // MARK: - NativeChromeHosting

    /// 一级 tab 根页面、以及没有标题的页面（写信等自绘返回的页）不显示系统栏。
    private func navigationBarShouldBeHidden(for viewController: UIViewController) -> Bool {
        viewController === viewControllers.first || viewController.navigationItem.title?.isEmpty != false
    }

    func refreshNavigationBarVisibility() {
        guard let top = topViewController else { return }
        setNavigationBarHidden(navigationBarShouldBeHidden(for: top), animated: true)
    }

    private func updateInteractivePopEnabled() {
        interactivePopGestureRecognizer?.isEnabled = viewControllers.count > 1
    }

    // MARK: - UINavigationControllerDelegate

    func navigationController(
        _ navigationController: UINavigationController,
        willShow viewController: UIViewController,
        animated: Bool
    ) {
        setNavigationBarHidden(navigationBarShouldBeHidden(for: viewController), animated: animated)
        onBarVisibilityChanged?(viewController === viewControllers.first)
    }

    func navigationController(
        _ navigationController: UINavigationController,
        didShow viewController: UIViewController,
        animated: Bool
    ) {
        updateInteractivePopEnabled()
        // 部分系统版本在 didShow 后会把 delegate 重置；每次确认仍由本类接管。
        installInteractivePopGesture(on: navigationController)
        updateInteractivePopEnabled()
    }

    // MARK: - UIGestureRecognizerDelegate

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === interactivePopGestureRecognizer else { return true }
        return viewControllers.count > 1
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        gestureRecognizer === interactivePopGestureRecognizer
    }
}

/// 一级入口的系统玻璃 TabBar。tab 列表来自 Kotlin 的同一份 bottomNavSections，
/// 两端不会漂移；图标改用 SF Symbols，交给系统做选中态填充与玻璃着色。
private final class AppTabBarController: UITabBarController, UITabBarControllerDelegate {
    private static let symbolNames: [String: String] = [
        "HOME": "house",
        "SCHEDULE": "calendar",
        "GRADES": "list.bullet.rectangle",
        "HOMEWORK": "folder",
        "PHYVLAB": "atom",
        "MORE": "square.grid.2x2",
    ]

    private var tabRouteIds: [String] = []
    private var tabStudentId: String?
    /// routeId → 该 tab 的导航栈。重配 tab 时按 routeId 复用，避免整条玻璃栏被拆掉、各 tab 返回栈丢失。
    private var controllersByRoute: [String: TabRootNavigationController] = [:]
    /// 被 5 格上限挤掉的一级入口（物理在线）用的悬浮玻璃圆按钮，贴在底栏右上缘之外。
    private var floatingEntry: UIVisualEffectView?
    private var floatingRouteId: String?
    private let floatingEntrySide: CGFloat = 46

    init(session: AuthenticatedSession) {
        super.init(nibName: nil, bundle: nil)
        delegate = self
        // M17 步骤 2 的「浮动/最小化 TabBar」在 UIKit 上确有等价 API（`tabBarMinimizeBehavior`，iOS 26+）。
        // 保持 .automatic 交给系统决定，但不选 .onScrollDown：滚动发生在 Compose 的 Skia 层里，
        // UIKit 观察不到 scroll view，滚动驱动的收起不会生效。是否真的浮动需手指滑一次确认。
        if #available(iOS 26.0, *) {
            tabBarMinimizeBehavior = .automatic
        }
        tabStudentId = session.profile.studentId
        apply(items: NativeShellKt.nativeTabItems(session: session), session: session)
    }

    @available(*, unavailable)
    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func reloadTabs(session: AuthenticatedSession) {
        let items = NativeShellKt.nativeTabItems(session: session)
        let routeIds = items.map(\.routeId)
        // 悬浮入口跟着同一个开关走，但 5 格上限会让底栏组成在开关前后完全一样，
        // 下面的 guard 因此会直接返回。必须在此之前先同步按钮，否则拨完开关按钮不出现。
        syncFloatingEntry(session: session)
        // 登录过程中 Kotlin 会因 profile/偏好写入产出新的会话实例（内部 ScreenModel 仍是同一批对象）。
        // 只有 tab 组成或账号真的变了才重建，否则整条玻璃 TabBar 会被拆掉、各 tab 返回栈丢失。
        guard routeIds != tabRouteIds || session.profile.studentId != tabStudentId else { return }
        let accountChanged = session.profile.studentId != tabStudentId
        tabStudentId = session.profile.studentId
        if accountChanged { controllersByRoute.removeAll() }
        apply(items: items, session: session)
    }

    /// 一级入口整份交给系统 TabBar：5 项上限由 Kotlin 侧 `NATIVE_TAB_BAR_MAX_ITEMS` 统一裁，
    /// 两端读同一份 `nativeTabItems`，Swift 不再自己过滤（曾在这里过滤过一次，导致设置里的
    /// 物理在线开关看起来完全没反应）。放不下的入口由「更多」目录承载并被宿主压栈。
    private func apply(items: [NativeTabItem], session: AuthenticatedSession) {
        let selectedRouteId = tabRouteIds.indices.contains(selectedIndex) ? tabRouteIds[selectedIndex] : nil
        tabRouteIds = items.map(\.routeId)
        viewControllers = items.map { item in
            if let existing = controllersByRoute[item.routeId] { return existing }
            let controller = TabRootNavigationController(
                session: session,
                tabRouteId: item.routeId,
                selectTab: { [weak self] routeId in self?.select(routeId: routeId) }
            )
            controller.tabBarItem = UITabBarItem(
                title: item.title,
                image: UIImage(systemName: Self.symbolNames[item.routeId] ?? "circle"),
                selectedImage: nil
            )
            controller.onBarVisibilityChanged = { [weak self] visible in
                self?.floatingEntry?.isHidden = !visible
            }
            controllersByRoute[item.routeId] = controller
            return controller
        }
        controllersByRoute = controllersByRoute.filter { tabRouteIds.contains($0.key) }
        // 增删入口后停在用户原来那一栏上，只有原选中项被收走时才回首页。
        if let selectedRouteId, let index = tabRouteIds.firstIndex(of: selectedRouteId) {
            selectedIndex = index
        } else {
            selectedIndex = 0
        }
        syncFloatingEntry(session: session)
    }

    /// 底栏装不下但用户已开启的入口改由悬浮圆按钮承载；关掉开关时按钮随之消失。
    private func syncFloatingEntry(session: AuthenticatedSession) {
        guard let item = NativeShellKt.nativeFloatingEntry(session: session) else {
            floatingEntry?.removeFromSuperview()
            floatingEntry = nil
            floatingRouteId = nil
            return
        }
        guard floatingRouteId != item.routeId else { return }
        floatingEntry?.removeFromSuperview()
        let button = makeFloatingEntry(item: item)
        floatingEntry = button
        floatingRouteId = item.routeId
        view.addSubview(button)
        view.setNeedsLayout()
    }

    private func makeFloatingEntry(item: NativeTabItem) -> UIVisualEffectView {
        let effect: UIVisualEffect
        if #available(iOS 26.0, *) {
            let glass = UIGlassEffect()
            glass.isInteractive = true
            effect = glass
        } else {
            effect = UIBlurEffect(style: .systemThinMaterial)
        }
        let container = UIVisualEffectView(effect: effect)
        container.layer.cornerRadius = floatingEntrySide / 2
        container.layer.cornerCurve = .continuous
        container.clipsToBounds = true
        container.isAccessibilityElement = true
        container.accessibilityLabel = item.title
        container.accessibilityTraits = .button

        let icon = UIImageView(
            image: UIImage(systemName: Self.symbolNames[item.routeId] ?? "circle")
        )
        icon.tintColor = .secondaryLabel
        icon.contentMode = .scaleAspectFit
        icon.isUserInteractionEnabled = false
        container.contentView.addSubview(icon)
        icon.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            icon.centerXAnchor.constraint(equalTo: container.contentView.centerXAnchor),
            icon.centerYAnchor.constraint(equalTo: container.contentView.centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 21),
            icon.heightAnchor.constraint(equalToConstant: 21),
        ])
        container.addGestureRecognizer(
            UITapGestureRecognizer(target: self, action: #selector(floatingEntryTapped))
        )
        return container
    }

    @objc private func floatingEntryTapped() {
        guard let floatingRouteId else { return }
        select(routeId: floatingRouteId)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let floatingEntry else { return }
        // 贴在玻璃条右上缘之外，不遮任何 tab。显隐由 `onBarVisibilityChanged` 决定：
        // 这里再按 tabBar.frame 猜一次会和 push 动画抢写入，结果按钮留在二级页上不走。
        let bar = tabBar.frame
        floatingEntry.frame = CGRect(
            x: bar.maxX - floatingEntrySide,
            y: bar.minY - floatingEntrySide - 8,
            width: floatingEntrySide,
            height: floatingEntrySide
        )
    }

    /// Kotlin 侧「一级入口互跳」转成系统 tab 切换；入口当前不在 tab 上时（例如关闭物理在线后
    /// 又从别处跳它）改为在当前 tab 上 push，避免点了没有任何反应。
    private func select(routeId: String) {
        if let index = tabRouteIds.firstIndex(of: routeId) {
            selectedIndex = index
            return
        }
        (selectedViewController as? TabRootNavigationController)?.openNativeRouteFromHost(routeId)
    }

    func tabBarController(
        _ tabBarController: UITabBarController,
        shouldSelect viewController: UIViewController
    ) -> Bool {
        // 再点已选中的 tab：回到该 tab 根页面（iOS 标准语义），不重复切页。
        guard tabBarController.selectedViewController === viewController,
              let controller = viewController as? UINavigationController,
              controller.viewControllers.count > 1
        else { return true }
        controller.popToRootViewController(animated: true)
        return false
    }

    /// 从系统溢出（More）列表里选 tab 时，UIKit 不走正常的视图加载路径，
    /// 而本壳把 Compose 根推迟到 `viewDidLoad` 才建 —— 结果该 tab 的栈是空的，
    /// 页面停在溢出列表上，看起来就是「点了没反应」。这里显式催一次加载。
    func tabBarController(
        _ tabBarController: UITabBarController,
        didSelect viewController: UIViewController
    ) {
        guard let controller = viewController as? TabRootNavigationController,
              controller.viewControllers.isEmpty
        else { return }
        controller.loadViewIfNeeded()
    }
}

/// 玻璃壳装配入口：登录与会话仍由一个 Compose 宿主管，登录后把一级入口交给
/// UITabBarController，退出登录时收回。
///
/// 这里用 child VC 而不是导航栈切换：登录宿主管线必须始终留在窗口里。原生容器一旦
/// 把它的视图移出窗口，Compose 组合可能被回收，会话来源与 `onAuthenticatedSessionChanged`
/// 的时序就不再可控。被玻璃 TabBar 盖住的登录组合此时只渲染一个占位空白（Kotlin 侧
/// nativeTabBarEnabled 分支），成本可忽略。
private final class LiquidGlassShellController: UIViewController {
    private var authenticatedSession: AuthenticatedSession?
    private var tabController: AppTabBarController?
    private var appActiveObserver: NSObjectProtocol?

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
        appActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.authenticatedSession?.notifyAppBecameActive()
        }
    }

    deinit {
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
                let tabs = AppTabBarController(session: session!)
                tabController = tabs
                embed(tabs)
                // 一级入口集合在 Compose 里观测（「物理在线」开关会增减一项），UIKit 收不到快照变化，
                // 所以由会话上的回调显式回推一次重配，否则拨完开关底栏看起来毫无反应。
                session!.onNativeTabItemsChanged = { _ in
                    DispatchQueue.main.async { [weak self, weak tabs] in
                        guard let self, let tabs, let session = self.authenticatedSession else { return }
                        tabs.reloadTabs(session: session)
                    }
                }
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
        child.view.frame = view.bounds
        child.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(child.view)
        child.didMove(toParent: self)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--security-smoke") {
            return SecuritySmokeViewControllerKt.SecuritySmokeViewController()
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

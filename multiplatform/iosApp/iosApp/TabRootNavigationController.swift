import BJTUShared
import Foundation
import UIKit

/// 单个一级入口的原生导航栈：根页面与 push 后的二级页都使用稳定的 UIKit 行内标题，
/// Compose 只负责正文，系统导航栏统一承载标题、同步状态和页面动作。
final class TabRootNavigationController: UINavigationController, UINavigationControllerDelegate, UIGestureRecognizerDelegate, NativeChromeHosting {
    private let session: AuthenticatedSession
    let nativeHostState = NativeHostState()
    private let tabItemsChanged: ([String]) -> Void
    private let tabRouteId: String
    private let selectTab: (String) -> Void
    let usesSystemBars: Bool
    private var centeredTitleLabel: NativeCenteredNavigationTitle?
    /// 正在展开页内搜索的页面。只在它位于栈顶时藏标题，push 出去的二级页照常显示自己的标题。
    private weak var searchingOwner: UIViewController?
    private var navigationBarHiddenState: Bool?
    /// 原生导航栏实际占掉的顶部高度（栏底 maxY，含状态栏），推给 Compose 做滚动内容顶边距；
    /// 内容经过横向玻璃，滚动方向驱动 iOS 27 原生栏划出。栏隐藏时推 0。
    private var pushedTopInset: CGFloat = -1
    private var headerGeometry: CGSize = .zero
    private var headerVertical: Bool?
    private var expandedHeaderHeight: CGFloat = 0
    private var expandedHeaderTop: CGFloat = 0
    private var headerTextCategory: UIContentSizeCategory?
    private var navigationBarGlassView: NativeNavigationBarGlassView?
    private var systemTitleVertical: Bool?
    private let glassState = NativeNavigationGlassState<ObjectIdentifier>()
    /// 二级页盖住底栏玻璃。玻璃留在原位，这里不负责把它收起或延后显示。
    var onNavigationWillShow: ((UINavigationController, UIViewController, Bool) -> Void)?

    init(
        session: AuthenticatedSession,
        tabRouteId: String,
        selectTab: @escaping (String) -> Void,
        usesSystemBars: Bool = false,
        tabItemsChanged: @escaping ([String]) -> Void = { _ in }
    ) {
        self.session = session
        self.tabRouteId = tabRouteId
        self.selectTab = selectTab
        self.usesSystemBars = usesSystemBars
        self.tabItemsChanged = tabItemsChanged
        super.init(nibName: nil, bundle: nil)
        nativeHostState.systemManagedContentBounds = usesSystemBars
        delegate = self
        // 一级页固定使用一条紧凑的原生标题栏；正文滚动不改变导航栏高度，
        // 避免 Compose/UIKit 两套滚动模型互相错位。
        navigationBar.prefersLargeTitles = false
        installInteractivePopGesture(on: self)
    }

    @available(*, unavailable)
    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func configureNativeBarMinimization(for item: UINavigationItem) {
        guard usesSystemBars else { return }
        if #available(iOS 27.0, *) {
            // Keep viewport geometry stable from creation, before Compose reports
            // its primary scroll consumer. UIKit alone animates the navigation bar.
            item.navigationBarMinimization.minimizationBehavior = .automatic
            item.navigationBarMinimization.safeAreaAdjustment = .disabled
        }
    }

    func configureScrollMinimization(for owner: UIViewController, enabled: Bool) {
        guard usesSystemBars, #available(iOS 27.0, *) else { return }
        let vertical = nativeBarIsVertical(in: traitCollection)
        var configuration = owner.navigationItem.navigationBarMinimization
        let oldBehavior = configuration.minimizationBehavior
        let oldAdjustment = configuration.safeAreaAdjustment
        configuration.minimizationBehavior = enabled && vertical ? .onScrollDown : .automatic
        configuration.safeAreaAdjustment = .disabled
        if oldBehavior != configuration.minimizationBehavior || oldAdjustment != configuration.safeAreaAdjustment {
            owner.navigationItem.navigationBarMinimization = configuration
        }
    }

    /// Compose 根控制器推迟到这里创建，冷启动不必同时付多份组合与 Metal 层的成本。
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = appBackgroundUIColor
        guard viewControllers.isEmpty else { return }
        navigationBar.clipsToBounds = false
        // Horizontal scroll-underlap uses NativeNavigationBarGlassView or the
        // native scroller's top edge. Keep the bar itself clear from its first
        // frame; Duo's sidebar material/minimization remains owned by UIKit.
        let glassView = NativeNavigationBarGlassView()
        navigationBarGlassView = glassView
        view.insertSubview(glassView, belowSubview: navigationBar)
        let appearance = UINavigationBarAppearance()
        appearance.configureWithTransparentBackground()
        if usesSystemBars {
            // Preserve UIKit's native side-bar material and scroll behavior.
            appearance.titleTextAttributes = [.font: UIFontMetrics(forTextStyle: .headline).scaledFont(for: UIFont.systemFont(ofSize: 20, weight: .semibold))]
        } else {
            // A clear color alone can retain the default material at rest.
            appearance.backgroundEffect = nil
            appearance.backgroundColor = .clear
            appearance.shadowColor = .clear
        }
        navigationBar.standardAppearance = appearance
        navigationBar.scrollEdgeAppearance = appearance
        navigationBar.compactAppearance = appearance
        navigationBar.compactScrollEdgeAppearance = appearance
        let binding = NativeChromeBinding()
        let composeRoot = MainViewControllerKt.NativeTabRootViewController(
            session: session,
            routeId: tabRouteId,
            nativeHostState: nativeHostState,
            onOpenNativeRoute: { [weak self] routeId in
                self?.openNativeRoute(routeId)
            },
            onCloseNativeRoute: { [weak self] in
                self?.popViewController(animated: true)
            },
            // 一级页的标题与右上胶囊都由系统栏承载：静态标题先放好，动态回报再覆盖。
            onTitleChanged: { title in
                DispatchQueue.main.async { binding.apply(title) }
            },
            onActionChanged: { action in
                DispatchQueue.main.async { binding.apply(action: action) }
            },
            onSelectNativeTab: { [weak self] routeId in
                self?.selectTab(routeId)
            },
            onTabItemsChanged: tabItemsChanged
        )
        let root = usesSystemBars ? SafeAreaComposeHost(content: composeRoot, nativeHostState: nativeHostState) : composeRoot
        if usesSystemBars { root.navigationItem.style = nativeBarIsVertical(in: traitCollection) ? .browser : .navigator }
        root.restorationIdentifier = tabRouteId
        let rootTitle = NativeShellKt.nativeRouteTitle(routeId: tabRouteId)
        root.navigationItem.title = rootTitle
        root.navigationItem.largeTitleDisplayMode = .never
        configureNativeBarMinimization(for: root.navigationItem)
        configureComposeHost(root)

        binding.controller = root
        setViewControllers([root], animated: false)
        setNativeTitle(rootTitle)
        updateInteractivePopEnabled()
    }

    func openNativeRouteFromHost(_ routeId: String) {
        openNativeRoute(routeId)
    }

    private func openNativeRoute(_ routeId: String) {
        guard topViewController?.restorationIdentifier != routeId else { return }
        let binding = NativeChromeBinding()
        let composeDestination = MainViewControllerKt.NativeDestinationViewController(
            session: session,
            routeId: routeId,
            nativeHostState: nativeHostState,
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
        let destination = usesSystemBars ? SafeAreaComposeHost(content: composeDestination, nativeHostState: nativeHostState) : composeDestination
        if usesSystemBars { destination.navigationItem.style = nativeBarIsVertical(in: traitCollection) ? .browser : .navigator }
        destination.restorationIdentifier = routeId
        let destinationTitle = NativeShellKt.nativeRouteTitle(routeId: routeId)
        destination.navigationItem.title = destinationTitle
        // 被 push 的页也使用行内标题，保持根页与二级页的高度和排版一致。
        destination.navigationItem.largeTitleDisplayMode = .never
        configureNativeBarMinimization(for: destination.navigationItem)
        configureComposeHost(destination)
        if usesSystemBars {
            destination.hidesBottomBarWhenPushed = true
        }
        binding.controller = destination
        setNativeTitle(destinationTitle, for: destination)
        // 先把目的地按最终尺寸 layout 一次，让 Compose 在转场开始前就开始画，
        // 减少滑动过程中 Metal 还没首帧的空窗。
        destination.loadViewIfNeeded()
        destination.view.frame = view.bounds
        destination.view.setNeedsLayout()
        destination.view.layoutIfNeeded()
        pinComposeSurfaceColor(destination.view)
        pushViewController(destination, animated: true)
    }

    // MARK: - NativeChromeHosting

    func setSearchActive(_ active: Bool, for owner: UIViewController) {
        if active {
            searchingOwner = owner
        } else if searchingOwner === owner {
            searchingOwner = nil
        } else {
            return
        }
        guard topViewController === owner, let label = centeredTitleLabel else { return }
        let alpha: CGFloat = active ? 0 : 1
        guard label.alpha != alpha else { return }
        UIView.animate(withDuration: 0.2, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
            label.alpha = alpha
        }
    }

    func setNativeTitle(_ title: String) {
        setNativeTitle(title, for: topViewController)
    }

    private func setNativeTitle(_ title: String, for owner: UIViewController?) {
        // 搜索框展开时它占满整条栏，标题不能叠在输入框上。
        let alpha: CGFloat = owner != nil && owner === searchingOwner ? 0 : 1
        if let centeredTitleLabel, centeredTitleLabel.alpha != alpha {
            centeredTitleLabel.layer.removeAllAnimations()
            centeredTitleLabel.alpha = alpha
        }
        if usesSystemBars && nativeBarIsVertical(in: traitCollection) {
            centeredTitleLabel?.isHidden = true
            return
        }
        guard !title.isEmpty else {
            centeredTitleLabel?.text = nil
            centeredTitleLabel?.isHidden = true
            return
        }
        let label: NativeCenteredNavigationTitle
        if let centeredTitleLabel {
            label = centeredTitleLabel
        } else {
            label = NativeCenteredNavigationTitle(frame: .zero)
            label.translatesAutoresizingMaskIntoConstraints = false
            navigationBar.addSubview(label)
            NSLayoutConstraint.activate([
                label.centerXAnchor.constraint(equalTo: navigationBar.centerXAnchor),
                label.centerYAnchor.constraint(equalTo: navigationBar.centerYAnchor, constant: 1),
                label.leadingAnchor.constraint(greaterThanOrEqualTo: navigationBar.leadingAnchor, constant: 16),
                label.trailingAnchor.constraint(lessThanOrEqualTo: navigationBar.trailingAnchor, constant: -16),
                label.heightAnchor.constraint(lessThanOrEqualToConstant: 44),
            ])
            centeredTitleLabel = label
        }
        // Same-value writes and subview reordering both invalidate the bar's layout.
        if label.text != title { label.text = title }
        if label.isHidden { label.isHidden = false }
        if navigationBar.subviews.last !== label { navigationBar.bringSubviewToFront(label) }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let vertical = usesSystemBars && nativeBarIsVertical(in: traitCollection)
        if usesSystemBars, systemTitleVertical != vertical {
            systemTitleVertical = vertical
            // UIKit can move its inline title to the leading edge to avoid a
            // long action group. Keep horizontal titles centered independently;
            // vertical layouts use the system's leading title representation.
            let appearance = navigationBar.standardAppearance.copy() as! UINavigationBarAppearance
            appearance.titleTextAttributes[.foregroundColor] = vertical ? UIColor.label : UIColor.clear
            navigationBar.standardAppearance = appearance
            navigationBar.scrollEdgeAppearance = appearance
            navigationBar.compactAppearance = appearance
            navigationBar.compactScrollEdgeAppearance = appearance
            setNativeTitle(topViewController?.navigationItem.title ?? "")
            if let topViewController {
                configureScrollMinimization(for: topViewController, enabled: (topViewController as? SafeAreaComposeHost)?.hasNativeScroller == true)
                restoreTopGlass(for: topViewController)
            }
        }
        let currentOwner = topViewController
        if let currentOwner {
            configureScrollMinimization(for: currentOwner, enabled: (currentOwner as? SafeAreaComposeHost)?.hasNativeScroller == true)
        }
        // Every system-bar host uses the same full-height viewport, including its
        // first frame. Compose consumes the header inset inside its list or spacer.
        // Duo can expose a top title while its reserved safe-area edge is on the
        // side. The safe-area top alone then misses the title's actual footprint.
        // Use UIKit's full bar fitting height, stable throughout minimization.
        let statusTop = currentOwner?.view.window?.safeAreaInsets.top ?? view.safeAreaInsets.top
        if !navigationBar.isHidden && (headerGeometry != view.bounds.size || headerVertical != vertical ||
            headerTextCategory != traitCollection.preferredContentSizeCategory || expandedHeaderHeight == 0) {
            headerGeometry = view.bounds.size
            headerVertical = vertical
            headerTextCategory = traitCollection.preferredContentSizeCategory
            expandedHeaderHeight = navigationBar.sizeThatFits(CGSize(width: view.bounds.width, height: 0)).height
            if let owner = currentOwner {
                let barRect = navigationBar.convert(navigationBar.bounds, to: owner.view)
                expandedHeaderTop = max(0, barRect.minY)
            }
        }
        let headerHeight = navigationBar.isHidden ? 0 : expandedHeaderHeight
        let topInset: CGFloat = usesSystemBars
            ? max(currentOwner?.view.safeAreaInsets.top ?? 0, expandedHeaderTop + headerHeight)
            : (navigationBar.isHidden ? 0 : navigationBar.frame.maxY)
        if let glassView = navigationBarGlassView {
            let glassBounds = CGRect(
                x: 0,
                y: 0,
                width: view.bounds.width,
                height: navigationBar.isHidden ? 0 : topInset,
            )
            glassView.frame = glassBounds
            glassView.isHidden = navigationBar.isHidden || vertical || !glassView.hasVisibleMaterial
            view.bringSubviewToFront(glassView)
            view.bringSubviewToFront(navigationBar)
        }
        if let centeredTitleLabel { navigationBar.bringSubviewToFront(centeredTitleLabel) }
        let ownsVisiblePage = !usesSystemBars || tabBarController?.selectedViewController === self
        if view.window != nil, ownsVisiblePage,
           abs(topInset - pushedTopInset) > 0.5 || abs(Float(topInset) - nativeHostState.glassTopBarInsetDp) > 0.5 {
            pushedTopInset = topInset
            nativeHostState.glassTopBarInsetDp = Float(topInset)
#if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--layout-metrics") {
                print("[LayoutMetrics] top=\(topInset) safe=\(currentOwner?.view.safeAreaInsets.top ?? 0) status=\(statusTop) header=\(headerHeight) bar=\(navigationBar.frame) viewport=\(currentOwner?.view.bounds ?? .zero)")
            }
#endif
        }
    }

    /// Horizontal bars retain their existing material. Vertical bars use UIKit's
    /// default layout; scroll updates never hide, move or resize the navigation bar.
    func setTopGlassProgress(_ progress: CGFloat, for owner: UIViewController) {
        if let visibleProgress = glassState.update(progress, for: ObjectIdentifier(owner)) {
            if (owner as? SafeAreaComposeHost)?.hasNativeScroller == true || (usesSystemBars && nativeBarIsVertical(in: traitCollection)) {
                navigationBarGlassView?.setProgress(0)
            } else {
                navigationBarGlassView?.setProgress(visibleProgress)
            }
        }
    }

    private func restoreTopGlass(for controller: UIViewController) {
        let progress = glassState.show(ObjectIdentifier(controller))
        if (controller as? SafeAreaComposeHost)?.hasNativeScroller == true || (usesSystemBars && nativeBarIsVertical(in: traitCollection)) {
            navigationBarGlassView?.setProgress(0)
        } else {
            navigationBarGlassView?.setProgress(progress)
        }
    }

    /// 只有一页例外不显示系统栏：没有标题的页（写信这类自绘返回的页）。一级 tab 根页现在也有标题，
    /// 所以不再按「是不是栈底」豁免。
    private func navigationBarShouldBeHidden(for viewController: UIViewController) -> Bool {
        viewController.navigationItem.title?.isEmpty != false
    }

    func refreshNavigationBarVisibility() {
        guard let top = topViewController else { return }
        let shouldHide = shouldHideBar(for: top)
        guard navigationBarHiddenState != shouldHide else { return }
        navigationBarHiddenState = shouldHide
        setNavigationBarHidden(shouldHide, animated: false)
    }

    private func shouldHideBar(for viewController: UIViewController) -> Bool {
        navigationBarShouldBeHidden(for: viewController)
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
        restoreTopGlass(for: viewController)
        let shouldHide = shouldHideBar(for: viewController)
        navigationBarHiddenState = shouldHide
        setNavigationBarHidden(shouldHide, animated: animated)
        // 玻璃不跟着进二级页消失，也不等返回动画结束再出现。宿主让二级页盖在它上面。
        onNavigationWillShow?(navigationController, viewController, animated)
    }

    func navigationController(
        _ navigationController: UINavigationController,
        didShow viewController: UIViewController,
        animated: Bool
    ) {
        if viewIfLoaded?.window != nil {
            session.reportActivePage(routeId: viewController.restorationIdentifier ?? tabRouteId)
        }
        // A pop does not recreate Compose. Restore this page's own title and scroll state,
        // including a cancelled interactive pop, without requiring a new scroll event.
        glassState.retain(Set(viewControllers.map { ObjectIdentifier($0) }))
        restoreTopGlass(for: viewController)
        let shouldHide = shouldHideBar(for: viewController)
        navigationBarHiddenState = shouldHide
        setNavigationBarHidden(shouldHide, animated: false)
        setNativeTitle(viewController.navigationItem.title ?? "")
        updateInteractivePopEnabled()
        // 部分系统版本在 didShow 后会把 delegate 重置；每次确认仍由本类接管。
        installInteractivePopGesture(on: navigationController)
        updateInteractivePopEnabled()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        session.reportActivePage(routeId: topViewController?.restorationIdentifier ?? tabRouteId)
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

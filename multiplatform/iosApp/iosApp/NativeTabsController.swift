import BJTUShared
import Foundation
import SwiftUI
import UIKit

// 一级入口的底栏容器：iOS 18–26 用 UITabBar 玻璃栏，iOS 27+ 交给
// UITabBarController 的标准容器（tab / sidebar 自适应）。
// 入口列表来自 Kotlin 的同一份 bottomNavSections。

/// 一级入口的系统玻璃 TabBar。tab 列表来自 Kotlin 的同一份 bottomNavSections，
/// 两端不会漂移；图标改用 SF Symbols，交给系统做选中态填充与玻璃着色。
private final class AppTabBarController: UIViewController, UITabBarDelegate, NativeTabsHosting {
    fileprivate static let symbolNames: [String: String] = [
        "HOME": "house",
        "SCHEDULE": "calendar",
        "GRADES": "list.bullet.rectangle",
        "HOMEWORK": "folder",
        "ASSIGNMENTS": "folder",
        "PHYVLAB": "atom",
        "CITEL": "curlybraces",
        "PHYSICS_LAB": "flask",
        "MORE": "square.grid.2x2",
        "EXAMS": "clock",
        "COURSEWARE": "books.vertical",
        "PLAYBACK": "play.rectangle",
        "CLASSROOM_OCCUPANCY": "building.2",
        "MAILBOX": "envelope",
        "CALENDAR": "calendar",
        "REPORT_CARD_DOWNLOAD": "doc.text",
        "SETTINGS": "gearshape",
    ]

    private var tabRouteIds: [String] = []
    private var pendingItems: [NativeTabItem]
    private var selectedRouteId: String?
    private var activeController: TabRootNavigationController?
    private let nativeTabBar = UITabBar()
    private var tabStudentId: String?
    private var measuredBottomInset: Float = 0
    private var measuredSystemBottomInset: Float = 0
    /// 玻璃底栏的真实占位高度要推给 Compose：宿主是全出血的，UIKit 不会把 tab bar 算进
    /// `WindowInsets.navigationBars`，而不可纵向滚动的全览表格（课程表色块概览）必须停在底栏上方。
    /// 弱引用即可：壳控制器已经强引用同一会话，这里只是取用，不该多持一份。
    private weak var session: AuthenticatedSession?
    /// routeId → 该 tab 的导航栈。重配 tab 时按 routeId 复用，避免整条玻璃栏被拆掉、各 tab 返回栈丢失。
    private var controllersByRoute: [String: TabRootNavigationController] = [:]
    /// 二级页整页盖住玻璃时为 true。玻璃仍在原位，只是层级在页面下面。
    private var contentCoversTabBar = false
    /// 转场中玻璃被放进导航容器，夹在根页和二级页之间。这段时间不要重排它的 frame。
    private var tabBarBorrowed = false
    private var navigationTransitionGeneration = 0

    init(session: AuthenticatedSession) {
        pendingItems = NativeShellKt.nativeTabItems(session: session)
        tabStudentId = session.profile.studentId
        self.session = session
        super.init(nibName: nil, bundle: nil)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(appBackgroundColor)
        nativeTabBar.delegate = self
        nativeTabBar.itemPositioning = .fill
        nativeTabBar.isTranslucent = true
        nativeTabBar.autoresizingMask = [.flexibleWidth, .flexibleTopMargin]
        view.addSubview(nativeTabBar)
        guard let session else { return }
        apply(items: pendingItems, session: session)
#if DEBUG
        // 取证用：`simctl launch … --tab=SCHEDULE` 直接停在某个一级入口。模拟器没有无头点击的口子，
        // 而合成鼠标点击会抢用户焦点，所以把「切 tab」做成启动参数（与既有的 --security-smoke 同一套路）。
        for arg in ProcessInfo.processInfo.arguments where arg.hasPrefix("--tab=") {
            let routeId = String(arg.dropFirst("--tab=".count))
            select(routeId: routeId)
        }
        if ProcessInfo.processInfo.arguments.contains("--layout-smoke"),
           let route = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("--route=") }) {
            DispatchQueue.main.async { [weak self] in self?.activeController?.openNativeRouteFromHost(String(route.dropFirst(8))) }
        }
#endif
    }

    @available(*, unavailable)
    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func scheduleTabReload() {
        DispatchQueue.main.async { [weak self] in
            guard let self, let session = self.session else { return }
            self.reloadTabs(session: session)
        }
    }

    func reloadTabs(session: AuthenticatedSession) {
        self.session = session
        let items = NativeShellKt.nativeTabItems(session: session)
        let routeIds = items.map(\.routeId)
        let accountChanged = session.profile.studentId != tabStudentId
        tabStudentId = session.profile.studentId
        pendingItems = items
        guard isViewLoaded else { return }
        guard routeIds != tabRouteIds || accountChanged else { return }
        if accountChanged { removeAllControllers() }
        apply(items: items, session: session)
    }

    /// 一级入口整份交给 UIKit 的 UITabBar；不再裁成五项，也不创建系统 More 溢出页。
    private func apply(items: [NativeTabItem], session: AuthenticatedSession) {
        restoreTabBarToHost()
        let previousRouteId = selectedRouteId
        let nextRouteIds = items.map(\.routeId)
        for (routeId, controller) in controllersByRoute where !nextRouteIds.contains(routeId) {
            controller.onNavigationWillShow = nil
            controller.willMove(toParent: nil)
            if controller.isViewLoaded { controller.view.removeFromSuperview() }
            controller.removeFromParent()
        }
        controllersByRoute = controllersByRoute.filter { nextRouteIds.contains($0.key) }
        tabRouteIds = nextRouteIds
        for item in items where controllersByRoute[item.routeId] == nil {
            let controller = TabRootNavigationController(
                session: session,
                tabRouteId: item.routeId,
                selectTab: { [weak self] routeId in self?.select(routeId: routeId) },
                tabItemsChanged: { [weak self] _ in self?.scheduleTabReload() }
            )
            // Seed a newly added tab before its first Compose frame; later layout reports stay host-local.
            controller.nativeHostState.glassTabBarBottomInsetDp = measuredBottomInset
            controller.nativeHostState.systemBottomInsetDp = measuredSystemBottomInset
            controller.nativeHostState.glassTopBarInsetDp = activeController?.nativeHostState.glassTopBarInsetDp ?? 0
            controller.tabBarItem = UITabBarItem(
                title: item.title,
                image: UIImage(systemName: Self.symbolNames[item.routeId] ?? "circle"),
                selectedImage: nil
            )
            controller.onNavigationWillShow = { [weak self] navigationController, viewController, animated in
                self?.handleNavigationWillShow(navigationController, viewController, animated)
            }
            controllersByRoute[item.routeId] = controller
            addChild(controller)
            controller.didMove(toParent: self)
        }
        let nativeItems = items.map {
            UITabBarItem(
                title: $0.title,
                image: UIImage(systemName: Self.symbolNames[$0.routeId] ?? "circle"),
                selectedImage: nil
            )
        }
        let routeToSelect = previousRouteId.flatMap { nextRouteIds.contains($0) ? $0 : nil }
            ?? nextRouteIds.first
        // A preference change can remove the currently selected item (PHYVLAB).
        // Update the item list and selection in one animation-disabled transaction;
        // otherwise UITabBar briefly keeps its old selected item and visibly slides
        // to the removed tab before the fallback route is selected.
        selectedRouteId = routeToSelect
        UIView.performWithoutAnimation {
            // Clear the old item before replacing the collection. UIKit may
            // otherwise resolve the old index against the shorter list for one
            // layout pass, which is visible as a PHYVLAB -> fallback jump.
            nativeTabBar.selectedItem = nil
            nativeTabBar.setItems(nativeItems, animated: false)
            if let routeToSelect,
               let index = nextRouteIds.firstIndex(of: routeToSelect) {
                nativeTabBar.selectedItem = nativeItems[index]
            } else {
                nativeTabBar.selectedItem = nil
            }
        }
        if let routeToSelect {
            select(routeId: routeToSelect)
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let intrinsicHeight = nativeTabBar.sizeThatFits(
            CGSize(width: view.bounds.width, height: CGFloat.greatestFiniteMagnitude)
        ).height
        // 玻璃一直占着原来的高度。二级页盖上来，不把高度收成 0，否则返回时要再长出来。
        let barHeight = max(80, intrinsicHeight + view.safeAreaInsets.bottom)
        if !tabBarBorrowed {
            nativeTabBar.isHidden = false
            nativeTabBar.frame = CGRect(
                x: 0,
                y: view.bounds.height - barHeight,
                width: view.bounds.width,
                height: barHeight
            )
            if let content = activeController?.view {
                content.frame = view.bounds
                if contentCoversTabBar {
                    view.insertSubview(content, aboveSubview: nativeTabBar)
                } else {
                    view.insertSubview(content, belowSubview: nativeTabBar)
                }
            }
        } else {
            activeController?.view.frame = view.bounds
        }

        let inset = barHeight
        let systemBottom = max(view.safeAreaInsets.bottom, view.window?.safeAreaInsets.bottom ?? 0)
        measuredBottomInset = Float(inset)
        measuredSystemBottomInset = Float(systemBottom)
        for controller in controllersByRoute.values {
            let state = controller.nativeHostState
            if abs(Float(inset) - state.glassTabBarBottomInsetDp) > 0.5 {
                state.glassTabBarBottomInsetDp = Float(inset)
            }
            // 根页使用完整 barHeight；二级页没有底栏，独立使用窗口安全区。
            if abs(Float(systemBottom) - state.systemBottomInsetDp) > 0.5 {
                state.systemBottomInsetDp = Float(systemBottom)
            }
        }
    }

    /// 玻璃留在原位。涉及根页的转场里，把它夹在根页和二级页之间：二级页滑过时盖住它，
    /// 返回时它本来就露在下面。二级页之间的推进不把玻璃抬到中间。安卓是新 Activity 盖住底栏，
    /// 这里同一套，不做另一套消失动画。
    private func handleNavigationWillShow(
        _ navigationController: UINavigationController,
        _ viewController: UIViewController,
        _ animated: Bool
    ) {
        guard navigationController === activeController else { return }
        navigationTransitionGeneration += 1
        let generation = navigationTransitionGeneration
        let showingRoot = viewController === navigationController.viewControllers.first
        guard animated, let coordinator = navigationController.transitionCoordinator else {
            restoreTabBarToHost()
            contentCoversTabBar = !showingRoot
            view.setNeedsLayout()
            return
        }
        let fromIsRoot = coordinator.viewController(forKey: .from) === navigationController.viewControllers.first
        let toIsRoot = coordinator.viewController(forKey: .to) === navigationController.viewControllers.first
        if !fromIsRoot && !toIsRoot {
            restoreTabBarToHost()
            contentCoversTabBar = true
            view.setNeedsLayout()
            return
        }
        tabBarBorrowed = true
        placeTabBarBetweenPages(navigationController, coordinator)
        coordinator.animate(alongsideTransition: { [weak self] context in
            self?.placeTabBarBetweenPages(navigationController, context)
        }, completion: { [weak self] _ in
            guard let self, self.navigationTransitionGeneration == generation else { return }
            self.restoreTabBarToHost()
            let visibleIsRoot = navigationController.topViewController === navigationController.viewControllers.first
            self.contentCoversTabBar = !visibleIsRoot
            self.view.setNeedsLayout()
            self.view.layoutIfNeeded()
        })
    }

    private func placeTabBarBetweenPages(
        _ navigationController: UINavigationController,
        _ context: UIViewControllerTransitionCoordinatorContext
    ) {
        guard let fromView = context.viewController(forKey: .from)?.view,
              let toView = context.viewController(forKey: .to)?.view,
              let container = deepestCommonAncestor(fromView, toView) else { return }
        let toIsRoot = context.viewController(forKey: .to) === navigationController.viewControllers.first
        let rootView = toIsRoot ? toView : fromView
        let coverView = toIsRoot ? fromView : toView
        guard let rootBranch = directSubview(of: container, containing: rootView),
              let coverBranch = directSubview(of: container, containing: coverView),
              rootBranch !== coverBranch else { return }
        let frameInHost = nativeTabBar.convert(nativeTabBar.bounds, to: view)
        // UIKit owns the order of the transition cards and its dimming layer.
        // Move only our tab bar: moving the destination card immediately above it
        // can put that card below UIKit's dimming layer and darken the incoming page.
        container.insertSubview(nativeTabBar, belowSubview: coverBranch)
        nativeTabBar.frame = container.convert(frameInHost, from: view)
        nativeTabBar.isHidden = false
        if navigationController.navigationBar.isDescendant(of: container) {
            container.bringSubviewToFront(navigationController.navigationBar)
        }
        tabBarBorrowed = true
    }

    private func restoreTabBarToHost() {
        tabBarBorrowed = false
        if nativeTabBar.superview !== view {
            view.addSubview(nativeTabBar)
        }
    }

    private func deepestCommonAncestor(_ first: UIView, _ second: UIView) -> UIView? {
        var seen = Set<ObjectIdentifier>()
        var current: UIView? = first
        while let node = current {
            seen.insert(ObjectIdentifier(node))
            current = node.superview
        }
        current = second.superview
        while let node = current {
            if seen.contains(ObjectIdentifier(node)) { return node }
            current = node.superview
        }
        return nil
    }

    private func directSubview(of container: UIView, containing target: UIView) -> UIView? {
        var current: UIView? = target
        while let node = current {
            if node.superview === container { return node }
            current = node.superview
        }
        return nil
    }

    private func removeAllControllers() {
        restoreTabBarToHost()
        for controller in controllersByRoute.values {
            controller.onNavigationWillShow = nil
            controller.willMove(toParent: nil)
            if controller.isViewLoaded { controller.view.removeFromSuperview() }
            controller.removeFromParent()
        }
        controllersByRoute.removeAll()
        activeController = nil
        selectedRouteId = nil
    }

    /// Kotlin 侧「一级入口互跳」转成系统 tab 切换；非一级路由仍在当前 tab 的
    /// UINavigationController 中 push，保持系统返回入口。
    private func select(routeId: String) {
        if let index = tabRouteIds.firstIndex(of: routeId) {
            selectedRouteId = routeId
            let next = controllersByRoute[routeId]!
            navigationTransitionGeneration += 1
            restoreTabBarToHost()
            contentCoversTabBar = next.viewControllers.count > 1
            if activeController !== next {
                activeController?.view.removeFromSuperview()
                next.loadViewIfNeeded()
                next.view.frame = view.bounds
                if contentCoversTabBar {
                    view.insertSubview(next.view, aboveSubview: nativeTabBar)
                } else {
                    view.insertSubview(next.view, belowSubview: nativeTabBar)
                }
                activeController = next
            }
            nativeTabBar.selectedItem = nativeTabBar.items?[index]
            view.setNeedsLayout()
            return
        }
        activeController?.loadViewIfNeeded()
        activeController?.openNativeRouteFromHost(routeId)
    }

    func tabBar(_ tabBar: UITabBar, didSelect item: UITabBarItem) {
        guard let index = tabBar.items?.firstIndex(where: { $0 === item }),
              tabRouteIds.indices.contains(index)
        else { return }
        let routeId = tabRouteIds[index]
        if selectedRouteId == routeId,
           let controller = controllersByRoute[routeId],
           controller.viewControllers.count > 1 {
            controller.popToRootViewController(animated: true)
        } else {
            select(routeId: routeId)
        }
    }
}

/// 玻璃壳装配入口：登录与会话仍由一个 Compose 宿主管，登录后把一级入口交给
/// UIKit tab 容器，退出登录时收回。
///
/// 这里用 child VC 而不是导航栈切换：登录宿主管线必须始终留在窗口里。原生容器一旦
/// 把它的视图移出窗口，Compose 组合可能被回收，会话来源与 `onAuthenticatedSessionChanged`
/// 的时序就不再可控。被玻璃 TabBar 盖住的登录组合此时只渲染一个占位空白（Kotlin 侧
/// nativeTabBarEnabled 分支），成本可忽略。
protocol NativeTabsHosting: AnyObject {
    func reloadTabs(session: AuthenticatedSession)
}

func makeNativeTabs(session: AuthenticatedSession) -> UIViewController & NativeTabsHosting {
    if #available(iOS 27.0, *) { return AdaptiveAppTabBarController(session: session) }
    return AppTabBarController(session: session)
}

/// Standard containers own the axes, safe content rectangle and transitions on Duo.
@available(iOS 27.0, *)
private final class AdaptiveAppTabBarController: UITabBarController, NativeTabsHosting {
    private var session: AuthenticatedSession
    private var controllersByRoute: [String: TabRootNavigationController] = [:]
    private var tabsByRoute: [String: UITab] = [:]
    private var routeIds: [String] = []
    private var studentId: String

    init(session: AuthenticatedSession) {
        self.session = session
        studentId = session.profile.studentId
        super.init(nibName: nil, bundle: nil)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = appBackgroundUIColor
        mode = .tabSidebar
        sidebar.preferredPlacement = .automatic
        reloadTabs(session: session)
#if DEBUG
        for arg in ProcessInfo.processInfo.arguments where arg.hasPrefix("--tab=") {
            select(routeId: String(arg.dropFirst(6)))
        }
        if ProcessInfo.processInfo.arguments.contains("--layout-smoke"),
           let route = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("--route=") }) {
            DispatchQueue.main.async { [weak self] in self?.select(routeId: String(route.dropFirst(8))) }
        }
#endif
    }

    private func scheduleTabReload() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.reloadTabs(session: self.session)
        }
    }

    func reloadTabs(session: AuthenticatedSession) {
        self.session = session
        guard isViewLoaded else { return }
        let items = NativeShellKt.nativeTabItems(session: session)
        let nextIds = items.map(\.routeId)
        let accountChanged = studentId != session.profile.studentId
        guard nextIds != routeIds || accountChanged else { return }
        let previousSelection = accountChanged ? nil : selectedTab?.identifier
        studentId = session.profile.studentId
        if accountChanged { controllersByRoute.removeAll(); tabsByRoute.removeAll() }
        controllersByRoute = controllersByRoute.filter { nextIds.contains($0.key) }
        tabsByRoute = tabsByRoute.filter { nextIds.contains($0.key) }
        for item in items where tabsByRoute[item.routeId] == nil {
            let controller = TabRootNavigationController(session: session, tabRouteId: item.routeId,
                selectTab: { [weak self] route in self?.select(routeId: route) }, usesSystemBars: true,
                tabItemsChanged: { [weak self] _ in self?.scheduleTabReload() })
            if let selected = selectedTab?.identifier, let previous = controllersByRoute[selected] {
                controller.nativeHostState.glassTopBarInsetDp = previous.nativeHostState.glassTopBarInsetDp
                controller.nativeHostState.systemBottomInsetDp = previous.nativeHostState.systemBottomInsetDp
            }
            controllersByRoute[item.routeId] = controller
            tabsByRoute[item.routeId] = UITab(title: item.title,
                image: UIImage(systemName: AppTabBarController.symbolNames[item.routeId] ?? "circle"),
                identifier: item.routeId) { _ in controller }
        }
        routeIds = nextIds
        UIView.performWithoutAnimation {
            tabs = nextIds.compactMap { tabsByRoute[$0] }
            selectedTab = previousSelection.flatMap { tabsByRoute[$0] } ?? tabs.first
        }
    }

    private func select(routeId: String) {
        if let tab = tabsByRoute[routeId] {
            selectedTab = tab
        } else if let route = selectedTab?.identifier, let navigation = controllersByRoute[route] {
            navigation.loadViewIfNeeded()
            navigation.openNativeRouteFromHost(routeId)
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // Bodies are already constrained by UIKit. Only their remaining system
        // bottom safe area is needed for scrolling to the home indicator.
        for controller in controllersByRoute.values {
            controller.nativeHostState.glassTabBarBottomInsetDp = 0
        }
    }
}

import BJTUShared
import Foundation
import SwiftUI
import UIKit

// Keep this pair aligned with LightColors/DarkColors.background in shared App.kt.
private let appBackgroundUIColor = UIColor { traits in
    if traits.userInterfaceStyle == .dark {
        return UIColor(red: 16.0 / 255.0, green: 18.0 / 255.0, blue: 22.0 / 255.0, alpha: 1)
    }
    return UIColor(red: 244.0 / 255.0, green: 245.0 / 255.0, blue: 249.0 / 255.0, alpha: 1)
}
private let appBackgroundColor = Color(uiColor: appBackgroundUIColor)

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
    private var lastScrollEdgeState: Bool?
    private var retainedActionTargets: [NativeBarActionTarget] = []
    private var actionTargets: [NativeBarActionRole: NativeBarActionTarget] = [:]
    private var lastActionLayoutKey: NativeBarActionLayoutKey?
    private var pendingTitle: String?
    private var pendingAction: NativeBarAction?
    private var hasPendingAction = false
    private var updateScheduled = false

    func apply(_ title: String) {
        guard !title.isEmpty else { return }
        pendingTitle = title
        scheduleFlush()
    }

    /// 同步状态、刷新与页面级动作由 Compose 声明、由系统导航栏渲染：不再把 Compose 胶囊
    /// 塞进标题栏。动作载体只在结构真正变化时重建；滚动时只保持渐变层几何稳定，避免控件
    /// 被销毁再创建而闪烁。
    func apply(action: NativeBarAction?) {
        pendingAction = action
        hasPendingAction = true
        scheduleFlush()
    }

    private func scheduleFlush() {
        guard !updateScheduled else { return }
        updateScheduled = true
        // Title and action callbacks arrive from separate Compose SideEffects.
        // Coalesce them into one main-queue transaction so UIKit never renders
        // a centered title first and then animates it away when buttons arrive.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.updateScheduled = false
            self.flushPendingChrome()
        }
    }

    private func flushPendingChrome() {
        guard let controller else { return }
        if let title = pendingTitle {
            pendingTitle = nil
            controller.navigationItem.title = title
            (controller.navigationController as? NativeChromeHosting)?.setNativeTitle(title)
        }
        if hasPendingAction {
            let action = pendingAction
            pendingAction = nil
            hasPendingAction = false
            applyAction(action)
        }
        (controller.navigationController as? NativeChromeHosting)?.refreshNavigationBarVisibility()
    }

    private func iconBarItem(
        title: String,
        symbolName: String,
        role: NativeBarActionRole,
        onClick: (() -> Void)?,
        enabled: Bool = true,
    ) -> UIBarButtonItem {
        let button = NativeBarIconButton(symbolName: symbolName)
        button.isEnabled = enabled
        if let onClick {
            let target = makeActionTarget(role: role, onInvoke: onClick)
            button.addTarget(
                target,
                action: #selector(NativeBarActionTarget.invoke(_:)),
                for: .touchUpInside,
            )
        }
        button.accessibilityLabel = title
        return UIBarButtonItem(customView: button)
    }

    private func makeActionTarget(
        role: NativeBarActionRole,
        onInvoke: @escaping () -> Void,
    ) -> NativeBarActionTarget {
        let target = NativeBarActionTarget(onInvoke: onInvoke)
        actionTargets[role] = target
        retainedActionTargets.append(target)
        return target
    }

    private func updateActionTargets(_ action: NativeBarAction) {
        actionTargets[.refresh]?.update(action.canRefresh ? action.onClick : nil)
        actionTargets[.status]?.update(action.onStatusClick)
        actionTargets[.extra]?.update(action.onExtraClick)
        actionTargets[.spinner]?.update(action.onStatusClick)
    }

    private func symbolName(for label: String, kind: NativeBarActionItemKind) -> String {
        switch kind {
        case .refresh:
            return "arrow.clockwise"
        case .extra:
            if label.localizedCaseInsensitiveContains("日历") {
                return "calendar.badge.plus"
            }
            return "ellipsis.circle"
        case .status:
            if label.contains("失败") || label.contains("错误") {
                return "exclamationmark.triangle"
            }
            if label.contains("已同步") || label.contains("成功") || label.contains("完成") {
                return "checkmark.circle"
            }
            return "info.circle"
        }
    }

    private func applyAction(_ action: NativeBarAction?) {
        guard let controller else { return }
        guard let action else {
            retainedActionTargets.removeAll()
            actionTargets.removeAll()
            lastActionLayoutKey = nil
            controller.navigationItem.leftBarButtonItems = nil
            controller.navigationItem.leftItemsSupplementBackButton = false
            controller.navigationItem.rightBarButtonItems = nil
            apply(scrollEdge: false)
            return
        }
        let layoutKey = NativeBarActionLayoutKey(action)
        if layoutKey == lastActionLayoutKey {
            // The only expected change here is the scroll-edge material or a
            // freshly captured Kotlin callback. Keep every UIKit view alive.
            updateActionTargets(action)
            apply(scrollEdge: action.scrolledUnder)
            return
        }
        lastActionLayoutKey = layoutKey
        retainedActionTargets.removeAll()
        actionTargets.removeAll()
        var items: [UIBarButtonItem] = []
        var leftItems: [UIBarButtonItem] = []
        if action.busy {
            // Busy feedback belongs to the native bar. A text item saying
            // “刷新中” plus a Compose progress bar duplicates the same state and
            // makes the page feel like two toolbars are fighting each other.
            let statusLabel = action.status.isEmpty ? action.label : action.status
            // Busy feedback is one compact native slot. Keeping the status text
            // in accessibility rather than adding another wide text slot leaves
            // enough room for the centered title even when a page also exposes
            // a calendar/action button.
            let button = NativeSpinnerButton(type: .system)
            button.accessibilityLabel = statusLabel
            button.accessibilityValue = "进行中"
            let spinner = UIActivityIndicatorView(style: .medium)
            spinner.color = .secondaryLabel
            spinner.startAnimating()
            if let onStatusClick = action.onStatusClick {
                let target = makeActionTarget(role: .spinner, onInvoke: onStatusClick)
                button.addTarget(
                    target,
                    action: #selector(NativeBarActionTarget.invoke(_:)),
                    for: .touchUpInside,
                )
            }
            spinner.translatesAutoresizingMaskIntoConstraints = false
            button.addSubview(spinner)
            NSLayoutConstraint.activate([
                spinner.centerXAnchor.constraint(equalTo: button.centerXAnchor),
                spinner.centerYAnchor.constraint(equalTo: button.centerYAnchor),
                spinner.widthAnchor.constraint(equalToConstant: 20),
                spinner.heightAnchor.constraint(equalToConstant: 20),
            ])
            items.append(UIBarButtonItem(customView: button))
        } else if action.canRefresh {
            items.append(
                iconBarItem(
                    title: action.label,
                    symbolName: symbolName(for: action.label, kind: .refresh),
                    role: .refresh,
                    onClick: action.onClick,
                )
            )
        }
        // UIKit 把 rightBarButtonItems 的第 0 项放在最右边：主操作（刷新）靠右，状态图标在它左边。
        if !action.busy && !action.status.isEmpty {
            items.append(
                iconBarItem(
                    title: action.status,
                    symbolName: symbolName(for: action.status, kind: .status),
                    role: .status,
                    onClick: action.onStatusClick,
                    enabled: action.onStatusClick != nil,
                )
            )
        }
        if let extraLabel = action.extraLabel, let onExtraClick = action.onExtraClick {
            leftItems.append(
                iconBarItem(
                    title: extraLabel,
                    symbolName: symbolName(for: extraLabel, kind: .extra),
                    role: .extra,
                    onClick: onExtraClick,
                )
            )
        }
        controller.navigationItem.leftItemsSupplementBackButton = !leftItems.isEmpty
        controller.navigationItem.leftBarButtonItems = leftItems.isEmpty ? nil : leftItems
        controller.navigationItem.rightBarButtonItems = items.isEmpty ? nil : items
        apply(scrollEdge: action.scrolledUnder)
    }

    /// Compose cannot expose a UIScrollView to UINavigationBar, so keep the
    /// edge state in the native host. The fade itself is a small UIKit
    /// gradient view below the bar; UINavigationBarAppearance stays
    /// transparent so there is only one continuous overlay. No Compose
    /// gradient is painted over content.
    ///
    /// Keep the title hierarchy stable. Compose's Skia scroll container is not
    /// a UIKit scroll view, so manually switching large-title display modes
    /// leaves UIKit's old large-title height behind. The title stays compact;
    /// the native material fade remains geometrically stable while the content
    /// moves underneath it.
    func apply(scrollEdge: Bool) {
        guard let controller, let navigationController = controller.navigationController else { return }
        guard lastScrollEdgeState != scrollEdge else { return }
        lastScrollEdgeState = scrollEdge
        let appearance = UINavigationBarAppearance()
        // Keep the system bar transparent in both scroll states. The single
        // native material layer below it owns the continuous blur-to-clear
        // transition; mixing it with UINavigationBar's default background
        // would produce a second hard-edged glass band over the first card.
        appearance.configureWithTransparentBackground()
        appearance.backgroundColor = .clear
        appearance.shadowColor = .clear
        appearance.titleTextAttributes = [
            // The visible title is the single UIKit label pinned to the
            // navigation-bar center. Keep UINavigationItem.title populated for
            // UIKit's visibility/accessibility semantics, but hide its
            // collision-avoiding copy so it cannot flash or drift sideways.
            .foregroundColor: UIColor.clear,
            .font: UIFontMetrics(forTextStyle: .title3).scaledFont(
                for: UIFont.systemFont(ofSize: 21, weight: .semibold),
            ),
        ]
        appearance.titlePositionAdjustment = UIOffset(horizontal: 0, vertical: 1)
        UIView.performWithoutAnimation {
            navigationController.navigationBar.standardAppearance = appearance
            navigationController.navigationBar.scrollEdgeAppearance = appearance
            navigationController.view.layoutIfNeeded()
        }
    }
}

private enum NativeBarActionRole: Hashable {
    case refresh
    case status
    case extra
    case spinner
}

private struct NativeBarActionLayoutKey: Equatable {
    let status: String
    let canRefresh: Bool
    let busy: Bool
    let label: String
    let hasStatusAction: Bool
    let extraLabel: String?
    let hasExtraAction: Bool

    init(_ action: NativeBarAction) {
        status = action.status
        canRefresh = action.canRefresh
        busy = action.busy
        label = action.label
        hasStatusAction = action.onStatusClick != nil
        extraLabel = action.extraLabel
        hasExtraAction = action.onExtraClick != nil
    }
}

private final class NativeBarActionTarget: NSObject {
    private var onInvoke: (() -> Void)?

    init(onInvoke: @escaping () -> Void) {
        self.onInvoke = onInvoke
    }

    func update(_ onInvoke: (() -> Void)?) {
        self.onInvoke = onInvoke
    }

    @objc func invoke(_ sender: UIControl) {
        onInvoke?()
    }
}

private enum NativeBarActionItemKind {
    case refresh
    case status
    case extra
}

private final class NativeBarIconButton: UIButton {
    init(symbolName: String) {
        super.init(frame: .zero)
        let symbolConfiguration = UIImage.SymbolConfiguration(
            pointSize: 18,
            weight: .semibold,
        )
        setImage(UIImage(systemName: symbolName, withConfiguration: symbolConfiguration), for: .normal)
        tintColor = .label
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: 32, height: 32)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

private final class NativeSpinnerButton: UIButton {
    override var intrinsicContentSize: CGSize {
        // Keep busy and idle action carriers equally compact so the title never
        // changes position when synchronization completes.
        CGSize(width: 32, height: 32)
    }
}

/// One native UILabel is overlaid on the navigation bar's own coordinate space.
/// `UINavigationItem.title` deliberately avoids this because UIKit shifts it to
/// avoid a long trailing action group; the app needs a title that stays centered
/// while those actions change asynchronously.
private final class NativeCenteredNavigationTitle: UILabel {
    override init(frame: CGRect) {
        super.init(frame: frame)
        font = UIFontMetrics(forTextStyle: .title3).scaledFont(
            for: UIFont.systemFont(ofSize: 21, weight: .semibold),
        )
        adjustsFontForContentSizeCategory = true
        textAlignment = .center
        numberOfLines = 1
        lineBreakMode = .byTruncatingTail
        textColor = .label
        isUserInteractionEnabled = false
        accessibilityTraits = .header
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

/// The navigation bar's own translucent background. UIKit supplies the title
/// and actions above it; this view supplies only an ordinary blur plus a
/// gradient alpha fade into the Compose/Skia content below. It is a child of
/// UINavigationBar rather than a separate content overlay.
private final class NativeNavigationBarBackgroundView: UIView {
    private let blurView: UIVisualEffectView
    private let fadeMask = CAGradientLayer()
    private var barHeight: CGFloat = 44

    override init(frame: CGRect) {
        blurView = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterial))
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        // Stay behind UINavigationBar's title/action content even when
        // UIKit rebuilds its private bar subviews during layout.
        layer.zPosition = -1
        blurView.isUserInteractionEnabled = false
        blurView.backgroundColor = .clear
        // Keep the status-bar and title-bar tones aligned while retaining a
        // visible, restrained blur over the content beneath the header.
        blurView.alpha = 0.58
        addSubview(blurView)

        fadeMask.startPoint = CGPoint(x: 0.5, y: 0.0)
        fadeMask.endPoint = CGPoint(x: 0.5, y: 1.0)
        layer.mask = fadeMask
        updateMask()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        blurView.frame = bounds
        fadeMask.frame = bounds
        updateMask()
    }

    func setBarHeight(_ height: CGFloat) {
        barHeight = height
        updateMask()
    }

    private func updateMask() {
        let totalHeight = max(bounds.height, 1)
        let barStop = min(max(barHeight / totalHeight, 0.05), 0.82)
        let fadeStop = min(barStop + 0.34, 0.98)
        fadeMask.locations = [
            0.0,
            NSNumber(value: Double(barStop)),
            NSNumber(value: Double(fadeStop)),
            1.0,
        ]
        fadeMask.colors = [
            UIColor.white.cgColor,
            UIColor.white.withAlphaComponent(0.92).cgColor,
            UIColor.white.withAlphaComponent(0.48).cgColor,
            UIColor.clear.cgColor,
        ]
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

private protocol NativeChromeHosting: AnyObject {
    func setNativeTitle(_ title: String)
    func refreshNavigationBarVisibility()
}

/// 单个一级入口的原生导航栈：根页面与 push 后的二级页都使用稳定的 UIKit 行内标题，
/// Compose 只负责正文，系统导航栏统一承载标题、同步状态和页面动作。
private final class TabRootNavigationController: UINavigationController, UINavigationControllerDelegate, UIGestureRecognizerDelegate, NativeChromeHosting {
    private let session: AuthenticatedSession
    private let tabRouteId: String
    private let selectTab: (String) -> Void
    private var centeredTitleLabel: NativeCenteredNavigationTitle?
    private var navigationBarBackgroundView: NativeNavigationBarBackgroundView?
    private var navigationBarHiddenState: Bool?
    /// 根页 ↔ 被压入的二级页切换时通知宿主：底栏被 push 藏起来时同步隐藏。
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
        // 一级页固定使用一条紧凑的原生标题栏；正文滚动只改变原生渐变
        // 的底部过渡，不改变导航栏高度，避免 Compose/UIKit 两套滚动模型互相错位。
        navigationBar.prefersLargeTitles = false
        installInteractivePopGesture(on: self)
    }

    @available(*, unavailable)
    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// Compose 根控制器推迟到这里创建，冷启动不必同时付多份组合与 Metal 层的成本。
    override func viewDidLoad() {
        super.viewDidLoad()
        guard viewControllers.isEmpty else { return }
        navigationBar.clipsToBounds = false
        navigationBar.backgroundColor = .clear
        let navigationBarBackgroundView = NativeNavigationBarBackgroundView(frame: .zero)
        self.navigationBarBackgroundView = navigationBarBackgroundView
        navigationBar.addSubview(navigationBarBackgroundView)
        navigationBar.sendSubviewToBack(navigationBarBackgroundView)
        let binding = NativeChromeBinding()
        let root = MainViewControllerKt.NativeTabRootViewController(
            session: session,
            routeId: tabRouteId,
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
            }
        )
        root.restorationIdentifier = tabRouteId
        let rootTitle = NativeShellKt.nativeRouteTitle(routeId: tabRouteId)
        root.navigationItem.title = rootTitle
        root.navigationItem.largeTitleDisplayMode = .never
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
        let destinationTitle = NativeShellKt.nativeRouteTitle(routeId: routeId)
        destination.navigationItem.title = destinationTitle
        // 被 push 的页也使用行内标题，保持根页与二级页的高度和排版一致。
        destination.navigationItem.largeTitleDisplayMode = .never
        configureComposeHost(destination)
        binding.controller = destination
        setNativeTitle(destinationTitle)
        destination.hidesBottomBarWhenPushed = true
        pushViewController(destination, animated: true)
    }

    // MARK: - NativeChromeHosting

    func setNativeTitle(_ title: String) {
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
        label.text = title
        label.isHidden = false
        navigationBar.bringSubviewToFront(label)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if let navigationBarBackgroundView {
            let fadeTail: CGFloat = 72
            navigationBarBackgroundView.frame = CGRect(
                x: 0,
                y: 0,
                width: navigationBar.bounds.width,
                height: navigationBar.bounds.height + fadeTail,
            )
            navigationBarBackgroundView.setBarHeight(navigationBar.bounds.height)
            navigationBarBackgroundView.isHidden = navigationBar.isHidden
            navigationBar.sendSubviewToBack(navigationBarBackgroundView)
        }
        if let centeredTitleLabel {
            navigationBar.bringSubviewToFront(centeredTitleLabel)
        }
    }

    /// 只有一页例外不显示系统栏：没有标题的页（写信这类自绘返回的页）。一级 tab 根页现在也有标题，
    /// 所以不再按「是不是栈底」豁免。
    private func navigationBarShouldBeHidden(for viewController: UIViewController) -> Bool {
        viewController.navigationItem.title?.isEmpty != false
    }

    func refreshNavigationBarVisibility() {
        guard let top = topViewController else { return }
        let shouldHide = navigationBarShouldBeHidden(for: top)
        guard navigationBarHiddenState != shouldHide else { return }
        navigationBarHiddenState = shouldHide
        setNavigationBarHidden(shouldHide, animated: false)
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
        let shouldHide = navigationBarShouldBeHidden(for: viewController)
        navigationBarHiddenState = shouldHide
        setNavigationBarHidden(shouldHide, animated: animated)
        onBarVisibilityChanged?(viewController === viewControllers.first)
    }

    func navigationController(
        _ navigationController: UINavigationController,
        didShow viewController: UIViewController,
        animated: Bool
    ) {
        // A pop does not recreate the root Compose controller, so restore the
        // single centered label explicitly when UIKit finishes returning to it.
        setNativeTitle(viewController.navigationItem.title ?? "")
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
private final class AppTabBarController: UIViewController, UITabBarDelegate {
    private static let symbolNames: [String: String] = [
        "HOME": "house",
        "SCHEDULE": "calendar",
        "GRADES": "list.bullet.rectangle",
        "HOMEWORK": "folder",
        "PHYVLAB": "atom",
        "MORE": "square.grid.2x2",
    ]

    private var tabRouteIds: [String] = []
    private var pendingItems: [NativeTabItem]
    private var selectedRouteId: String?
    private var activeController: TabRootNavigationController?
    private let nativeTabBar = UITabBar()
    private var tabStudentId: String?
    /// 玻璃底栏的真实占位高度要推给 Compose：宿主是全出血的，UIKit 不会把 tab bar 算进
    /// `WindowInsets.navigationBars`，而不可纵向滚动的全览表格（课程表色块概览）必须停在底栏上方。
    /// 弱引用即可：壳控制器已经强引用同一会话，这里只是取用，不该多持一份。
    private weak var session: AuthenticatedSession?
    private var pushedBottomInset: CGFloat = -1
    /// routeId → 该 tab 的导航栈。重配 tab 时按 routeId 复用，避免整条玻璃栏被拆掉、各 tab 返回栈丢失。
    private var controllersByRoute: [String: TabRootNavigationController] = [:]
    private var tabBarVisible = true

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
#endif
    }

    @available(*, unavailable)
    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func reloadTabs(session: AuthenticatedSession) {
        // 登录流程会产出新的会话实例，而底栏高度是挂在实例上的状态：换实例时必须让去重失效，
        // 否则下一次布局过阈值才推、极端情况下永远不推，课程表又钻回底栏下面。
        if session !== self.session {
            self.session = session
            pushedBottomInset = -1
        }
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
        let previousRouteId = selectedRouteId
        let nextRouteIds = items.map(\.routeId)
        for (routeId, controller) in controllersByRoute where !nextRouteIds.contains(routeId) {
            controller.onBarVisibilityChanged = nil
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
                selectTab: { [weak self] routeId in self?.select(routeId: routeId) }
            )
            controller.tabBarItem = UITabBarItem(
                title: item.title,
                image: UIImage(systemName: Self.symbolNames[item.routeId] ?? "circle"),
                selectedImage: nil
            )
            controller.onBarVisibilityChanged = { [weak self] visible in
                self?.setTabBarVisible(visible)
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
        let barHeight = tabBarVisible
            ? max(80, intrinsicHeight + view.safeAreaInsets.bottom)
            : 0
        nativeTabBar.frame = CGRect(
            x: 0,
            y: view.bounds.height - barHeight,
            width: view.bounds.width,
            height: barHeight
        )
        nativeTabBar.isHidden = !tabBarVisible
        activeController?.view.frame = view.bounds

        if let session {
            let inset = barHeight
            if abs(inset - pushedBottomInset) > 0.5 {
                pushedBottomInset = inset
                session.glassTabBarBottomInsetDp = Float(inset)
            }
        }
    }

    private func setTabBarVisible(_ visible: Bool) {
        guard tabBarVisible != visible else { return }
        tabBarVisible = visible
        view.setNeedsLayout()
    }

    private func removeAllControllers() {
        for controller in controllersByRoute.values {
            controller.onBarVisibilityChanged = nil
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
            if activeController !== next {
                activeController?.view.removeFromSuperview()
                next.loadViewIfNeeded()
                next.view.frame = view.bounds
                view.insertSubview(next.view, belowSubview: nativeTabBar)
                activeController = next
            }
            nativeTabBar.selectedItem = nativeTabBar.items?[index]
            setTabBarVisible(next.viewControllers.count <= 1)
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
            setTabBarVisible(true)
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
                session!.onNativeTabItemsChanged = { [weak self, weak tabs] _ in
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
        if ProcessInfo.processInfo.arguments.contains("--sheet-smoke") {
            return NativeSheetSmokeViewControllerKt.NativeSheetSmokeViewController()
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

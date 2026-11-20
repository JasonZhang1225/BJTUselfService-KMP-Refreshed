import BJTUShared
import Foundation
import UIKit

// 原生栏与 Compose 的声明式动作绑定：标题、同步状态、刷新与页面级动作
// 合并成一帧 UIKit 事务；滚动进度只更新已有控件，不重建按钮。
// 页面动作的 SF Symbol 映射见 symbolName(for:kind:)。

protocol NativeChromeHosting: AnyObject {
    func setNativeTitle(_ title: String)
    func setSearchActive(_ active: Bool, for owner: UIViewController)
    func refreshNavigationBarVisibility()
}

// MARK: - M17 液态玻璃壳（iOS 26+）

/// 宿主导航栏绑定：Kotlin 首帧之后才回报动态标题（教学楼名等）与右侧动作，需要回填到已入栈的控制器，
/// 并让宿主重算导航栏显隐——空标题代表「本页自绘顶栏」，系统玻璃栏不该出现。
final class NativeChromeBinding {
    weak var controller: UIViewController?
    private var lastGlassProgress: CGFloat = -1
    private var retainedActionTargets: [NativeBarActionTarget] = []
    private var actionTargets: [NativeBarActionRole: NativeBarActionTarget] = [:]
    private var actionButtons: [NativeBarActionRole: NativeBarIconButton] = [:]
    private var systemActionItems: [NativeBarActionRole: UIBarButtonItem] = [:]
    private var usesSystemBars: Bool { (controller?.navigationController as? TabRootNavigationController)?.usesSystemBars == true }
    private var lastActionLayoutKey: NativeBarActionLayoutKey?
    private var lastActionVertical: Bool?
    private var lastActionVisualKey: [String]?
    private var pendingTitle: String?
    private var pendingAction: NativeBarAction?
    private var hasPendingAction = false
    private var updateScheduled = false
    private var pageSearch: NativePageSearch?
    /// 系统搜索框是否展开；与 Compose 的 searchActive 分开，展开当帧就要挪按钮。
    private var searchPresented = false
    private var lastSearchPresented = false
    private var lastAppliedAction: NativeBarAction?

    func apply(_ title: String) {
        guard !title.isEmpty else { return }
        pendingTitle = title
        scheduleFlush()
    }

    /// 同步状态、刷新与页面级动作由 Compose 声明、由系统导航栏渲染：不再把 Compose 胶囊
    /// 塞进标题栏。动作载体只在结构真正变化时重建；滚动时不重建控件，避免控件
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
            if controller.navigationItem.title != title { controller.navigationItem.title = title }
            if controller.navigationController?.topViewController === controller {
                (controller.navigationController as? NativeChromeHosting)?.setNativeTitle(title)
            }
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
        if usesSystemBars {
            let target = onClick.map { makeActionTarget(role: role, onInvoke: $0) }
            let item = UIBarButtonItem(image: UIImage(systemName: symbolName), style: .plain, target: target, action: #selector(NativeBarActionTarget.invoke(_:)))
            item.title = title
            item.accessibilityLabel = title
            item.isEnabled = enabled && onClick != nil
            if #available(iOS 27.1, *) { item.axisBehavior = .verticalPreferred }
            systemActionItems[role] = item
            return item
        }
        let button = NativeBarIconButton(symbolName: symbolName)
        button.setVisualEnabled(enabled)
        if let onClick {
            let target = makeActionTarget(role: role, onInvoke: onClick)
            button.addTarget(
                target,
                action: #selector(NativeBarActionTarget.invoke(_:)),
                for: .touchUpInside,
            )
        }
        button.accessibilityLabel = title
        actionButtons[role] = button
        let item = UIBarButtonItem(customView: button)
        // NOTE: do NOT set sharesBackground = false here. Verified live (lldb + screenshot):
        // when every item opts out of the shared background, UIKit builds no background view
        // at all (_UIBarBackground stays empty) and the bar renders transparent no matter what
        // appearance is assigned. Default sharing gives the Settings-style frosted bar; the
        // icon itself stays a plain glyph, no nested capsules.
        return item
    }

    private func todayBarItem(vertical: Bool, onClick: @escaping () -> Void) -> UIBarButtonItem {
        if usesSystemBars && vertical {
            let target = makeActionTarget(role: .extra, onInvoke: onClick)
            let item = UIBarButtonItem(title: "今", style: .plain, target: target, action: #selector(NativeBarActionTarget.invoke(_:)))
            item.accessibilityLabel = "回到今天"
            item.setTitleTextAttributes([.font: UIFontMetrics(forTextStyle: .title3).scaledFont(for: UIFont.systemFont(ofSize: 22, weight: .semibold))], for: .normal)
            if #available(iOS 27.1, *) { item.axisBehavior = .verticalPreferred }
            return item
        }
        // Use the same square carrier and system bar background as the right-side
        // icons. A configured glass text button gets compressed into a capsule.
        let button = NativeBarIconButton(title: "今")
        button.accessibilityLabel = "回到今天"
        let target = makeActionTarget(role: .extra, onInvoke: onClick)
        button.addTarget(target, action: #selector(NativeBarActionTarget.invoke(_:)), for: .touchUpInside)
        actionButtons[.extra] = button
        return UIBarButtonItem(customView: button)
    }

    private func fixedActionSpacing() -> UIBarButtonItem {
        let item = UIBarButtonItem(barButtonSystemItem: .fixedSpace, target: nil, action: nil)
        item.width = 8
        return item
    }

    private func spinnerBarItem(title: String) -> UIBarButtonItem {
        let button = NativeSpinnerButton(type: .system)
        button.accessibilityLabel = title
        button.accessibilityValue = "进行中"
        button.isEnabled = false
        button.alpha = 0.72

        let spinner = UIActivityIndicatorView(style: .medium)
        spinner.color = .secondaryLabel
        spinner.startAnimating()
        spinner.translatesAutoresizingMaskIntoConstraints = false
        button.addSubview(spinner)
        NSLayoutConstraint.activate([
            spinner.centerXAnchor.constraint(equalTo: button.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: button.centerYAnchor),
            spinner.widthAnchor.constraint(equalToConstant: 20),
            spinner.heightAnchor.constraint(equalToConstant: 20),
        ])

        let item = UIBarButtonItem(customView: button)
        if usesSystemBars, #available(iOS 27.1, *) { item.axisBehavior = .verticalPreferred }
        // Same as iconBarItem: keep the shared bar background so _UIBarBackground is built.
        return item
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

    private func updateActionVisuals(_ action: NativeBarAction) {
        // Compose reports the bar on every recomposition (many per second while syncing).
        // Re-assigning identical images/titles to bar items relayouts UINavigationBar each
        // time; on device that cost ~40% of the main thread during sync.
        let visualKey = [action.status, action.label, action.extraLabel ?? ""]
        guard visualKey != lastActionVisualKey else { return }
        lastActionVisualKey = visualKey
        systemActionItems[.status]?.image = UIImage(systemName: symbolName(for: action.status, kind: .status))
        systemActionItems[.status]?.accessibilityLabel = action.status
        systemActionItems[.status]?.title = action.status
        systemActionItems[.refresh]?.title = action.label
        actionButtons[.status]?.update(
            symbolName: symbolName(for: action.status, kind: .status),
            accessibilityLabel: action.status,
        )
        actionButtons[.refresh]?.accessibilityLabel = action.label
        actionButtons[.extra]?.accessibilityLabel = action.extraLabel == "今" ? "回到今天" : action.extraLabel
    }

    private func symbolName(for label: String, kind: NativeBarActionItemKind) -> String {
        switch kind {
        case .refresh:
            return "arrow.clockwise"
        case .extra:
            if label.contains("写信") {
                return "square.and.pencil"
            }
            if label.localizedCaseInsensitiveContains("日历") {
                return "calendar.badge.plus"
            }
            if label.contains("导出") { return "square.and.arrow.up" }
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
        (controller as? SafeAreaComposeHost)?.setScrollController(action?.scrollController)
        applySearch(action, to: controller)
        guard let action else {
            retainedActionTargets.removeAll()
            actionTargets.removeAll()
            actionButtons.removeAll()
            systemActionItems.removeAll()
            lastActionLayoutKey = nil
            lastActionVisualKey = nil
            lastAppliedAction = nil
            UIView.performWithoutAnimation {
                controller.navigationItem.leftBarButtonItems = nil
                controller.navigationItem.leftItemsSupplementBackButton = false
                controller.navigationItem.rightBarButtonItems = nil
            }
            ensureTransparentBar()
            applyGlassProgress(0)
            return
        }
        lastAppliedAction = action
        let layoutKey = NativeBarActionLayoutKey(action)
        let vertical = usesSystemBars && nativeBarIsVertical(in: controller.traitCollection)
        if layoutKey == lastActionLayoutKey && lastActionVertical == vertical && lastSearchPresented == searchPresented {
            // The only expected change here is scroll-driven glass/title progress or a
            // freshly captured Kotlin callback. Keep every UIKit view alive.
            updateActionTargets(action)
            updateActionVisuals(action)
            applyGlassProgress(CGFloat(action.scrollProgress))
            return
        }
        lastActionLayoutKey = layoutKey
        lastActionVertical = vertical
        lastSearchPresented = searchPresented
        lastActionVisualKey = nil
        retainedActionTargets.removeAll()
        actionTargets.removeAll()
        actionButtons.removeAll()
        systemActionItems.removeAll()
        var items: [UIBarButtonItem] = []
        var leftItems: [UIBarButtonItem] = []
        // Login and content sync share the same trailing spinner. Busy must
        // still draw when canRefresh is false (silent auto-login sets it so
        // the user cannot start another refresh mid-login).
        let refreshItem: UIBarButtonItem?
#if DEBUG
        let isBusy = action.busy || (ProcessInfo.processInfo.arguments.contains("--layout-smoke") && ProcessInfo.processInfo.arguments.contains("--chrome-busy"))
#else
        let isBusy = action.busy
#endif
        if isBusy {
            let title = action.label.isEmpty ? action.status : action.label
            refreshItem = spinnerBarItem(title: title.isEmpty ? "加载中" : title)
        } else if action.canRefresh {
            refreshItem = iconBarItem(
                title: action.label,
                symbolName: symbolName(for: action.label, kind: .refresh),
                role: .refresh,
                onClick: action.onClick,
            )
        } else {
            refreshItem = nil
        }
        let statusItem = action.status.isEmpty
            ? nil
            : iconBarItem(
                title: action.status,
                symbolName: symbolName(for: action.status, kind: .status),
                role: .status,
                onClick: action.onStatusClick,
                enabled: action.onStatusClick != nil,
            )

        if let statusItem, let refreshItem {
            // rightBarButtonItems is laid out from index 0 at the trailing
            // edge, so refresh is first and status follows after a fixed gap.
            items.append(refreshItem)
            if !usesSystemBars { items.append(fixedActionSpacing()) }
            items.append(statusItem)
        } else if let statusItem {
            items.append(statusItem)
        } else if let refreshItem {
            items.append(refreshItem)
        }
        if let extraLabel = action.extraLabel, let onExtraClick = action.onExtraClick {
            if extraLabel == "今" {
                leftItems.append(todayBarItem(vertical: vertical, onClick: onExtraClick))
            } else {
                leftItems.append(
                    iconBarItem(
                        title: extraLabel,
                        symbolName: symbolName(for: extraLabel, kind: .extra),
                        role: .extra,
                        onClick: onExtraClick,
                    )
                )
            }
        }
        if usesSystemBars {
            // In a horizontal bar, Today and export have their own leading placement so the
            // status/refresh group leaves a readable gap around the centered title.
            // Duo's vertical layout keeps all three in the same side group.
            let leadingExtra = !vertical && (action.extraLabel == "今" || action.extraLabel?.contains("导出") == true ||
                action.extraLabel?.contains("添加到日历") == true)
            if !leadingExtra {
                items.append(contentsOf: leftItems)
                leftItems.removeAll()
            }
        }
        // 收起时搜索按钮放在左侧，和右侧的同步状态、刷新分开。展开期间挪到尾部：
        // 系统给前部的输入框只有约 180pt，放在尾部才铺满整条栏（其余内容系统会藏起）。
        var searchItem: UIBarButtonItem?
        if #available(iOS 26.0, *), !vertical, action.searchPrompt != nil,
           controller.navigationItem.searchController != nil {
            searchItem = controller.navigationItem.searchBarPlacementBarButtonItem
        }
        let leadingSearchItem = searchPresented ? nil : searchItem
        UIView.performWithoutAnimation {
            controller.navigationItem.leadingItemGroups = []
            controller.navigationItem.leftItemsSupplementBackButton = !leftItems.isEmpty || leadingSearchItem != nil
            if let leadingSearchItem {
                controller.navigationItem.leftBarButtonItems = nil
                controller.navigationItem.leadingItemGroups = [
                    UIBarButtonItemGroup(barButtonItems: [leadingSearchItem] + leftItems, representativeItem: nil)
                ]
            } else {
                controller.navigationItem.leftBarButtonItems = leftItems.isEmpty ? nil : leftItems
            }
            controller.navigationItem.rightBarButtonItems = items.isEmpty ? nil : items
            if usesSystemBars {
                let ordered = Array(items.reversed())
                if vertical && action.extraLabel == "今", let today = ordered.first {
                    // Separate groups give Today its own system glass circle.
                    controller.navigationItem.trailingItemGroups = [
                        UIBarButtonItemGroup(barButtonItems: [today], representativeItem: nil),
                        UIBarButtonItemGroup(barButtonItems: Array(ordered.dropFirst()), representativeItem: nil),
                    ].filter { !$0.barButtonItems.isEmpty }
                } else {
                    controller.navigationItem.trailingItemGroups = ordered.isEmpty ? [] : [
                        UIBarButtonItemGroup(barButtonItems: ordered, representativeItem: nil)
                    ]
                }
            }
            if let searchItem, searchPresented {
                controller.navigationItem.trailingItemGroups = [
                    UIBarButtonItemGroup(barButtonItems: [searchItem], representativeItem: nil)
                ]
            }
        }
        ensureTransparentBar()
        applyGlassProgress(CGFloat(action.scrollProgress))
    }

    /// 本页声明了搜索就挂系统 UISearchController；输入框、展开收起动画与取消都交给系统，
    /// Compose 只按回传的文字把列表换成结果。页面不再声明搜索时摘掉。
    private func applySearch(_ action: NativeBarAction?, to controller: UIViewController) {
        guard let action, action.searchPrompt != nil else {
            pageSearch?.detach(from: controller)
            pageSearch = nil
            (controller.navigationController as? NativeChromeHosting)?.setSearchActive(false, for: controller)
            return
        }
        let search = pageSearch ?? NativePageSearch()
        if pageSearch == nil {
            // 输入框展开后占满整条栏，居中标题让位；收起再淡回。
            search.onActiveChange = { [weak self, weak controller] active in
                guard let self, let controller else { return }
                (controller.navigationController as? NativeChromeHosting)?.setSearchActive(active, for: controller)
                self.setSearchPresented(active)
            }
        }
        pageSearch = search
        search.update(action)
        search.attach(to: controller)
    }

    /// 搜索展开/收起时只重排栏内按钮位置（见 applyAction），不等 Compose 回报。
    private func setSearchPresented(_ presented: Bool) {
        guard searchPresented != presented else { return }
        searchPresented = presented
        if let lastAppliedAction { applyAction(lastAppliedAction) }
    }

    /// Transparent bar background setup (titles only). Assigned on structural changes;
    /// the glass itself is a dedicated overlay driven per-frame below.
    ///
    /// Rationale, all verified live: manually built appearances ignore configureWith*
    /// on iOS 27, and sharesBackground=false suppresses _UIBarBackground entirely — so
    /// the appearance path can only do titles, never glass.
    ///
    /// Keep the title hierarchy stable. Compose's Skia scroll container is not
    /// a UIKit scroll view, so manually switching large-title display modes
    /// leaves UIKit's old large-title height behind. The title stays compact
    /// while the content moves underneath it.
    private func ensureTransparentBar() {
        guard !usesSystemBars, let controller, let navigationController = controller.navigationController else { return }
        let appearance = UINavigationBarAppearance()
        appearance.configureWithTransparentBackground()
        // Explicitly remove the default material too: a clear tint alone still
        // leaves UIKit's background effect visible at the unscrolled edge.
        appearance.backgroundEffect = nil
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
            navigationController.navigationBar.compactAppearance = appearance
            navigationController.navigationBar.compactScrollEdgeAppearance = appearance
        }
    }

    /// Glass intensity follows scroll depth directly (0 top → 1). Direct tracking needs
    /// no animation: it cannot lag the finger, overshoot, or flash, and it is
    /// Reduce-Motion-safe (no autonomous motion). Epsilon cuts redundant writes.
    private func applyGlassProgress(_ progress: CGFloat) {
        guard let controller, let navigationController = controller.navigationController else { return }
        let reachesEndpoint = progress == 0 || progress == 1
        if progress != lastGlassProgress && (reachesEndpoint || abs(progress - lastGlassProgress) > 0.005) {
            lastGlassProgress = progress
            if let host = controller as? SafeAreaComposeHost {
                // The host merges this with UIKit rubber-band displacement Compose never sees.
                host.setComposeGlassProgress(progress)
            } else {
                (navigationController as? TabRootNavigationController)?.setTopGlassProgress(progress, for: controller)
            }
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
    let hasStatus: Bool
    let canRefresh: Bool
    let busy: Bool
    let hasStatusAction: Bool
    let extraLabel: String?
    let hasExtraAction: Bool
    let hasSearch: Bool

    init(_ action: NativeBarAction) {
        hasStatus = !action.status.isEmpty
        canRefresh = action.canRefresh
        busy = action.busy
        hasStatusAction = action.onStatusClick != nil
        extraLabel = action.extraLabel
        hasExtraAction = action.onExtraClick != nil
        hasSearch = action.searchPrompt != nil
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
    private var currentSymbolName: String?
    init(title: String) {
        super.init(frame: .zero)
        setTitle(title, for: .normal)
        setTitleColor(.label, for: .normal)
        titleLabel?.font = .systemFont(ofSize: 18, weight: .semibold)
        setVisualEnabled(true)
    }

    init(symbolName: String) {
        super.init(frame: .zero)
        update(symbolName: symbolName, accessibilityLabel: nil)
        setVisualEnabled(true)
    }

    func update(symbolName: String, accessibilityLabel: String?) {
        let symbolConfiguration = UIImage.SymbolConfiguration(
            pointSize: 18,
            weight: .semibold,
        )
        if symbolName != currentSymbolName {
            currentSymbolName = symbolName
            setImage(UIImage(systemName: symbolName, withConfiguration: symbolConfiguration), for: .normal)
        }
        if let accessibilityLabel, self.accessibilityLabel != accessibilityLabel {
            self.accessibilityLabel = accessibilityLabel
        }
    }

    func setVisualEnabled(_ enabled: Bool) {
        isEnabled = enabled
        alpha = enabled ? 1.0 : 0.46
        tintColor = enabled ? .label : .secondaryLabel
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

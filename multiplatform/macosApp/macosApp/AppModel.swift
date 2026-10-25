import SwiftUI
import AppKit
import BJTUShared

/// 宿主侧的应用状态。
///
/// Kotlin 侧只负责共享业务界面与导航请求；侧栏条目、当前选中项、滚动进度
/// 都由共享层回报后在这里落地，SwiftUI 再据此渲染原生外观。
@MainActor
final class AppModel: ObservableObject, NativeDesktopPresentation {

    lazy var controller = MacosAppController()

    @Published private(set) var sidebarItems: [NativeSidebarItem] = []
    @Published private(set) var selection: String?
    @Published private(set) var isSignedIn = false
    @Published private(set) var accountName = ""
    @Published private(set) var accountDepartment = ""
    /// 内容区滚动偏移（像素）。原生工具栏据此在内容滚过顶栏时切换材质。
    @Published private(set) var scrollOffset: Double = 0

    @Published private(set) var home: NativeDesktopHome?
    func showHome(value: NativeDesktopHome?) {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--native-home-smoke") {
            let selected = value?.days.first(where: { $0.selected })?.id ?? "none"
            FileHandle.standardOutput.write(Data("Native home: \(selected), events=\(value?.events.count ?? 0)\n".utf8))
        }
        #endif
        DispatchQueue.main.async { [weak self] in self?.home = value }
    }

    @Published private(set) var login: NativeDesktopLogin?
    @Published private(set) var guide: NativeDesktopGuide?
    @Published private(set) var sheet: MacosSheetState?

    private func updateSheet(id: String, value: MacosSheetState?) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if let value { self.sheet = value }
            else if self.sheet?.id == id { self.sheet = nil }
        }
    }
    @Published private(set) var pageTitle = "交大自由行"
    @Published private(set) var canGoBack = false
    @Published private(set) var barAction: NativeBarAction?
    private var backAction: (() -> Void)?

    func showLogin(value: NativeDesktopLogin?) {
        DispatchQueue.main.async { [weak self] in self?.login = value }
    }
    func showGuide(value: NativeDesktopGuide?) {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--native-ui-smoke") {
            FileHandle.standardOutput.write(Data("Native guide remaining: \(value?.remaining ?? -1)\n".utf8))
        }
        #endif
        DispatchQueue.main.async { [weak self] in self?.guide = value }
    }
    func updateNavigation(title: String, canGoBack: Bool, onBack: @escaping () -> Void) {
        DispatchQueue.main.async { [weak self] in
            self?.pageTitle = title
            self?.canGoBack = canGoBack
            self?.backAction = onBack
        }
    }
    func updateAction(value: NativeBarAction?) {
        DispatchQueue.main.async { [weak self] in self?.barAction = value }
    }
    func goBack() { backAction?() }

    private var rootViewController: NSViewController?

    /// 共享层的 Compose 视图控制器；SwiftUI 用 NSViewRepresentable 承载它。
    var composeViewController: NSViewController? { rootViewController }

    func bootstrap() {
        guard rootViewController == nil else { return }
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--native-home-smoke") {
            let arguments = ProcessInfo.processInfo.arguments
            let dark = arguments.contains("--native-ui-dark")
            NSApp.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            let scenario = arguments.firstIndex(of: "--home-scenario").flatMap { $0 + 1 < arguments.count ? arguments[$0 + 1] : nil } ?? "normal"
            sidebarItems = [NativeSidebarItem(routeId: "HOME", title: "首页", systemImage: "house")]
            selection = "HOME"
            rootViewController = MacosPresentationPreview().createHomePreview(
                presentation: self,
                onSheetChanged: { [weak self] id, value in self?.updateSheet(id: id, value: value) },
                darkTheme: dark, scenario: scenario
            )
            return
        }
        if ProcessInfo.processInfo.arguments.contains("--native-ui-smoke") {
            let dark = ProcessInfo.processInfo.arguments.contains("--native-ui-dark")
            NSApp.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            rootViewController = MacosPresentationPreview().createPresentationPreview(
                presentation: self,
                onSheetChanged: { [weak self] id, value in self?.updateSheet(id: id, value: value) },
                darkTheme: dark
            )
            return
        }
        #endif
        let controller = self.controller
        rootViewController = controller.createRootViewController(
            onSectionChanged: { [weak self] routeId in
                DispatchQueue.main.async {
                    self?.selection = routeId
                }
            },
            onSessionChanged: { [weak self] session in
                DispatchQueue.main.async {
                    self?.isSignedIn = session != nil
                    self?.refreshAccount()
                    self?.refreshSidebar()
                }
            },
            onScrollProgress: { [weak self] value in
                DispatchQueue.main.async {
                    self?.scrollOffset = value.doubleValue
                }
            },
            presentation: self,
            onSheetChanged: { [weak self] id, value in self?.updateSheet(id: id, value: value) }
        )
        refreshSidebar()
        refreshAccount()
    }

    func select(_ item: NativeSidebarItem) {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--native-home-smoke") { return }
        #endif
        selection = item.routeId
        controller.selectSidebarItem(routeId: item.routeId)
    }

    func updateTitlebarInset(_ points: Double) {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--native-ui-smoke") || ProcessInfo.processInfo.arguments.contains("--native-home-smoke") { return }
        #endif
        controller.updateTitlebarInset(points: points)
    }

    func refreshCurrentPage() {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--native-home-smoke") { barAction?.onClick(); return }
        if ProcessInfo.processInfo.arguments.contains("--native-ui-smoke") { return }
        #endif
        controller.refreshCurrentPage()
    }

    private func refreshSidebar() {
        sidebarItems = controller.sidebarItems()
        if let selection, !sidebarItems.contains(where: { $0.routeId == selection }) {
            self.selection = nil
        }
        if selection == nil {
            selection = sidebarItems.first?.routeId
        }
    }

    private func refreshAccount() {
        accountName = controller.accountName()
        accountDepartment = controller.accountDepartment()
    }
}

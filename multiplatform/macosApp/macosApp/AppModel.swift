import SwiftUI
import AppKit
import BJTUShared

/// 宿主侧的应用状态。
///
/// Kotlin 侧只负责共享业务界面与导航请求；侧栏条目、当前选中项、滚动进度
/// 都由共享层回报后在这里落地，SwiftUI 再据此渲染原生外观。
@MainActor
final class AppModel: ObservableObject {

    let controller = MacosAppController()

    @Published private(set) var sidebarItems: [NativeSidebarItem] = []
    @Published private(set) var selection: String?
    @Published private(set) var isSignedIn = false
    @Published private(set) var accountName = ""
    @Published private(set) var accountDepartment = ""
    /// 内容区滚动偏移（像素）。原生工具栏据此在内容滚过顶栏时切换材质。
    @Published private(set) var scrollOffset: Double = 0

    private var rootViewController: NSViewController?

    /// 共享层的 Compose 视图控制器；SwiftUI 用 NSViewRepresentable 承载它。
    var composeViewController: NSViewController? { rootViewController }

    func bootstrap() {
        guard rootViewController == nil else { return }
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
            }
        )
        refreshSidebar()
        refreshAccount()
    }

    func select(_ item: NativeSidebarItem) {
        selection = item.routeId
        controller.selectSidebarItem(routeId: item.routeId)
    }

    func refreshCurrentPage() {
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

import BJTUShared
import Foundation
import UIKit

/// 页内搜索：iOS 26 起是导航栏里的系统搜索按钮（液态玻璃），点开就地展开成输入框，
/// 取消后收回；更早系统退回导航栏下方的常驻搜索栏。只把文字和开合事件转给 Compose。
final class NativePageSearch: NSObject, UISearchResultsUpdating, UISearchControllerDelegate {
    let searchController = UISearchController(searchResultsController: nil)
    private var onOpen: (() -> Void)?
    private var onQueryChange: ((String) -> Void)?
    private var onClose: (() -> Void)?
    /// 系统搜索框展开（true）或收起（false），宿主据此让出居中标题。
    var onActiveChange: ((Bool) -> Void)?
    private var lastQuery: String?
    /// Compose 上一次回报的开合状态。只在它由开变关时收起系统搜索（如离开作业页）：
    /// 滚动期间排队的旧动作仍带着「未打开」，若按值同步，会把用户刚点开的搜索立刻关掉。
    private var composeActive = false

    override init() {
        super.init()
        searchController.searchResultsUpdater = self
        searchController.delegate = self
        // 结果就在原页面里换，不需要系统再盖一层暗色遮罩。
        searchController.obscuresBackgroundDuringPresentation = false
        searchController.searchBar.autocapitalizationType = .none
        searchController.searchBar.returnKeyType = .search
    }

    func update(_ action: NativeBarAction) {
        onOpen = action.onSearchOpen
        onQueryChange = action.onSearchQueryChange
        onClose = action.onSearchClose
        if let prompt = action.searchPrompt, searchController.searchBar.placeholder != prompt {
            searchController.searchBar.placeholder = prompt
        }
        let active = action.searchActive
        if composeActive && !active && searchController.isActive {
            searchController.isActive = false
        }
        composeActive = active
    }

    func attach(to controller: UIViewController) {
        let item = controller.navigationItem
        guard item.searchController !== searchController else { return }
        // 搜索激活时 push 详情再返回，结果和输入框都还在。
        controller.definesPresentationContext = true
        item.hidesSearchBarWhenScrolling = false
        if #available(iOS 26.0, *) {
            item.preferredSearchBarPlacement = .integratedButton
        }
        item.searchController = searchController
    }

    func detach(from controller: UIViewController) {
        if searchController.isActive { searchController.isActive = false }
        if controller.navigationItem.searchController === searchController {
            controller.navigationItem.searchController = nil
        }
    }

    func updateSearchResults(for searchController: UISearchController) {
        let text = searchController.searchBar.text ?? ""
        guard text != lastQuery else { return }
        lastQuery = text
        onQueryChange?(text)
    }

    func willPresentSearchController(_ searchController: UISearchController) {
        lastQuery = nil
        onActiveChange?(true)
        onOpen?()
    }

    func willDismissSearchController(_ searchController: UISearchController) {
        lastQuery = nil
        onActiveChange?(false)
        onClose?()
    }
}

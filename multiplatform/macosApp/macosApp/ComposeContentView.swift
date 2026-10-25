import SwiftUI
import AppKit
import BJTUShared

/// 用 NSView 承载 Kotlin/Native 侧创建的 Compose 视图。
///
/// 共享业务界面画在 Kotlin 侧的 SkiaLayer 上，这个类型只负责把它放进
/// SwiftUI 的视图树，并保证视图控制器随宿主一起释放。
struct ComposeContentView: NSViewRepresentable {

    let viewController: NSViewController

    func makeNSView(context: Context) -> NSView {
        // 触碰 view 会触发 loadView，把 Compose 的渲染视图装进内容视图。
        let view = viewController.view
        view.translatesAutoresizingMaskIntoConstraints = false
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        // Compose 自己管理重绘，这里不需要同步状态。
    }

    static func dismantleNSView(_ nsView: NSView, coordinator: Void) {
        // Compose 宿主由 Kotlin 侧持有，视图控制器随 AppModel 一起释放。
    }
}

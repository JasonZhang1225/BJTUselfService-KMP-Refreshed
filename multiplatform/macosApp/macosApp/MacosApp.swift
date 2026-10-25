import SwiftUI
import AppKit

@main
struct MacosApp: App {

    @StateObject private var model = AppModel()

    var body: some Scene {
        Window("交大自由行", id: "main") {
            ContentView(model: model)
                .frame(minWidth: 960, minHeight: 620)
                .onAppear { WindowCapture.runIfRequested() }
        }
        .windowStyle(.titleBar)
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandGroup(after: .toolbar) {
                Button("刷新当前页面") {
                    model.refreshCurrentPage()
                }
                .keyboardShortcut("r", modifiers: .command)
            }
        }
    }
}

/// 调试用：把窗口画面渲染成 PNG 存盘。
///
/// 本机的 shell 没有「屏幕录制」权限，`screencapture` 拿不到窗口内容；
/// 应用进程内渲染自己的窗口不需要该权限，因此取证走这条路。
enum WindowCapture {

    static func runIfRequested() {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "--capture-window"),
              index + 1 < arguments.count else { return }
        let path = arguments[index + 1]
        let delay = arguments.firstIndex(of: "--capture-delay")
            .flatMap { $0 + 1 < arguments.count ? Double(arguments[$0 + 1]) : nil } ?? 6
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            capture(to: path)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                NSApp.terminate(nil)
            }
        }
    }

    private static func capture(to path: String) {
        guard let window = NSApp.windows.first(where: { $0.isVisible }) else { return }
        // contentView 的 superview 是主题框架（含标题栏与工具栏），
        // 只取 contentView 会丢掉工具栏这一段原生外观。
        let target = window.contentView?.superview ?? window.contentView
        guard let target else { return }
        guard let rep = target.bitmapImageRepForCachingDisplay(in: target.bounds) else { return }
        target.cacheDisplay(in: target.bounds, to: rep)
        guard let data = rep.representation(using: .png, properties: [:]) else { return }
        let url = URL(fileURLWithPath: path)
        try? FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try? data.write(to: url)
    }
}

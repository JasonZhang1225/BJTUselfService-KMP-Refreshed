import SwiftUI
import AppKit
import BJTUShared

/// macOS 宿主主界面。
///
/// 侧栏、工具栏与材质全部由 SwiftUI 画（原生 `NavigationSplitView`），
/// 右侧内容区是 Kotlin 侧共享的 Compose 业务界面。
/// 滚动时顶栏由透明材质过渡到磨砂材质，对齐 Mac App Store 的观感。
struct ContentView: View {

    @ObservedObject var model: AppModel
    @State private var searchText = ""

    private var filteredItems: [NativeSidebarItem] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return model.sidebarItems }
        return model.sidebarItems.filter { $0.title.localizedCaseInsensitiveContains(query) }
    }

    /// 内容滚过顶栏后，工具栏从透明过渡到磨砂。
    private var toolbarMaterial: Material {
        model.scrollOffset > 12 ? .ultraThinMaterial : .regularMaterial
    }

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            detail
        }
        .navigationTitle("交大自由行")
        .navigationSubtitle(currentTitle)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.refreshCurrentPage()
                } label: {
                    Label("刷新", systemImage: "arrow.clockwise")
                }
                .help("刷新当前页面")
            }
        }
        .toolbarBackground(toolbarMaterial, for: .windowToolbar)
        .onAppear { model.bootstrap() }
    }

    private var currentTitle: String {
        guard let selection = model.selection else { return "" }
        return model.sidebarItems.first(where: { $0.routeId == selection })?.title ?? ""
    }

    // MARK: - 侧栏

    private var sidebar: some View {
        VStack(spacing: 0) {
            List(selection: Binding(
                get: { model.selection },
                set: { newValue in
                    guard let newValue,
                          let item = model.sidebarItems.first(where: { $0.routeId == newValue })
                    else { return }
                    model.select(item)
                }
            )) {
                ForEach(filteredItems, id: \.routeId) { item in
                    Label(item.title, systemImage: item.systemImage)
                        .tag(item.routeId)
                        .accessibilityLabel(item.title)
                }
            }
            .listStyle(.sidebar)
            .searchable(text: $searchText, placement: .sidebar, prompt: "搜索")

            Divider()

            accountFooter
        }
        .navigationSplitViewColumnWidth(min: 190, ideal: 224, max: 320)
    }

    private var accountFooter: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.18))
                    .frame(width: 30, height: 30)
                Text(initial)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Color.accentColor)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(model.accountName.isEmpty ? "未登录" : model.accountName)
                    .font(.system(size: 12, weight: .medium))
                if !model.accountDepartment.isEmpty {
                    Text(model.accountDepartment)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
    }

    private var initial: String {
        guard let first = model.accountName.first else { return "?" }
        return String(first)
    }

    // MARK: - 内容区

    @ViewBuilder
    private var detail: some View {
        if let viewController = model.composeViewController {
            ComposeContentView(viewController: viewController)
                .ignoresSafeArea(.container, edges: .bottom)
        } else {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

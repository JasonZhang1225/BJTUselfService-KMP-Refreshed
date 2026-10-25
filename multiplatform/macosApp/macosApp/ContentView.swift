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
    @State private var pageSearchText = ""

    private var filteredItems: [NativeSidebarItem] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return model.sidebarItems }
        return model.sidebarItems.filter { $0.title.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            detail
        }
        .navigationTitle(model.login == nil ? model.pageTitle : "交大自由行")
        .toolbar {
            ToolbarItem(placement: .navigation) {
                if model.canGoBack && model.login == nil {
                    Button(action: model.goBack) { Label("返回", systemImage: "chevron.left") }
                        .keyboardShortcut("[", modifiers: .command)
                }
            }
            ToolbarItemGroup(placement: .primaryAction) {
                if model.login == nil, let action = model.barAction {
                    if action.busy { ProgressView().controlSize(.small) }
                    if !action.status.isEmpty {
                        if let onStatus = action.onStatusClick {
                            Button(action.status, action: onStatus)
                        } else {
                            Text(action.status).foregroundStyle(.secondary)
                        }
                    }
                    if let label = action.extraLabel, let onExtra = action.onExtraClick {
                        Button(label, action: onExtra)
                    }
                    if let prompt = action.searchPrompt {
                        TextField(prompt, text: Binding(
                            get: { pageSearchText },
                            set: { value in
                                pageSearchText = value
                                action.onSearchOpen?()
                                action.onSearchQueryChange?(value)
                            }
                        ))
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 180)
                    }
                    if action.canRefresh {
                        Button(action: action.onClick) { Label(action.label, systemImage: "arrow.clockwise") }
                            .disabled(action.busy)
                    }
                }
            }
        }
        .toolbarBackground(model.home != nil || model.login != nil ? .automatic : (model.scrollOffset > 12 ? .visible : .hidden), for: .windowToolbar)
        .onChange(of: model.pageTitle) { _ in pageSearchText = "" }
        .sheet(isPresented: Binding(get: { model.guide != nil }, set: { _ in })) {
            ReleaseGuideHost(model: model)
        }
        .sheet(isPresented: Binding(get: { model.sheet != nil }, set: { visible in
            if !visible { model.sheet?.onDismiss() }
        })) {
            NativeSheetHost(model: model)
        }
        .onAppear { model.bootstrap() }
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
            ZStack {
                ComposeContentView(viewController: viewController)
                    .background(TitlebarInsetReader(onChange: model.updateTitlebarInset))
                    .ignoresSafeArea(.container, edges: model.home == nil && model.login == nil ? [.top, .bottom] : [])
                    .allowsHitTesting(model.login == nil && model.home == nil)
                    .accessibilityHidden(model.home != nil || model.login != nil)
                if let login = model.login {
                    NativeLoginView(login: login)
                } else if let home = model.home {
                    NativeHomeView(home: home)
                }
            }
        } else {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

private struct ReleaseGuideView: View {
    let guide: NativeDesktopGuide

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(guide.title).font(.title2.bold())
            ScrollView {
                Text(guide.body).font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            if guide.saveFailed {
                Text("确认保存失败，请重试。").foregroundStyle(.red)
            }
            HStack {
                Spacer()
                Button(guide.remaining > 0 ? "确认（\(guide.remaining)）" : "确认", action: guide.onConfirm)
                    .keyboardShortcut(.defaultAction)
                    .disabled(guide.remaining > 0)
            }
        }
        .padding(28)
        .frame(width: 520, height: 480)
        .interactiveDismissDisabled()
    }
}

private struct NativeLoginView: View {
    let login: NativeDesktopLogin
    @State private var username = ""
    @State private var password = ""
    @FocusState private var field: Field?
    private enum Field { case username, password }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Image(systemName: "tram.fill")
                    .font(.system(size: 44)).foregroundStyle(.tint)
                    .accessibilityHidden(true)
                VStack(spacing: 8) {
                    Text("登录交大自由行").font(.largeTitle.bold())
                    Text("使用 MIS 统一身份认证账号").foregroundStyle(.secondary)
                }
                Form {
                    TextField("学号", text: $username)
                        .textContentType(.username)
                        .focused($field, equals: .username)
                        .onSubmit { field = .password }
                    SecureField("密码", text: $password)
                        .textContentType(.password)
                        .focused($field, equals: .password)
                        .onSubmit(submit)
                    Toggle("保存登录信息", isOn: Binding(
                        get: { login.rememberCredentials },
                        set: { login.onRememberChange(KotlinBoolean(value: $0)) }
                    ))
                    .disabled(!login.canRememberCredentials)
                }
                .formStyle(.grouped)
                .disabled(login.busy)
                if !login.message.isEmpty {
                    Text(login.message).font(.callout).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Button(action: submit) {
                    HStack {
                        if login.busy { ProgressView().controlSize(.small) }
                        Text(login.busy ? "正在登录…" : "登录")
                    }.frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).controlSize(.large)
                .disabled(login.busy || username.isEmpty || password.isEmpty)
                Text("登录信息保存在系统钥匙串中。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .frame(maxWidth: 380).padding(40)
            .frame(maxWidth: .infinity)
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .onAppear { username = login.username; password = login.password }
        .onChange(of: login.username) { if field != .username { username = $0 } }
        .onChange(of: login.password) { if field != .password { password = $0 } }
        .onChange(of: username) { login.onUsernameChange($0) }
        .onChange(of: password) { login.onPasswordChange($0) }
    }

    private func submit() {
        guard !login.busy, !username.isEmpty, !password.isEmpty else { return }
        field = nil
        login.onLogin()
    }
}


/// Read the actual window toolbar height instead of assuming a title-bar size.
private struct TitlebarInsetReader: NSViewRepresentable {
    let onChange: (Double) -> Void
    func makeNSView(context: Context) -> InsetView {
        let view = InsetView()
        view.onChange = onChange
        return view
    }
    func updateNSView(_ view: InsetView, context: Context) {
        view.onChange = onChange
        view.reportInset()
    }
    final class InsetView: NSView {
        var onChange: ((Double) -> Void)?
        private var previous: Double?
        override func viewDidMoveToWindow() { super.viewDidMoveToWindow(); reportInset() }
        override func layout() { super.layout(); reportInset() }
        func reportInset() {
            guard let window else { return }
            let height = max(0, window.frame.height - window.contentLayoutRect.height)
            guard height != previous else { return }
            previous = height
            onChange?(height)
        }
    }
}


private struct NativeSheetView: View {
    let sheet: MacosSheetState
    var body: some View {
        VStack(spacing: 16) {
            if let title = sheet.title {
                Text(title).font(.title2.bold()).frame(maxWidth: .infinity, alignment: .leading)
            }
            ComposeContentView(viewController: sheet.viewController)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            HStack {
                if sheet.showDismissButton {
                    Button(sheet.dismissLabel ?? "关闭", action: sheet.onDismiss)
                        .keyboardShortcut(.cancelAction).disabled(!sheet.dismissEnabled)
                }
                Spacer()
                if let label = sheet.todayLabel, let action = sheet.onToday {
                    Button(label, action: action).disabled(!sheet.todayEnabled)
                }
                if let label = sheet.confirmLabel, let action = sheet.onConfirm {
                    Button(label, action: action).disabled(!sheet.confirmEnabled)
                        .keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(24)
        .frame(minWidth: 480, idealWidth: 560, maxWidth: 760, minHeight: 340, idealHeight: 480, maxHeight: 620)
        .interactiveDismissDisabled(!sheet.showDismissButton || !sheet.dismissEnabled)
    }
}


private struct ReleaseGuideHost: View {
    @ObservedObject var model: AppModel
    var body: some View {
        if let guide = model.guide { ReleaseGuideView(guide: guide) }
    }
}

private struct NativeSheetHost: View {
    @ObservedObject var model: AppModel
    var body: some View {
        if let sheet = model.sheet { NativeSheetView(sheet: sheet) }
    }
}

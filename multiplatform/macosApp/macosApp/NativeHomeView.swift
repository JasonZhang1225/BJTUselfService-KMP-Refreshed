import SwiftUI
import BJTUShared

/// SwiftUI owns scrolling and controls. Kotlin supplies resolved agenda data and actions.
struct NativeHomeView: View {
    let home: NativeDesktopHome

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("今天").font(.largeTitle.bold()).id("native-home-top")
                    Text("日程与校园动态").foregroundStyle(.secondary)
                }
                if let error = home.errorMessage {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .font(.callout).foregroundStyle(.orange)
                }
                ForEach(home.nowEvents, id: \.id) { event in
                    homeCard { NativeHomeEventRow(event: event) }
                }
                if !home.dueSoon.isEmpty {
                    homeCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Label("48 小时内有 \(home.dueSoon.count) 项作业截止", systemImage: "clock.badge.exclamationmark")
                                .font(.headline).foregroundStyle(.orange)
                            ForEach(Array(home.dueSoon.prefix(3).enumerated()), id: \.offset) { _, text in
                                Text(text).font(.callout).foregroundStyle(.secondary)
                            }
                            if home.dueSoon.count > 3 { Text("另有 \(home.dueSoon.count - 3) 项").font(.caption).foregroundStyle(.secondary) }
                            Button("查看作业", action: home.onOpenHomework)
                        }
                    }
                }
                agenda
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 16), count: max(1, min(2, home.summaries.count))), alignment: .leading, spacing: 16) {
                    ForEach(home.summaries, id: \.title) { summary in
                        homeCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Label(summary.title, systemImage: summary.title == "图书馆" ? "books.vertical" : "envelope")
                                    .font(.headline)
                                Text(summary.value).font(.title.bold())
                                Text(summary.detail).font(.callout).foregroundStyle(.secondary)
                                Button(summary.title == "图书馆" ? "查看借阅" : "查看邮箱", action: summary.onOpen)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                if !home.changes.isEmpty {
                    homeCard {
                        VStack(alignment: .leading, spacing: 16) {
                            HStack {
                                Text("数据变动").font(.title3.bold())
                                Spacer()
                                Button("全部标记已读", action: home.onClearChanges)
                            }
                            ForEach(home.changes, id: \.id) { group in
                                Button(action: group.onOpen) {
                                    HStack {
                                        Text(group.title).fontWeight(.medium)
                                        Spacer()
                                        Text(group.summary).foregroundStyle(.secondary)
                                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                                    }.padding(.vertical, 5)
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .frame(maxWidth: 1100, alignment: .leading)
            .padding(32)
            .frame(maxWidth: .infinity, alignment: .top)
        }
        .scrollContentBackground(.visible)
        .background(Color(nsColor: .windowBackgroundColor))
        .modifier(NativeHomeScrollEdge())
        .accessibilityIdentifier("native-home-scroll")
        .onChange(of: home.todayRequest) { _ in proxy.scrollTo("native-home-top", anchor: .top) }
        }
    }

    private var weekMarks: [NativeHomeMark] {
        var seen = Set<String>()
        return home.days.flatMap(\.marks).filter { seen.insert($0.label).inserted }
    }

    private var agenda: some View {
        homeCard {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(home.weekTitle).font(.title2.bold())
                        Text(home.dateRange).font(.callout).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if home.loading { ProgressView().controlSize(.small).accessibilityLabel("日程加载中") }
                    Button("今天", action: home.onToday).disabled(home.todaySelected)
                    ControlGroup {
                        Button { home.onPrevious?() } label: { Image(systemName: "chevron.left") }
                            .disabled(home.onPrevious == nil).help("上一周").accessibilityLabel("上一周")
                        Button { home.onNext?() } label: { Image(systemName: "chevron.right") }
                            .disabled(home.onNext == nil).help("下一周").accessibilityLabel("下一周")
                    }
                }
                HStack(alignment: .top, spacing: 8) {
                    ForEach(home.days, id: \.id) { day in
                        Button(action: day.onSelect) {
                            VStack(spacing: 9) {
                                Text(day.weekday).font(.caption)
                                Text(day.number).font(.title2.bold()).monospacedDigit()
                                Text(day.marks.isEmpty ? "—" : "\(day.marks.reduce(0) { $0 + Int($1.count) }) 项").font(.caption2)
                                HStack(spacing: 3) {
                                    ForEach(Array(day.marks.enumerated()), id: \.offset) { _, mark in
                                        Circle().fill(day.selected ? Color.white : Color(homeRGB: mark.colorRgb)).frame(width: 4, height: 4)
                                    }
                                }.frame(height: 8)
                            }
                            .frame(maxWidth: .infinity, minHeight: 80)
                            .foregroundStyle(day.selected ? Color.white : Color.primary)
                            .background(day.selected ? Color.accentColor : Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(day.today && !day.selected ? Color.accentColor : .clear, lineWidth: 1))
                            .contentShape(RoundedRectangle(cornerRadius: 10))
                        }
                        .buttonStyle(.plain)
                        .help(day.accessibilityLabel)
                        .accessibilityLabel(day.accessibilityLabel)
                        .accessibilityAddTraits(day.selected ? .isSelected : [])
                    }
                }
                if !weekMarks.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 80))], alignment: .leading, spacing: 8) {
                    ForEach(weekMarks, id: \.label) { mark in
                        HStack(spacing: 5) {
                            Circle().fill(Color(homeRGB: mark.colorRgb)).frame(width: 5, height: 5)
                            Text(mark.label).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                }
                Divider()
                Text(home.selectedDateTitle).font(.headline)
                if home.events.isEmpty {
                    Label(home.loading ? "正在加载日程…" : "当天没有课程、作业、考试或实验安排。", systemImage: "calendar")
                        .font(.callout).foregroundStyle(.secondary).padding(.vertical, 8)
                } else {
                    VStack(spacing: 0) {
                        ForEach(home.events, id: \.id) { event in
                            NativeHomeEventRow(event: event)
                            if event.id != home.events.last?.id { Divider() }
                        }
                    }
                }
            }
        }
    }

    private func homeCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content().frame(maxWidth: .infinity, alignment: .leading).padding(20)
            .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Color.primary.opacity(0.07), lineWidth: 1))
    }
}

private struct NativeHomeEventRow: View {
    let event: NativeHomeEvent
    var body: some View {
        Button(action: event.onOpen) {
            HStack(spacing: 14) {
                RoundedRectangle(cornerRadius: 2).fill(Color(homeRGB: event.colorRgb)).frame(width: 4)
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(event.kind).font(.caption).foregroundStyle(.secondary)
                        if event.done { Label("已提交", systemImage: "checkmark.circle.fill").font(.caption).foregroundStyle(.green) }
                    }
                    Text(event.title).font(.body.weight(.medium)).foregroundStyle(.primary)
                    if !event.detail.isEmpty { Text(event.detail).font(.callout).foregroundStyle(.secondary) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

private struct NativeHomeScrollEdge: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 26.0, *) {
            content.scrollEdgeEffectStyle(.soft, for: .top)
        } else {
            content
        }
    }
}

private extension Color {
    init(homeRGB: Int64) {
        self.init(.sRGB, red: Double((homeRGB >> 16) & 255) / 255,
                  green: Double((homeRGB >> 8) & 255) / 255, blue: Double(homeRGB & 255) / 255, opacity: 1)
    }
}

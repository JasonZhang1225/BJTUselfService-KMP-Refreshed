import ActivityKit
import SwiftUI
import WidgetKit

// MARK: - 日程实时活动（灵动岛 + 锁屏）

/// 内容由宿主 App 推送（CourseLiveActivityCoordinator）。倒计时与进度条用系统计时视图自己走，
/// 不需要逐秒更新；开课那一刻靠 staleDate 触发一次重画，从“即将上课”切到“正在上课”。
struct CourseLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: CourseLiveActivityAttributes.self) { context in
            LiveActivityContent(state: context.state)
        } dynamicIsland: { context in
            courseDynamicIsland(context)
        }
        // `.small`：Apple Watch 智能叠放用单独的视图（见 [LiveActivityWatch]），
        // 不声明时手表照搬收起态（“下课 16:00”）。
        .supplementalActivityFamilies([.small, .medium])
    }
}

/// 锁屏 / 通知中心用 `.medium`，手表智能叠放用 `.small`。
private struct LiveActivityContent: View {
    let state: CourseLiveActivityAttributes.ContentState
    @Environment(\.activityFamily) private var family

    var body: some View {
        Group {
            if family == .small {
                LiveActivityWatch(state: state, now: Date())
            } else {
                LiveActivityLockScreen(state: state, now: Date())
            }
        }
        // 锁屏和通知中心透明底，直接用系统的液态玻璃材质；手表保持系统默认的不透明底，透明会和下层卡片叠在一起。
        .activityBackgroundTint(family == .small ? nil : Color.clear)
        .activitySystemActionForegroundColor(Color(hex: state.color))
    }
}

private func courseDynamicIsland(_ context: ActivityViewContext<CourseLiveActivityAttributes>) -> DynamicIsland {
    let state = context.state
    let now = Date()
    let accent = Color(hex: state.color)
    return DynamicIsland {
        DynamicIslandExpandedRegion(.leading) {
            HStack(spacing: 6) {
                LiveBadge(state: state, size: 26)
                Text(state.statusText(at: now))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(accent)
                    .lineLimit(1)
            }
            .padding(.leading, 10)
            .padding(.top, 6)
        }
        DynamicIslandExpandedRegion(.trailing) {
            LiveCountdown(state: state, now: now, style: .expanded)
                // 限制长中文分钟计时的理想宽度，避免区域移到摄像头下方、
                // 抬高整张卡片并把底部“接下来”推出系统高度上限。
                .frame(maxWidth: 112, alignment: .trailing)
                .padding(.trailing, 10)
                .padding(.top, 6)
        }
        DynamicIslandExpandedRegion(.bottom) {
            // 展开态有系统高度上限和大圆角，不能沿用锁屏卡片的字号、间距。
            VStack(alignment: .leading, spacing: 4) {
                Text(state.name)
                    .font(.headline.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                LiveDetailLine(state: state, font: .caption)
                LiveTimelineBar(state: state, accent: accent, font: .caption2)
                if let next = nextLine(state) {
                    Text(next)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 2)
            .padding(.bottom, 10)
        }
    } compactLeading: {
        // 收起态保持系统默认的短胶囊：左侧是类别色图标外加环形进度，课名留给长按展开和锁屏。
        LiveRing(state: state, now: now)
            .frame(width: 24, height: 24)
            .padding(.leading, 2)
    } compactTrailing: {
        LiveCountdown(state: state, now: now, style: .compact)
    } minimal: {
        LiveRing(state: state, now: now)
    }
    .keylineTint(accent)
}

// MARK: - 锁屏 / 通知横幅

private struct LiveActivityLockScreen: View {
    let state: CourseLiveActivityAttributes.ContentState
    let now: Date

    var body: some View {
        let accent = Color(hex: state.color)
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .center, spacing: 12) {
                LiveBadge(state: state, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(state.statusText(at: now))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(accent)
                    Text(state.name)
                        .font(.title3.weight(.bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                Spacer(minLength: 8)
                LiveCountdown(state: state, now: now, style: .lockScreen)
            }
            LiveDetailLine(state: state, font: .subheadline)
            LiveTimelineBar(state: state, accent: accent, font: .footnote)
            if let next = nextLine(state) {
                Text(next)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }
}

// MARK: - Apple Watch 智能叠放

/// 手表上横向窄：App 名单独占顶行（不挤占下面一行的宽度），下面左边类别色环形进度，右边“上课·距结束 / 1 小时 28 分钟”。
private struct LiveActivityWatch: View {
    let state: CourseLiveActivityAttributes.ContentState
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("交大自由行")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
            HStack(alignment: .center, spacing: 6) {
                LiveRing(state: state, now: now)
                    .frame(width: 30, height: 30)
                Spacer(minLength: 2)
                LiveCountdown(state: state, now: now, style: .watch)
                    .layoutPriority(1)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        // 手表卡片永远是深色底，但视图由 iPhone 按自己的外观渲染：iPhone 在浅色模式时 .primary/.secondary
        // 会解析成黑/深灰，在手表上整栏文字看不见、只剩圆环。固定深色并让文字用显式颜色。
        .environment(\.colorScheme, .dark)
    }
}

// MARK: - 组件

/// 类别色圆形徽章 + 白色图标；灵动岛、锁屏共用。
private struct LiveBadge: View {
    let state: CourseLiveActivityAttributes.ContentState
    let size: CGFloat

    var body: some View {
        Image(systemName: state.symbol)
            .font(.system(size: size * 0.46, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Circle().fill(Color(hex: state.color).gradient))
            .accessibilityHidden(true)
    }
}

/// 地点 · 教师，一行放不下时先缩字再截断。
private struct LiveDetailLine: View {
    let state: CourseLiveActivityAttributes.ContentState
    var font: Font = .footnote

    var body: some View {
        HStack(spacing: 12) {
            if !state.place.isEmpty {
                Label(state.place, systemImage: "mappin.and.ellipse")
                    .lineLimit(1)
            }
            if !state.teacher.isEmpty {
                Label(state.teacher, systemImage: "person.fill")
                    .lineLimit(1)
            }
        }
        .font(font)
        .foregroundStyle(.secondary)
        .labelStyle(LiveLabelStyle())
        .minimumScaleFactor(0.8)
    }
}

private struct LiveLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 3) {
            configuration.icon.imageScale(.small)
            configuration.title
        }
    }
}

/// 开始时间 —— 进度 —— 结束时间。进度条由系统按时间自己走。
private struct LiveTimelineBar: View {
    let state: CourseLiveActivityAttributes.ContentState
    let accent: Color
    var font: Font = .caption

    var body: some View {
        HStack(spacing: 8) {
            Text(clock(state.start))
            ProgressView(timerInterval: state.start...state.end, countsDown: false) {
                EmptyView()
            } currentValueLabel: {
                EmptyView()
            }
            .progressViewStyle(.linear)
            .tint(accent)
            Text(clock(state.end))
        }
        .font(font.monospacedDigit().weight(.medium))
        .foregroundStyle(.secondary)
    }
}

/// 时间，两行：开课前“距开始 + 倒计时”；上课时收起态“下课 + 下课时刻”，展开/锁屏/手表“距结束 + N 小时 N 分钟”。
private struct LiveCountdown: View {
    enum Style { case compact, expanded, lockScreen, watch }

    let state: CourseLiveActivityAttributes.ContentState
    let now: Date
    let style: Style

    var body: some View {
        let accent = Color(hex: state.color)
        let phase = state.phase(at: now)
        // 收起态居中：右对齐时说明文字会贴到灵动岛圆角被裁掉。
        VStack(alignment: style == .compact ? .center : .trailing, spacing: style == .compact ? -1 : 0) {
            Text(caption(phase, style: style))
                .font(captionFont)
                .foregroundStyle(style == .compact || style == .watch ? accent.opacity(0.85) : .secondary)
                .lineLimit(1)
                // 手表上“考试·距开始”偏长：宁可略缩也不截成“…”。
                .minimumScaleFactor(style == .watch ? 0.8 : 1)
            switch phase {
            case .finished:
                Text("--:--")
                    .font(timerFont)
                    .foregroundStyle(.secondary)
            case .upcoming:
                Text(timerInterval: now...state.start, countsDown: true)
                    .font(timerFont)
                    .foregroundStyle(accent)
                    .multilineTextAlignment(style == .compact ? .center : .trailing)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    // 计时文字默认按最长格式占位，固定宽度避免撑宽、折行；手表空间够，不限宽。
                    .frame(
                        width: style == .watch ? nil : timerWidth(hours: state.start.timeIntervalSince(now) >= 3600, seconds: true),
                        alignment: style == .compact ? .center : .trailing
                    )
            case .ongoing:
                if style == .compact {
                    // 收起态显示下课时刻：系统分钟计时只有全称单位（“1 小时 29 分钟”），会把灵动岛两侧对称撑宽；
                    // 静态时刻最短，剩余多少由进度环表达。
                    Text(clock(state.end))
                        .font(timerFont)
                        .foregroundStyle(accent)
                        .lineLimit(1)
                        .fixedSize()
                } else {
                    // 展开、锁屏、手表：系统分钟计时，中文写作“1 小时 29 分钟”，每分钟自己走。
                    LiveMinuteCountdown(now: now, end: state.end)
                        .font(.system(size: minuteTimerSize, weight: .semibold, design: .rounded).monospacedDigit())
                        // 手表上用类别色：卡片是深色底，不能依赖 iPhone 的浅/深色外观。
                        .foregroundStyle(style == .watch ? AnyShapeStyle(accent) : AnyShapeStyle(.primary))
                        .multilineTextAlignment(.trailing)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
        }
    }

    private var captionFont: Font {
        switch style {
        case .compact: return .system(size: 9, weight: .semibold)
        case .expanded: return .caption2.weight(.medium)
        case .lockScreen: return .footnote.weight(.medium)
        case .watch: return .system(size: 12, weight: .semibold)
        }
    }

    private var minuteTimerSize: CGFloat {
        switch style {
        case .compact: return 15
        case .watch: return 17
        case .expanded: return 14
        case .lockScreen: return 24
        }
    }

    private var timerSize: CGFloat {
        switch style {
        case .compact: return 13
        case .expanded: return 18
        case .lockScreen: return 30
        case .watch: return 17
        }
    }

    private var timerFont: Font {
        .system(size: timerSize, weight: .semibold, design: .rounded).monospacedDigit()
    }

    /// 按字号给足“m:ss”“h:mm:ss”或“h:mm”的宽度。
    private func timerWidth(hours: Bool, seconds: Bool) -> CGFloat {
        let size = timerSize
        // 圆体等宽数字约 0.62 字宽，冒号约 0.3。
        let digits: CGFloat = hours ? (seconds ? 5 : 3) : 4
        let colons: CGFloat = hours && seconds ? 2 : 1
        return ceil(size * (digits * 0.62 + colons * 0.3)) + 4
    }

    private func caption(_ phase: CourseLiveActivityPhase, style: Style) -> String {
        if style == .watch {
            // 手表不显示课名和状态行，说明文字前加上这一项是什么：“上课·距结束”“实验·距开始”“考试·已结束”。
            let kind = state.isExam ? "考试" : (state.isLab ? "实验" : "上课")
            switch phase {
            case .upcoming: return "\(kind)·距开始"
            case .ongoing: return "\(kind)·距结束"
            case .finished: return "\(kind)·已结束"
            }
        }
        switch phase {
        case .upcoming: return "距开始"
        case .ongoing: return style == .compact ? "下课" : "距结束"
        case .finished: return "已结束"
        }
    }
}

/// 距下课只到分钟。实时活动里 App 不能逐分钟刷新文字，只能用系统自走计时：iOS 18 起分钟精度的系统计时
/// 写作“1 小时 29 分钟”（英文同样是全称，不缩写）；自定义 DiscreteFormatStyle 在实时活动里会渲染成占位块。
private struct LiveMinuteCountdown: View {
    let now: Date
    let end: Date

    var body: some View {
        Text(
            .currentDate,
            format: .timer(countingDownIn: now..<end, showsHours: true, maxFieldCount: 2, maxPrecision: .seconds(60))
        )
    }
}

/// 类别色图标外加环形进度（收起态左侧、最小态共用）：开课前圆环逐渐填满；上课后从空开始，走过的部分填色，与进度条同向。
private struct LiveRing: View {
    let state: CourseLiveActivityAttributes.ContentState
    let now: Date

    var body: some View {
        let accent = Color(hex: state.color)
        let phase = state.phase(at: now)
        let icon = Image(systemName: state.symbol)
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(accent)
        switch phase {
        case .upcoming:
            let from = min(state.start.addingTimeInterval(-state.leadTime), now)
            ProgressView(timerInterval: from...state.start, countsDown: false) {
                EmptyView()
            } currentValueLabel: {
                icon
            }
            .progressViewStyle(.circular)
            .tint(accent)
        case .ongoing:
            ProgressView(timerInterval: state.start...state.end, countsDown: false) {
                EmptyView()
            } currentValueLabel: {
                icon
            }
            .progressViewStyle(.circular)
            .tint(accent)
        case .finished:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(accent)
        }
    }
}

private func nextLine(_ state: CourseLiveActivityAttributes.ContentState) -> String? {
    guard let name = state.nextName, let start = state.nextStart else { return nil }
    return "接下来 · \(clock(start)) \(name)"
}

#if DEBUG
// Xcode Canvas：在不同 iPhone 尺寸和辅助功能字号下检查边缘及“接下来”一行。
private enum CourseLiveActivityPreview {
    static func state(ongoing: Bool) -> CourseLiveActivityAttributes.ContentState {
        let start = Date().addingTimeInterval(ongoing ? -60 : 18 * 60)
        return .init(
            occurrenceId: "preview",
            name: ongoing ? "大学物理 (A)II" : "国际议题演讲与陈述",
            place: "第八教学楼 8109",
            teacher: "示例教师",
            kind: "required",
            color: 0xD55287,
            start: start,
            end: start.addingTimeInterval(110 * 60),
            nextName: "计算机系统导论",
            nextStart: start.addingTimeInterval(4 * 3600)
        )
    }
}

#Preview("灵动岛 · 即将 / 正在上课", as: .dynamicIsland(.expanded), using: CourseLiveActivityAttributes()) {
    CourseLiveActivityWidget()
} contentStates: {
    CourseLiveActivityPreview.state(ongoing: false)
    CourseLiveActivityPreview.state(ongoing: true)
}

#Preview("锁屏 · 对照", as: .content, using: CourseLiveActivityAttributes()) {
    CourseLiveActivityWidget()
} contentStates: {
    CourseLiveActivityPreview.state(ongoing: false)
    CourseLiveActivityPreview.state(ongoing: true)
}
#endif

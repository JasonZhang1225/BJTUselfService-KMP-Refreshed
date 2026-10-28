import ActivityKit
import SwiftUI
import WidgetKit

// MARK: - 回放下载实时活动（灵动岛 + 锁屏/通知）

/// 课程回放下载与合成的实时活动。
///
/// 收起态：左边是环形进度（圆环正中放图标），右边两排——上面是阶段（下载中/拼接中），下面是百分比。
/// 展开态与锁屏卡片给出课节标题、当前步骤、体积与速度。
struct PlaybackDownloadWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: PlaybackDownloadAttributes.self) { context in
            PlaybackDownloadLockScreen(state: context.state)
        } dynamicIsland: { context in
            playbackDownloadDynamicIsland(context)
        }
        // 不声明 .small：手表上没有单独的小视图，直接沿用收起态（否则会把锁屏布局硬塞进手表）。
    }
}

// MARK: - 灵动岛

private func playbackDownloadDynamicIsland(_ context: ActivityViewContext<PlaybackDownloadAttributes>) -> DynamicIsland {
    let state = context.state
    let accent = Color(hex: PlaybackDownloadAttributes.accentColor)
    return DynamicIsland {
        DynamicIslandExpandedRegion(.leading) {
            HStack(spacing: 6) {
                PlaybackDownloadRing(state: state, size: 26)
                Text(state.phase)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(accent)
                    .lineLimit(1)
            }
            .padding(.leading, 4)
            .frame(maxHeight: .infinity, alignment: .center)
        }
        DynamicIslandExpandedRegion(.trailing) {
            Text(state.percentText)
                .font(.system(size: 18, weight: .semibold, design: .rounded).monospacedDigit())
                .foregroundStyle(accent)
                .padding(.trailing, 4)
                .frame(maxHeight: .infinity, alignment: .center)
        }
        DynamicIslandExpandedRegion(.bottom) {
            VStack(alignment: .leading, spacing: 6) {
                Text(state.title)
                    .font(.title3.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if !state.detail.isEmpty {
                    Text(state.detail)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                if !state.metricsText.isEmpty {
                    Text(state.metricsText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                PlaybackDownloadBar(state: state, accent: accent)
            }
            .padding(.horizontal, 6)
            .padding(.top, 2)
        }
    } compactLeading: {
        // 左边：环形进度，圆环正中放当前阶段的图标。
        PlaybackDownloadRing(state: state, size: 24)
            .padding(.leading, 2)
    } compactTrailing: {
        // 右边两排：上面阶段，下面百分比。紧凑区空间很紧，缩放交给里面的文字，不给整列固定尺寸。
        VStack(alignment: .trailing, spacing: -1) {
            Text(state.phase)
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(accent)
                .lineLimit(1)
                .fixedSize()
            Text(state.percentText)
                .font(.system(size: 13, weight: .semibold, design: .rounded).monospacedDigit())
                .foregroundStyle(accent)
                .lineLimit(1)
                .fixedSize()
        }
    } minimal: {
        PlaybackDownloadRing(state: state, size: 22)
    }
    .keylineTint(accent)
}

// MARK: - 锁屏 / 通知横幅

private struct PlaybackDownloadLockScreen: View {
    let state: PlaybackDownloadAttributes.ContentState

    var body: some View {
        let accent = Color(hex: PlaybackDownloadAttributes.accentColor)
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .center, spacing: 12) {
                PlaybackDownloadRing(state: state, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(state.phase)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(accent)
                    Text(state.title)
                        .font(.title3.weight(.bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                Spacer(minLength: 8)
                Text(state.percentText)
                    .font(.system(size: 24, weight: .semibold, design: .rounded).monospacedDigit())
                    .foregroundStyle(accent)
            }
            if !state.detail.isEmpty {
                Text(state.detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            PlaybackDownloadBar(state: state, accent: accent)
            if !state.metricsText.isEmpty {
                Text(state.metricsText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        // 与日程实时活动一致：锁屏/通知中心透明底，透出系统的液态玻璃材质。
        .activityBackgroundTint(Color.clear)
        .activitySystemActionForegroundColor(accent)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    private var accessibilityText: String {
        var parts = [state.phase, state.title, state.percentText]
        if !state.detail.isEmpty { parts.append(state.detail) }
        if !state.metricsText.isEmpty { parts.append(state.metricsText) }
        return parts.joined(separator: "，")
    }
}

// MARK: - 组件

/// 环形进度：自绘圆环（尺寸完全可控），圆环正中放阶段图标；进度未知时只画轨道与图标。
private struct PlaybackDownloadRing: View {
    let state: PlaybackDownloadAttributes.ContentState
    let size: CGFloat

    var body: some View {
        let accent = Color(hex: PlaybackDownloadAttributes.accentColor)
        let lineWidth = max(2, size * 0.11)
        ZStack {
            Circle()
                .stroke(accent.opacity(0.22), lineWidth: lineWidth)
            if let fraction = state.fraction {
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(accent, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            Image(systemName: state.symbol)
                .font(.system(size: size * 0.40, weight: .bold))
                .foregroundStyle(accent)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// 展开态与锁屏用的横条进度。
private struct PlaybackDownloadBar: View {
    let state: PlaybackDownloadAttributes.ContentState
    let accent: Color

    var body: some View {
        if let fraction = state.fraction {
            ProgressView(value: fraction, total: 1)
                .progressViewStyle(.linear)
                .tint(accent)
        } else {
            // 进度未知时不显示转圈（实时活动是静态快照，转圈没有动画意义）。
            Capsule()
                .fill(accent.opacity(0.25))
                .frame(height: 4)
        }
    }
}

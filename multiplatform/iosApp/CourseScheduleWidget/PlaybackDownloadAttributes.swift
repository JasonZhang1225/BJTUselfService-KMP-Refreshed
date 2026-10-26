import ActivityKit
import Foundation

// 宿主 App 与小组件扩展共用：宿主用它发起/更新实时活动，扩展用它画灵动岛和锁屏卡片。

/// 课程回放下载/合成实时活动。
///
/// 与日程实时活动分开成另一个活动类型：两者可以同时存在（一边在上课、一边在下载回放），
/// 各自有独立的灵动岛胶囊与锁屏卡片。
struct PlaybackDownloadAttributes: ActivityAttributes {
    /// 强调色：与课件下载入口同色系（浅色 0x566DAF / 深色 0x9EAFE9 的中间值），深浅色下都可读。
    static let accentColor: Int64 = 0x5B76C4

    struct ContentState: Codable, Hashable {
        /// 课节标题，如 `2026-10-20 16:20-18:10`。
        var title: String
        /// 阶段：下载中 / 拼接中 / 保存中 / 已完成 / 失败 / 已取消。
        var phase: String
        /// 当前步骤，如 `下载教师画面（第 1/2 路）`。
        var detail: String
        /// 0..1；-1 表示进度未知。
        var progress: Double
        /// 体积文案，如 `1.2 MB / 约 5.4 GB`；没有就空串。
        var size: String
        /// 速度文案，如 `9.1 MB/s`；没有就空串。
        var speed: String
    }
}

extension PlaybackDownloadAttributes.ContentState {
    /// 任务结束后的阶段：这些状态要短暂停留再消失，让用户看到结果。
    static let terminalPhases: Set<String> = ["已完成", "失败", "已取消"]

    var isTerminal: Bool { Self.terminalPhases.contains(phase) }

    /// 0..1；进度未知时返回 nil。
    var fraction: Double? {
        guard progress >= 0 else { return nil }
        return min(max(progress, 0), 1)
    }

    var percentText: String {
        guard let fraction else { return "—" }
        return "\(Int((fraction * 100).rounded()))%"
    }

    /// 阶段图标：下载、拼接（合并）、保存、完成、失败。
    var symbol: String {
        switch phase {
        case "拼接中": return "square.on.square"
        case "保存中": return "tray.and.arrow.down.fill"
        case "已完成": return "checkmark.circle.fill"
        case "失败": return "exclamationmark.triangle.fill"
        case "已取消": return "xmark.circle.fill"
        default: return "arrow.down.circle.fill"
        }
    }

    /// 体积与速度合成一行；没有就空串。
    var metricsText: String {
        [size, speed].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

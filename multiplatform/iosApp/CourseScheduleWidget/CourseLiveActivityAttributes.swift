import ActivityKit
import Foundation

// 宿主 App 与小组件扩展共用：宿主用它发起/更新实时活动，扩展用它画灵动岛和锁屏卡片。

/// 课前多久自动出现实时活动：卡片 Live 菜单里二选一，选过的作为以后的默认值。
let courseLiveActivityLeadMinuteOptions = [10, 20]
let courseLiveActivityDefaultLeadMinutes = 10

/// 日程实时活动。只有一个活动实例，换课时更新内容而不是重开（后台不能新建实时活动）。
struct CourseLiveActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// 与 Kotlin `liveActivityOccurrenceId` 同一格式：`yyyy-MM-dd|开始分钟|结束分钟|课名`。
        var occurrenceId: String
        var name: String
        var place: String
        var teacher: String
        /// 课程类别：required/limited/elective/pe/unknown/lab/exam。
        var kind: String
        /// 课程类别强调色 0xRRGGBB，与课程表、小组件一致。
        var color: Int64
        var start: Date
        var end: Date
        /// 同一天紧接着的下一项，只显示名字和开始时间。
        var nextName: String?
        var nextStart: Date?
        /// 这一项按课前多少分钟出现；旧版本写入的内容没有此字段，按默认值。
        var leadMinutes: Int? = nil
    }
}

enum CourseLiveActivityPhase {
    case upcoming, ongoing, finished
}

extension CourseLiveActivityAttributes.ContentState {
    func phase(at now: Date) -> CourseLiveActivityPhase {
        if now < start { return .upcoming }
        return now < end ? .ongoing : .finished
    }

    /// 系统在这一刻把活动标为过期并重画一次，界面借此从“即将开始”切到“进行中”。
    func staleDate(after now: Date) -> Date? {
        if now < start { return start }
        return now < end ? end : nil
    }

    /// 开课前圆环从这一刻开始填充。
    var leadTime: TimeInterval { TimeInterval((leadMinutes ?? courseLiveActivityDefaultLeadMinutes) * 60) }

    var isLab: Bool { kind == "lab" }
    var isExam: Bool { kind == "exam" }

    /// SF Symbol：实验烧瓶、考试试卷、体育跑步，其余为课本。
    var symbol: String {
        switch kind {
        case "lab": return "flask.fill"
        case "exam": return "pencil.and.list.clipboard"
        case "pe": return "figure.run"
        default: return "book.closed.fill"
        }
    }

    func statusText(at now: Date) -> String {
        switch phase(at: now) {
        case .upcoming: return isExam ? "即将考试" : (isLab ? "即将开始" : "即将上课")
        case .ongoing: return isExam ? "考试中" : (isLab ? "实验中" : "正在上课")
        case .finished: return isExam ? "考试结束" : (isLab ? "实验结束" : "已下课")
        }
    }
}

func courseLiveActivityOccurrenceId(day: String, startMinute: Int, endMinute: Int, name: String) -> String {
    "\(day)|\(startMinute)|\(endMinute)|\(name)"
}

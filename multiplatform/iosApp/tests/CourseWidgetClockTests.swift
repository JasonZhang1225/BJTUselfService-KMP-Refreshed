import Foundation

@main
struct CourseWidgetClockRegression {
    static func main() {
        var checks = 0
        func expect(_ key: String, _ minute: Int, _ zone: String, _ expected: String, separator: String = " ") {
            let date = CourseWidgetCalendar.date(fromKey: key)!
            let actual = CourseWidgetCalendar.displayClock(
                minute: minute, on: date, zone: TimeZone(identifier: zone)!, markerSeparator: separator
            )
            precondition(actual == expected, "\(key) \(zone): expected \(expected), got \(actual)")
            checks += 1
        }
        // 美国冬令时和夏令时切换前后，使用正在渲染的日期。
        expect("2026-10-31", 480, "America/New_York", "前日 20:00")
        expect("2026-11-02", 480, "America/New_York", "前日 19:00")
        expect("2026-03-07", 480, "America/New_York", "前日 19:00")
        expect("2026-03-09", 480, "America/New_York", "前日 20:00")
        expect("2026-11-02", 480, "America/New_York", "前日\n19:00", separator: "\n")
        expect("2026-11-02", 480, "Asia/Shanghai", "08:00")
        expect("2026-11-02", 480, "Europe/London", "00:00")
        expect("2026-12-31", 1200, "Pacific/Auckland", "次日 01:00")
        // 切换发生在北京周日的下午，早课仍使用前一天的夏令时偏移。
        expect("2026-11-01", 480, "America/New_York", "前日 20:00")
        expect("2026-11-01", 850, "America/New_York", "01:10")
        expect("2026-10-26", 850, "America/New_York", "02:10")
        let weekDates = ["2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31", "2026-11-01"]
            .map { CourseWidgetCalendar.date(fromKey: $0)! }
        let newYork = TimeZone(identifier: "America/New_York")!
        precondition(!CourseWidgetCalendar.clocksAreUniform(minutes: [480, 850], dates: weekDates, zone: newYork))
        precondition(CourseWidgetCalendar.clocksAreUniform(minutes: [480], dates: weekDates, zone: newYork))
        precondition(CourseWidgetCalendar.clocksAreUniform(minutes: [480, 850], dates: weekDates, zone: TimeZone(identifier: "Asia/Shanghai")!))
        checks += 3
        // 实时活动提醒和课程起止时刻使用同一真实时刻格式化入口。
        let schoolDate = CourseWidgetCalendar.date(fromKey: "2026-11-02")!
        let instant = schoolDate.addingTimeInterval(480 * 60)
        precondition(CourseWidgetCalendar.displayClock(instant, schoolDate: schoolDate,
            zone: TimeZone(identifier: "America/New_York")!) == "前日 19:00")
        print("Course widget clock regression: \(checks + 1) checks passed")
    }
}

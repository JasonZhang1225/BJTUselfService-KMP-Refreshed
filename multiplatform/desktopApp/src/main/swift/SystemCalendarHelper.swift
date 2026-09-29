import AppKit
import Darwin
import EventKit
import Foundation

private struct Payload: Decodable {
    let calendars: [CalendarPayload]
}

private struct CalendarPayload: Decodable {
    let name: String
    let colorHex: String
    let managedCourseRange: ManagedCourseRangePayload?
    let events: [EventPayload]
}

private struct ManagedCourseRangePayload: Decodable {
    let startLocal: String
    let endLocal: String
}

private struct EventPayload: Decodable {
    let stableId: String
    let title: String
    let startLocal: String
    let endLocal: String
    let location: String
    let notes: String
    let recurrence: RecurrencePayload?
}

private struct RecurrencePayload: Decodable {
    let occurrenceCount: Int
    let excludedStartLocals: [String]
    let lastEndLocal: String
}

private struct Result: Encodable {
    let ok: Bool
    let calendars: Int
    let inserted: Int
    let updated: Int
    let reason: String?
}

private let markerPrefix = "[BJTU-ID:"
private let courseMarkerPrefix = "[BJTU-ID:course-"

@main
struct SystemCalendarHelper {
    static func main() async {
        guard CommandLine.arguments.count == 2 else {
            output(Result(ok: false, calendars: 0, inserted: 0, updated: 0, reason: "input"), exit: 2)
        }
        do {
            let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
            let payload = try JSONDecoder().decode(Payload.self, from: data)
            let store = EKEventStore()
            let granted: Bool
            if #available(macOS 14.0, *) {
                granted = try await store.requestFullAccessToEvents()
            } else {
                granted = try await withCheckedThrowingContinuation { continuation in
                    store.requestAccess(to: .event) { allowed, error in
                        if let error { continuation.resume(throwing: error) }
                        else { continuation.resume(returning: allowed) }
                    }
                }
            }
            guard granted else {
                output(Result(ok: false, calendars: 0, inserted: 0, updated: 0, reason: "permission"), exit: 3)
            }
            let result = try install(payload, into: store)
            output(result, exit: 0)
        } catch {
            output(Result(ok: false, calendars: 0, inserted: 0, updated: 0, reason: "io"), exit: 4)
        }
    }

    private static func install(_ payload: Payload, into store: EKEventStore) throws -> Result {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "Asia/Shanghai")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        var calendarCount = 0
        var inserted = 0
        var updated = 0

        for batch in payload.calendars where !batch.events.isEmpty || batch.managedCourseRange != nil {
            let calendar = try findOrCreateCalendar(batch, store: store)
            calendarCount += 1
            let dated = try batch.events.map { event -> (EventPayload, Date, Date) in
                guard let start = formatter.date(from: event.startLocal),
                      let end = formatter.date(from: event.endLocal) else {
                    throw CalendarError.invalidDate
                }
                return (event, start, end)
            }

            var existingNonCourse: [String: EKEvent] = [:]
            if let range = batch.managedCourseRange {
                guard let rangeStart = formatter.date(from: range.startLocal),
                      let rangeEnd = formatter.date(from: range.endLocal),
                      rangeStart < rangeEnd else {
                    throw CalendarError.invalidDate
                }
                // Only marked course series are eligible; unmarked user-created items and exam markers stay untouched.
                // The academic term fits below EventKit's maximum predicate interval.
                let existingCourses = store.events(matching: store.predicateForEvents(
                    withStart: rangeStart,
                    end: rangeEnd,
                    calendars: [calendar]
                )).compactMap { event -> (String, EKEvent)? in
                    guard let marker = event.notes?.split(separator: "\n").first.map(String.init),
                          courseStableId(from: marker) != nil else { return nil }
                    return (marker, event)
                }
                let existingStableIds = Set(existingCourses.compactMap { courseStableId(from: $0.0) })
                let desiredCourseIds = Set(batch.events
                    .filter { $0.stableId.hasPrefix("course-") }
                    .map(\.stableId))
                let firstOccurrenceBySeries = Dictionary(grouping: existingCourses) { $0.1.calendarItemIdentifier }
                    .compactMapValues { occurrences in
                        occurrences.min { $0.1.startDate < $1.1.startDate }?.1
                    }
                for event in firstOccurrenceBySeries.values {
                    try store.remove(event, span: .futureEvents, commit: false)
                }
                if !firstOccurrenceBySeries.isEmpty { try store.commit() }
                inserted += desiredCourseIds.subtracting(existingStableIds).count
                updated += desiredCourseIds.intersection(existingStableIds).count
            } else if !dated.isEmpty {
                let earliest = dated.map(\.1).min()!
                let latest = dated.map(\.2).max()!.addingTimeInterval(1)
                let predicate = store.predicateForEvents(withStart: earliest, end: latest, calendars: [calendar])
                for event in store.events(matching: predicate) {
                    guard let marker = event.notes?.split(separator: "\n").first.map(String.init),
                          marker.hasPrefix(markerPrefix) else { continue }
                    if let previous = existingNonCourse[marker] {
                        if event.startDate < previous.startDate { existingNonCourse[marker] = event }
                    } else {
                        existingNonCourse[marker] = event
                    }
                }
            }

            for (draft, start, end) in dated {
                let marker = "\(markerPrefix)\(draft.stableId)]"
                let isManagedCourse = draft.stableId.hasPrefix("course-") && batch.managedCourseRange != nil
                let existing = isManagedCourse ? nil : existingNonCourse[marker]
                let event = existing ?? EKEvent(eventStore: store)
                if !isManagedCourse {
                    if existing != nil { updated += 1 } else { inserted += 1 }
                }
                event.calendar = calendar
                event.title = draft.title
                event.startDate = start
                event.endDate = end
                event.location = draft.location.isEmpty ? nil : draft.location
                event.notes = draft.notes.isEmpty ? marker : "\(marker)\n\(draft.notes)"
                event.isAllDay = false
                event.recurrenceRules?.forEach(event.removeRecurrenceRule)
                if let recurrence = draft.recurrence, recurrence.occurrenceCount > 1 {
                    let recurrenceEnd = EKRecurrenceEnd(occurrenceCount: recurrence.occurrenceCount)
                    event.addRecurrenceRule(
                        EKRecurrenceRule(recurrenceWith: .weekly, interval: 1, end: recurrenceEnd)
                    )
                }
                try store.save(
                    event,
                    span: existing != nil && draft.recurrence != nil ? .futureEvents : .thisEvent,
                    commit: false
                )
            }
            try store.commit()

            // 先让重复规则进入数据库，EventKit 才能检索并删除停课/单双周 occurrence。
            for (draft, start, end) in dated {
                guard let recurrence = draft.recurrence else { continue }
                let marker = "\(markerPrefix)\(draft.stableId)]"
                let duration = end.timeIntervalSince(start)
                for excludedText in recurrence.excludedStartLocals {
                    guard let excludedStart = formatter.date(from: excludedText) else {
                        throw CalendarError.invalidDate
                    }
                    let exceptionPredicate = store.predicateForEvents(
                        withStart: excludedStart.addingTimeInterval(-1),
                        end: excludedStart.addingTimeInterval(duration + 1),
                        calendars: [calendar]
                    )
                    if let occurrence = store.events(matching: exceptionPredicate).first(where: { event in
                        event.notes?.split(separator: "\n").first.map(String.init) == marker &&
                            abs(event.startDate.timeIntervalSince(excludedStart)) < 1
                    }) {
                        try store.remove(occurrence, span: .thisEvent, commit: false)
                    }
                }
            }
        }
        try store.commit()
        return Result(ok: true, calendars: calendarCount, inserted: inserted, updated: updated, reason: nil)
    }

    private static func courseStableId(from marker: String) -> String? {
        guard marker.hasPrefix(courseMarkerPrefix), marker.hasSuffix("]") else { return nil }
        let stableId = String(marker.dropFirst(markerPrefix.count).dropLast())
        return stableId == "course-" ? nil : stableId
    }

    private static func findOrCreateCalendar(_ payload: CalendarPayload, store: EKEventStore) throws -> EKCalendar {
        if let existing = store.calendars(for: .event).first(where: {
            $0.title == payload.name && $0.allowsContentModifications
        }) {
            return existing
        }
        guard let source = store.defaultCalendarForNewEvents?.source
            ?? store.sources.first(where: { $0.sourceType == .local }) else {
            throw CalendarError.missingSource
        }
        let calendar = EKCalendar(for: .event, eventStore: store)
        calendar.title = payload.name
        calendar.source = source
        if let color = NSColor(hex: payload.colorHex)?.cgColor {
            calendar.cgColor = color
        }
        try store.saveCalendar(calendar, commit: true)
        return calendar
    }

    private static func output(_ result: Result, exit code: Int32) -> Never {
        if let data = try? JSONEncoder().encode(result), let text = String(data: data, encoding: .utf8) {
            FileHandle.standardOutput.write(Data(text.utf8))
        }
        exit(code)
    }
}

private enum CalendarError: Error {
    case invalidDate
    case missingSource
}

private extension NSColor {
    convenience init?(hex: String) {
        let clean = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        guard clean.count == 6, let value = Int(clean, radix: 16) else { return nil }
        self.init(
            calibratedRed: CGFloat((value >> 16) & 0xff) / 255,
            green: CGFloat((value >> 8) & 0xff) / 255,
            blue: CGFloat(value & 0xff) / 255,
            alpha: 1,
        )
    }
}

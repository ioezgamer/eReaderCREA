import Foundation

/// Data shared between the app and its home-screen widgets (same App Group).
enum CREAShared {
    static let groupID = "group.org.crea.reader.ios"
    /// Falls back to the standard defaults when the App Group is not provisioned, so the app keeps working.
    static var defaults: UserDefaults { UserDefaults(suiteName: groupID) ?? .standard }
    static let widgetKey = "crea.widget.snapshot.v1"
    static let statsKey = "crea.reader.stats.days.v1"
}

struct CREADayStat: Codable, Equatable {
    var seconds: Int = 0
    var pages: Int = 0
}

/// Time and pages read per day, stored as `{"2026-10-14":{"seconds":..,"pages":..}}`.
/// A day counts for the streak with at least `streakMinSeconds`; a short today does not break it.
struct CREAReadingStatsData {
    static let streakMinSeconds = 60
    var days: [String: CREADayStat]

    static func key(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// The last `count` days, oldest first; the final entry is today.
    func last(_ count: Int, now: Date = Date(), calendar: Calendar = .current) -> [CREADayStat] {
        (0..<count).reversed().map { offset in
            let day = calendar.date(byAdding: .day, value: -offset, to: now) ?? now
            return days[Self.key(day, calendar: calendar)] ?? CREADayStat()
        }
    }

    var totals: CREADayStat {
        CREADayStat(seconds: days.values.reduce(0) { $0 + $1.seconds }, pages: days.values.reduce(0) { $0 + $1.pages })
    }

    func streak(now: Date = Date(), calendar: Calendar = .current) -> Int {
        var day = now
        if (days[Self.key(day, calendar: calendar)]?.seconds ?? 0) < Self.streakMinSeconds {
            day = calendar.date(byAdding: .day, value: -1, to: day) ?? day
        }
        var count = 0
        while (days[Self.key(day, calendar: calendar)]?.seconds ?? 0) >= Self.streakMinSeconds {
            count += 1
            day = calendar.date(byAdding: .day, value: -1, to: day) ?? day
        }
        return count
    }
}

/// "45 min" or "2 h 5 min".
func creaFormatReadingTime(_ seconds: Int) -> String {
    let minutes = seconds / 60
    return minutes < 60 ? "\(minutes) min" : "\(minutes / 60) h \(minutes % 60) min"
}

/// What the widgets draw; written by the app, read by the extension.
struct CREAWidgetSnapshot: Codable {
    struct Book: Codable, Identifiable {
        var id: String
        var title: String
        var author: String?
        var percent: Int
        var coverFile: String?
    }
    var reading: [Book] = []
    var minutesToday = 0
    var pagesToday = 0
    var streak = 0
    var finished = 0

    static func load() -> CREAWidgetSnapshot {
        guard let data = CREAShared.defaults.data(forKey: CREAShared.widgetKey),
              let value = try? JSONDecoder().decode(CREAWidgetSnapshot.self, from: data) else { return CREAWidgetSnapshot() }
        return value
    }
}

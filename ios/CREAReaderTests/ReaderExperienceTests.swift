import XCTest
@testable import CREAReader

final class ReaderExperienceTests: XCTestCase {
    private var calendar: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c }
    private func date(_ day: Int) -> Date { calendar.date(from: DateComponents(year: 2026, month: 10, day: day, hour: 12))! }
    private func key(_ day: Int) -> String { CREAReadingStatsData.key(date(day), calendar: calendar) }

    func testStreakCountsDaysWithAtLeastOneMinute() {
        let data = CREAReadingStatsData(days: [key(14): .init(seconds: 90), key(13): .init(seconds: 60), key(12): .init(seconds: 59)])
        XCTAssertEqual(data.streak(now: date(14), calendar: calendar), 2)
    }

    func testShortTodayDoesNotBreakStreak() {
        let data = CREAReadingStatsData(days: [key(14): .init(seconds: 10), key(13): .init(seconds: 120), key(12): .init(seconds: 120)])
        XCTAssertEqual(data.streak(now: date(14), calendar: calendar), 2)
    }

    func testLastDaysOldestFirstEndingToday() {
        let data = CREAReadingStatsData(days: [key(14): .init(seconds: 5, pages: 2), key(12): .init(seconds: 7, pages: 1)])
        let week = data.last(3, now: date(14), calendar: calendar)
        XCTAssertEqual(week.map(\.seconds), [7, 0, 5])
        XCTAssertEqual(data.totals, CREADayStat(seconds: 12, pages: 3))
    }

    func testReadingTimeFormat() {
        XCTAssertEqual(creaFormatReadingTime(45 * 60), "45 min")
        XCTAssertEqual(creaFormatReadingTime(2 * 3600 + 5 * 60), "2 h 5 min")
    }

    func testAIServerMustBeHttpsAndApproved() {
        XCTAssertTrue(CREAReaderAI.isAcceptableServer("https://crea-reader-ai.onrender.com"))
        XCTAssertFalse(CREAReaderAI.isAcceptableServer("http://crea-reader-ai.onrender.com"))
        XCTAssertFalse(CREAReaderAI.isAcceptableServer("https://example.com"))
        XCTAssertFalse(CREAReaderAI.isAcceptableServer("https://crea-reader-ai.onrender.com/path"))
        XCTAssertFalse(CREAReaderAI.isAcceptableServer(""))
    }

    func testNotesExportContainsBookAndNotes() {
        let item = CREAAnnotation(bookHash: "h", kind: "highlight", locatorJSON: "{}", title: "Un texto", note: "Mi nota")
        let text = CREANotesExport.text(bookTitle: "Libro", items: [item])
        XCTAssertTrue(text.hasPrefix("# Libro"))
        XCTAssertTrue(text.contains("> Un texto"))
        XCTAssertTrue(text.contains("Nota: Mi nota"))
    }
}

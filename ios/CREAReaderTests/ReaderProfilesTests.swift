import XCTest
@testable import CREAReader

@MainActor
final class ReaderProfilesTests: XCTestCase {
    private func book(_ key: String, title: String = "Libro", author: String? = nil) -> CREABookRef {
        CREABookRef(key: key, title: title, author: author, driveFileId: key.hasPrefix("drive:") ? String(key.dropFirst(6)) : nil)
    }
    private func profile(_ id: String = "p1", group: String? = nil) -> CREAReaderProfile {
        CREAReaderProfile(id: id, nickname: id, createdAt: 1, updatedAt: 1, group: group)
    }

    func testProgressMarksFinishedAndKeepsPosition() {
        var p = CREAReaderRules.opened(profile(), book: book("drive:a"), now: 1_000)
        p = CREAReaderRules.progressed(p, book: book("drive:a"), progression: "{\"x\":1}", percent: 96, now: 2_000)
        XCTAssertEqual(p.history.first?.finishedAt, 2_000)
        XCTAssertEqual(p.history.first?.progression, "{\"x\":1}")
        XCTAssertEqual(CREAReaderRules.finished(p).count, 1)
        XCTAssertTrue(CREAReaderRules.continueReading(p).isEmpty)
    }

    func testMergeKeepsLatestPositionAndEarliestFinish() {
        var a = CREAReaderRules.progressed(profile(), book: book("drive:a"), progression: "A", percent: 40, now: 1_000)
        var b = CREAReaderRules.progressed(profile(), book: book("drive:a"), progression: "B", percent: 96, now: 3_000)
        a.days = ["2026-10-01"]; b.days = ["2026-10-02"]
        let merged = CREAReaderRules.merge(a, b)
        XCTAssertEqual(merged.history.count, 1)
        XCTAssertEqual(merged.history.first?.progression, "B")
        XCTAssertEqual(merged.history.first?.finishedAt, 3_000)
        XCTAssertEqual(merged.days, ["2026-10-01", "2026-10-02"])
    }

    func testFavoriteLatestChangeWins() {
        var p = CREAReaderRules.favorite(profile(), book: book("drive:a"), on: true, now: 10)
        p = CREAReaderRules.favorite(p, book: book("drive:a"), on: false, now: 20)
        XCTAssertFalse(CREAReaderRules.isFavorite(p, key: "drive:a"))
        XCTAssertTrue(CREAReaderRules.favorites(p).isEmpty)
    }

    func testGroupsAndNicknamesIgnoreCaseAndAccents() {
        XCTAssertTrue(CREAReaderRules.sameGroup("Club Martes", " club martes "))
        XCTAssertFalse(CREAReaderRules.sameGroup(nil, "club"))
        XCTAssertEqual(CREAReaderRules.normalize("  Ánä "), "ana")
    }

    func testDecodesAndroidJSONWithMissingFields() throws {
        let json = #"{"id":"abc","nickname":"Ana","createdAt":5,"updatedAt":6,"history":[{"key":"drive:z","title":"T","openedAt":9}]}"#
        let p = try JSONDecoder().decode(CREAReaderProfile.self, from: Data(json.utf8))
        XCTAssertEqual(p.nickname, "Ana")
        XCTAssertEqual(p.history.first?.percent, 0)
        XCTAssertFalse(p.hidden)
        XCTAssertNil(p.group)
    }

    func testPercentFromLocator() {
        XCTAssertEqual(CREAReaderRules.percent(fromLocator: #"{"locations":{"totalProgression":0.456}}"#), 46)
        XCTAssertEqual(CREAReaderRules.percent(fromLocator: "no json"), 0)
    }

    func testRecommendationsExplainWhyAndSkipStartedBooks() {
        var me = profile("me", group: "Club")
        me = CREAReaderRules.progressed(me, book: book("drive:1", author: "Rubén Darío"), progression: "x", percent: 100, now: 5_000)
        var other = profile("other", group: "club")
        other = CREAReaderRules.progressed(other, book: book("drive:3"), progression: "x", percent: 100, now: 5_000)
        let catalog = [
            CREACandidateBook(key: "drive:1", title: "Leído", author: "Rubén Darío", categories: ["Poesía"], driveFileId: "1", addedAt: 1),
            CREACandidateBook(key: "drive:2", title: "Azul", author: "Rubén Darío", categories: ["Poesía"], driveFileId: "2", addedAt: 2),
            CREACandidateBook(key: "drive:3", title: "Del grupo", author: nil, categories: [], driveFileId: "3", addedAt: 3),
        ]
        let picks = CREAReaderInsights.recommend(me: me, everyone: [me, other], catalog: catalog, now: 6_000)
        XCTAssertFalse(picks.contains { $0.book.key == "drive:1" })
        XCTAssertEqual(picks.first { $0.book.key == "drive:2" }?.reason, .sameAuthor("Rubén Darío"))
        XCTAssertEqual(picks.first { $0.book.key == "drive:3" }?.reason, .groupFinished(1))
    }

    func testAchievementsUnlock() {
        var p = profile()
        p.days = ["2026-10-01", "2026-10-02", "2026-10-03"]
        p = CREAReaderRules.progressed(p, book: book("drive:a", author: "A"), progression: "x", percent: 100, now: 1)
        let list = CREAReaderInsights.achievements(p)
        XCTAssertTrue(list.first { $0.id == .firstBook }?.unlocked == true)
        XCTAssertTrue(list.first { $0.id == .streak3 }?.unlocked == true)
        XCTAssertFalse(list.first { $0.id == .fiveBooks }?.unlocked == true)
    }

    func testStoreChoosesProfileAndAdoptsGuestReading() {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("readers-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = CREAReaderStore(directory: directory)
        store.setModeOverride(true)
        store.endSession()
        store.opened(book("drive:a"))                     // read as a guest
        XCTAssertNotNil(store.guest)
        let ana = store.create(nickname: "Ana", avatar: 1, color: 2, group: "Club martes")
        XCTAssertEqual(store.current()?.id, ana.id)
        XCTAssertNil(store.guest)
        store.opened(book("drive:b"))
        let keys = Set(store.current()?.history.map(\.key) ?? [])
        XCTAssertEqual(keys, ["drive:a", "drive:b"])
        store.endSession()
        XCTAssertNil(store.current())
        store.setModeOverride(nil)
    }
}

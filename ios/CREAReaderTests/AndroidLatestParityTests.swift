import XCTest
import AVFoundation
import ReadiumShared
import Combine
@testable import CREAReader

final class AndroidLatestParityTests: XCTestCase {
    private func fixture(_ name: String = "reader") throws -> URL { try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "epub")) }
    private func remote(_ file: URL, checksum: String? = nil, category: String? = nil) throws -> CREARemoteBook {
        var data: [String: Any] = ["id": "test-drive-book", "name": "Lectura - CREA.epub", "mimeType": "application/epub+zip", "size": String(try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)]
        if let checksum { data["md5Checksum"] = checksum }; if let category { data["category"] = category }
        return try JSONDecoder().decode(CREARemoteBook.self, from: JSONSerialization.data(withJSONObject: data))
    }
    func testAndroidOmittedDefaultsDecodeInSharedAIIndex() throws {
        let json = #"{"books":[{"driveFileId":"book","version":"checksum","title":"Título","summary":"Una sinopsis auténtica"}]}"#
        let decoded = try JSONDecoder().decode(CREASharedInsightIndex.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.schema, 1); XCTAssertEqual(decoded.books.first?.categories, [])
        XCTAssertEqual(decoded.books.first?.level, "general"); XCTAssertEqual(decoded.books.first?.confidence, "medium")
        let roundTrip = try JSONDecoder().decode(CREASharedInsightIndex.self, from: JSONEncoder().encode(decoded))
        XCTAssertEqual(roundTrip.books.first?.version, "checksum")
    }
    func testAndroidInactiveAndEmptyProfilesDecodeWithoutDefaults() throws {
        let empty = try JSONDecoder().decode(CREACommunityProfile.self, from: Data(#"{"userId":"device"}"#.utf8))
        XCTAssertFalse(empty.active); XCTAssertTrue(empty.readings.isEmpty)
        let zero = try JSONDecoder().decode(CREACommunityReading.self, from: Data(#"{"driveFileId":"book"}"#.utf8))
        XCTAssertEqual(zero.percent, 0)
    }
    func testCatalogSearchMatchesWordsInAnyOrderAndIgnoresAccents() {
        XCTAssertTrue(CREACatalogRules.matches("Biología de Rubén Darío", query: "dario BIOLOGIA"))
        XCTAssertFalse(CREACatalogRules.matches("Biología de Rubén Darío", query: "biologia física"))
    }
    func testCategoryTreeIncludesIntermediateFoldersAndDescendantsOnly() {
        let paths = ["Ciencias / Biología / Botánica", "Ciencias / Física", "Ciencias sociales"]
        let chips = CREACategoryPaths.chips(paths, selected: "Ciencias / Biología")
        XCTAssertTrue(chips.contains("Ciencias")); XCTAssertTrue(chips.contains("Ciencias / Biología / Botánica"))
        XCTAssertTrue(CREACategoryPaths.matches("Ciencias / Biología / Botánica", selected: "Ciencias"))
        XCTAssertFalse(CREACategoryPaths.matches("Ciencias sociales", selected: "Ciencias"))
        XCTAssertFalse(CREACategoryPaths.matches(nil, selected: "Ciencias"))
    }
    func testDriveWebTitlesAndAuthoredNamesMatchAndroid094() {
        XCTAssertEqual(CREADriveNames.describe("_libros_epub_La-leyenda-de-Sleepy-Hollow.epub", createdByApp: false).title, "La leyenda de Sleepy Hollow")
        XCTAssertEqual(CREADriveNames.describe("Spider-Man.epub", createdByApp: false).title, "Spider-Man")
        let names = CREADriveNames.describe("Libro - Autora.epub", createdByApp: true)
        XCTAssertEqual(names.title, "Libro"); XCTAssertEqual(names.author, "Autora")
        XCTAssertNil(CREADriveNames.describe("Libro - Autora.epub", createdByApp: false).author)
    }
    func testSimilarEditionsUseIdentifierOrTitleAuthorWithinSameFormat() {
        var book = LocalBook(id: UUID(), name: "Poesía", filename: "a.epub", kind: "EPUB")
        book.author = "Rubén Darío"; book.publicationIdentifier = "isbn-123456"
        XCTAssertTrue(CREADuplicateRules.similar(title: "POESIA", author: "Ruben Dario", identifier: nil, kind: "EPUB", to: book))
        XCTAssertTrue(CREADuplicateRules.similar(title: "Otro título", author: nil, identifier: "ISBN-123456", kind: "EPUB", to: book))
        XCTAssertFalse(CREADuplicateRules.similar(title: "POESIA", author: "Ruben Dario", identifier: "isbn-123456", kind: "PDF", to: book))
    }
    func testVoiceRankingPrefersQualityThenLatinAmericanSpanish() {
        let voices = [CREAVoiceChoice(id: "basic", name: "Básica", language: "es-MX", quality: 1),
                      CREAVoiceChoice(id: "spain", name: "Mejorada", language: "es-ES", quality: 2),
                      CREAVoiceChoice(id: "latin", name: "Mejorada", language: "es-MX", quality: 2),
                      CREAVoiceChoice(id: "english", name: "Premium", language: "en-US", quality: 3)]
        XCTAssertEqual(CREAVoiceChoice.best(voices, language: "es-NI")?.id, "latin")
        XCTAssertEqual(CREAVoiceChoice.best(voices, language: "en")?.id, "english")
        XCTAssertNil(CREAVoiceChoice.best(voices, language: "fr"))
    }
    func testManualInstalledVoiceIsNeverReplacedByAutomaticRanking() throws {
        let voice = try XCTUnwrap(AVSpeechSynthesisVoice.speechVoices().first)
        let defaults = UserDefaults.standard, saved = defaults.object(forKey: "crea.voice.id")
        defer { defaults.set(saved, forKey: "crea.voice.id") }
        defaults.set(voice.identifier, forKey: "crea.voice.id")
        let utterance = AVSpeechUtterance(string: "Lectura de prueba")
        CREAVoiceConfiguration.apply(to: utterance)
        XCTAssertEqual(utterance.voice?.identifier, voice.identifier)
    }
    func testCommunityRecommendationsUseSimilarReadersAndExcludeOwnBooks() {
        let now = Date(), stamp = Int64(now.timeIntervalSince1970 * 1000)
        let similar = CREACommunityProfile(userId: "similar", nickname: "Lector", active: true, readings: [CREACommunityReading(driveFileId: "mine", percent: 50, updatedAt: stamp), CREACommunityReading(driveFileId: "next", percent: 98, updatedAt: stamp)])
        let other = CREACommunityProfile(userId: "other", nickname: "Otro", active: true, readings: [CREACommunityReading(driveFileId: "otherBook", percent: 99, updatedAt: stamp)])
        let feed = CREACommunityFeed.build(members: [similar, other], titles: ["mine": "Actual", "next": "Siguiente", "otherBook": "Otro"], mine: ["mine"], now: now)
        XCTAssertEqual(feed.recommended.first?.id, "next")
        XCTAssertTrue(feed.recommended.first?.similar == true)
        XCTAssertFalse(feed.recommended.contains { $0.id == "mine" })
    }
    func testCommunityReadingNowExcludesFinishedStaleAndMissingBooks() {
        let now = Date(), stamp = Int64(now.timeIntervalSince1970 * 1000)
        let profile = CREACommunityProfile(userId: "one", nickname: "Lector", active: true, readings: [
            CREACommunityReading(driveFileId: "fresh", percent: 50, updatedAt: stamp), CREACommunityReading(driveFileId: "finished", percent: 98, updatedAt: stamp),
            CREACommunityReading(driveFileId: "old", percent: 50, updatedAt: stamp - 31 * 86400 * 1000), CREACommunityReading(driveFileId: "missing", percent: 50, updatedAt: stamp)])
        let feed = CREACommunityFeed.build(members: [profile], titles: ["fresh": "Nuevo", "finished": "Terminado", "old": "Antiguo"], mine: [], now: now)
        XCTAssertEqual(feed.readingNow.map { $0.bookID }, ["fresh"])
    }
    func testDownloadedFileIntegrityAndWrongChecksum() throws {
        let file = try fixture(), md5 = try CREABookIdentity.md5(file)
        let validation = try CREATransferValidation.validate(file, book: remote(file, checksum: md5))
        XCTAssertEqual(validation.hash, try CREABookIdentity.hash(file))
        XCTAssertThrowsError(try CREATransferValidation.validate(file, book: remote(file, checksum: "00000000000000000000000000000000")))
    }
    @MainActor func testUpdatedEditionPreservesIdentityStateAndNotesFromOpenReader() throws {
        let library = LocalLibrary(), notes = CREAAnnotationStore(), old = try fixture(), updated = try fixture("reader-updated")
        let book = try library.add(url: old), oldHash = try CREABookIdentity.hash(old), newHash = try CREABookIdentity.hash(updated)
        library.update(book) { $0.driveFileID = "test-drive-book"; $0.progress = 0.7; $0.favorite = true; $0.collections = ["Clase"]; $0.readingLocatorJSON = "{\"locations\":{\"totalProgression\":0.7}}" }
        notes.add(hash: oldHash, kind: "highlight", locator: "{}", title: "Texto", note: "Nota antes de actualizar")
        defer { library.remove(book); for item in notes.items(for: newHash) { notes.remove(item) } }
        try library.installRemote(remote(updated, checksum: CREABookIdentity.md5(updated), category: "Ciencias / Biología"), file: updated, hash: newHash, md5: CREABookIdentity.md5(updated), accountEmail: nil, annotations: notes)
        let current = try XCTUnwrap(library.books.first { $0.id == book.id })
        XCTAssertEqual(current.progress, 0.7); XCTAssertTrue(current.favorite); XCTAssertEqual(current.collections, ["Clase"])
        XCTAssertEqual(current.category, "Ciencias / Biología"); XCTAssertEqual(current.contentHash, newHash)
        XCTAssertEqual(try CREABookIdentity.hash(library.url(for: current)), newHash)
        XCTAssertEqual(notes.items(for: newHash).count, 1)
        notes.add(hash: oldHash, kind: "bookmark", locator: "{\"page\":2}", title: "Marcador del lector abierto")
        XCTAssertEqual(notes.items(for: newHash).count, 2)
    }
    @MainActor func testSimilarImportRequiresApprovalAndKeepsExistingProgress() async throws {
        let library = LocalLibrary(), original = try fixture(), updated = try fixture("reader-updated")
        let book = try library.add(url: original)
        library.update(book) { $0.name = "Lectura de prueba"; $0.author = "CREA"; $0.publicationIdentifier = "crea-test"; $0.progress = 0.6; $0.favorite = true }
        let queue = CREAImportCoordinator(library: library), offered = expectation(description: "Ask before second edition")
        let subscription = queue.$pending.compactMap { $0 }.prefix(1).sink { _ in offered.fulfill() }
        defer { subscription.cancel(); for item in library.books.filter({ $0.contentHash == (try? CREABookIdentity.hash(original)) || $0.contentHash == (try? CREABookIdentity.hash(updated)) }) { library.remove(item) } }
        let work = Task { await queue.run([updated]) }
        await fulfillment(of: [offered], timeout: 10)
        XCTAssertEqual(queue.pending?.existing.id, book.id)
        queue.choose(true); queue.confirmationClosed()
        let outcome = await work.value
        if case .completed = outcome {} else { XCTFail("Import should complete after approval") }
        XCTAssertEqual(library.books.filter { $0.publicationIdentifier == "crea-test" }.count, 2)
        XCTAssertEqual(library.books.first { $0.id == book.id }?.progress, 0.6)
        XCTAssertTrue(library.books.first { $0.id == book.id }?.favorite == true)
    }
    @MainActor func testEPUBFontScaleRemainsAMultiplier() async throws {
        let model = EPUBBookModel(), file = try fixture()
        await model.load(url: file, initialLocatorJSON: nil, onProgress: { _ in }, onLocator: { _ in })
        model.applyPreferences(fontSize: 1.4, scroll: false, darkMode: false)
        XCTAssertEqual(model.navigator?.settings.fontSize, 1.4)
        model.stopSpeech()
    }
}

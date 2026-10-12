import XCTest
import CryptoKit
@testable import CREAReader

final class ReaderParityTests: XCTestCase {
    func testContentHashStableAcrossNamesAndLargeChunks() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let bytes = Data(repeating: 65, count: 2_100_005)
        let first = directory.appendingPathComponent("a.epub"), second = directory.appendingPathComponent("b.epub")
        try bytes.write(to: first); try bytes.write(to: second)
        let expected = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(try CREABookIdentity.hash(first), expected)
        XCTAssertEqual(try CREABookIdentity.hash(second), expected)
    }
    func testOPDSNamespacesAcquisitionsRelativeLinksAndPagination() throws {
        let xml = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Biblioteca</title><link rel="next" href="?page=2"/>
          <entry><title>Libro &amp; lectura</title><author><name>Autora</name></author>
            <summary><![CDATA[Una historia <b>real</b>]]></summary>
            <link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="books/a.epub"/>
          </entry>
          <entry><title>Autores</title><link type="application/atom+xml;profile=opds-catalog" href="authors.atom"/></entry>
        </feed>
        """
        let parser = CREAOPDSParser(base: URL(string: "https://example.org/catalog.atom")!)
        try parser.parse(Data(xml.utf8))
        XCTAssertEqual(parser.entries.count, 2)
        XCTAssertEqual(parser.entries[0].title, "Libro & lectura")
        XCTAssertEqual(parser.entries[0].author, "Autora")
        XCTAssertEqual(parser.entries[0].acquisition?.url.absoluteString, "https://example.org/books/a.epub")
        XCTAssertEqual(parser.entries[1].navigation?.url.absoluteString, "https://example.org/authors.atom")
        XCTAssertEqual(parser.next?.absoluteString, "https://example.org/catalog.atom?page=2")
    }
    func testMalformedFeedDoesNotBecomeEmptySuccess() {
        let parser = CREAOPDSParser(base: URL(string: "https://example.org")!)
        XCTAssertThrowsError(try parser.parse(Data("<feed><entry>".utf8)))
    }
    @MainActor func testAnnotationDeletionWinsOverOlderRemoteEdit() {
        let hash = UUID().uuidString
        let store = CREAAnnotationStore()
        store.add(hash: hash, kind: "highlight", locator: "{}", title: "Texto")
        guard let old = store.items(for: hash).first else { return XCTFail("Annotation missing") }
        store.remove(old)
        store.merge([old])
        XCTAssertTrue(store.items(for: hash).isEmpty)
        XCTAssertTrue(store.records.first { $0.id == old.id }?.deleted == true)
    }
    @MainActor func testNewerRemoteAnnotationWinsAndDuplicateIDsAreMerged() {
        let hash = UUID().uuidString
        let store = CREAAnnotationStore()
        store.add(hash: hash, kind: "bookmark", locator: "{}", title: "Inicio")
        guard var remote = store.items(for: hash).first else { return XCTFail("Bookmark missing") }
        remote.note = "Nota actualizada"; remote.updatedAt = Date().addingTimeInterval(10)
        store.merge([remote, remote])
        XCTAssertEqual(store.items(for: hash).count, 1)
        XCTAssertEqual(store.items(for: hash).first?.note, "Nota actualizada")
    }
    @MainActor func testLibraryImportDeduplicatesAndPreservesReadingData() throws {
        let library = LocalLibrary()
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".epub")
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "reader", withExtension: "epub"))
        try FileManager.default.copyItem(at: fixture, to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let count = library.books.count
        try library.add(url: file)
        guard let book = library.books.first else { return XCTFail("Import missing") }
        defer { library.remove(book) }
        library.update(book) { $0.progress = 0.6; $0.collection = "Curso"; $0.favorite = true }
        try library.add(url: file, displayName: "Otro nombre")
        XCTAssertEqual(library.books.count, count + 1)
        let restored = library.books.first { $0.id == book.id }
        XCTAssertEqual(restored?.progress, 0.6)
        XCTAssertEqual(restored?.collection, "Curso")
        XCTAssertEqual(restored?.favorite, true)
    }
    @MainActor func testRealEPUBOpensHasContentsAndSearchLocators() async throws {
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "reader", withExtension: "epub"))
        let model = EPUBBookModel()
        await model.load(url: fixture, initialLocatorJSON: nil, onProgress: { _ in }, onLocator: { _ in })
        XCTAssertNil(model.message)
        XCTAssertNotNil(model.navigator)
        XCTAssertFalse(model.contents.isEmpty)
        await model.search("lectura")
        XCTAssertNil(model.searchError)
        XCTAssertFalse(model.results.isEmpty)
        XCTAssertNotNil(model.results.first?.jsonString)
        model.stopSpeech()
    }
    @MainActor func testRealPDFOpensAndSearchesBothPages() async throws {
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "reader", withExtension: "pdf"))
        let model = CREAPDFModel()
        await model.load(fixture, progress: 1, onProgress: { _ in })
        defer { model.close() }
        XCTAssertTrue(model.ready)
        XCTAssertEqual(model.pageCount, 2)
        model.search("lectura")
        XCTAssertEqual(model.matches.count, 2)
        XCTAssertEqual(model.page, 2)
    }
    func testOPDS2NavigationAndAcquisition() throws {
        let feed = #"{"metadata":{"title":"CREA"},"publications":[{"metadata":{"title":"Libro","author":[{"name":"Autora"}]},"links":[{"href":"a.epub","type":"application/epub+zip","rel":["http://opds-spec.org/acquisition/open-access"]}]}],"navigation":[{"title":"Temas","href":"topics.json","type":"application/opds+json"}],"links":[{"href":"?page=2","rel":"next"}]}"#
        let parser = CREAOPDSParser(base: URL(string: "https://example.org/feed.json")!)
        try parser.parse(Data(feed.utf8))
        XCTAssertEqual(parser.entries.count, 2)
        XCTAssertEqual(parser.entries[0].author, "Autora")
        XCTAssertNotNil(parser.entries[0].acquisition)
        XCTAssertNotNil(parser.entries[1].navigation)
        XCTAssertNotNil(parser.next)
    }
}

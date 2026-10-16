import SwiftUI
import PDFKit
import WidgetKit
import ReadiumShared
import ReadiumStreamer

/// Publishes the library and stats to the home-screen widgets and keeps them current.
@MainActor
enum CREAWidgetBridge {
    private static var pending: Task<Void, Never>?
    static let scheme = "creareader"

    /// Coalesces bursts (progress changes on every page turn) into one update.
    static func refresh() {
        pending?.cancel()
        pending = Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }
            await publish()
        }
    }

    static func bookURL(_ id: UUID) -> URL? { URL(string: "\(scheme)://book/\(id.uuidString)") }

    private static var coversDirectory: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: CREAShared.groupID)?
            .appendingPathComponent("widget-covers", isDirectory: true)
    }

    private static func publish() async {
        let library = LocalLibrary.shared
        let stats = CREAReadingStats.shared.data
        let inProgress = library.books.filter { $0.progress > 0.0 && $0.progress < 0.99 }
            .sorted { ($0.progressUpdatedAt ?? $0.lastOpened ?? .distantPast) > ($1.progressUpdatedAt ?? $1.lastOpened ?? .distantPast) }
        let unread = library.books.filter { $0.progress == 0 }.sorted { ($0.importedAt ?? .distantPast) > ($1.importedAt ?? .distantPast) }
        let chosen = Array((inProgress + unread).prefix(3))
        if let directory = coversDirectory { try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
        var books: [CREAWidgetSnapshot.Book] = []
        for book in chosen {
            var file: String?
            if let directory = coversDirectory {
                let name = book.id.uuidString + ".jpg"
                let target = directory.appendingPathComponent(name)
                if FileManager.default.fileExists(atPath: target.path) { file = name }
                else if let image = await cover(of: book, library: library), let data = image.jpegData(compressionQuality: 0.8) {
                    if (try? data.write(to: target)) != nil { file = name }
                }
            }
            books.append(.init(id: book.id.uuidString, title: book.name, author: book.author,
                               percent: Int((book.progress * 100).rounded()), coverFile: file))
        }
        let today = stats.last(1).first ?? CREADayStat()
        let snapshot = CREAWidgetSnapshot(reading: books, minutesToday: today.seconds / 60, pagesToday: today.pages,
                                          streak: stats.streak(), finished: library.books.filter { $0.progress >= 0.99 }.count)
        if let encoded = try? JSONEncoder().encode(snapshot) { CREAShared.defaults.set(encoded, forKey: CREAShared.widgetKey) }
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Thumbnail scaled to widget size so the extension never decodes a full cover.
    private static func cover(of book: LocalBook, library: LocalLibrary) async -> UIImage? {
        let url = library.url(for: book)
        var image: UIImage?
        if book.kind == "PDF" {
            image = PDFDocument(url: url)?.page(at: 0)?.thumbnail(of: CGSize(width: 300, height: 420), for: .mediaBox)
        } else if book.kind == "EPUB" {
            let http = DefaultHTTPClient(), retriever = AssetRetriever(httpClient: http)
            let opener = PublicationOpener(parser: DefaultPublicationParser(httpClient: http, assetRetriever: retriever, pdfFactory: DefaultPDFDocumentFactory()))
            if let absolute = url.anyURL.absoluteURL,
               case let .success(asset) = await retriever.retrieve(url: absolute),
               case let .success(publication) = await opener.open(asset: asset, allowUserInteraction: false),
               case let .success(found) = await publication.cover() { image = found }
        }
        guard let image else { return nil }
        let width: CGFloat = 300, scale = min(1, width / max(1, image.size.width))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        return UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }
}

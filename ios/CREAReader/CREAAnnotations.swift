import Foundation
import CryptoKit

/// Content identity survives renaming, reimporting and temporary Drive copies.
enum CREABookIdentity {
    static func hash(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var digest = SHA256()
        while let chunk = try handle.read(upToCount: 1_048_576), !chunk.isEmpty {
            digest.update(data: chunk)
        }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

struct CREAAnnotation: Codable, Identifiable, Equatable {
    var id = UUID()
    let bookHash: String
    let kind: String
    let locatorJSON: String
    var title: String
    var note: String = ""
    var color: String = "yellow"
    var updatedAt = Date()
    var deleted = false
}

@MainActor
final class CREAAnnotationStore: ObservableObject {
    static let shared = CREAAnnotationStore()
    @Published private(set) var records: [CREAAnnotation] = []
    private let key = "crea.reader.annotations.v1"
    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let saved = try? JSONDecoder().decode([CREAAnnotation].self, from: data) { records = saved }
    }
    func items(for hash: String) -> [CREAAnnotation] {
        records.filter { $0.bookHash == hash && !$0.deleted }.sorted { $0.updatedAt > $1.updatedAt }
    }
    func add(hash: String, kind: String, locator: String, title: String, note: String = "", color: String = "yellow") {
        guard !hash.isEmpty else { return }
        if kind == "bookmark", items(for: hash).contains(where: { $0.kind == kind && $0.locatorJSON == locator }) { return }
        records.append(CREAAnnotation(bookHash: hash, kind: kind, locatorJSON: locator, title: title, note: note, color: color))
        persist()
    }
    func edit(_ item: CREAAnnotation, note: String, color: String) {
        guard let index = records.firstIndex(where: { $0.id == item.id }) else { return }
        records[index].note = note; records[index].color = color; records[index].updatedAt = Date()
        persist()
    }
    func remove(_ item: CREAAnnotation) {
        guard let index = records.firstIndex(where: { $0.id == item.id }) else { return }
        records[index].deleted = true; records[index].updatedAt = Date(); persist()
    }
    func merge(_ remote: [CREAAnnotation]) {
        var indexed = Dictionary(records.map { ($0.id, $0) }, uniquingKeysWith: { a, b in a.updatedAt >= b.updatedAt ? a : b })
        for item in remote where item.updatedAt > (indexed[item.id]?.updatedAt ?? .distantPast) { indexed[item.id] = item }
        records = indexed.values.sorted { $0.id.uuidString < $1.id.uuidString }; persist()
    }
    private func persist() {
        if let data = try? JSONEncoder().encode(records) { UserDefaults.standard.set(data, forKey: key) }
    }
}

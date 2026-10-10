import Foundation
import CryptoKit

/// Reads Android's v2 progress manifest without deleting or rewriting it.
@MainActor
final class CREAProgressSync: ObservableObject {
    @Published private(set) var status = "Progreso pendiente de sincronización"
    @Published private(set) var busy = false

    private func sha256(for file: URL) -> String? {
        guard let bytes = try? Data(contentsOf: file, options: [.mappedIfSafe]) else { return nil }
        return SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }

    private func matchingBook(_ local: LocalBook, manifest: [[String: Any]], library: LocalLibrary) -> [String: Any]? {
        if let remoteID = local.driveFileID,
           let entry = manifest.first(where: { $0["driveFileId"] as? String == remoteID }) {
            return entry
        }
        guard let digest = sha256(for: library.url(for: local)) else { return nil }
        return manifest.first(where: { $0["contentHash"] as? String == digest })
    }

    func pull(catalog: CREADriveCatalog, library: LocalLibrary) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            guard let remote = try await catalog.loadPrivateProgressManifest() else {
                status = "Android todavía no ha creado un manifiesto compartido."
                return
            }
            let entries = remote.document["books"] as? [[String: Any]] ?? []
            var applied = 0
            for book in library.books {
                guard let match = matchingBook(book, manifest: entries, library: library),
                      let locator = match["progression"] as? String,
                      let data = locator.data(using: .utf8),
                      let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let locations = obj["locations"] as? [String: Any],
                      let amount = locations["totalProgression"] as? Double else { continue }
                let time = Date(timeIntervalSince1970: Double(match["progressUpdatedAt"] as? Int64 ?? 0) / 1000)
                guard time > (book.progressUpdatedAt ?? .distantPast) else { continue }
                library.update(book) { entry in
                    entry.progress = max(0, min(1, amount))
                    entry.progressUpdatedAt = time
                    entry.readingLocatorJSON = locator
                    if entry.driveFileID == nil { entry.driveFileID = match["driveFileId"] as? String }
                }
                UserDefaults.standard.set(locator, forKey: "crea.epub.locator." + book.filename)
                applied += 1
            }
            status = "Progreso recuperado de Android: \(applied) libros actualizados"
        } catch {
            status = error.localizedDescription
        }
    }
}

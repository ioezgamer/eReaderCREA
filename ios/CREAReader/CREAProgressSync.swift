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
    /// Merge only newer EPUB reading positions into the existing Android manifest.
    /// Keeps Android's other books, tombstones, devices and unknown fields intact.
    func push(catalog: CREADriveCatalog, library: LocalLibrary) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            guard let remote = try await catalog.loadPrivateProgressManifest() else {
                status = "Primero sincroniza la biblioteca desde Android."
                return
            }
            var document = remote.document
            var entries = document["books"] as? [[String: Any]] ?? []
            var changed = 0
            var readings: [[String: Any]] = []
            let now = Int64(Date().timeIntervalSince1970 * 1000)

            for book in library.books {
                guard let match = matchingBook(book, manifest: entries, library: library),
                      let index = entries.firstIndex(where: {
                          ($0["contentHash"] as? String) == (match["contentHash"] as? String)
                      }) else { continue }
                var entry = entries[index]
                let remoteID = entry["driveFileId"] as? String ?? book.driveFileID
                let stamp = Int64((book.progressUpdatedAt ?? .distantPast).timeIntervalSince1970 * 1000)
                let oldStamp = (entry["progressUpdatedAt"] as? NSNumber)?.int64Value ?? 0
                if let locator = book.readingLocatorJSON,
                   !locator.isEmpty && book.kind == "EPUB" && stamp > oldStamp {
                    entry["progression"] = locator
                    entry["progressUpdatedAt"] = stamp
                    entries[index] = entry
                    changed += 1
                }
                if let remoteID, book.progress > 0 {
                    readings.append([
                        "driveFileId": remoteID,
                        "percent": Int(max(0, min(100, (book.progress * 100).rounded()))),
                        "updatedAt": max(0, stamp)
                    ])
                }
            }

            var deviceID = UserDefaults.standard.string(forKey: "crea.reader.syncDeviceID")
            if deviceID == nil {
                deviceID = UUID().uuidString
                UserDefaults.standard.set(deviceID, forKey: "crea.reader.syncDeviceID")
            }
            let stableID = deviceID!
            var devices = document["devices"] as? [[String: Any]] ?? []
            let previous = devices.first(where: { ($0["deviceId"] as? String) == stableID })
            let previousReadings = previous?["readings"] as? [[String: Any]] ?? []
            let previousPairs = previousReadings.map { "\($0["driveFileId"] ?? ""):\($0["percent"] ?? 0)" }.sorted()
            let newPairs = readings.map { "\($0["driveFileId"] ?? ""):\($0["percent"] ?? 0)" }.sorted()
            let deviceChanged = previous == nil || previousPairs != newPairs
            if !deviceChanged && changed == 0 {
                status = "El progreso ya está actualizado"
                return
            }

            devices.removeAll { ($0["deviceId"] as? String) == stableID }
            devices.append([
                "deviceId": stableID,
                "name": "CREA Reader iOS",
                "updatedAt": deviceChanged ? now : (previous?["updatedAt"] ?? now),
                "readings": readings
            ])
            document["books"] = entries
            document["devices"] = devices
            document["generatedAt"] = now
            document["deviceId"] = stableID
            try await catalog.savePrivateProgressManifest(document, fileID: remote.fileID)
            status = "Progreso enviado a Drive: \(changed) libros"
        } catch {
            status = "No se pudo enviar el progreso: " + error.localizedDescription
        }
    }

}

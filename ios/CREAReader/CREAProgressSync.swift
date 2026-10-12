import Foundation
import Combine
import CryptoKit
import GoogleSignIn

/// Reading position of an online Drive book. Never keyed by its temporary file name.
struct CREAOnlineReadingCheckpoint: Codable {
    var progress: Double
    var locatorJSON: String?
    var updatedAt: Date
}
struct CREADeviceSnapshot: Decodable, Identifiable {
    struct Reading: Decodable, Identifiable {
        let driveFileId: String
        let percent: Int
        let updatedAt: Int64
        var id: String { driveFileId }
    }
    let deviceId: String
    let name: String
    let updatedAt: Int64
    let readings: [Reading]
    var id: String { deviceId }
}

/// Android v2 private manifest sync plus persistent online-only reading positions.
@MainActor
final class CREAProgressSync: ObservableObject {
    @Published private(set) var status = "Progreso pendiente de sincronización"
    @Published private(set) var busy = false
    @Published private(set) var remotePercents: [String: Int] = [:]
    @Published private(set) var onlineCheckpoints: [String: CREAOnlineReadingCheckpoint] = [:]
    @Published private(set) var devices: [CREADeviceSnapshot] = []
    private let storeKey = "crea.reader.onlineCheckpoints.v1"

    init() {
        if let data = UserDefaults.standard.data(forKey: storeKey),
           let saved = try? JSONDecoder().decode([String: CREAOnlineReadingCheckpoint].self, from: data) {
            onlineCheckpoints = saved
        }
    }
    func resetAccount() { remotePercents = [:]; devices = []; status = "Progreso pendiente de sincronización" }

    private func accountKey(_ id: String) -> String? {
        guard !id.isEmpty,
              let email = GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased(),
              !email.isEmpty else { return nil }
        return email + "|" + id
    }

    private func onlineReadingsForThisAccount() -> [(id: String, checkpoint: CREAOnlineReadingCheckpoint)] {
        guard let email = GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased() else { return [] }
        let prefix = email + "|"
        return onlineCheckpoints.compactMap { key, value in
            guard key.hasPrefix(prefix) else { return nil }
            return (id: String(key.dropFirst(prefix.count)), checkpoint: value)
        }
    }

    func checkpoint(for id: String) -> CREAOnlineReadingCheckpoint? {
        guard let key = accountKey(id) else { return nil }
        return onlineCheckpoints[key]
    }

    func percent(for id: String) -> Int? {
        if let saved = checkpoint(for: id) {
            return Int((max(0, min(1, saved.progress)) * 100).rounded())
        }
        return remotePercents[id]
    }

    private func persistOnline() {
        if let encoded = try? JSONEncoder().encode(onlineCheckpoints) {
            UserDefaults.standard.set(encoded, forKey: storeKey)
        }
    }

    private func progressFromLocator(_ locator: String) -> Double? {
        guard let data = locator.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let locations = root["locations"] as? [String: Any],
              let progress = locations["totalProgression"] as? NSNumber else { return nil }
        return max(0, min(1, progress.doubleValue))
    }

    /// Invoked on Readium or PDFKit page changes. No cloud request per page.
    func recordOnlineProgress(for id: String, fraction: Double) {
        guard fraction.isFinite, let key = accountKey(id) else { return }
        onlineCheckpoints[key] = CREAOnlineReadingCheckpoint(
            progress: max(0, min(1, fraction)),
            locatorJSON: onlineCheckpoints[key]?.locatorJSON,
            updatedAt: Date()
        )
        persistOnline()
    }

    /// The complete EPUB locator is needed to reopen at the precise text position.
    func recordOnlineLocator(for id: String, locator: String) {
        guard !locator.isEmpty, let key = accountKey(id) else { return }
        let previous = onlineCheckpoints[key]
        onlineCheckpoints[key] = CREAOnlineReadingCheckpoint(
            progress: progressFromLocator(locator) ?? previous?.progress ?? 0,
            locatorJSON: locator,
            updatedAt: Date()
        )
        persistOnline()
    }

    /// Adopt the online bookmark after a download, unless offline reading is newer.
    func promoteDownloadedBook(_ id: String, library: LocalLibrary) {
        guard let point = checkpoint(for: id),
              let book = library.books.first(where: { $0.driveFileID == id }),
              point.updatedAt > (book.progressUpdatedAt ?? .distantPast) else { return }
        library.update(book) { local in
            local.progress = max(0, min(1, point.progress))
            if let locator = point.locatorJSON { local.readingLocatorJSON = locator }
            local.progressUpdatedAt = point.updatedAt
            local.lastOpened = point.updatedAt
        }
    }

    private func sha256(for file: URL) -> String? {
        try? CREABookIdentity.hash(file)
    }

    private func matchedEntry(_ local: LocalBook, in entries: [[String: Any]], library: LocalLibrary) -> [String: Any]? {
        if let id = local.driveFileID,
           let match = entries.first(where: { $0["driveFileId"] as? String == id }) {
            return match
        }
        guard let hash = sha256(for: library.url(for: local)) else { return nil }
        return entries.first(where: { $0["contentHash"] as? String == hash })
    }

    private func millisecondStamp(_ entry: [String: Any]) -> Int64 {
        (entry["progressUpdatedAt"] as? NSNumber)?.int64Value ?? 0
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
            if let data = try? JSONSerialization.data(withJSONObject: remote.document["devices"] ?? []),
               let decoded = try? JSONDecoder().decode([CREADeviceSnapshot].self, from: data) { devices = decoded }
            var latest: [String: (percent: Int, stamp: Int64)] = [:]
            var onlineUpdated = 0

            for entry in entries {
                guard let id = entry["driveFileId"] as? String,
                      let locator = entry["progression"] as? String,
                      let progress = progressFromLocator(locator) else { continue }
                let stamp = millisecondStamp(entry)
                if stamp >= (latest[id]?.stamp ?? Int64.min) {
                    latest[id] = (Int((progress * 100).rounded()), stamp)
                }
                if let key = accountKey(id),
                   stamp > Int64((onlineCheckpoints[key]?.updatedAt ?? .distantPast).timeIntervalSince1970 * 1000) {
                    onlineCheckpoints[key] = CREAOnlineReadingCheckpoint(
                        progress: progress, locatorJSON: locator,
                        updatedAt: Date(timeIntervalSince1970: Double(stamp) / 1000)
                    )
                    onlineUpdated += 1
                }
            }

            // Android includes percentages for non-downloaded books in device readings.
            if let devices = remote.document["devices"] as? [[String: Any]] {
                for device in devices {
                    guard let readings = device["readings"] as? [[String: Any]] else { continue }
                    for reading in readings {
                        guard let id = reading["driveFileId"] as? String,
                              let percent = reading["percent"] as? NSNumber else { continue }
                        let stamp = (reading["updatedAt"] as? NSNumber)?.int64Value ?? 0
                        if stamp > (latest[id]?.stamp ?? Int64.min) {
                            latest[id] = (max(0, min(100, percent.intValue)), stamp)
                        }
                    }
                }
            }
            remotePercents = latest.mapValues { $0.percent }
            if onlineUpdated > 0 { persistOnline() }

            var applied = 0
            var linked = 0
            for book in library.books {
                if let account = book.driveAccount, account != GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased() { continue }
                guard let entry = matchedEntry(book, in: entries, library: library) else {
                    if let id = book.driveFileID { promoteDownloadedBook(id, library: library) }
                    continue
                }
                if book.driveFileID == nil, let id = entry["driveFileId"] as? String {
                    library.update(book) { $0.driveFileID = id; $0.driveAccount = GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased() }
                    linked += 1
                }
                if let locator = entry["progression"] as? String,
                   let progress = progressFromLocator(locator) {
                    let stamp = Date(timeIntervalSince1970: Double(millisecondStamp(entry)) / 1000)
                    if stamp > (book.progressUpdatedAt ?? .distantPast) {
                        library.update(book) { local in
                            local.progress = progress
                            local.readingLocatorJSON = locator
                            local.progressUpdatedAt = stamp
                            if local.driveFileID == nil { local.driveFileID = entry["driveFileId"] as? String }
                        }
                        applied += 1
                    }
                }
                if let id = book.driveFileID ?? (entry["driveFileId"] as? String) {
                    promoteDownloadedBook(id, library: library)
                }
            }
            status = "Progreso recuperado: \(applied) avances y \(onlineUpdated) posiciones de lectura en línea; \(linked) libros vinculados."
        } catch {
            status = "No se pudo recibir el progreso: " + error.localizedDescription
        }
    }

    /// Preserve all Android metadata and other devices, updating only known entries
    /// and this iOS device's online/offline readings.
    func push(catalog: CREADriveCatalog, library: LocalLibrary) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            let remote = try await catalog.loadPrivateProgressManifest()
            var document = remote?.document ?? ["schemaVersion": 1, "books": [[String: Any]](), "tombstones": [[String: Any]](), "devices": [[String: Any]]()]
            var entries = document["books"] as? [[String: Any]] ?? []
            var changed = 0
            var readings: [String: (percent: Int, stamp: Int64)] = [:]
            let now = Int64(Date().timeIntervalSince1970 * 1000)

            func record(_ id: String?, fraction: Double, stamp: Int64) {
                guard let id, fraction > 0, stamp > 0 else { return }
                if stamp >= (readings[id]?.stamp ?? Int64.min) {
                    readings[id] = (Int((max(0, min(1, fraction)) * 100).rounded()), stamp)
                }
            }
            func updateEntry(id: String?, hash: String?, locator: String?, stamp: Int64) {
                guard let locator, !locator.isEmpty, stamp > 0 else { return }
                let index = entries.firstIndex { entry in
                    (id != nil && entry["driveFileId"] as? String == id) ||
                    (hash != nil && entry["contentHash"] as? String == hash)
                }
                guard let index, stamp > millisecondStamp(entries[index]) else { return }
                entries[index]["progression"] = locator
                entries[index]["progressUpdatedAt"] = stamp
                changed += 1
            }

            for book in library.books {
                if let account = book.driveAccount, account != GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased() { continue }
                if let id = book.driveFileID, let hash = book.contentHash ?? sha256(for: library.url(for: book)),
                   !entries.contains(where: { $0["contentHash"] as? String == hash || $0["driveFileId"] as? String == id }) {
                    entries.append([
                        "contentHash": hash, "driveFileId": id, "fileName": book.name + "." + book.kind.lowercased(),
                        "mediaType": book.kind == "PDF" ? "application/pdf" : "application/epub+zip",
                        "title": book.name, "identifier": "", "fileSize": (try? library.url(for: book).resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0,
                        "createdAt": now, "updatedAt": now, "progression": "{}", "progressUpdatedAt": 0
                    ])
                    changed += 1
                }
                let matched = matchedEntry(book, in: entries, library: library)
                let id = book.driveFileID ?? (matched?["driveFileId"] as? String)
                let stamp = Int64((book.progressUpdatedAt ?? .distantPast).timeIntervalSince1970 * 1000)
                record(id, fraction: book.progress, stamp: stamp)
                if book.kind == "EPUB" {
                    updateEntry(id: id, hash: matched?["contentHash"] as? String,
                                locator: book.readingLocatorJSON, stamp: stamp)
                }
                if book.kind == "PDF", let id, stamp > 0,
                   let locator = book.readingLocatorJSON ?? (try? JSONSerialization.data(withJSONObject: ["href": book.filename, "type": "application/pdf", "locations": ["totalProgression": book.progress]])).flatMap({ String(data: $0, encoding: .utf8) }) {
                    updateEntry(id: id, hash: matched?["contentHash"] as? String, locator: locator, stamp: stamp)
                }
            }
            for online in onlineReadingsForThisAccount() {
                let id = online.id
                let point = online.checkpoint
                let offlineDate = library.books.first(where: { $0.driveFileID == id })?.progressUpdatedAt
                if let offlineDate, offlineDate >= point.updatedAt { continue }
                let stamp = Int64(point.updatedAt.timeIntervalSince1970 * 1000)
                record(id, fraction: point.progress, stamp: stamp)
                updateEntry(id: id, hash: nil, locator: point.locatorJSON, stamp: stamp)
            }

            let deviceID: String
            if let existing = UserDefaults.standard.string(forKey: "crea.reader.syncDeviceID") {
                deviceID = existing
            } else {
                deviceID = UUID().uuidString
                UserDefaults.standard.set(deviceID, forKey: "crea.reader.syncDeviceID")
            }
            let list: [[String: Any]] = readings.sorted { $0.key < $1.key }.map { id, value in
                ["driveFileId": id, "percent": value.percent, "updatedAt": value.stamp]
            }
            if remote == nil && entries.isEmpty && list.isEmpty {
                status = "No hay libros vinculados ni progreso para enviar."
                return
            }
            var devices = document["devices"] as? [[String: Any]] ?? []
            let previous = devices.first(where: { $0["deviceId"] as? String == deviceID })
            let oldReadings = previous?["readings"] as? [[String: Any]] ?? []
            func signature(_ items: [[String: Any]]) -> [String] {
                items.map { "\($0["driveFileId"] ?? ""):\($0["percent"] ?? 0):\($0["updatedAt"] ?? 0)" }.sorted()
            }
            let deviceChanged = previous == nil || signature(oldReadings) != signature(list)
            if changed == 0 && !deviceChanged {
                status = "El progreso ya está actualizado"
                return
            }
            devices.removeAll { $0["deviceId"] as? String == deviceID }
            devices.append([
                "deviceId": deviceID,
                "name": "CREA Reader iOS",
                "updatedAt": deviceChanged ? now : (previous?["updatedAt"] ?? now),
                "readings": list
            ])
            document["books"] = entries
            document["devices"] = devices
            document["generatedAt"] = now
            document["deviceId"] = deviceID
            try await catalog.savePrivateProgressManifest(document, fileID: remote?.fileID)
            status = "Progreso enviado a Drive: \(changed) posiciones y \(list.count) lecturas registradas"
        } catch {
            status = "No se pudo enviar el progreso: " + error.localizedDescription
        }
    }
}

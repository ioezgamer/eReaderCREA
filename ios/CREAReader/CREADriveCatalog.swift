import Foundation
import UIKit
import CryptoKit
import GoogleSignIn

struct CREARemoteBook: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let mimeType: String
    let size: String?
    let modifiedTime: String?
    let md5Checksum: String?
    let thumbnailLink: String?
    var category: String?
    var description: String?
    var isEPUB: Bool { name.lowercased().hasSuffix(".epub") }
    var displayName: String { (name as NSString).deletingPathExtension }
}

struct CREACommunityReading: Decodable {
    let driveFileId: String
    let percent: Int
}
struct CREACommunityProfile: Decodable, Identifiable {
    let userId: String
    let nickname: String
    let active: Bool
    let readings: [CREACommunityReading]
    var id: String { userId }
}
private struct DriveListResponse: Decodable {
    let files: [CREARemoteBook]
    let nextPageToken: String?
}

@MainActor
final class CREADriveCatalog: ObservableObject {
    @Published private(set) var books: [CREARemoteBook] = []
    @Published private(set) var community: [CREACommunityProfile] = []
    @Published private(set) var isLoading = false
    @Published private(set) var downloading: Set<String> = []
    @Published var errorMessage: String?
    @Published private(set) var communityPublishing = false
    @Published private(set) var communityStatus = ""
    @Published var lastSynced: Date?
    private let cacheKey = "crea.ios.drive.catalog.v1"
    private let folderName = "CREA Reader"
    private var folderID: String?
    private var metadataCache: [String: CREABookMetadata] = [:]
    private let api = "https://www.googleapis.com/drive/v3/files"

    init() {
        if let data = UserDefaults.standard.data(forKey: cacheKey),
           let cached = try? JSONDecoder().decode([CREARemoteBook].self, from: data) {
            books = cached
        }
    }

    private func token() async throws -> String {
        guard let user = GIDSignIn.sharedInstance.currentUser else {
            throw failure("Primero inicia sesión con Google desde Ajustes.")
        }
        return try await withCheckedThrowingContinuation { continuation in
            user.refreshTokensIfNeeded { refreshed, error in
                if let error { continuation.resume(throwing: error) }
                else if let token = refreshed?.accessToken.tokenString {
                    continuation.resume(returning: token)
                } else { continuation.resume(throwing: self.failure("No se obtuvo el token de Google.")) }
            }
        }
    }

    private func failure(_ reason: String) -> NSError {
        NSError(domain: "CREADrive", code: 1, userInfo: [NSLocalizedDescriptionKey: reason])
    }

    private func request(_ url: URL, token: String) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw failure("Google Drive devolvió un error. Verifica los permisos de la cuenta.")
        }
        return data
    }

    private func list(query: String, fields: String, token: String) async throws -> [CREARemoteBook] {
        var all: [CREARemoteBook] = []
        var next: String?
        repeat {
            var components = URLComponents(string: api)!
            var items = [
                URLQueryItem(name: "q", value: query),
                URLQueryItem(name: "fields", value: "nextPageToken,files(" + fields + ")"),
                URLQueryItem(name: "pageSize", value: "1000"),
                URLQueryItem(name: "supportsAllDrives", value: "false")
            ]
            if let next { items.append(URLQueryItem(name: "pageToken", value: next)) }
            components.queryItems = items
            let data = try await request(components.url!, token: token)
            let response = try JSONDecoder().decode(DriveListResponse.self, from: data)
            all.append(contentsOf: response.files)
            next = response.nextPageToken
        } while next != nil
        return all
    }

    func synchronize() async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let access = try await token()
            let folders = try await list(query: "name = '\(folderName)' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                                         fields: "id,name,mimeType", token: access)
            guard let folder = folders.first else {
                throw failure("No existe la carpeta CREA Reader en esta cuenta de Google.")
            }
            folderID = folder.id
            var pending: [(id: String, category: String?)] = [(folder.id, nil)]
            var visited = Set<String>()
            var found: [CREARemoteBook] = []
            while !pending.isEmpty {
                let parent = pending.removeFirst()
                guard visited.insert(parent.id).inserted else { continue }
                let children = try await list(query: "'\(parent.id)' in parents and trashed = false",
                                              fields: "id,name,mimeType,size,modifiedTime,md5Checksum,thumbnailLink,description", token: access)
                for file in children {
                    if file.mimeType == "application/vnd.google-apps.folder" {
                        pending.append((file.id, parent.category ?? file.name))
                    } else if file.name.lowercased().hasSuffix(".epub") || file.name.lowercased().hasSuffix(".pdf") {
                        var book = file
                        book.category = parent.category
                        found.append(book)
                    }
                }
            }
            books = found.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            UserDefaults.standard.set(try JSONEncoder().encode(books), forKey: cacheKey)
            lastSynced = Date()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadCommunity() async {
        guard let folderID else {
            await synchronize()
            if self.folderID == nil { return }
            await loadCommunity()
            return
        }
        do {
            let access = try await token()
            let files = try await list(query: "'\(folderID)' in parents and name contains 'crea-lector-' and trashed = false",
                                       fields: "id,name,mimeType", token: access)
            var members: [CREACommunityProfile] = []
            for file in files.prefix(100) {
                guard file.name.hasPrefix("crea-lector-") && file.name.hasSuffix(".json") else { continue }
                var url = URLComponents(string: api + "/" + file.id)!
                url.queryItems = [URLQueryItem(name: "alt", value: "media")]
                if let data = try? await request(url.url!, token: access),
                   let profile = try? JSONDecoder().decode(CREACommunityProfile.self, from: data),
                   profile.active && !profile.nickname.isEmpty &&
                   profile.userId != UserDefaults.standard.string(forKey: "crea.reader.syncDeviceID") {
                    members.append(profile)
                }
            }
            community = members
        } catch { errorMessage = error.localizedDescription }
    }

    /// Create/update the same public community profile Android uses.
    /// Only called after explicit opt-in and the separate Drive write permission.
    func publishCommunity(enabled: Bool, nickname: String, library: LocalLibrary) async -> Bool {
        guard !communityPublishing else { return false }
        communityPublishing = true
        defer { communityPublishing = false }
        do {
            guard GIDSignIn.sharedInstance.currentUser?.grantedScopes?
                .contains("https://www.googleapis.com/auth/drive") == true else {
                throw failure("Para compartir lecturas, autoriza el permiso adicional de Google Drive.")
            }
            let cleanName = String(nickname.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
            guard !enabled || cleanName.count >= 2 else {
                throw failure("Escribe un apodo de al menos 2 caracteres para compartir.")
            }
            if folderID == nil { await synchronize() }
            guard let folderID else { throw failure("No encuentro la carpeta CREA Reader en Drive.") }
            let access = try await token()
            var deviceID = UserDefaults.standard.string(forKey: "crea.reader.syncDeviceID")
            if deviceID == nil {
                deviceID = UUID().uuidString
                UserDefaults.standard.set(deviceID, forKey: "crea.reader.syncDeviceID")
            }
            guard let stableID = deviceID else { throw failure("No se pudo obtener el ID local.") }
            let fileName = "crea-lector-" + stableID + ".json"
            let known = try await list(query: "'\(folderID)' in parents and name = '\(fileName)' and trashed = false",
                                       fields: "id,name,mimeType", token: access)
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let readings: [[String: Any]] = enabled ? library.books.compactMap { book in
                guard let remoteID = book.driveFileID,
                      book.progress > 0, book.progressUpdatedAt != nil else { return nil }
                return [
                    "driveFileId": remoteID,
                    "percent": Int(max(0, min(100, (book.progress * 100).rounded()))),
                    "updatedAt": Int64((book.progressUpdatedAt ?? Date()).timeIntervalSince1970 * 1000)
                ]
            } : []
            let profile: [String: Any] = [
                "userId": stableID,
                "nickname": enabled ? cleanName : "",
                "active": enabled,
                "updatedAt": now,
                "readings": readings
            ]
            let bytes = try JSONSerialization.data(withJSONObject: profile)
            let response: HTTPURLResponse?
            if let existing = known.first {
                var url = URLComponents(string: "https://www.googleapis.com/upload/drive/v3/files/" + existing.id)!
                url.queryItems = [URLQueryItem(name: "uploadType", value: "media")]
                var call = URLRequest(url: url.url!)
                call.httpMethod = "PATCH"
                call.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
                call.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
                let (_, result) = try await URLSession.shared.upload(for: call, from: bytes)
                response = result as? HTTPURLResponse
            } else {
                // Revoking before any publish should not create a public file.
                if !enabled {
                    communityStatus = "No hay ninguna lectura publicada desde este iPhone."
                    return true
                }
                let boundary = "CREA" + UUID().uuidString
                let metadata: [String: Any] = [
                    "name": fileName, "mimeType": "application/json", "parents": [folderID]
                ]
                let metadataBytes = try JSONSerialization.data(withJSONObject: metadata)
                var data = Data()
                data.append(Data(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").utf8))
                data.append(metadataBytes)
                data.append(Data(("\r\n--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").utf8))
                data.append(bytes)
                data.append(Data(("\r\n--" + boundary + "--\r\n").utf8))
                var url = URLComponents(string: "https://www.googleapis.com/upload/drive/v3/files")!
                url.queryItems = [URLQueryItem(name: "uploadType", value: "multipart"),
                                  URLQueryItem(name: "fields", value: "id,name")]
                var call = URLRequest(url: url.url!)
                call.httpMethod = "POST"
                call.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
                call.setValue("multipart/related; boundary=" + boundary, forHTTPHeaderField: "Content-Type")
                let (_, result) = try await URLSession.shared.upload(for: call, from: data)
                response = result as? HTTPURLResponse
            }
            guard let response, (200...299).contains(response.statusCode) else {
                throw failure("Drive rechazó el cambio de privacidad (HTTP \(response?.statusCode ?? 0)).")
            }
            communityStatus = enabled ? "Se comparten \(readings.count) lecturas con tu apodo."
                                     : "Tus lecturas dejaron de compartirse."
            return true
        } catch {
            communityStatus = error.localizedDescription
            return false
        }
    }

    private func coverCacheURL(for book: CREARemoteBook) -> URL {
        let cacheRoot = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CREAReaderCovers", isDirectory: true)
        let key = book.id + ":" + (book.modifiedTime ?? "") + ":" + (book.md5Checksum ?? "")
        let hex = SHA256.hash(data: Data(key.utf8))
            .map { String(format: "%02x", $0) }.joined()
        return cacheRoot.appendingPathComponent(hex + ".img")
    }

    private func range(book: CREARemoteBook, start: Int64, end: Int64, token access: String) async -> Data? {
        guard start >= 0, end >= start, end - start < 4_300_000 else { return nil }
        var components = URLComponents(string: api + "/" + book.id)!
        components.queryItems = [URLQueryItem(name: "alt", value: "media")]
        var call = URLRequest(url: components.url!)
        call.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        call.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
        call.timeoutInterval = 15
        guard let (data, response) = try? await URLSession.shared.data(for: call),
              let http = response as? HTTPURLResponse,
              http.statusCode == 206,
              data.count == Int(end - start + 1) else { return nil }
        return data
    }

    /// Use Drive's thumbnail when available, otherwise read only EPUB ZIP ranges
    /// (container, package metadata and cover entry), never download for offline use.
    func cover(for book: CREARemoteBook) async -> Data? {
        let cache = coverCacheURL(for: book)
        if let saved = try? Data(contentsOf: cache), UIImage(data: saved) != nil { return saved }
        do {
            let access = try await token()
            var candidate: Data?
            if let link = book.thumbnailLink,
               let url = URL(string: link),
               url.scheme == "https",
               let host = url.host?.lowercased(),
               host == "googleapis.com" || host.hasSuffix(".googleapis.com") ||
               host == "googleusercontent.com" || host.hasSuffix(".googleusercontent.com") ||
               host == "google.com" || host.hasSuffix(".google.com") {
                let bytes = try? await request(url, token: access)
                if let bytes, bytes.count < 4_000_000, UIImage(data: bytes) != nil { candidate = bytes }
            }
            if candidate == nil,
               book.isEPUB, let bytes = book.size.flatMap(Int64.init),
               bytes > 50, bytes <= 300_000_000 {
                candidate = await CREARemoteEPUBCover.extract(size: bytes) { start, end in
                    await self.range(book: book, start: start, end: end, token: access)
                }
            }
            guard let candidate, UIImage(data: candidate) != nil else { return nil }
            try? FileManager.default.createDirectory(at: cache.deletingLastPathComponent(),
                                                     withIntermediateDirectories: true)
            try? candidate.write(to: cache, options: .atomic)
            return candidate
        } catch { return nil }
    }

    /// Authentic book metadata from the EPUB package; nothing is inferred from its filename.
    /// Missing synopsis and page counts remain missing instead of being invented.
    func inspect(_ book: CREARemoteBook) async -> CREABookMetadata {
        let cacheKey = book.id + ":" + (book.modifiedTime ?? "") + ":" + (book.md5Checksum ?? "")
        if let previous = metadataCache[cacheKey] { return previous }
        var details = CREABookMetadata(
            title: nil, author: nil, summary: book.description, publisher: nil,
            language: nil, published: nil, editionPages: nil, excerpt: nil
        )
        if book.isEPUB, let size = book.size.flatMap(Int64.init),
           size > 50, size <= 300_000_000 {
            do {
                let access = try await token()
                if let found = await CREARemoteEPUBCover.inspect(size: size, readRange: { start, end in
                    await self.range(book: book, start: start, end: end, token: access)
                }) {
                    details = found
                    if (details.summary ?? "").isEmpty { details.summary = book.description }
                }
            } catch {
                // The basic Google Drive description is still usable without EPUB metadata.
            }
        }
        metadataCache[cacheKey] = details
        return details
    }

    /// Fetch a temporary, authenticated reading copy. It never enters My Library.
    /// The caller must delete this file when the reader or preview closes.
    func temporaryReadingCopy(_ book: CREARemoteBook) async throws -> URL {
        let upperLimit: Int64 = 250 * 1024 * 1024
        if let size = book.size.flatMap(Int64.init), size > upperLimit {
            throw failure("El archivo supera 250 MB y no puede abrirse temporalmente.")
        }
        let access = try await token()
        var endpoint = URLComponents(string: api + "/" + book.id)!
        endpoint.queryItems = [URLQueryItem(name: "alt", value: "media")]
        guard let url = endpoint.url else { throw failure("Enlace del libro no válido.") }
        var call = URLRequest(url: url)
        call.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        call.timeoutInterval = 120
        let (download, response) = try await URLSession.shared.download(for: call)
        guard let http = response as? HTTPURLResponse,
              (200...299).contains(http.statusCode) else {
            throw failure("No se pudo abrir el libro desde Google Drive. Revisa la conexión.")
        }
        let attributes = try FileManager.default.attributesOfItem(atPath: download.path)
        let bytes = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        guard bytes > 0, bytes <= upperLimit else {
            throw failure("El archivo está vacío o supera el límite de lectura temporal.")
        }
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("CREAReaderOnline", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let destination = root.appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(book.isEPUB ? "epub" : "pdf")
        try FileManager.default.moveItem(at: download, to: destination)
        return destination
    }

    /// Read the existing Android manifest from the app-private Drive folder.
    /// A missing manifest is not interpreted as an empty library.
    func loadPrivateProgressManifest() async throws -> (document: [String: Any], fileID: String)? {
        guard let user = GIDSignIn.sharedInstance.currentUser,
              user.grantedScopes?.contains("https://www.googleapis.com/auth/drive.appdata") == true else {
            throw failure("Activa el permiso para sincronizar el progreso desde Ajustes.")
        }
        let access = try await token()
        var listing = URLComponents(string: api)!
        listing.queryItems = [
            URLQueryItem(name: "spaces", value: "appDataFolder"),
            URLQueryItem(name: "q", value: "name = 'crea-reader-library-v2.json' and trashed = false"),
            URLQueryItem(name: "fields", value: "nextPageToken,files(id,name)"),
            URLQueryItem(name: "pageSize", value: "100")
        ]
        let response = try await request(listing.url!, token: access)
        guard let json = try JSONSerialization.jsonObject(with: response) as? [String: Any],
              let files = json["files"] as? [[String: Any]],
              let fileID = files.first?["id"] as? String else { return nil }
        var media = URLComponents(string: api + "/" + fileID)!
        media.queryItems = [URLQueryItem(name: "alt", value: "media")]
        let bytes = try await request(media.url!, token: access)
        guard let document = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              document["books"] is [[String: Any]] else {
            throw failure("El manifiesto privado de Android no contiene una lista válida de libros.")
        }
        return (document, fileID)
    }

    /// Update only the existing private Android manifest. Never create an empty
    /// replacement which could hide books when Android has not synchronized yet.
    func savePrivateProgressManifest(_ document: [String: Any], fileID: String) async throws {
        guard let user = GIDSignIn.sharedInstance.currentUser,
              user.grantedScopes?.contains("https://www.googleapis.com/auth/drive.appdata") == true else {
            throw failure("Falta el permiso privado de Drive. Habilítalo desde Ajustes.")
        }
        guard JSONSerialization.isValidJSONObject(document) else {
            throw failure("Los datos de progreso no son un JSON válido.")
        }
        let access = try await token()
        let body = try JSONSerialization.data(withJSONObject: document, options: [.sortedKeys])
        var target = URLComponents(string: "https://www.googleapis.com/upload/drive/v3/files/" + fileID)!
        target.queryItems = [URLQueryItem(name: "uploadType", value: "media")]
        var call = URLRequest(url: target.url!)
        call.httpMethod = "PATCH"
        call.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        call.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        let (_, response) = try await URLSession.shared.upload(for: call, from: body)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            throw failure("Drive rechazó la escritura del progreso (HTTP \(code)).")
        }
    }

    func download(_ book: CREARemoteBook, into library: LocalLibrary) async {
        guard !downloading.contains(book.id) else { return }
        downloading.insert(book.id)
        defer { downloading.remove(book.id) }
        do {
            let access = try await token()
            var parts = URLComponents(string: api + "/" + book.id)!
            parts.queryItems = [URLQueryItem(name: "alt", value: "media")]
            let data = try await request(parts.url!, token: access)
            guard !data.isEmpty else { throw failure("El libro descargado está vacío.") }
            let ext = book.isEPUB ? "epub" : "pdf"
            let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + ext)
            defer { try? FileManager.default.removeItem(at: temp) }
            try data.write(to: temp, options: .atomic)
            try library.add(url: temp, displayName: book.displayName, driveFileID: book.id)
            errorMessage = nil
        } catch { errorMessage = error.localizedDescription }
    }
}

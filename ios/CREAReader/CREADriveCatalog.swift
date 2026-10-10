import Foundation
import GoogleSignIn

struct CREARemoteBook: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let mimeType: String
    let size: String?
    let modifiedTime: String?
    let md5Checksum: String?
    let thumbnailLink: String?
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
    @Published var lastSynced: Date?
    private let cacheKey = "crea.ios.drive.catalog.v1"
    private let folderName = "CREA Reader"
    private var folderID: String?
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
            var pending = [folder.id]
            var visited = Set<String>()
            var found: [CREARemoteBook] = []
            while !pending.isEmpty {
                let parent = pending.removeFirst()
                guard visited.insert(parent).inserted else { continue }
                let children = try await list(query: "'\(parent)' in parents and trashed = false",
                                              fields: "id,name,mimeType,size,modifiedTime,md5Checksum,thumbnailLink", token: access)
                for file in children {
                    if file.mimeType == "application/vnd.google-apps.folder" { pending.append(file.id) }
                    else if file.name.lowercased().hasSuffix(".epub") || file.name.lowercased().hasSuffix(".pdf") { found.append(file) }
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
                   profile.active && !profile.nickname.isEmpty {
                    members.append(profile)
                }
            }
            community = members
        } catch { errorMessage = error.localizedDescription }
    }

    func cover(for book: CREARemoteBook) async -> Data? {
        guard let link = book.thumbnailLink, let url = URL(string: link) else { return nil }
        do {
            let access = try await token()
            return try await request(url, token: access)
        } catch { return nil }
    }

    /// Read the existing Android manifest from the app-private Drive folder.
    /// A missing manifest is not interpreted as an empty library.
    func loadPrivateProgressManifest() async throws -> (document: [String: Any], fileID: String)? {
        guard let user = GIDSignIn.sharedInstance.currentUser,
              user.grantedScopes.contains("https://www.googleapis.com/auth/drive.appdata") else {
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
              user.grantedScopes.contains("https://www.googleapis.com/auth/drive.appdata") else {
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

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

private struct DriveListResponse: Decodable {
    let files: [CREARemoteBook]
    let nextPageToken: String?
}

@MainActor
final class CREADriveCatalog: ObservableObject {
    @Published private(set) var books: [CREARemoteBook] = []
    @Published private(set) var isLoading = false
    @Published private(set) var downloading: Set<String> = []
    @Published var errorMessage: String?
    @Published var lastSynced: Date?
    private let cacheKey = "crea.ios.drive.catalog.v1"
    private let folderName = "CREA Reader"
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
            try library.add(url: temp, displayName: book.displayName)
            errorMessage = nil
        } catch { errorMessage = error.localizedDescription }
    }
}

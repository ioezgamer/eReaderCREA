import Foundation
import UIKit
import CryptoKit
import GoogleSignIn
import Combine

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
    var appProperties: [String: String]?
    var ownedByMe: Bool?
    var isEPUB: Bool { name.lowercased().hasSuffix(".epub") }
    var displayName: String { CREADriveNames.describe(name, createdByApp: appProperties?["kind"] == "book").title }
    var authorFromName: String? { CREADriveNames.describe(name, createdByApp: appProperties?["kind"] == "book").author }
    var versionKey: String { md5Checksum ?? String(CREACatalogRules.milliseconds(modifiedTime)) }
}

struct CREACommunityReading: Decodable {
    let driveFileId: String
    let percent: Int
    let updatedAt: Int64?
    enum CodingKeys: String, CodingKey { case driveFileId, percent, updatedAt }
    init(driveFileId: String, percent: Int, updatedAt: Int64? = nil) { self.driveFileId = driveFileId; self.percent = percent; self.updatedAt = updatedAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        driveFileId = try c.decode(String.self, forKey: .driveFileId); percent = try c.decodeIfPresent(Int.self, forKey: .percent) ?? 0; updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt)
    }
}
struct CREACommunityProfile: Decodable, Identifiable {
    let userId: String
    let nickname: String
    let active: Bool
    let readings: [CREACommunityReading]
    let updatedAt: Int64?
    var id: String { userId }
    enum CodingKeys: String, CodingKey { case userId, nickname, active, readings, updatedAt }
    init(userId: String, nickname: String, active: Bool, readings: [CREACommunityReading], updatedAt: Int64? = nil) { self.userId = userId; self.nickname = nickname; self.active = active; self.readings = readings; self.updatedAt = updatedAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        userId = try c.decode(String.self, forKey: .userId); nickname = try c.decodeIfPresent(String.self, forKey: .nickname) ?? ""
        active = try c.decodeIfPresent(Bool.self, forKey: .active) ?? false; readings = try c.decodeIfPresent([CREACommunityReading].self, forKey: .readings) ?? []
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt)
    }
}
private struct DriveListResponse: Decodable {
    let files: [CREARemoteBook]
    let nextPageToken: String?
}

struct CREASharedInsightIndex: Codable {
    struct Entry: Codable {
        let driveFileId: String
        let version: String?
        let title: String
        let author: String?
        let summary: String
        let categories: [String]
        let level: String
        let confidence: String
        var info: CREAAIBookInfo { CREAAIBookInfo(title: title, author: author, summary: summary, categories: categories, level: level, confidence: confidence) }
        enum CodingKeys: String, CodingKey { case driveFileId, version, title, author, summary, categories, level, confidence }
        init(book: CREARemoteBook, info: CREAAIBookInfo) {
            driveFileId = book.id; version = book.versionKey; title = info.title; author = info.author; summary = info.summary; categories = info.categories; level = info.level; confidence = info.confidence
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            driveFileId = try c.decode(String.self, forKey: .driveFileId); version = try c.decodeIfPresent(String.self, forKey: .version)
            title = try c.decode(String.self, forKey: .title); author = try c.decodeIfPresent(String.self, forKey: .author)
            summary = try c.decode(String.self, forKey: .summary); categories = try c.decodeIfPresent([String].self, forKey: .categories) ?? []
            level = try c.decodeIfPresent(String.self, forKey: .level) ?? "general"; confidence = try c.decodeIfPresent(String.self, forKey: .confidence) ?? "medium"
        }
    }
    let schema: Int
    let books: [Entry]
    enum CodingKeys: String, CodingKey { case schema, books }
    init(books: [Entry]) { schema = 1; self.books = books }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decodeIfPresent(Int.self, forKey: .schema) ?? 1; books = try c.decodeIfPresent([Entry].self, forKey: .books) ?? []
    }
}

@MainActor
final class CREADriveCatalog: ObservableObject {
    @Published private(set) var books: [CREARemoteBook] = []
    @Published private(set) var community: [CREACommunityProfile] = []
    @Published private(set) var isLoading = false
    @Published private(set) var downloading: Set<String> = []
    @Published private(set) var uploading: Set<UUID> = []
    @Published private(set) var sharedInsights: [String: CREAAIBookInfo] = [:]
    @Published private(set) var insightVersions: [String: String] = [:]
    @Published private(set) var generatingInsights = false
    @Published var insightStatus: String?
    private var sharedIndexVersion: String?
    private var pendingInsightWrites: [CREASharedInsightIndex.Entry] = []
    private var loadedInsightIndex = CREASharedInsightIndex(books: [])
    private var transferObserver: AnyCancellable?
    @Published var errorMessage: String?
    @Published private(set) var communityPublishing = false
    @Published private(set) var communityStatus = ""
    @Published var lastSynced: Date?
    @Published private(set) var folders: [String: String] = [:]
    var needsAutoSync: Bool { guard let lastSynced else { return true }; let age = Date().timeIntervalSince(lastSynced); return age < 0 || age >= 900 }
    private var syncWaiters: [CheckedContinuation<Void, Never>] = []
    private let cacheKey = "crea.ios.drive.catalog.v1"
    private let folderName = "CREA Reader"
    private var folderID: String?
    private var metadataCache: [String: CREABookMetadata] = [:]
    private var manifestETags: [String: String] = [:]
    private var accountID: String?
    private var scopedCacheKey: String { cacheKey + "." + (accountID ?? "signed-out") }
    private let api = "https://www.googleapis.com/drive/v3/files"

    init(cleanupTemporaryFiles: Bool = true) {
        // Remove any orphaned reading copies left by an app crash or forced quit.
        let leftovers = FileManager.default.temporaryDirectory
            .appendingPathComponent("CREAReaderOnline", isDirectory: true)
        if cleanupTemporaryFiles { try? FileManager.default.removeItem(at: leftovers) }
        transferObserver = CREABackgroundDownloads.shared.$records.sink { [weak self] records in
            let owner = GIDSignIn.sharedInstance.currentUser?.userID
            self?.downloading = Set(records.values.filter { $0.ownerID == owner && $0.active }.map { $0.book.id })
        }
    }

    func resetForCurrentAccount() {
        let current = GIDSignIn.sharedInstance.currentUser?.userID
        guard current != accountID else { return }
        accountID = current; folderID = nil; metadataCache = [:]; manifestETags = [:]
        folders = [:]
        books = []; community = []; sharedInsights = [:]; lastSynced = nil
        insightVersions = [:]; sharedIndexVersion = nil; pendingInsightWrites = []; loadedInsightIndex = CREASharedInsightIndex(books: [])
        if current != nil, let data = UserDefaults.standard.data(forKey: scopedCacheKey + ".pendingInsights"),
           let pending = try? JSONDecoder().decode([CREASharedInsightIndex.Entry].self, from: data) { pendingInsightWrites = pending }
        errorMessage = nil; communityStatus = ""
        if current != nil, let data = UserDefaults.standard.data(forKey: scopedCacheKey),
           let cached = try? JSONDecoder().decode([CREARemoteBook].self, from: data) { books = cached }
        if current != nil, let data = UserDefaults.standard.data(forKey: scopedCacheKey + ".insights"),
           let cached = try? JSONDecoder().decode(CREASharedInsightIndex.self, from: data) { applyInsights(cached) }
        sharedIndexVersion = UserDefaults.standard.string(forKey: scopedCacheKey + ".insightVersion")
        folders = UserDefaults.standard.dictionary(forKey: scopedCacheKey + ".folders") as? [String: String] ?? [:]
        folderID = folders[""]
        lastSynced = UserDefaults.standard.object(forKey: scopedCacheKey + ".lastSync") as? Date
    }

    private func token() async throws -> String {
        resetForCurrentAccount()
        guard let user = GIDSignIn.sharedInstance.currentUser else {
            throw failure("Primero inicia sesión con Google desde Ajustes.")
        }
        return try await withCheckedThrowingContinuation { continuation in
            user.refreshTokensIfNeeded { refreshed, error in
                if let error { continuation.resume(throwing: error) }
                else if let token = refreshed?.accessToken.tokenString, refreshed?.userID == GIDSignIn.sharedInstance.currentUser?.userID {
                    continuation.resume(returning: token)
                } else { continuation.resume(throwing: self.failure("No se obtuvo el token de Google.")) }
            }
        }
    }

    private func failure(_ reason: String) -> NSError {
        NSError(domain: "CREADrive", code: 1, userInfo: [NSLocalizedDescriptionKey: reason])
    }

    private func request(_ url: URL, token: String) async throws -> Data {
        let owner = GIDSignIn.sharedInstance.currentUser?.userID
        var request = URLRequest(url: url)
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard owner != nil, owner == GIDSignIn.sharedInstance.currentUser?.userID else { throw failure("La cuenta cambió durante la operación. Reintenta con la cuenta actual.") }
        if url.query?.contains("alt=media") == true, let http = response as? HTTPURLResponse, let etag = http.value(forHTTPHeaderField: "ETag") { manifestETags[url.lastPathComponent] = etag }
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw failure("Google Drive devolvió un error. Verifica los permisos de la cuenta.")
        }
        return data
    }

    /// Resumable binary upload; the original book stays local if any step fails.
    func upload(_ book: LocalBook, library: LocalLibrary, categoryPath: String? = nil) async {
        guard uploading.insert(book.id).inserted else { return }
        defer { uploading.remove(book.id) }
        do {
            guard GIDSignIn.sharedInstance.currentUser?.grantedScopes?.contains("https://www.googleapis.com/auth/drive") == true else {
                throw failure("Autoriza la subida a Drive desde Ajustes primero.")
            }
            let access = try await token()
            let owner = GIDSignIn.sharedInstance.currentUser?.userID
            let source = library.url(for: book)
            let hash = try await Task.detached { try CREABookIdentity.hash(source) }.value
            let md5 = try await Task.detached { try CREABookIdentity.md5(source) }.value
            if folderID == nil {
                let folders = try await list(query: "name = 'CREA Reader' and mimeType = 'application/vnd.google-apps.folder' and trashed = false", fields: "id,name,mimeType", token: access)
                folderID = folders.first?.id
                if folderID == nil {
                    var call = URLRequest(url: URL(string: api)!)
                    call.httpMethod = "POST"; call.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
                    call.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    call.httpBody = try JSONSerialization.data(withJSONObject: ["name": folderName, "mimeType": "application/vnd.google-apps.folder"])
                    let (data, response) = try await URLSession.shared.data(for: call)
                    guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                          let object = try JSONSerialization.jsonObject(with: data) as? [String: Any], let id = object["id"] as? String else { throw failure("No se pudo crear la carpeta de biblioteca.") }
                    folderID = id
                }
            }
            guard let folderID else { throw failure("No se encontró la carpeta de biblioteca.") }
            await synchronize()
            guard owner == GIDSignIn.sharedInstance.currentUser?.userID else { throw failure("La cuenta cambió durante la subida.") }
            if let linked = books.first(where: { remote in
                (remote.isEPUB ? "EPUB" : "PDF") == book.kind && (remote.md5Checksum == md5 || remote.appProperties?["sha256"] == hash || CREADuplicateRules.sameNames(book.name, book.author, remote.displayName, remote.authorFromName))
            }) {
                library.update(book) { $0.driveFileID = linked.id; $0.contentHash = hash; $0.driveAccount = GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased(); $0.category = linked.category }
                errorMessage = "Este archivo o publicación ya está en Drive: " + linked.displayName
                return
            }
            let parent: String
            if let categoryPath, !categoryPath.isEmpty {
                guard let target = folders[categoryPath] else { throw failure("La carpeta seleccionada no existe. Actualiza el catálogo.") }
                parent = target
            } else { parent = folderID }
            let mime = book.kind == "PDF" ? "application/pdf" : "application/epub+zip"
            let size = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            let fullName = [book.name, book.author].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " - ")
            let clean = fullName.components(separatedBy: CharacterSet(charactersIn: "/\\:*?\"<>|").union(.controlCharacters)).joined(separator: " ")
            var start = URLRequest(url: URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id")!)
            start.httpMethod = "POST"
            start.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
            start.setValue("application/json", forHTTPHeaderField: "Content-Type")
            start.setValue(mime, forHTTPHeaderField: "X-Upload-Content-Type")
            start.setValue(String(size), forHTTPHeaderField: "X-Upload-Content-Length")
            start.httpBody = try JSONSerialization.data(withJSONObject: ["name": String(clean.prefix(120)) + "." + book.kind.lowercased(), "parents": [parent], "appProperties": ["kind": "book", "sha256": hash]])
            let (_, response) = try await URLSession.shared.data(for: start)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let location = http.value(forHTTPHeaderField: "Location"), let session = URL(string: location),
                  session.scheme == "https", session.host == "www.googleapis.com" else { throw failure("Drive no inició la subida.") }
            var put = URLRequest(url: session)
            put.httpMethod = "PUT"; put.setValue(mime, forHTTPHeaderField: "Content-Type")
            put.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
            let (data, result) = try await URLSession.shared.upload(for: put, fromFile: source)
            guard owner == GIDSignIn.sharedInstance.currentUser?.userID else { throw failure("La cuenta cambió durante la subida; actualiza el catálogo de la cuenta original.") }
            guard let uploaded = result as? HTTPURLResponse, (200...299).contains(uploaded.statusCode),
                  let object = try JSONSerialization.jsonObject(with: data) as? [String: Any], let id = object["id"] as? String else { throw failure("No se completó la subida. Tu libro local está guardado.") }
            library.update(book) { $0.driveFileID = id; $0.contentHash = hash; $0.driveAccount = GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased(); $0.category = categoryPath }
            await synchronize()
        } catch { errorMessage = error.localizedDescription }
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
        if isLoading { await withCheckedContinuation { syncWaiters.append($0) }; return }
        isLoading = true
        defer { isLoading = false; let waiters = syncWaiters; syncWaiters = []; waiters.forEach { $0.resume() } }
        do {
            let access = try await token()
            let folders = try await list(query: "name = '\(folderName)' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                                         fields: "id,name,mimeType,ownedByMe", token: access)
            guard let folder = folders.sorted(by: { ($0.ownedByMe == true ? 1 : 0) > ($1.ownedByMe == true ? 1 : 0) }).first else {
                throw failure("No existe la carpeta CREA Reader en esta cuenta de Google.")
            }
            folderID = folder.id
            var pending: [(id: String, category: String?)] = [(folder.id, nil)]
            var visited = Set<String>()
            var found: [CREARemoteBook] = []
            var folderPaths: [String: String] = ["": folder.id]
            while !pending.isEmpty {
                let parent = pending.removeFirst()
                guard visited.insert(parent.id).inserted else { continue }
                guard visited.count <= 500 else { throw failure("La biblioteca tiene demasiadas subcarpetas.") }
                let children = try await list(query: "'\(parent.id)' in parents and trashed = false",
                                              fields: "id,name,mimeType,size,modifiedTime,md5Checksum,thumbnailLink,description,appProperties", token: access)
                for file in children {
                    if file.mimeType == "application/vnd.google-apps.folder" {
                        let path = CREACategoryPaths.join(parent.category, file.name)
                        pending.append((file.id, path)); folderPaths[path] = file.id
                    } else if file.name.lowercased().hasSuffix(".epub") || file.name.lowercased().hasSuffix(".pdf") {
                        var book = file
                        book.category = parent.category
                        found.append(book)
                    }
                }
            }
            books = found.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            self.folders = folderPaths
            UserDefaults.standard.set(folderPaths, forKey: scopedCacheKey + ".folders")
            for local in LocalLibrary.shared.books {
                if let remote = found.first(where: { $0.id == local.driveFileID }), local.driveAccount == nil || local.driveAccount == GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased() {
                    LocalLibrary.shared.update(local) { $0.category = remote.category }
                    if local.remoteMD5 == nil, remote.md5Checksum != nil {
                        let url = LocalLibrary.shared.url(for: local)
                        let hash = try? await Task.detached { try CREABookIdentity.md5(url) }.value
                        LocalLibrary.shared.update(local) { $0.remoteMD5 = hash }
                    }
                }
            }
            UserDefaults.standard.set(try JSONEncoder().encode(books), forKey: scopedCacheKey)
            lastSynced = Date()
            UserDefaults.standard.set(lastSynced, forKey: scopedCacheKey + ".lastSync")
            errorMessage = nil
            await loadSharedInsights()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadSharedInsights() async {
        guard let folderID else { return }
        do {
            let access = try await token()
            let files = try await list(query: "'\(folderID)' in parents and name = 'crea-ai-catalog.json' and trashed = false", fields: "id,name,mimeType,modifiedTime,md5Checksum", token: access)
            guard let shared = files.first else { sharedIndexVersion = nil; UserDefaults.standard.removeObject(forKey: scopedCacheKey + ".insightVersion"); applyInsights(CREASharedInsightIndex(books: [])); return }
            let version = shared.modifiedTime ?? shared.md5Checksum
            if version != nil && version == sharedIndexVersion { applyInsights(loadedInsightIndex); return }
            let id = shared.id
            let data = try await request(URL(string: api + "/" + id + "?alt=media")!, token: access)
            guard data.count <= 10_000_000 else { throw failure("El índice de fichas supera el tamaño admitido.") }
            let index = try JSONDecoder().decode(CREASharedInsightIndex.self, from: data)
            guard index.schema == 1 else { throw failure("Versión de fichas compartidas no compatible.") }
            applyInsights(index); sharedIndexVersion = version
            UserDefaults.standard.set(version, forKey: scopedCacheKey + ".insightVersion")
        } catch { errorMessage = "El catálogo está disponible, pero no se pudieron actualizar las fichas compartidas: " + error.localizedDescription }
    }
    private func applyInsights(_ index: CREASharedInsightIndex) {
        var combined = Dictionary(index.books.map { ($0.driveFileId, $0) }, uniquingKeysWith: { _, b in b })
        for pending in pendingInsightWrites where books.first(where: { $0.id == pending.driveFileId })?.versionKey == pending.version { combined[pending.driveFileId] = pending }
        let usable = combined.values.filter { !$0.summary.hasPrefix("(Ejemplo)") }
        loadedInsightIndex = CREASharedInsightIndex(books: usable)
        sharedInsights = Dictionary(usable.map { ($0.driveFileId, $0.info) }, uniquingKeysWith: { _, latest in latest })
        insightVersions = Dictionary(usable.compactMap { entry in entry.version.map { (entry.driveFileId, $0) } }, uniquingKeysWith: { _, latest in latest })
        if let data = try? JSONEncoder().encode(CREASharedInsightIndex(books: usable)) { UserDefaults.standard.set(data, forKey: scopedCacheKey + ".insights") }
    }
    func insight(for book: CREARemoteBook) -> CREAAIBookInfo? { insightVersions[book.id] == book.versionKey ? sharedInsights[book.id] : nil }
    func title(for book: CREARemoteBook) -> String { insight(for: book)?.title ?? book.displayName }
    func categories(for book: CREARemoteBook) -> [String] { Array(Set((book.category.map { [$0] } ?? []) + (insight(for: book)?.categories ?? []))).sorted() }
    func needsUpdate(_ book: CREARemoteBook, local: LocalBook?) -> Bool {
        guard let local else { return false }
        if let md5 = book.md5Checksum, let saved = local.remoteMD5 { return md5 != saved }
        if let saved = local.remoteModifiedTime, let current = book.modifiedTime { return saved != current }
        return false
    }
    func synchronizeInsights(ai: CREAReaderAI, generate: Bool) async {
        guard generate, !generatingInsights else { return }
        generatingInsights = true; defer { generatingInsights = false }
        if folderID == nil { await synchronize() }
        guard folderID != nil else { return }
        await ai.refresh()
        guard ai.canUseForCatalog, GIDSignIn.sharedInstance.currentUser?.grantedScopes?.contains("https://www.googleapis.com/auth/drive") == true else { return }
        let owner = GIDSignIn.sharedInstance.currentUser?.userID
        let pending = books.filter { insight(for: $0) == nil }.prefix(6)
        var created: [CREASharedInsightIndex.Entry] = []
        for book in pending {
            do {
                try Task.checkCancellation()
                guard owner == GIDSignIn.sharedInstance.currentUser?.userID else { return }
                var excerpt: String?
                if book.isEPUB { excerpt = await inspect(book).excerpt }
                let info = try await ai.bookInfo(fileName: book.name, excerpt: excerpt)
                guard !info.summary.hasPrefix("(Ejemplo)"), !info.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
                created.append(CREASharedInsightIndex.Entry(book: book, info: info))
            } catch { insightStatus = error.localizedDescription; break }
        }
        var queued = Dictionary(pendingInsightWrites.map { ($0.driveFileId, $0) }, uniquingKeysWith: { _, b in b })
        for item in created { queued[item.driveFileId] = item }
        pendingInsightWrites = Array(queued.values)
        if let data = try? JSONEncoder().encode(pendingInsightWrites) { UserDefaults.standard.set(data, forKey: scopedCacheKey + ".pendingInsights") }
        guard !pendingInsightWrites.isEmpty, let folderID else { return }
        do {
            let access = try await token()
            guard owner == GIDSignIn.sharedInstance.currentUser?.userID else { return }
            let files = try await list(query: "'\(folderID)' in parents and name = 'crea-ai-catalog.json' and trashed = false", fields: "id,name,mimeType", token: access)
            var entries: [CREASharedInsightIndex.Entry] = []
            if let existing = files.first {
                let bytes = try await request(URL(string: api + "/" + existing.id + "?alt=media")!, token: access)
                let index = try JSONDecoder().decode(CREASharedInsightIndex.self, from: bytes)
                guard index.schema == 1 else { throw failure("El índice de IA tiene una versión no compatible.") }
                entries = index.books
            }
            var merged = Dictionary(entries.map { ($0.driveFileId, $0) }, uniquingKeysWith: { _, b in b })
            for item in pendingInsightWrites where books.first(where: { $0.id == item.driveFileId })?.versionKey == item.version { merged[item.driveFileId] = item }
            let live = Set(books.map { $0.id })
            let index = CREASharedInsightIndex(books: merged.values.filter { live.contains($0.driveFileId) && !$0.summary.hasPrefix("(Ejemplo)") }.sorted { $0.driveFileId < $1.driveFileId })
            try await writeSharedJSON(try JSONEncoder().encode(index), name: "crea-ai-catalog.json", folderID: folderID, fileID: files.first?.id, access: access)
            pendingInsightWrites = []; UserDefaults.standard.removeObject(forKey: scopedCacheKey + ".pendingInsights")
            applyInsights(index); sharedIndexVersion = nil
            insightStatus = "\(created.count) fichas creadas y compartidas"
        } catch {
            applyInsights(loadedInsightIndex)
            insightStatus = "Fichas conservadas para reintentar compartir: " + error.localizedDescription
        }
    }
    private func writeSharedJSON(_ bytes: Data, name: String, folderID: String, fileID: String?, access: String) async throws {
        var request: URLRequest; var body = bytes
        if let fileID {
            request = URLRequest(url: URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(fileID)?uploadType=media")!)
            request.httpMethod = "PATCH"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            if let etag = manifestETags[fileID] { request.setValue(etag, forHTTPHeaderField: "If-Match") }
        } else {
            let boundary = UUID().uuidString
            body = Data("--\(boundary)\r\nContent-Type: application/json\r\n\r\n".utf8)
            body.append(try JSONSerialization.data(withJSONObject: ["name": name, "parents": [folderID], "mimeType": "application/json"]))
            body.append(Data("\r\n--\(boundary)\r\nContent-Type: application/json\r\n\r\n".utf8)); body.append(bytes); body.append(Data("\r\n--\(boundary)--\r\n".utf8))
            request = URLRequest(url: URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")!)
            request.httpMethod = "POST"; request.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        }
        request.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        let (_, response) = try await URLSession.shared.upload(for: request, from: body)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { throw failure("Drive rechazó las fichas compartidas.") }
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
        var read = URLRequest(url: media.url!)
        read.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        let (bytes, mediaResponse) = try await URLSession.shared.data(for: read)
        guard let mediaHTTP = mediaResponse as? HTTPURLResponse, (200...299).contains(mediaHTTP.statusCode) else { throw failure("No se pudo leer el progreso privado.") }
        manifestETags[fileID] = mediaHTTP.value(forHTTPHeaderField: "ETag")
        guard let document = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              document["books"] is [[String: Any]] else {
            throw failure("El manifiesto privado de Android no contiene una lista válida de libros.")
        }
        return (document, fileID)
    }

    /// Update only the existing private Android manifest. Never create an empty
    /// replacement which could hide books when Android has not synchronized yet.
    func savePrivateProgressManifest(_ document: [String: Any], fileID: String?) async throws {
        guard let user = GIDSignIn.sharedInstance.currentUser,
              user.grantedScopes?.contains("https://www.googleapis.com/auth/drive.appdata") == true else {
            throw failure("Falta el permiso privado de Drive. Habilítalo desde Ajustes.")
        }
        guard JSONSerialization.isValidJSONObject(document) else {
            throw failure("Los datos de progreso no son un JSON válido.")
        }
        let access = try await token()
        let body = try JSONSerialization.data(withJSONObject: document, options: [.sortedKeys])
        guard let fileID else {
            // Recheck before first creation; never replace a manifest another device created.
            if try await loadPrivateProgressManifest() != nil { throw failure("Otro dispositivo creó el manifiesto. Vuelve a sincronizar para combinarlo.") }
            let boundary = UUID().uuidString
            var bytes = Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8)
            bytes.append(try JSONSerialization.data(withJSONObject: ["name": "crea-reader-library-v2.json", "parents": ["appDataFolder"]]))
            bytes.append(Data("\r\n--\(boundary)\r\nContent-Type: application/json\r\n\r\n".utf8)); bytes.append(body)
            bytes.append(Data("\r\n--\(boundary)--\r\n".utf8))
            var create = URLRequest(url: URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")!)
            create.httpMethod = "POST"; create.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
            create.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            let (_, response) = try await URLSession.shared.upload(for: create, from: bytes)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { throw failure("No se pudo crear el manifiesto privado.") }
            return
        }
        var target = URLComponents(string: "https://www.googleapis.com/upload/drive/v3/files/" + fileID)!
        target.queryItems = [URLQueryItem(name: "uploadType", value: "media")]
        var call = URLRequest(url: target.url!)
        call.httpMethod = "PATCH"
        call.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        call.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        if let etag = manifestETags[fileID] { call.setValue(etag, forHTTPHeaderField: "If-Match") }
        let (_, response) = try await URLSession.shared.upload(for: call, from: body)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            if code == 412 { throw failure("El progreso cambió en otro dispositivo. Sincroniza de nuevo; se conserva tu avance local.") }
            throw failure("Drive rechazó la escritura del progreso (HTTP \(code)).")
        }
    }

    func download(_ book: CREARemoteBook, into library: LocalLibrary) async {
        do {
            let access = try await token()
            guard let owner = GIDSignIn.sharedInstance.currentUser?.userID else { throw failure("Inicia sesión con Google.") }
            try checkDownloadSpace([book])
            let id = CREABackgroundDownloads.shared.enqueue(book, token: access, ownerID: owner, accountEmail: GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased())
            await CREABackgroundDownloads.shared.wait(id)
            errorMessage = CREABackgroundDownloads.shared.records[id]?.error
        } catch { errorMessage = error.localizedDescription }
    }
    func checkDownloadSpace(_ selection: [CREARemoteBook]) throws {
        let required = try CREATransferValidation.requiredSpace(selection)
        let volume = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        if let available = try? volume.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage, available < required {
            throw failure("No hay espacio para esta selección. Se requieren aproximadamente \(ByteCountFormatter.string(fromByteCount: required, countStyle: .file)).")
        }
    }
    func downloadBatch(_ selection: [CREARemoteBook]) async {
        do {
            try checkDownloadSpace(selection)
            let access = try await token()
            guard let owner = GIDSignIn.sharedInstance.currentUser?.userID else { throw failure("Inicia sesión con Google.") }
            for book in selection {
                _ = CREABackgroundDownloads.shared.enqueue(book, token: access, ownerID: owner, accountEmail: GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased())
            }
            errorMessage = nil
        } catch { errorMessage = error.localizedDescription }
    }
}

import SwiftUI
import PDFKit
import UniformTypeIdentifiers
import GoogleSignIn
import ReadiumShared
import ReadiumStreamer
import UserNotifications

@main
struct CREAReaderApp: App {
    @UIApplicationDelegateAdaptor(CREADownloadAppDelegate.self) private var downloadsDelegate
    init() { CREABackgroundSync.register(); CREABackgroundDownloads.shared.activate() }
    var body: some Scene { WindowGroup { ReaderHomeView() } }
}

enum CREAStyle {
    static let blue = Color(red: 0.14, green: 0.47, blue: 0.88)
    static let pink = Color(red: 0.89, green: 0.28, blue: 0.65)
    static let orange = Color(red: 0.98, green: 0.54, blue: 0.22)
    static let green = Color(red: 0.29, green: 0.70, blue: 0.40)
    static let gradient = LinearGradient(colors: [blue.opacity(0.13), pink.opacity(0.07), orange.opacity(0.06)], startPoint: .topLeading, endPoint: .bottomTrailing)
}

struct LocalBook: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    let filename: String
    let kind: String
    var favorite: Bool = false
    var progress: Double = 0
    var lastOpened: Date? = nil
    var collection: String = ""
    // Optional to preserve decoding of books imported before Drive synchronization.
    var driveFileID: String?
    var readingLocatorJSON: String?
    var progressUpdatedAt: Date?
    var contentHash: String?
    var driveAccount: String?
    var author: String?
    var collections: [String]?
    var importedAt: Date?
    var fileSize: Int?
    var remoteMD5: String?
    var remoteModifiedTime: String?
    var category: String?
    var publicationIdentifier: String?
}

@MainActor final class LocalLibrary: ObservableObject {
    static let shared = LocalLibrary()
    @Published private(set) var books: [LocalBook] = []
    @Published var layoutGrid = true
    @Published private var collectionNames: [String] = []
    private let storeKey = "crea.reader.localBooks"
    private let layoutKey = "crea.reader.layoutGrid"

    init() {
        collectionNames = UserDefaults.standard.stringArray(forKey: "crea.reader.collections") ?? []
        layoutGrid = UserDefaults.standard.object(forKey: layoutKey) as? Bool ?? true
        if let data = UserDefaults.standard.data(forKey: storeKey),
           let saved = try? JSONDecoder().decode([LocalBook].self, from: data) {
            books = saved.filter { FileManager.default.fileExists(atPath: url(for: $0).path) }
        }
        Task {
            for book in books where book.contentHash == nil || book.importedAt == nil || book.fileSize == nil {
                let source = url(for: book)
                let hash: String?
                if let stored = book.contentHash { hash = stored }
                else { hash = try? await Task.detached { try CREABookIdentity.hash(source) }.value }
                let values = try? source.resourceValues(forKeys: [.creationDateKey, .fileSizeKey])
                update(book) { $0.contentHash = hash; $0.importedAt = $0.importedAt ?? values?.creationDate; $0.fileSize = values?.fileSize }
            }
        }
    }

    private var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    func url(for book: LocalBook) -> URL { documents.appendingPathComponent(book.filename) }
    @discardableResult func add(url source: URL, displayName: String? = nil, driveFileID: String? = nil) throws -> LocalBook {
        let ext = source.pathExtension.lowercased()
        let audioFormats = ["mp3", "m4a", "m4b", "aac", "wav", "audiobook"]
        let imageFormats = ["cbz", "jpg", "jpeg", "png", "gif", "webp"]
        guard (["epub", "pdf"] + audioFormats + imageFormats).contains(ext) else {
            throw NSError(domain: "CREAReader", code: 1, userInfo: [NSLocalizedDescriptionKey: "Formato no compatible. Importa EPUB, PDF, audio o imágenes/CBZ."])
        }
        let hasAccess = source.startAccessingSecurityScopedResource()
        defer { if hasAccess { source.stopAccessingSecurityScopedResource() } }
        let name = UUID().uuidString + "." + ext
        let destination = documents.appendingPathComponent(name)
        var committed = false
        defer { if !committed { try? FileManager.default.removeItem(at: destination) } }
        var coordinationError: NSError?
        var copyError: Error?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: source, options: [], error: &coordinationError) { coordinatedURL in
            do { try FileManager.default.copyItem(at: coordinatedURL, to: destination) }
            catch { copyError = error }
        }
        if let issue = coordinationError ?? (copyError as NSError?) {
            try? FileManager.default.removeItem(at: destination)
            throw issue
        }
        guard FileManager.default.fileExists(atPath: destination.path),
              ((try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) > 0 else {
            throw NSError(domain: "CREAReader", code: 2, userInfo: [NSLocalizedDescriptionKey: "No se pudo copiar el libro. Descárgalo desde iCloud primero."])
        }
        let hash = try CREABookIdentity.hash(destination)
        if let existing = books.first(where: { $0.contentHash == hash || (try? CREABookIdentity.hash(url(for: $0))) == hash }) {
            try? FileManager.default.removeItem(at: destination)
            if let driveFileID {
                update(existing) { $0.driveFileID = driveFileID; $0.driveAccount = GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased() }
            }
            return books.first(where: { $0.id == existing.id }) ?? existing
        }
        books.insert(LocalBook(id: UUID(), name: displayName ?? source.deletingPathExtension().lastPathComponent,
                               filename: name, kind: audioFormats.contains(ext) ? "AUDIO" : imageFormats.contains(ext) ? "IMAGEN" : ext.uppercased(), driveFileID: driveFileID, contentHash: hash,
                               driveAccount: driveFileID == nil ? nil : GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased(),
                               importedAt: Date(), fileSize: try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize), at: 0)
        persist()
        committed = true
        if let added = books.first { Task { await enrich(added, preserveTitle: displayName != nil) } }
        return books[0]
    }
    private func enrich(_ book: LocalBook, preserveTitle: Bool) async {
        let source = url(for: book)
        if book.kind == "PDF", let document = PDFDocument(url: source) {
            let title = document.documentAttributes?[PDFDocumentAttribute.titleAttribute] as? String
            let author = document.documentAttributes?[PDFDocumentAttribute.authorAttribute] as? String
            update(book) { if !preserveTitle, let title, !title.isEmpty { $0.name = title }; $0.author = author }
            return
        }
        guard ["EPUB", "AUDIO", "IMAGEN"].contains(book.kind) else { return }
        let http = DefaultHTTPClient(), retriever = AssetRetriever(httpClient: DefaultHTTPClient())
        let opener = PublicationOpener(parser: DefaultPublicationParser(httpClient: http, assetRetriever: retriever, pdfFactory: DefaultPDFDocumentFactory()))
        guard let absolute = source.anyURL.absoluteURL,
              case let .success(asset) = await retriever.retrieve(url: absolute),
              case let .success(publication) = await opener.open(asset: asset, allowUserInteraction: false) else { return }
        update(book) {
            if !preserveTitle, let title = publication.metadata.title, !title.isEmpty { $0.name = title }
            $0.author = publication.metadata.authors.map { $0.name }.joined(separator: ", ")
            $0.publicationIdentifier = publication.metadata.identifier
        }
    }
    func update(_ book: LocalBook, mutate: (inout LocalBook) -> Void) {
        guard let index = books.firstIndex(where: { $0.id == book.id }) else { return }
        mutate(&books[index])
        persist()
    }
    func installRemote(_ remote: CREARemoteBook, file: URL, hash: String, md5: String, accountEmail: String?, annotations: CREAAnnotationStore? = nil) throws {
        if let local = books.first(where: { $0.driveFileID == remote.id && ($0.driveAccount == nil || $0.driveAccount == accountEmail) }) {
            let destination = url(for: local)
            let staged = documents.appendingPathComponent(UUID().uuidString + ".replacement")
            defer { try? FileManager.default.removeItem(at: staged) }
            try FileManager.default.copyItem(at: file, to: staged)
            let oldHash: String
            if let known = local.contentHash { oldHash = known } else { oldHash = try CREABookIdentity.hash(destination) }
            let size = try staged.resourceValues(forKeys: [.fileSizeKey]).fileSize
            _ = try FileManager.default.replaceItemAt(destination, withItemAt: staged)
            (annotations ?? CREAAnnotationStore.shared).preserveForUpdatedBook(from: oldHash, to: hash)
            update(local) { $0.contentHash = hash; $0.fileSize = size; $0.remoteMD5 = md5; $0.remoteModifiedTime = remote.modifiedTime; $0.driveAccount = accountEmail; $0.category = remote.category }
        } else {
            try add(url: file, displayName: remote.displayName, driveFileID: remote.id)
            if let local = books.first(where: { $0.driveFileID == remote.id }) {
                update(local) { $0.remoteMD5 = md5; $0.remoteModifiedTime = remote.modifiedTime; $0.driveAccount = accountEmail; $0.category = remote.category }
            }
        }
    }
    func markFinished(_ book: LocalBook) {
        update(book) { local in
            local.progress = 1; local.progressUpdatedAt = Date()
            if let json = local.readingLocatorJSON, let data = json.data(using: .utf8),
               var locator = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                var locations = locator["locations"] as? [String: Any] ?? [:]
                locations["totalProgression"] = 1.0; locator["locations"] = locations
                if let encoded = try? JSONSerialization.data(withJSONObject: locator), let value = String(data: encoded, encoding: .utf8) { local.readingLocatorJSON = value }
            }
        }
    }
    func setGrid(_ value: Bool) { layoutGrid = value; UserDefaults.standard.set(value, forKey: layoutKey) }
    var collections: [String] { Array(Set(collectionNames + books.flatMap { memberships($0) })).sorted() }
    func memberships(_ book: LocalBook) -> [String] { Array(Set((book.collections ?? []) + (book.collection.isEmpty ? [] : [book.collection]))).sorted() }
    func setMembership(_ book: LocalBook, collection: String, included: Bool) {
        guard let current = books.first(where: { $0.id == book.id }) else { return }
        var names = Set(memberships(current)); if included { names.insert(collection) } else { names.remove(collection) }
        update(book) { $0.collection = ""; $0.collections = names.sorted() }
    }
    func createCollection(_ raw: String) throws {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 40, !collections.contains(where: { $0.localizedCaseInsensitiveCompare(name) == .orderedSame }) else {
            throw NSError(domain: "CREAReader", code: 4, userInfo: [NSLocalizedDescriptionKey: "Usa un nombre único de 1 a 40 caracteres."])
        }
        collectionNames.append(name); UserDefaults.standard.set(collectionNames, forKey: "crea.reader.collections")
    }
    func deleteCollection(_ name: String) {
        collectionNames.removeAll { $0 == name }
        for book in books where memberships(book).contains(name) { setMembership(book, collection: name, included: false) }
        UserDefaults.standard.set(collectionNames, forKey: "crea.reader.collections")
    }
    func renameCollection(_ old: String, to new: String) throws {
        if old == new.trimmingCharacters(in: .whitespacesAndNewlines) { return }
        try createCollection(new)
        let name = new.trimmingCharacters(in: .whitespacesAndNewlines)
        for book in books where memberships(book).contains(old) { setMembership(book, collection: name, included: true) }
        deleteCollection(old)
    }
    func remove(_ book: LocalBook) {
        try? FileManager.default.removeItem(at: url(for: book))
        books.removeAll { $0.id == book.id }
        persist()
    }
    private func persist() {
        if let data = try? JSONEncoder().encode(books) { UserDefaults.standard.set(data, forKey: storeKey) }
    }
}

private struct CREAPopularBook: Identifiable {
    let id: String
    let count: Int
}

private struct CREAOnlineReadingSession: Identifiable {
    let id = UUID()
    let book: CREARemoteBook
    let fileURL: URL
}

private enum ReaderTab: Int, CaseIterable {
    case library, catalog, explore, community, settings
    var title: String {
        switch self {
        case .library: "Mi biblioteca"
        case .catalog: "Catálogo en línea"
        case .explore: "Explorar"
        case .community: "Comunidad"
        case .settings: "Ajustes"
        }
    }
    var icon: String {
        switch self {
        case .library: "books.vertical.fill"
        case .catalog: "cloud.fill"
        case .explore: "safari"
        case .community: "person.2.fill"
        case .settings: "gearshape.fill"
        }
    }
}

struct ReaderHomeView: View {
    @StateObject private var library = LocalLibrary.shared
    @StateObject private var googleAuth = CREAAuthSession()
    @StateObject private var driveCatalog = CREADriveCatalog()
    @StateObject private var progressSync = CREAProgressSync()
    @StateObject private var readerAI = CREAReaderAI()
    @StateObject private var imports = CREAImportCoordinator.shared
    @State private var pendingImports: [URL] = []
    @State private var categoryTarget: LocalBook?
    @State private var uploadTarget: LocalBook?
    @State private var libraryCategory: String?
    @AppStorage("crea.reader.library.mode") private var libraryModeStorage = "legacy"
    @AppStorage("crea.reader.library.coverSize") private var libraryCoverSize = "medium"
    @AppStorage("crea.reader.ai.autoInsights") private var autoInsights = false
    @AppStorage("crea.reader.download.notifications") private var downloadNotifications = false
    @State private var notificationMessage: String?
    @State private var tab: ReaderTab = .library
    @State private var importing = false
    @State private var selection: LocalBook?
    @State private var error: String?
    @State private var notice: String?
    @State private var search = ""
    @State private var filter = "Todos"
    @AppStorage("crea.reader.library.sort") private var sort = "Últimos"
    @AppStorage("crea.reader.communityShareEnabled") private var communityShareEnabled = false
    @AppStorage("crea.reader.communityShareAccount") private var communityShareAccount = ""
    @AppStorage("crea.reader.communityNickname") private var communityNickname = ""
    @State private var catalogStatus = "Todos"
    @State private var catalogSearch = ""
    @State private var catalogCategory = "Todos"
    @State private var selectedRemoteBook: CREARemoteBook?
    @State private var pendingOnlineReading: CREAOnlineReadingSession?
    @State private var activeOnlineReading: CREAOnlineReadingSession?
    @State private var temporaryOnlineFile: URL?
    @State private var pendingLocalRead: LocalBook?
    @State private var collectionTarget: LocalBook?
    @State private var collectionName = ""
    @State private var selectedCollection: String?
    @State private var showCollectionManager = false
    @ObservedObject private var annotations = CREAAnnotationStore.shared
    @AppStorage("crea.reader.theme") private var theme = "Sistema"
    @AppStorage("crea.reader.language") private var language = "es"
    @AppStorage("crea.reader.autoSync") private var autoSync = false
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme
    private var libraryMode: CREAListMode { CREAListMode(rawValue: libraryModeStorage) ?? (library.layoutGrid ? .grid : .list) }
    private var librarySize: CREACoverSize { CREACoverSize(rawValue: libraryCoverSize) ?? .medium }
    private var libraryModeBinding: Binding<String> { Binding(get: { libraryMode.rawValue }, set: { libraryModeStorage = $0; library.setGrid($0 != "list") }) }

    private var visibleBooks: [LocalBook] {
        let matching = library.books.filter { book in
            let text = CREACatalogRules.matches(book.name + " " + (book.author ?? ""), query: search)
            let kind = filter == "Todos" || (filter == "Favoritos" && book.favorite) ||
                (filter == "Leyendo" && book.progress > 0 && book.progress < 0.98) ||
                (filter == "Sin leer" && book.progress == 0) ||
                (filter == "Terminados" && book.progress >= 0.98) || filter == book.kind ||
                (filter == "En Drive" && book.driveFileID != nil) ||
                (filter == "Solo aquí" && book.driveFileID == nil) ||
                (filter == "Recientes" && (book.importedAt ?? .distantPast) > Date().addingTimeInterval(-14 * 86400)) ||
                (filter == "Marcadores" && annotations.items(for: book.contentHash ?? "").contains { $0.kind == "bookmark" }) ||
                (filter == "Con notas" && annotations.items(for: book.contentHash ?? "").contains { !$0.note.isEmpty })
            return text && kind && (selectedCollection.map { library.memberships(book).contains($0) } ?? true) && CREACategoryPaths.matches(book.category, selected: libraryCategory)
        }
        switch sort {
        case "Título": return matching.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        case "Autor": return matching.sorted { ($0.author ?? "").localizedStandardCompare($1.author ?? "") == .orderedAscending }
        case "Progreso": return matching.sorted { $0.progress > $1.progress }
        case "Descarga": return matching.sorted { ($0.importedAt ?? .distantPast) > ($1.importedAt ?? .distantPast) }
        case "Tamaño": return matching.sorted { ($0.fileSize ?? 0) > ($1.fileSize ?? 0) }
        default: return matching.sorted { ($0.lastOpened ?? .distantPast) > ($1.lastOpened ?? .distantPast) }
        }
    }

    var body: some View {
        ZStack {
            CREAStyle.gradient.ignoresSafeArea()
            TabView(selection: $tab) {
                libraryScreen.tabItem { Label("Biblioteca", systemImage: ReaderTab.library.icon) }.tag(ReaderTab.library)
                catalogScreen.tabItem { Label("Catálogo", systemImage: ReaderTab.catalog.icon) }.tag(ReaderTab.catalog)
                exploreScreen.tabItem { Label("Explorar", systemImage: ReaderTab.explore.icon) }.tag(ReaderTab.explore)
                communityScreen.tabItem { Label("Comunidad", systemImage: ReaderTab.community.icon) }.tag(ReaderTab.community)
                settingsScreen.tabItem { Label("Ajustes", systemImage: ReaderTab.settings.icon) }.tag(ReaderTab.settings)
            }
            .tint(CREAStyle.blue)
            .task(id: googleAuth.accountEmail) {
                driveCatalog.resetForCurrentAccount()
                progressSync.resetAccount()
                await CREABackgroundDownloads.shared.resumeReadyForCurrentAccount()
                await readerAI.refresh(force: true)
            }
        }
        .preferredColorScheme(theme == "Oscuro" ? .dark : theme == "Claro" ? .light : nil)
        .environment(\.locale, Locale(identifier: language))
        .onOpenURL { url in
            if GIDSignIn.sharedInstance.handle(url) { return }
            guard url.isFileURL else { return }
            tab = .library
            Task { await imports.run([url]) }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { CREABackgroundSync.schedule() }
            if phase == .active {
                if let decision = imports.pending { CREAImportPresenter.present(decision, coordinator: imports) }
                Task { await CREABackgroundDownloads.shared.resumeReadyForCurrentAccount() }
            }
            if phase == .active && autoSync && googleAuth.progressAuthorized && driveCatalog.needsAutoSync {
                Task { await driveCatalog.synchronize(); await progressSync.pull(catalog: driveCatalog, library: library); await progressSync.push(catalog: driveCatalog, library: library) }
            }
        }
        .sheet(item: $selectedRemoteBook, onDismiss: {
            if let pending = pendingOnlineReading {
                pendingOnlineReading = nil
                temporaryOnlineFile = pending.fileURL
                activeOnlineReading = pending
            } else if let pending = pendingLocalRead {
                pendingLocalRead = nil
                tab = .library
                open(pending)
            }
        }) { book in
            NavigationStack {
                CREAOnlineBookDetailsView(
                    book: book,
                    catalog: driveCatalog,
                    library: library,
                    progressSync: progressSync,
                    ai: readerAI,
                    onReadLocal: { local in
                        pendingLocalRead = local
                        selectedRemoteBook = nil
                    },
                    onReadOnline: { temporary in
                        pendingOnlineReading = CREAOnlineReadingSession(book: book, fileURL: temporary)
                        selectedRemoteBook = nil
                    },
                    onDownloaded: {
                        progressSync.promoteDownloadedBook(book.id, library: library)
                        if googleAuth.progressAuthorized {
                            Task { await progressSync.pull(catalog: driveCatalog, library: library) }
                        }
                    }
                )
            }
        }
        .fullScreenCover(item: $activeOnlineReading, onDismiss: {
            if let temporaryOnlineFile {
                try? FileManager.default.removeItem(at: temporaryOnlineFile)
                self.temporaryOnlineFile = nil
            }
        }) { online in
            NavigationStack {
                Group {
                    if online.book.isEPUB {
                        EPUBBookScreen(
                            url: online.fileURL,
                            title: online.book.displayName,
                            onProgress: { amount in
                                progressSync.recordOnlineProgress(for: online.book.id, fraction: amount)
                            },
                            initialLocatorJSON: progressSync.checkpoint(for: online.book.id)?.locatorJSON,
                            onLocator: { locator in
                                progressSync.recordOnlineLocator(for: online.book.id, locator: locator)
                            },
                            ai: readerAI,
                            isTemporaryOnline: true
                        )
                    } else {
                        PDFReaderScreen(
                            url: online.fileURL,
                            title: online.book.displayName,
                            onProgress: { amount in
                                progressSync.recordOnlineProgress(for: online.book.id, fraction: amount)
                            },
                            initialProgress: progressSync.checkpoint(for: online.book.id)?.progress ?? 0
                        )
                    }
                }
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Cerrar") { activeOnlineReading = nil }
                    }
                }
                .onDisappear {
                    if googleAuth.progressAuthorized {
                        Task { await progressSync.push(catalog: driveCatalog, library: library) }
                    }
                }
            }
        }
        .sheet(isPresented: $importing, onDismiss: {
            let urls = pendingImports; pendingImports = []
            if !urls.isEmpty { Task { await imports.run(urls) } }
        }) {
            BookFilePicker { picked in
                pendingImports = picked
                importing = false
            }
        }
        .onChange(of: imports.pending?.id) { _, id in if id != nil, let decision = imports.pending { CREAImportPresenter.present(decision, coordinator: imports) } }
        .sheet(item: $categoryTarget) { book in CREALibraryCategorySheet(library: library, catalog: driveCatalog, book: book) }
        .sheet(item: $uploadTarget) { book in CREAUploadSheet(catalog: driveCatalog, library: library, book: book) }
        .onChange(of: imports.report) { _, value in if let value { notice = value; imports.report = nil } }
        .onChange(of: imports.processing) { _, active in if !active { Task { await CREABackgroundDownloads.shared.resumeReadyForCurrentAccount() } } }
        .alert("Importación completada", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
            Button("Aceptar") { notice = nil }
        } message: { Text(notice ?? "") }
        .alert("CREA Reader", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("Aceptar") { error = nil }
        } message: { Text(error ?? "") }
    }

    private var libraryScreen: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    welcome
                    if imports.processing { ProgressView("Revisando e importando libros…") }
                    CREACategoryPicker(paths: Array(Set(library.books.compactMap { $0.category })), selected: $libraryCategory)
                    if let recent = library.books.filter({ $0.lastOpened != nil }).sorted(by: { ($0.lastOpened ?? .distantPast) > ($1.lastOpened ?? .distantPast) }).first {
                        Button { open(recent) } label: {
                            HStack(spacing: 14) {
                                Image(systemName: "book.closed.fill").font(.title2).foregroundStyle(CREAStyle.blue)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text("CONTINUAR LEYENDO").font(.caption2).bold().foregroundStyle(CREAStyle.blue)
                                    Text(recent.name).font(.headline).foregroundStyle(.primary).lineLimit(1)
                                    ProgressView(value: recent.progress).tint(CREAStyle.green)
                                }
                                Spacer()
                                Image(systemName: "arrow.right.circle.fill").font(.title2).foregroundStyle(CREAStyle.blue)
                            }
                            .padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
                        }.buttonStyle(.plain)
                    }
                    HStack {
                        Label("Mis libros", systemImage: "books.vertical").font(.title3.bold())
                        Text("\(library.books.count)").foregroundStyle(.secondary)
                        Spacer()
                        CREAViewMenu(mode: libraryModeBinding, size: $libraryCoverSize)
                        Menu {
                            ForEach(["Últimos", "Descarga", "Título", "Autor", "Progreso", "Tamaño"], id: \.self) { value in
                                Button(LocalizedStringKey(value)) { sort = value }
                            }
                        } label: { Image(systemName: "arrow.up.arrow.down").font(.title3) }
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(["Todos", "Leyendo", "Sin leer", "Terminados", "Favoritos", "Recientes", "Marcadores", "Con notas", "En Drive", "Solo aquí", "EPUB", "PDF", "AUDIO", "IMAGEN"], id: \.self) { value in
                                Button(LocalizedStringKey(value)) { filter = value }
                                    .font(.subheadline.weight(.medium))
                                    .padding(.horizontal, 13).padding(.vertical, 9)
                                    .background(filter == value ? CREAStyle.blue : Color.primary.opacity(0.07), in: Capsule())
                                    .foregroundStyle(filter == value ? Color.white : Color.primary)
                            }
                        }
                    }
                    if !library.collections.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                Button("Todas las colecciones") { selectedCollection = nil }
                                ForEach(library.collections, id: \.self) { name in
                                    Button(name) { selectedCollection = name }.buttonStyle(.bordered)
                                        .tint(selectedCollection == name ? CREAStyle.blue : Color.secondary)
                                }
                            }
                        }
                    }
                    if visibleBooks.isEmpty {
                        ContentUnavailableView(search.isEmpty ? "Aún no hay libros" : "Sin resultados",
                            systemImage: "books.vertical",
                            description: Text("Importa libros EPUB o PDF desde Archivos para construir tu biblioteca."))
                            .frame(maxWidth: .infinity)
                    } else if libraryMode != .list {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 125, maximum: 180), spacing: 12)], spacing: 16) {
                            ForEach(visibleBooks) { book in bookCard(book) }
                        }
                    } else {
                        LazyVStack(spacing: 10) {
                            ForEach(visibleBooks) { book in bookRow(book) }
                        }
                    }
                }
                .padding(18)
            }
            .background(CREAStyle.gradient)
            .navigationTitle("CREA Reader")
            .task(id: googleAuth.progressAuthorized) {
                if googleAuth.progressAuthorized {
                    if driveCatalog.needsAutoSync { await driveCatalog.synchronize() }
                    await progressSync.pull(catalog: driveCatalog, library: library)
                }
            }
            .refreshable {
                if googleAuth.progressAuthorized {
                    await driveCatalog.synchronize()
                    await progressSync.pull(catalog: driveCatalog, library: library)
                }
            }
            .searchable(text: $search, prompt: "Buscar en mi biblioteca")
            .sheet(item: $collectionTarget) { book in CREACollectionsSheet(library: library, book: book) }
            .sheet(isPresented: $showCollectionManager) { CREACollectionsSheet(library: library, book: nil) }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showCollectionManager = true } label: { Label("Gestionar colecciones", systemImage: "folder.badge.gearshape") }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { importing = true } label: { Image(systemName: "plus.circle.fill").font(.title3) }
                        .accessibilityLabel("Importar libros")
                }
            }
            .navigationDestination(item: $selection) { book in
                if book.kind == "EPUB" {
                    EPUBBookScreen(
                        url: library.url(for: book),
                        title: book.name,
                        onProgress: { amount in
                            library.update(book) {
                                $0.progress = amount
                                $0.progressUpdatedAt = Date()
                                $0.lastOpened = Date()
                            }
                        },
                        initialLocatorJSON: library.books.first(where: { $0.id == book.id })?.readingLocatorJSON,
                        onLocator: { serialized in
                            library.update(book) {
                                $0.readingLocatorJSON = serialized
                                $0.progressUpdatedAt = Date()
                            }
                        },
                        ai: readerAI
                    )
                    .onDisappear {
                        if googleAuth.progressAuthorized || (communityShareEnabled && communityShareAccount == (googleAuth.accountEmail ?? "") && googleAuth.communityAuthorized) {
                            Task {
                                if googleAuth.progressAuthorized {
                                    await progressSync.push(catalog: driveCatalog, library: library)
                                }
                                if communityShareEnabled && communityShareAccount == (googleAuth.accountEmail ?? "") && googleAuth.communityAuthorized {
                                    _ = await driveCatalog.publishCommunity(
                                        enabled: true, nickname: communityNickname, library: library)
                                }
                            }
                        }
                    }
                } else if book.kind == "AUDIO" || book.kind == "IMAGEN" {
                    CREAMediaScreen(url: library.url(for: book), title: book.name, audioMode: book.kind == "AUDIO",
                        initialProgress: book.progress, locator: book.readingLocatorJSON,
                        onProgress: { amount in library.update(book) { $0.progress = amount; $0.progressUpdatedAt = Date(); $0.lastOpened = Date() } },
                        onLocator: { json in library.update(book) { $0.readingLocatorJSON = json; $0.progressUpdatedAt = Date() } })
                } else {
                    PDFReaderScreen(url: library.url(for: book), title: book.name, onProgress: { amount in
                        library.update(book) {
                            $0.progress = amount
                            $0.progressUpdatedAt = Date()
                            $0.lastOpened = Date()
                        }
                    }, initialProgress: book.progress, onLocator: { json in
                        library.update(book) { $0.readingLocatorJSON = json; $0.progressUpdatedAt = Date() }
                    })
                    .onDisappear {
                        if googleAuth.progressAuthorized { Task { await progressSync.push(catalog: driveCatalog, library: library) } }
                    }
                }
            }
        }
    }

    private var welcome: some View {
        HStack(spacing: 14) {
            Image(systemName: "books.vertical.fill")
                .font(.largeTitle).foregroundStyle(.white)
                .frame(width: 64, height: 64)
                .background(LinearGradient(colors: [CREAStyle.blue, CREAStyle.pink], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 18))
            VStack(alignment: .leading, spacing: 4) {
                Text("Tu espacio de lectura").font(.title3.bold())
                Text("CREA · Mentes abiertas").font(.subheadline).foregroundStyle(.secondary)
                Text("\(library.books.count) libros disponibles sin conexión").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    private func bookCard(_ book: LocalBook) -> some View {
        Button { open(book) } label: {
            VStack(alignment: .leading, spacing: 8) {
                ZStack(alignment: .bottomLeading) {
                    CREALocalCoverView(url: library.url(for: book), kind: book.kind, title: book.name)
                        .clipShape(RoundedRectangle(cornerRadius: 15))
                    Text(book.kind).font(.caption2.bold()).foregroundStyle(.white)
                        .padding(7).background(.black.opacity(0.28), in: Capsule()).padding(9)
                }
                .aspectRatio(0.76, contentMode: .fit)
                if libraryMode != .covers { Text(book.name).font(.subheadline.bold()).foregroundStyle(.primary).lineLimit(2).frame(minHeight: 38, alignment: .topLeading) }
                ProgressView(value: book.progress).tint(CREAStyle.green)
                HStack {
                    Text("\(Int(book.progress * 100))%").font(.caption2).foregroundStyle(.secondary)
                    Spacer()
                    Image(systemName: book.favorite ? "heart.fill" : "heart")
                        .foregroundStyle(book.favorite ? CREAStyle.pink : Color.secondary)
                }
            }
            .padding(10).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
        .contextMenu { bookActions(book) }
    }

    private func bookRow(_ book: LocalBook) -> some View {
        Button { open(book) } label: {
            HStack(spacing: 14) {
                CREALocalCoverView(url: library.url(for: book), kind: book.kind, title: book.name)
                    .frame(width: librarySize == .small ? 44 : librarySize == .large ? 82 : 60, height: librarySize == .small ? 62 : librarySize == .large ? 114 : 84)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 8) {
                    Text(book.name).font(.headline).foregroundStyle(.primary).lineLimit(2)
                    Text(book.kind).font(.caption).foregroundStyle(.secondary)
                    if let category = book.category { Text(category).font(.caption).foregroundStyle(.secondary) }
                    HStack(spacing: 8) {
                        ProgressView(value: book.progress).tint(CREAStyle.green)
                        Text("\(Int(book.progress * 100))%")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Image(systemName: book.favorite ? "heart.fill" : "chevron.right")
                    .foregroundStyle(book.favorite ? CREAStyle.pink : Color.secondary)
            }
            .padding(12).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .contextMenu { bookActions(book) }
    }

    @ViewBuilder private func bookActions(_ book: LocalBook) -> some View {
        ShareLink(item: library.url(for: book)) { Label("Compartir archivo", systemImage: "square.and.arrow.up") }
        if ["EPUB", "PDF"].contains(book.kind) {
            Button("Subir a Google Drive", systemImage: "icloud.and.arrow.up") {
                if googleAuth.communityAuthorized { uploadTarget = book } else { error = "Autoriza la subida de libros a Drive en Ajustes primero." }
            }.disabled(driveCatalog.uploading.contains(book.id))
        }
        if book.driveFileID == nil { Button("Categoría", systemImage: "folder") { categoryTarget = book } }
        Button(book.favorite ? "Quitar favorito" : "Añadir a favoritos", systemImage: book.favorite ? "heart.slash" : "heart") {
            library.update(book) { $0.favorite.toggle() }
        }
        Button("Marcar como terminado", systemImage: "checkmark.circle") {
            library.markFinished(book)
        }
        Button("Añadir a colección", systemImage: "folder") {
            collectionName = book.collection
            collectionTarget = book
        }
        Button("Eliminar del dispositivo", systemImage: "trash", role: .destructive) { library.remove(book) }
    }

    private func open(_ book: LocalBook) {
        library.update(book) { $0.lastOpened = Date() }
        selection = book
    }

    private var catalogScreen: some View {
        CREACatalogScreen(catalog: driveCatalog, library: library, auth: googleAuth, progress: progressSync, ai: readerAI,
            onDetails: { selectedRemoteBook = $0 }, onRead: { tab = .library; open($0) })
    }

    private var exploreScreen: some View {
        CREAExploreScreen(library: library)
    }

    private var communityScreen: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    communityHeading
                    CREACommunityDiscovery(catalog: driveCatalog, library: library, progress: progressSync, sharingEnabled: communityIsOnCurrentAccount) { book in selectedRemoteBook = book }
                    communityMembersSection
                    communitySharingPanel
                }
                .padding()
            }
            .background(CREAStyle.gradient)
            .navigationTitle("Comunidad")
            .task(id: googleAuth.accountEmail) {
                if googleAuth.accountEmail != nil { await driveCatalog.loadCommunity() }
            }
        }
    }

    private var communityHeading: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Qué leen los demás").font(.title2.bold())
            Text("Solo aparecen las personas que eligieron compartir sus lecturas con CREA.")
                .font(.subheadline).foregroundStyle(.secondary)
            Button("Actualizar lecturas compartidas") {
                Task { await driveCatalog.loadCommunity() }
            }.buttonStyle(.borderedProminent)
        }
    }

    private var communityPopularBooks: [CREAPopularBook] {
        var counts: [String: Int] = [:]
        for member in driveCatalog.community {
            for reading in member.readings {
                counts[reading.driveFileId, default: 0] += 1
            }
        }
        var popular: [CREAPopularBook] = []
        for (id, count) in counts {
            popular.append(CREAPopularBook(id: id, count: count))
        }
        popular.sort { first, second in
            if first.count == second.count { return first.id < second.id }
            return first.count > second.count
        }
        return Array(popular.prefix(4))
    }

    private var communityLeaderboard: some View {
        let popular = communityPopularBooks
        return Group {
            if !popular.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Label("Los más leídos", systemImage: "chart.bar.fill").font(.headline)
                    ForEach(popular) { item in
                        HStack {
                            Text(driveCatalog.books.first(where: { $0.id == item.id })?.displayName
                                 ?? "Libro compartido")
                                .font(.subheadline).lineLimit(2)
                            Spacer()
                            Text("\(item.count) lectores").font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding()
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
            }
        }
    }

    private var communityMembersSection: some View {
        Group {
            if driveCatalog.community.isEmpty {
                ContentUnavailableView("Sin lecturas compartidas", systemImage: "person.2",
                    description: Text("Aún no hay perfiles públicos disponibles en la carpeta compartida."))
            }
            ForEach(driveCatalog.community) { member in
                communityMemberCard(member)
            }
        }
    }

    private func communityMemberCard(_ member: CREACommunityProfile) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(member.nickname, systemImage: "person.crop.circle").font(.headline)
            ForEach(member.readings, id: \.driveFileId) { reading in
                communityReadingRow(reading)
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private func communityReadingRow(_ reading: CREACommunityReading) -> some View {
        let title = driveCatalog.books.first(where: { $0.id == reading.driveFileId })?.displayName
            ?? "Libro compartido"
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title).font(.subheadline).lineLimit(2)
                Spacer()
                Text("\(reading.percent)%").font(.subheadline.monospacedDigit())
            }
            ProgressView(value: Double(max(0, min(reading.percent, 100))) / 100)
                .tint(CREAStyle.green)
        }
    }

    private var communityIsOnCurrentAccount: Bool {
        communityShareEnabled && communityShareAccount == (googleAuth.accountEmail ?? "")
    }

    private var communitySharingPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Compartir mis lecturas", systemImage: "person.crop.circle.badge.checkmark")
                .font(.headline)
            Text("Es opcional. Solo se publica tu apodo, los libros vinculados y el porcentaje de lectura. Puedes desactivarlo.")
                .font(.footnote).foregroundStyle(.secondary)
            communitySharingAccountControls
            if driveCatalog.communityPublishing { ProgressView("Actualizando comunidad") }
            if !driveCatalog.communityStatus.isEmpty {
                Text(driveCatalog.communityStatus).font(.footnote).foregroundStyle(.secondary)
            }
            if let message = googleAuth.message {
                Text(message).font(.footnote).foregroundStyle(.red)
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    @ViewBuilder
    private var communitySharingAccountControls: some View {
        if googleAuth.accountEmail == nil {
            Text("Inicia sesión con Google desde Ajustes para compartir.")
                .font(.footnote).foregroundStyle(.secondary)
        } else if !googleAuth.communityAuthorized {
            Button("Autorizar compartir con Comunidad") {
                googleAuth.requestCommunityAccess()
            }.buttonStyle(.bordered)
            Text("Este permiso permite modificar archivos de Google Drive. El catálogo funciona sin él.")
                .font(.caption).foregroundStyle(.secondary)
        } else {
            communitySharingButtons
        }
    }

    private var communitySharingButtons: some View {
        VStack(alignment: .leading, spacing: 10) {
            TextField("Apodo que verán los lectores", text: $communityNickname)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
            Button {
                Task {
                    let success = await driveCatalog.publishCommunity(
                        enabled: true, nickname: communityNickname, library: library)
                    if success {
                        communityShareEnabled = true
                        communityShareAccount = googleAuth.accountEmail ?? ""
                    }
                }
            } label: {
                Label(communityIsOnCurrentAccount ? "Actualizar lo que comparto" : "Empezar a compartir",
                      systemImage: "person.crop.circle.badge.plus")
            }
            .buttonStyle(.borderedProminent)
            .disabled(driveCatalog.communityPublishing ||
                      (communityShareEnabled && !communityIsOnCurrentAccount) ||
                      communityNickname.trimmingCharacters(in: .whitespacesAndNewlines).count < 2)
            if communityShareEnabled && !communityIsOnCurrentAccount {
                Text("Tus lecturas se compartieron desde otra cuenta. Inicia sesión con esa cuenta para desactivarlo antes de cambiar.")
                    .font(.footnote).foregroundStyle(.orange)
            }
            if communityIsOnCurrentAccount {
                Button(role: .destructive) {
                    Task {
                        let success = await driveCatalog.publishCommunity(
                            enabled: false, nickname: communityNickname, library: library)
                        if success {
                            communityShareEnabled = false
                            communityShareAccount = ""
                        }
                    }
                } label: {
                    Label("Dejar de compartir", systemImage: "person.crop.circle.badge.xmark")
                }
                .disabled(driveCatalog.communityPublishing)
            }
        }
    }

    private var settingsScreen: some View {
        NavigationStack {
            Form {
                Section("Apariencia") {
                    Picker("Idioma", selection: $language) { Text("Español").tag("es"); Text("English").tag("en") }
                    NavigationLink("Lectura en voz alta") { CREAVoiceSettings() }
                    Picker("Tema", selection: $theme) {
                        Text("Sistema").tag("Sistema")
                        Text("Claro").tag("Claro")
                        Text("Oscuro").tag("Oscuro")
                    }
                    Picker("Visualización", selection: libraryModeBinding) {
                        ForEach(CREAListMode.allCases, id: \.rawValue) { Text(LocalizedStringKey($0.title)).tag($0.rawValue) }
                    }
                    Picker("Tamaño de portadas", selection: $libraryCoverSize) {
                        ForEach(CREACoverSize.allCases, id: \.rawValue) { Text(LocalizedStringKey($0.title)).tag($0.rawValue) }
                    }
                }
                Section("Almacenamiento") {
                    LabeledContent("Libros guardados", value: "\(library.books.count)")
                    LabeledContent("Formato EPUB", value: "\(library.books.filter { $0.kind == "EPUB" }.count)")
                    LabeledContent("Formato PDF", value: "\(library.books.filter { $0.kind == "PDF" }.count)")
                    if library.books.allSatisfy({ $0.fileSize != nil }) {
                        LabeledContent("Espacio usado", value: ByteCountFormatter.string(fromByteCount: library.books.reduce(0) { $0 + Int64($1.fileSize ?? 0) }, countStyle: .file))
                    }
                }
                Section("Sincronización") {
                    Toggle("Sincronización automática", isOn: $autoSync).onChange(of: autoSync) { _, _ in CREABackgroundSync.schedule() }
                    Text("iOS decide cuándo permite actualizar en segundo plano. También se sincroniza al volver a la aplicación.").font(.footnote)
                    CREAGoogleAccountView(auth: googleAuth)
                    if googleAuth.accountEmail != nil {
                        if !googleAuth.communityAuthorized {
                            Button("Autorizar subida de libros a Drive") { googleAuth.requestCommunityAccess() }
                            Text("La subida requiere permiso de escritura en Drive. Los archivos se suben solo al pulsar Subir.").font(.footnote)
                        } else {
                            Text("Desde el menú de cada libro, Subir a Drive permite elegir la carpeta de destino.").font(.footnote)
                        }
                        if let issue = driveCatalog.errorMessage { Text(issue).font(.footnote).foregroundStyle(.red) }
                    }
                    if googleAuth.progressAuthorized {
                        Button {
                            Task {
                                await driveCatalog.synchronize()
                                await progressSync.pull(catalog: driveCatalog, library: library)
                                await progressSync.push(catalog: driveCatalog, library: library)
                            }
                        } label: {
                            Label("Sincronizar progreso Android y iOS", systemImage: "arrow.triangle.2.circlepath")
                        }
                        .disabled(progressSync.busy)
                    }
                    Text(progressSync.status).font(.footnote).foregroundStyle(.secondary)
                    NavigationLink("Lecturas por dispositivo") {
                        List(progressSync.devices) { device in
                            Section(device.name) {
                                Text(Date(timeIntervalSince1970: Double(device.updatedAt) / 1000), style: .date).font(.caption)
                                ForEach(device.readings) { reading in
                                    LabeledContent(driveCatalog.books.first { $0.id == reading.driveFileId }?.displayName ?? "Libro no disponible en catálogo", value: "\(reading.percent)%")
                                }
                            }
                        }.navigationTitle("Mis dispositivos")
                    }
                    Text("Se conserva el progreso más reciente y los datos de los demás dispositivos.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Descargas") {
                    Toggle("Avisar cuando una descarga termina", isOn: $downloadNotifications)
                        .onChange(of: downloadNotifications) { _, enabled in
                            if enabled {
                                UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
                                    Task { @MainActor in
                                        if !granted { downloadNotifications = false; notificationMessage = error?.localizedDescription ?? "Autoriza las notificaciones en Ajustes de iOS." }
                                        else { notificationMessage = nil }
                                    }
                                }
                            }
                        }
                    if let notificationMessage { Text(notificationMessage).font(.footnote) }
                    Text("Las descargas usan el servicio de fondo de iOS. Al cerrar forzadamente la app, iOS puede detenerlas; puedes reintentar desde el catálogo.").font(.footnote)
                }
                Section("Asistente de IA con Claude") {
                    CREAAISettingsView(ai: readerAI)
                    Toggle("Crear fichas faltantes al actualizar catálogo", isOn: $autoInsights)
                    Text("Con IA activa y permiso de escritura en Drive, se analizan hasta 6 libros por actualización y se comparte la ficha con los demás dispositivos. Consume la cuota del servidor CREA.").font(.footnote)
                }
                Section("Información") {
                    LabeledContent("Versión iOS", value: "\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.9.3") · beta")
                    Text("CREA Reader · Inspirado en la interfaz Android de CREA").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Ajustes")
        }
    }
}

struct BookFilePicker: UIViewControllerRepresentable {
    let onPick: ([URL]) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let epub = UTType(filenameExtension: "epub") ?? UTType(importedAs: "org.idpf.epub-container")
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf, epub, .audio, .image, UTType(filenameExtension: "cbz") ?? .zip, UTType(filenameExtension: "audiobook") ?? .json], asCopy: true)
        picker.allowsMultipleSelection = true
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: ([URL]) -> Void
        init(onPick: @escaping ([URL]) -> Void) { self.onPick = onPick }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onPick(urls)
        }
    }
}

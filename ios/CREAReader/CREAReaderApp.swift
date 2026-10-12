import SwiftUI
import PDFKit
import UniformTypeIdentifiers
import GoogleSignIn
import ReadiumShared
import ReadiumStreamer

@main
struct CREAReaderApp: App {
    init() { CREABackgroundSync.register() }
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
    func add(url source: URL, displayName: String? = nil, driveFileID: String? = nil) throws {
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
            return
        }
        books.insert(LocalBook(id: UUID(), name: displayName ?? source.deletingPathExtension().lastPathComponent,
                               filename: name, kind: audioFormats.contains(ext) ? "AUDIO" : imageFormats.contains(ext) ? "IMAGEN" : ext.uppercased(), driveFileID: driveFileID, contentHash: hash,
                               driveAccount: driveFileID == nil ? nil : GIDSignIn.sharedInstance.currentUser?.profile?.email.lowercased(),
                               importedAt: Date(), fileSize: try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize), at: 0)
        persist()
        committed = true
        if let added = books.first { Task { await enrich(added, preserveTitle: displayName != nil) } }
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
        }
    }
    func update(_ book: LocalBook, mutate: (inout LocalBook) -> Void) {
        guard let index = books.firstIndex(where: { $0.id == book.id }) else { return }
        mutate(&books[index])
        persist()
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

    private var visibleBooks: [LocalBook] {
        let matching = library.books.filter { book in
            let text = search.isEmpty || (book.name + " " + (book.author ?? "")).localizedStandardContains(search)
            let kind = filter == "Todos" || (filter == "Favoritos" && book.favorite) ||
                (filter == "Leyendo" && book.progress > 0 && book.progress < 0.98) ||
                (filter == "Sin leer" && book.progress == 0) ||
                (filter == "Terminados" && book.progress >= 0.98) || filter == book.kind ||
                (filter == "En Drive" && book.driveFileID != nil) ||
                (filter == "Solo aquí" && book.driveFileID == nil) ||
                (filter == "Recientes" && (book.importedAt ?? .distantPast) > Date().addingTimeInterval(-14 * 86400)) ||
                (filter == "Marcadores" && annotations.items(for: book.contentHash ?? "").contains { $0.kind == "bookmark" }) ||
                (filter == "Con notas" && annotations.items(for: book.contentHash ?? "").contains { !$0.note.isEmpty })
            return text && kind && (selectedCollection.map { library.memberships(book).contains($0) } ?? true)
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
                await readerAI.refresh(force: true)
            }
        }
        .preferredColorScheme(theme == "Oscuro" ? .dark : theme == "Claro" ? .light : nil)
        .environment(\.locale, Locale(identifier: language))
        .onOpenURL { url in
            if GIDSignIn.sharedInstance.handle(url) { return }
            guard url.isFileURL else { return }
            do { try library.add(url: url); tab = .library; notice = "Libro importado a Mi biblioteca" }
            catch { self.error = error.localizedDescription }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { CREABackgroundSync.schedule() }
            if phase == .active && autoSync && googleAuth.progressAuthorized {
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
        .sheet(isPresented: $importing) {
            BookFilePicker { picked in
                importing = false
                var failures: [String] = []
                var imported = 0
                for item in picked {
                    do { try library.add(url: item); imported += 1 }
                    catch { failures.append(item.lastPathComponent + ": " + error.localizedDescription) }
                }
                if !failures.isEmpty { self.error = failures.joined(separator: "\n") }
                else if imported > 0 { self.notice = imported == 1 ? "Libro importado a Mi biblioteca" : "\(imported) libros importados" }
            }
        }
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
                        Menu {
                            Button("Cuadrícula", systemImage: "square.grid.2x2") { library.setGrid(true) }
                            Button("Lista", systemImage: "list.bullet") { library.setGrid(false) }
                        } label: { Image(systemName: library.layoutGrid ? "square.grid.2x2" : "list.bullet").font(.title3) }
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
                    } else if library.layoutGrid {
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
                    await driveCatalog.synchronize()
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
                    CREALocalCoverView(url: library.url(for: book), kind: book.kind)
                        .clipShape(RoundedRectangle(cornerRadius: 15))
                    Text(book.kind).font(.caption2.bold()).foregroundStyle(.white)
                        .padding(7).background(.black.opacity(0.28), in: Capsule()).padding(9)
                }
                .aspectRatio(0.76, contentMode: .fit)
                Text(book.name).font(.subheadline.bold()).foregroundStyle(.primary).lineLimit(2).frame(height: 38, alignment: .topLeading)
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
                CREALocalCoverView(url: library.url(for: book), kind: book.kind)
                    .frame(width: 60, height: 84)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 8) {
                    Text(book.name).font(.headline).foregroundStyle(.primary).lineLimit(2)
                    Text(book.kind).font(.caption).foregroundStyle(.secondary)
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
        if googleAuth.communityAuthorized && ["EPUB", "PDF"].contains(book.kind) {
            Button("Subir a Google Drive", systemImage: "icloud.and.arrow.up") {
                Task { await driveCatalog.upload(book, library: library) }
            }.disabled(driveCatalog.uploading.contains(book.id))
        }
        Button(book.favorite ? "Quitar favorito" : "Añadir a favoritos", systemImage: book.favorite ? "heart.slash" : "heart") {
            library.update(book) { $0.favorite.toggle() }
        }
        Button("Marcar como terminado", systemImage: "checkmark.circle") {
            library.update(book) {
                $0.progress = 1
                $0.progressUpdatedAt = Date()
            }
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
        NavigationStack {
            Group {
                if googleAuth.accountEmail == nil {
                    VStack(spacing: 16) {
                        Image(systemName: "cloud.fill").font(.largeTitle).foregroundStyle(CREAStyle.blue)
                        Text("Inicia sesión para consultar tu catálogo de Google Drive")
                            .multilineTextAlignment(.center)
                        CREAGoogleAccountView(auth: googleAuth)
                    }.padding()
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            HStack {
                                Label("Catálogo de CREA Reader", systemImage: "cloud")
                                Spacer()
                                if driveCatalog.isLoading { ProgressView() }
                                Button("Actualizar") { Task {
                                    await driveCatalog.synchronize()
                                    if googleAuth.progressAuthorized { await progressSync.pull(catalog: driveCatalog, library: library) }
                                } }
                            }
                            .padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                            if let issue = driveCatalog.errorMessage {
                                Text(issue).font(.footnote).foregroundStyle(.red)
                            }
                            Text("\(driveCatalog.books.count) libros encontrados. Solo se descargan los que elijas.")
                                .font(.subheadline).foregroundStyle(.secondary)
                            HStack {
                                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                                TextField("Buscar libros en Drive", text: $catalogSearch)
                                    .autocorrectionDisabled()
                            }
                            .padding(12)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(["Todos", "Sin descargar", "Descargados", "Leyendo", "Terminados"], id: \.self) { state in
                                        Button(state) { catalogStatus = state }
                                            .font(.subheadline)
                                            .padding(.horizontal, 12).padding(.vertical, 8)
                                            .background(catalogStatus == state ? CREAStyle.blue : Color.primary.opacity(0.08), in: Capsule())
                                            .foregroundStyle(catalogStatus == state ? Color.white : Color.primary)
                                    }
                                }
                            }
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(["Todos"] + Array(Set(driveCatalog.books.compactMap { $0.category })).sorted(), id: \.self) { category in
                                        Button(category) { catalogCategory = category }
                                            .font(.subheadline)
                                            .padding(.horizontal, 12).padding(.vertical, 8)
                                            .background(catalogCategory == category ? CREAStyle.blue : Color.primary.opacity(0.08), in: Capsule())
                                            .foregroundStyle(catalogCategory == category ? Color.white : Color.primary)
                                    }
                                }
                            }
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 135), spacing: 12)], spacing: 14) {
                                ForEach(driveCatalog.books.filter { book in
                                    let local = library.books.first(where: { $0.driveFileID == book.id })
                                    let percentage = local.map { Int($0.progress * 100) }
                                        ?? progressSync.percent(for: book.id) ?? 0
                                    let matchesStatus: Bool
                                    switch catalogStatus {
                                    case "Sin descargar": matchesStatus = local == nil
                                    case "Descargados": matchesStatus = local != nil
                                    case "Leyendo": matchesStatus = percentage > 0 && percentage < 100
                                    case "Terminados": matchesStatus = percentage >= 100
                                    default: matchesStatus = true
                                    }
                                    return matchesStatus &&
                                        (catalogCategory == "Todos" || book.category == catalogCategory) &&
                                        (catalogSearch.isEmpty || book.displayName.localizedStandardContains(catalogSearch))
                                }) { book in
                                    let localCopy = library.books.first(where: { $0.driveFileID == book.id })
                                    VStack(alignment: .leading, spacing: 10) {
                                        Button {
                                            selectedRemoteBook = book
                                        } label: {
                                            CREARemoteCoverView(book: book, catalog: driveCatalog)
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityLabel("Ver información de \(book.displayName)")
                                        Button {
                                            selectedRemoteBook = book
                                        } label: {
                                            Text(book.displayName).font(.subheadline.bold())
                                                .foregroundStyle(.primary).lineLimit(2)
                                        }
                                        .buttonStyle(.plain)
                                        if let category = book.category {
                                            Text(category).font(.caption2).foregroundStyle(.secondary)
                                        }
                                        HStack {
                                            Text(book.isEPUB ? "EPUB" : "PDF")
                                            Spacer()
                                            if localCopy != nil {
                                                Label("Descargado", systemImage: "checkmark.circle.fill")
                                            } else {
                                                Text("En línea")
                                            }
                                        }
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        if let percent = localCopy.map({ Int($0.progress * 100) }) ?? progressSync.percent(for: book.id),
                                           percent > 0 {
                                            HStack(spacing: 6) {
                                                ProgressView(value: Double(percent) / 100).tint(CREAStyle.green)
                                                Text("\(percent)%").font(.caption2.monospacedDigit())
                                            }
                                        }
                                        Button {
                                            selectedRemoteBook = book
                                        } label: {
                                            Label("Vista previa y ficha", systemImage: "eye")
                                                .font(.caption)
                                                .frame(maxWidth: .infinity, minHeight: 44)
                                        }
                                        .buttonStyle(.bordered)
                                        if let localCopy {
                                            Button {
                                                tab = .library
                                                open(localCopy)
                                            } label: {
                                                Label("Leer", systemImage: "book")
                                            }
                                            .buttonStyle(.borderedProminent)
                                        } else {
                                            Button {
                                                Task {
                                                    await driveCatalog.download(book, into: library)
                                                    progressSync.promoteDownloadedBook(book.id, library: library)
                                                    if googleAuth.progressAuthorized {
                                                        await progressSync.pull(catalog: driveCatalog, library: library)
                                                    }
                                                }
                                            } label: {
                                                if driveCatalog.downloading.contains(book.id) {
                                                    ProgressView()
                                                } else {
                                                    Label("Descargar", systemImage: "arrow.down.circle")
                                                }
                                            }
                                            .disabled(driveCatalog.downloading.contains(book.id))
                                            .buttonStyle(.borderedProminent)
                                        }
                                    }
                                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                                }
                            }
                        }.padding()
                    }
                    .refreshable {
                        await driveCatalog.synchronize()
                        if googleAuth.progressAuthorized { await progressSync.pull(catalog: driveCatalog, library: library) }
                    }
                    .task(id: googleAuth.accountEmail) {
                        await driveCatalog.synchronize()
                        if googleAuth.progressAuthorized { await progressSync.pull(catalog: driveCatalog, library: library) }
                    }
                }
            }
            .background(CREAStyle.gradient)
            .navigationTitle("Catálogo en línea")
        }
    }

    private var exploreScreen: some View {
        CREAExploreScreen(library: library)
    }

    private var communityScreen: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    communityHeading
                    communityLeaderboard
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
                    Picker("Visualización", selection: Binding(get: { library.layoutGrid }, set: { library.setGrid($0) })) {
                        Text("Cuadrícula").tag(true)
                        Text("Lista").tag(false)
                    }
                }
                Section("Almacenamiento") {
                    LabeledContent("Libros guardados", value: "\(library.books.count)")
                    LabeledContent("Formato EPUB", value: "\(library.books.filter { $0.kind == "EPUB" }.count)")
                    LabeledContent("Formato PDF", value: "\(library.books.filter { $0.kind == "PDF" }.count)")
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
                            Button("Subir libros locales a Drive") {
                                Task {
                                    for book in library.books where book.driveFileID == nil && ["EPUB", "PDF"].contains(book.kind) {
                                        await driveCatalog.upload(book, library: library)
                                    }
                                    await progressSync.push(catalog: driveCatalog, library: library)
                                }
                            }.disabled(!driveCatalog.uploading.isEmpty)
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
                Section("Asistente de IA con Claude") {
                    CREAAISettingsView(ai: readerAI)
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

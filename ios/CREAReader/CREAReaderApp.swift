import SwiftUI
import PDFKit
import UniformTypeIdentifiers

@main
struct CREAReaderApp: App {
    var body: some Scene { WindowGroup { ReaderHomeView() } }
}

private enum CREAStyle {
    static let blue = Color(red: 0.14, green: 0.47, blue: 0.88)
    static let pink = Color(red: 0.89, green: 0.28, blue: 0.65)
    static let orange = Color(red: 0.98, green: 0.54, blue: 0.22)
    static let green = Color(red: 0.29, green: 0.70, blue: 0.40)
    static let gradient = LinearGradient(colors: [blue.opacity(0.13), pink.opacity(0.07), orange.opacity(0.06)], startPoint: .topLeading, endPoint: .bottomTrailing)
}

struct LocalBook: Identifiable, Codable, Hashable {
    let id: UUID
    let name: String
    let filename: String
    let kind: String
    var favorite: Bool = false
    var progress: Double = 0
    var lastOpened: Date? = nil
    var collection: String = ""
}

@MainActor final class LocalLibrary: ObservableObject {
    @Published private(set) var books: [LocalBook] = []
    @Published var layoutGrid = true
    private let storeKey = "crea.reader.localBooks"
    private let layoutKey = "crea.reader.layoutGrid"

    init() {
        layoutGrid = UserDefaults.standard.object(forKey: layoutKey) as? Bool ?? true
        if let data = UserDefaults.standard.data(forKey: storeKey),
           let saved = try? JSONDecoder().decode([LocalBook].self, from: data) {
            books = saved.filter { FileManager.default.fileExists(atPath: url(for: $0).path) }
        }
    }

    private var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    func url(for book: LocalBook) -> URL { documents.appendingPathComponent(book.filename) }
    func add(url source: URL) throws {
        let ext = source.pathExtension.lowercased()
        guard ["epub", "pdf"].contains(ext) else {
            throw NSError(domain: "CREAReader", code: 1, userInfo: [NSLocalizedDescriptionKey: "Solo se admiten EPUB y PDF."])
        }
        let hasAccess = source.startAccessingSecurityScopedResource()
        defer { if hasAccess { source.stopAccessingSecurityScopedResource() } }
        let name = UUID().uuidString + "." + ext
        let destination = documents.appendingPathComponent(name)
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
        books.insert(LocalBook(id: UUID(), name: source.deletingPathExtension().lastPathComponent,
                               filename: name, kind: ext.uppercased()), at: 0)
        persist()
    }
    func update(_ book: LocalBook, mutate: (inout LocalBook) -> Void) {
        guard let index = books.firstIndex(where: { $0.id == book.id }) else { return }
        mutate(&books[index])
        persist()
    }
    func setGrid(_ value: Bool) { layoutGrid = value; UserDefaults.standard.set(value, forKey: layoutKey) }
    func remove(_ book: LocalBook) {
        try? FileManager.default.removeItem(at: url(for: book))
        books.removeAll { $0.id == book.id }
        persist()
    }
    private func persist() {
        if let data = try? JSONEncoder().encode(books) { UserDefaults.standard.set(data, forKey: storeKey) }
    }
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
    @StateObject private var library = LocalLibrary()
    @State private var tab: ReaderTab = .library
    @State private var importing = false
    @State private var selection: LocalBook?
    @State private var error: String?\n    @State private var notice: String?
    @State private var search = ""
    @State private var filter = "Todos"
    @State private var sort = "Últimos"
    @AppStorage("crea.reader.theme") private var theme = "Sistema"
    @Environment(\.colorScheme) private var colorScheme

    private var visibleBooks: [LocalBook] {
        let matching = library.books.filter { book in
            let text = search.isEmpty || book.name.localizedStandardContains(search)
            let kind = filter == "Todos" || (filter == "Favoritos" && book.favorite) ||
                (filter == "Leyendo" && book.progress > 0 && book.progress < 1) ||
                (filter == "Sin leer" && book.progress == 0) ||
                (filter == "Terminados" && book.progress >= 1) || filter == book.kind
            return text && kind
        }
        switch sort {
        case "Título": return matching.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        case "Progreso": return matching.sorted { $0.progress > $1.progress }
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
        }
        .preferredColorScheme(theme == "Oscuro" ? .dark : theme == "Claro" ? .light : nil)
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
                            ForEach(["Últimos", "Título", "Progreso"], id: \.self) { value in
                                Button(value) { sort = value }
                            }
                        } label: { Image(systemName: "arrow.up.arrow.down").font(.title3) }
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(["Todos", "Leyendo", "Sin leer", "Terminados", "Favoritos", "EPUB", "PDF"], id: \.self) { value in
                                Button(value) { filter = value }
                                    .font(.subheadline.weight(.medium))
                                    .padding(.horizontal, 13).padding(.vertical, 9)
                                    .background(filter == value ? CREAStyle.blue : Color.primary.opacity(0.07), in: Capsule())
                                    .foregroundStyle(filter == value ? Color.white : Color.primary)
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
            .searchable(text: $search, prompt: "Buscar en mi biblioteca")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { importing = true } label: { Image(systemName: "plus.circle.fill").font(.title3) }
                        .accessibilityLabel("Importar libros")
                }
            }
            .navigationDestination(item: $selection) { book in
                if book.kind == "EPUB" {
                    EPUBBookScreen(url: library.url(for: book), title: book.name)
                } else {
                    PDFReaderScreen(url: library.url(for: book), title: book.name, onProgress: { amount in
                        library.update(book) { $0.progress = amount; $0.lastOpened = Date() }
                    })
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
                    RoundedRectangle(cornerRadius: 15)
                        .fill(LinearGradient(colors: [CREAStyle.blue.opacity(0.78), CREAStyle.pink.opacity(0.77)], startPoint: .topLeading, endPoint: .bottomTrailing))
                    Image(systemName: book.kind == "PDF" ? "doc.richtext" : "book.closed.fill")
                        .font(.system(size: 46)).foregroundStyle(.white.opacity(0.85))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
                Image(systemName: book.kind == "PDF" ? "doc.richtext.fill" : "book.closed.fill")
                    .font(.title2).foregroundStyle(.white)
                    .frame(width: 60, height: 74)
                    .background(LinearGradient(colors: [CREAStyle.blue, CREAStyle.pink], startPoint: .top, endPoint: .bottom), in: RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 8) {
                    Text(book.name).font(.headline).foregroundStyle(.primary).lineLimit(2)
                    Text(book.kind).font(.caption).foregroundStyle(.secondary)
                    ProgressView(value: book.progress).tint(CREAStyle.green)
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
        Button(book.favorite ? "Quitar favorito" : "Añadir a favoritos", systemImage: book.favorite ? "heart.slash" : "heart") {
            library.update(book) { $0.favorite.toggle() }
        }
        Button("Marcar como terminado", systemImage: "checkmark.circle") {
            library.update(book) { $0.progress = 1 }
        }
        Button("Eliminar del dispositivo", systemImage: "trash", role: .destructive) { library.remove(book) }
    }

    private func open(_ book: LocalBook) {
        library.update(book) { $0.lastOpened = Date() }
        selection = book
    }

    private var catalogScreen: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Image(systemName: "cloud.fill").font(.system(size: 58)).foregroundStyle(CREAStyle.blue)
                Text("Catálogo en línea").font(.title2.bold())
                Text("Todos los libros de tu carpeta CREA Reader en Google Drive, sin descargarlos automáticamente.")
                    .multilineTextAlignment(.center).foregroundStyle(.secondary)
                Label("Conexión con Google Drive pendiente", systemImage: "lock.shield")
                    .font(.subheadline).padding(12).background(.regularMaterial, in: Capsule())
                Text("La versión iOS necesita OAuth propio y sincronización del catálogo. Tu biblioteca Android no se modifica.")
                    .font(.footnote).multilineTextAlignment(.center).foregroundStyle(.secondary)
            }
            .padding(24).frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(CREAStyle.gradient).navigationTitle("Catálogo en línea")
        }
    }

    private var exploreScreen: some View {
        NavigationStack {
            List {
                Section("Bibliotecas gratuitas en español") {
                    Link(destination: URL(string: "https://www.textos.info")!) {
                        Label("textos.info", systemImage: "book.closed")
                    }
                    Link(destination: URL(string: "https://www.gutenberg.org/browse/languages/es")!) {
                        Label("Proyecto Gutenberg · Español", systemImage: "globe")
                    }
                }
                Section {
                    Text("Puedes descargar EPUB o PDF desde estos sitios e importarlos en Mi biblioteca mediante Archivos.")
                        .foregroundStyle(.secondary)
                }
            }.scrollContentBackground(.hidden).background(CREAStyle.gradient).navigationTitle("Explorar")
        }
    }

    private var communityScreen: some View {
        NavigationStack {
            ContentUnavailableView("Comunidad CREA", systemImage: "person.2.fill",
                description: Text("Las recomendaciones y lecturas compartidas se habilitarán después de integrar la sincronización y el consentimiento de privacidad."))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(CREAStyle.gradient)
                .navigationTitle("Comunidad")
        }
    }

    private var settingsScreen: some View {
        NavigationStack {
            Form {
                Section("Apariencia") {
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
                    Label("Google Drive: no conectado", systemImage: "cloud.slash")
                    Text("La app iOS todavía no solicita permisos de Drive ni sube archivos.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Información") {
                    LabeledContent("Versión iOS", value: "0.8.3 · desarrollo")
                    Text("CREA Reader · Inspirado en la interfaz Android de CREA").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Ajustes")
        }
    }
}

struct PDFReaderScreen: View {
    let url: URL
    let title: String
    let onProgress: (Double) -> Void
    var body: some View {
        PDFReaderView(url: url, onProgress: onProgress)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

struct PDFReaderView: UIViewRepresentable {
    let url: URL
    let onProgress: (Double) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(onProgress: onProgress) }
    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.document = PDFDocument(url: url)
        context.coordinator.observe(view)
        return view
    }
    func updateUIView(_ view: PDFView, context: Context) {}
    final class Coordinator {
        let onProgress: (Double) -> Void
        var observer: NSObjectProtocol?
        init(onProgress: @escaping (Double) -> Void) { self.onProgress = onProgress }
        func observe(_ view: PDFView) {
            observer = NotificationCenter.default.addObserver(forName: Notification.Name.PDFViewPageChanged, object: view, queue: .main) { [weak view] _ in
                guard let view, let page = view.currentPage, let document = view.document, document.pageCount > 0 else { return }
                let position = document.index(for: page) + 1
                self.onProgress(Double(position) / Double(document.pageCount))
            }
        }
        deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
    }
}

struct BookFilePicker: UIViewControllerRepresentable {
    let onPick: ([URL]) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let epub = UTType(filenameExtension: "epub") ?? UTType(importedAs: "org.idpf.epub-container")
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf, epub], asCopy: true)
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

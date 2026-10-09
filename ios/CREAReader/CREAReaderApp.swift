import SwiftUI
import PDFKit
import UniformTypeIdentifiers

@main
struct CREAReaderApp: App {
    var body: some Scene {
        WindowGroup {
            ReaderHomeView()
        }
    }
}

struct LocalBook: Identifiable, Codable {
    let id: UUID
    let name: String
    let filename: String
    let kind: String
}

@MainActor
final class LocalLibrary: ObservableObject {
    @Published private(set) var books: [LocalBook] = []
    private let storeKey = "crea.reader.localBooks"

    init() {
        if let raw = UserDefaults.standard.data(forKey: storeKey),
           let saved = try? JSONDecoder().decode([LocalBook].self, from: raw) {
            books = saved.filter { FileManager.default.fileExists(atPath: url(for: $0).path) }
        }
    }

    func url(for book: LocalBook) -> URL {
        documents.appendingPathComponent(book.filename)
    }

    private var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    func add(url source: URL) throws {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let ext = source.pathExtension.lowercased()
        guard ["pdf", "epub"].contains(ext) else { return }
        let filename = UUID().uuidString + "." + ext
        try FileManager.default.copyItem(at: source, to: documents.appendingPathComponent(filename))
        books.insert(LocalBook(id: UUID(), name: source.deletingPathExtension().lastPathComponent,
                               filename: filename, kind: ext.uppercased()), at: 0)
        persist()
    }

    func remove(_ book: LocalBook) {
        try? FileManager.default.removeItem(at: url(for: book))
        books.removeAll { $0.id == book.id }
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(books) {
            UserDefaults.standard.set(data, forKey: storeKey)
        }
    }
}

struct ReaderHomeView: View {
    @StateObject private var library = LocalLibrary()
    @State private var showingImporter = false
    @State private var selectedBook: LocalBook?
    @State private var errorText: String?
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        TabView {
            NavigationStack {
                Group {
                    if library.books.isEmpty {
                        ContentUnavailableView("Tu biblioteca está vacía",
                            systemImage: "books.vertical",
                            description: Text("Importa un PDF o EPUB desde Archivos. La lectura EPUB llegará en una próxima versión."))
                    } else {
                        List {
                            ForEach(library.books) { book in
                                Button {
                                    if book.kind == "PDF" { selectedBook = book }
                                    else { errorText = "La lectura EPUB aún no está implementada en iOS." }
                                } label: {
                                    Label {
                                        VStack(alignment: .leading) {
                                            Text(book.name).foregroundStyle(.primary)
                                            Text(book.kind).font(.caption).foregroundStyle(.secondary)
                                        }
                                    } icon: { Image(systemName: "book.closed.fill").foregroundStyle(.blue) }
                                }
                            }
                            .onDelete { offsets in
                                for index in offsets { library.remove(library.books[index]) }
                            }
                        }
                    }
                }
                .navigationTitle("Mi biblioteca")
                .toolbar {
                    Button("Importar", systemImage: "plus") { showingImporter = true }
                }
                .fileImporter(isPresented: $showingImporter,
                              allowedContentTypes: [.pdf, UTType(filenameExtension: "epub") ?? .data],
                              allowsMultipleSelection: true) { result in
                    do {
                        for url in try result.get() { try library.add(url: url) }
                    } catch {
                        errorText = error.localizedDescription
                    }
                }
                .navigationDestination(item: $selectedBook) { book in
                    PDFReaderView(url: library.url(for: book)).navigationTitle(book.name)
                }
                .alert("CREA Reader", isPresented: Binding(
                    get: { errorText != nil },
                    set: { if !$0 { errorText = nil } })) {
                    Button("Aceptar") { errorText = nil }
                } message: {
                    Text(errorText ?? "")
                }
            }
            .tabItem { Label("Mi biblioteca", systemImage: "books.vertical") }

            NavigationStack {
                ContentUnavailableView("Catálogo en línea",
                    systemImage: "cloud",
                    description: Text("La conexión OAuth con Google Drive y el catálogo remoto están pendientes de implementar para iOS."))
                    .navigationTitle("Catálogo")
            }
            .tabItem { Label("Catálogo", systemImage: "cloud") }
        }
        .tint(.blue)
    }
}

struct PDFReaderView: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.document = PDFDocument(url: url)
        return view
    }
    func updateUIView(_ view: PDFView, context: Context) {}
}

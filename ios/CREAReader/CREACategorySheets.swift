import SwiftUI

struct CREALibraryCategorySheet: View {
    @ObservedObject var library: LocalLibrary
    @ObservedObject var catalog: CREADriveCatalog
    let book: LocalBook
    @State private var path: String
    @Environment(\.dismiss) private var dismiss
    init(library: LocalLibrary, catalog: CREADriveCatalog, book: LocalBook) {
        self.library = library; self.catalog = catalog; self.book = book; _path = State(initialValue: book.category ?? "")
    }
    var body: some View {
        NavigationStack {
            Form {
                Text(book.name)
                TextField("Categoría (por ejemplo Ciencias / Biología)", text: $path)
                Button("Sin categoría") { path = "" }
                ForEach(Array(Set(library.books.compactMap { $0.category } + catalog.folders.keys.filter { !$0.isEmpty })).sorted(), id: \.self) { category in
                    Button(category) { path = category }
                }
            }.navigationTitle("Categoría del libro")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Guardar") { let clean = path.trimmingCharacters(in: .whitespacesAndNewlines); library.update(book) { $0.category = clean.isEmpty ? nil : clean }; dismiss() }
                    }
                }
        }
    }
}
struct CREAUploadSheet: View {
    @ObservedObject var catalog: CREADriveCatalog
    @ObservedObject var library: LocalLibrary
    let book: LocalBook
    @State private var path = ""
    @State private var uploading = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Text(book.name).font(.headline)
                Picker("Carpeta de destino en Drive", selection: $path) {
                    Text("CREA Reader (raíz)").tag("")
                    ForEach(catalog.folders.keys.filter { !$0.isEmpty }.sorted(), id: \.self) { Text($0).tag($0) }
                }
                Text("Se reutiliza el archivo existente cuando coincide su contenido o su título y autor. La subida se realiza solo al pulsar Subir.").font(.footnote)
                if let issue = catalog.errorMessage { Text(issue).foregroundStyle(.red) }
                Button {
                    Task {
                        uploading = true; defer { uploading = false }
                        await catalog.upload(book, library: library, categoryPath: path.isEmpty ? nil : path)
                        if library.books.first(where: { $0.id == book.id })?.driveFileID != nil { dismiss() }
                    }
                } label: { if uploading { ProgressView("Subiendo…") } else { Label("Subir a Drive", systemImage: "icloud.and.arrow.up") } }.disabled(uploading || catalog.isLoading)
            }.navigationTitle("Subir libro")
                .toolbar { Button("Cerrar") { dismiss() }.disabled(uploading) }
                .task { await catalog.synchronize(); if let category = book.category, catalog.folders[category] != nil { path = category } }
        }
    }
}

struct CREACommunityDiscovery: View {
    @ObservedObject var catalog: CREADriveCatalog
    @ObservedObject var library: LocalLibrary
    @ObservedObject var progress: CREAProgressSync
    let sharingEnabled: Bool
    let open: (CREARemoteBook) -> Void
    private var feed: CREACommunityFeed {
        let titles = Dictionary(catalog.books.map { ($0.id, catalog.title(for: $0)) }, uniquingKeysWith: { a, _ in a })
        let mine = Set(catalog.books.filter { book in library.books.contains { $0.driveFileID == book.id } || (progress.percent(for: book.id) ?? 0) > 0 }.map { $0.id })
        return CREACommunityFeed.build(members: catalog.community, titles: titles, mine: mine)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if sharingEnabled && !feed.recommended.isEmpty {
                interests("Recomendados para ti", items: feed.recommended)
            }
            if !feed.readingNow.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Label("Leyendo ahora", systemImage: "book.pages").font(.headline)
                    ForEach(feed.readingNow) { point in
                        if let book = catalog.books.first(where: { $0.id == point.bookID }) {
                            Button { open(book) } label: {
                                VStack(alignment: .leading) {
                                    Text("\(point.nickname) · \(catalog.title(for: book))").foregroundStyle(.primary)
                                    Text("\(point.percent)%").font(.caption).foregroundStyle(.secondary)
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }
            interests("Lo más leído", items: feed.popular)
        }
    }
    private func interests(_ title: String, items: [CREACommunityInterest]) -> some View {
        Group {
            if !items.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text(LocalizedStringKey(title)).font(.headline)
                    ForEach(items) { item in
                        if let book = catalog.books.first(where: { $0.id == item.id }) {
                            Button { open(book) } label: {
                                HStack {
                                    Text(catalog.title(for: book)).foregroundStyle(.primary)
                                    Spacer()
                                    VStack(alignment: .trailing) {
                                        Text("\(item.readers) lectores · \(item.finishers) terminaron").font(.caption)
                                        if item.similar { Text("Lecturas parecidas a las tuyas").font(.caption2) }
                                    }.foregroundStyle(.secondary)
                                }
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }
}

import SwiftUI
import GoogleSignIn

struct CREACatalogScreen: View {
    @ObservedObject var catalog: CREADriveCatalog
    @ObservedObject var library: LocalLibrary
    @ObservedObject var auth: CREAAuthSession
    @ObservedObject var progress: CREAProgressSync
    @ObservedObject var ai: CREAReaderAI
    let onDetails: (CREARemoteBook) -> Void
    let onRead: (LocalBook) -> Void
    @ObservedObject private var transfers = CREABackgroundDownloads.shared
    @AppStorage("crea.reader.catalog.mode") private var mode = "grid"
    @AppStorage("crea.reader.catalog.coverSize") private var coverSize = "medium"
    @AppStorage("crea.reader.catalog.sort") private var sort = "Título"
    @AppStorage("crea.reader.ai.autoInsights") private var autoInsights = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var search = ""
    @State private var status = "Todos"
    @State private var category: String?
    @State private var selecting = false
    @State private var selected = Set<String>()
    @State private var confirmBatch = false
    private var listMode: CREAListMode { CREAListMode(rawValue: mode) ?? .grid }
    private var size: CREACoverSize { CREACoverSize(rawValue: coverSize) ?? .medium }
    private func local(_ book: CREARemoteBook) -> LocalBook? { library.books.first { $0.driveFileID == book.id } }
    private func percent(_ book: CREARemoteBook) -> Int { local(book).map { Int(($0.progress * 100).rounded()) } ?? progress.percent(for: book.id) ?? 0 }
    private var categories: [String] { Array(Set(catalog.folders.keys.filter { !$0.isEmpty } + catalog.books.flatMap { catalog.categories(for: $0) })).sorted() }
    private var visible: [CREARemoteBook] {
        let matches = catalog.books.filter { book in
            let downloaded = local(book) != nil, amount = percent(book)
            let state: Bool
            switch status {
            case "Sin descargar": state = !downloaded
            case "Descargados": state = downloaded
            case "Leyendo": state = amount > 0 && amount < 98
            case "Terminados": state = amount >= 98
            case "Nuevos": state = CREACatalogRules.recent(book.modifiedTime)
            case "Actualizaciones": state = catalog.needsUpdate(book, local: local(book))
            default: state = true
            }
            let info = catalog.insight(for: book)
            let text = [catalog.title(for: book), info?.author ?? book.authorFromName ?? "", catalog.categories(for: book).joined(separator: " ")].joined(separator: " ")
            let categoryMatch = category == nil || catalog.categories(for: book).contains { CREACategoryPaths.matches($0, selected: category) }
            return state && categoryMatch && CREACatalogRules.matches(text, query: search)
        }
        let sorted = matches.sorted { a, b in
            if sort == "Autor" { return CREACatalogRules.folded(catalog.insight(for: a)?.author ?? a.authorFromName ?? "") < CREACatalogRules.folded(catalog.insight(for: b)?.author ?? b.authorFromName ?? "") }
            if sort == "Cambios recientes" { return CREACatalogRules.milliseconds(a.modifiedTime) > CREACatalogRules.milliseconds(b.modifiedTime) }
            return CREACatalogRules.folded(catalog.title(for: a)) < CREACatalogRules.folded(catalog.title(for: b))
        }
        // The book last opened on this device (read online or downloaded) goes first, with any order chosen.
        // While searching by text the order of the search is kept.
        guard search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let last = sorted.max(by: { lastOpened($0) < lastOpened($1) }), lastOpened(last) > 0,
              let index = sorted.firstIndex(of: last) else { return sorted }
        return [last] + sorted.enumerated().filter { $0.offset != index }.map { $0.element }
    }

    /// When this book was last opened on this device: downloaded copy or online reading (0 if never).
    private func lastOpened(_ book: CREARemoteBook) -> TimeInterval {
        let own = local(book).map { ($0.progressUpdatedAt ?? $0.lastOpened ?? .distantPast).timeIntervalSince1970 } ?? 0
        let online = progress.checkpoint(for: book.id)?.updatedAt.timeIntervalSince1970 ?? 0
        return max(own, online, 0)
    }
    private func refresh() async {
        await catalog.synchronize()
        if auth.progressAuthorized { await progress.pull(catalog: catalog, library: library) }
        await catalog.synchronizeInsights(ai: ai, generate: autoInsights)
    }
    var body: some View {
        NavigationStack {
            Group {
                if auth.accountEmail == nil { VStack { Text("Inicia sesión para consultar tu catálogo de Google Drive"); CREAGoogleAccountView(auth: auth) }.padding() }
                else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            HStack {
                                Text("\(catalog.books.count) libros en Drive").font(.headline)
                                Spacer(); if catalog.isLoading { ProgressView() }
                                Button("Actualizar") { Task { await refresh() } }.disabled(catalog.isLoading)
                            }
                            if let date = catalog.lastSynced { HStack { Text("Última sincronización:"); Text(date, style: .relative) }.font(.caption).foregroundStyle(.secondary) }
                            if let issue = catalog.errorMessage {
                                Text(issue).foregroundStyle(.red)
                                if !catalog.books.isEmpty { Text("Puedes consultar la copia guardada; puede estar desactualizada.").font(.caption) }
                            }
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack { ForEach(["Todos", "Sin descargar", "Descargados", "Leyendo", "Terminados", "Nuevos", "Actualizaciones"], id: \.self) { value in
                                    CREAFilterChip(title: value, selected: status == value) { status = value }
                                } }
                            }
                            CREACategoryPicker(paths: categories, selected: $category)
                            if selecting {
                                HStack {
                                    Button("Seleccionar visibles") { selected.formUnion(visible.filter { local($0) == nil || catalog.needsUpdate($0, local: local($0)) }.map { $0.id }) }
                                    Spacer()
                                    Button("Descargar \(selected.count)") { confirmBatch = true }.disabled(selected.isEmpty)
                                }
                            }
                            if ai.canUseForCatalog && auth.communityAuthorized {
                                Menu {
                                    Button("Crear y compartir fichas faltantes", systemImage: "sparkles") { Task { await catalog.synchronizeInsights(ai: ai, generate: true) } }
                                } label: { Label(catalog.generatingInsights ? "Creando fichas…" : "Fichas de los libros", systemImage: "sparkles").font(.subheadline).frame(minHeight: 44) }.disabled(catalog.generatingInsights)
                            }
                            if let message = catalog.insightStatus { Text(message).font(.caption) }
                            if visible.isEmpty { ContentUnavailableView("Sin resultados", systemImage: "books.vertical") }
                            if listMode == .list || dynamicTypeSize.isAccessibilitySize {
                                LazyVStack(spacing: 12) { ForEach(visible) { book in card(book, list: true) } }
                            } else {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: max(160, size.columnWidth(listMode))), spacing: 16, alignment: .top)], spacing: 16) { ForEach(visible) { book in card(book, list: false) } }
                            }
                        }.padding(16).frame(maxWidth: 1000).frame(maxWidth: .infinity)
                    }.refreshable { await refresh() }
                }
            }.background(CREAStyle.gradient).navigationTitle("Catálogo").navigationBarTitleDisplayMode(.inline)
                .searchable(text: $search, prompt: "Buscar título, autor o categoría")
                .toolbar {
                    CREAViewMenu(mode: $mode, size: $coverSize)
                    Menu { Picker("Orden", selection: $sort) { ForEach(["Título", "Autor", "Cambios recientes"], id: \.self) { Text(LocalizedStringKey($0)) } } } label: { Label("Orden", systemImage: "arrow.up.arrow.down") }
                    Button(selecting ? "Terminar selección" : "Seleccionar") { selecting.toggle(); if !selecting { selected = [] } }
                }
                .task(id: auth.accountEmail) { if auth.accountEmail != nil, catalog.needsAutoSync { await refresh() } }
                .onReceive(transfers.$records) { records in
                    for record in records.values where record.state == "completed" && record.ownerID == GIDSignIn.sharedInstance.currentUser?.userID { progress.promoteDownloadedBook(record.book.id, library: library) }
                }
                .confirmationDialog("Descargar selección", isPresented: $confirmBatch) {
                    Button("Iniciar descargas") {
                        let batch = catalog.books.filter { selected.contains($0.id) }
                        Task { await catalog.downloadBatch(batch) }; selected = []; selecting = false
                    }
                } message: { Text("Se descargarán \(selected.count) libros. Se comprueba el espacio libre antes de comenzar.") }
        }
    }
    private func activate(_ book: CREARemoteBook) { if selecting { if selected.contains(book.id) { selected.remove(book.id) } else { selected.insert(book.id) } } else { onDetails(book) } }
    private func card(_ book: CREARemoteBook, list: Bool) -> some View {
        let info = catalog.insight(for: book), downloaded = local(book)
        return VStack(alignment: .leading, spacing: 12) {
            if list {
                HStack(alignment: .top, spacing: 14) {
                    coverButton(book).frame(width: 72)
                    VStack(alignment: .leading, spacing: 10) {
                        heading(book, info: info, grid: false)
                        statusLabel(book, downloaded: downloaded)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    selectionMark(book)
                }
            } else {
                coverButton(book).overlay(alignment: .topTrailing) { selectionMark(book).padding(8) }
                if listMode != .covers { heading(book, info: info, grid: true) }
                statusLabel(book, downloaded: downloaded)
            }
            CREABookProgress(percent: percent(book))
            transferActions(book, downloaded: downloaded)
        }
        .modifier(CREAShelfSurface())
        .contextMenu {
            Button("Seleccionar para descarga") { selecting = true; selected.insert(book.id) }
            Button("Ficha y vista previa") { onDetails(book) }
        }
    }

    private func coverButton(_ book: CREARemoteBook) -> some View {
        Button { activate(book) } label: {
            CREABookCoverFrame { CREARemoteCoverView(book: book, catalog: catalog, height: nil) }
        }.buttonStyle(.plain).accessibilityLabel(catalog.title(for: book))
    }

    @ViewBuilder private func selectionMark(_ book: CREARemoteBook) -> some View {
        if selecting {
            Button { activate(book) } label: {
                Image(systemName: selected.contains(book.id) ? "checkmark.circle.fill" : "circle")
                    .font(.title2).foregroundStyle(CREAStyle.blue)
                    .frame(width: 44, height: 44)
                    .background(.regularMaterial, in: Circle())
            }.buttonStyle(.plain)
                .accessibilityLabel(selected.contains(book.id) ? "Quitar de selección" : "Seleccionar libro")
        }
    }

    private func heading(_ book: CREARemoteBook, info: CREAAIBookInfo?, grid: Bool) -> some View {
        Button { activate(book) } label: {
            CREABookHeading(title: catalog.title(for: book), author: info?.author ?? book.authorFromName,
                            category: catalog.categories(for: book).joined(separator: " · "), grid: grid)
        }.buttonStyle(.plain)
    }

    @ViewBuilder private func transferActions(_ book: CREARemoteBook, downloaded: LocalBook?) -> some View {
        if let transfer = transfers.record(for: book.id), transfer.active {
            HStack {
                if let amount = transfer.progress { ProgressView(value: amount) } else { ProgressView() }
                Button { transfers.cancel(book.id) } label: {
                    Image(systemName: "xmark.circle.fill").frame(width: 44, height: 44)
                }.accessibilityLabel("Cancelar descarga").disabled(transfer.state == "processing")
            }
            Text(transfer.state == "processing" ? "Verificando y guardando…" : "Descargando…")
                .font(.caption).foregroundStyle(.secondary)
        } else if !selecting {
            if let transfer = transfers.record(for: book.id), let error = transfer.error {
                Button { onDetails(book) } label: { Label("No se pudo descargar", systemImage: "exclamationmark.circle").font(.caption) }
                    .accessibilityHint(error)
            }
            if let downloaded {
                Button { onRead(downloaded) } label: {
                    Label("Leer", systemImage: "book").frame(maxWidth: .infinity, minHeight: 32)
                }.buttonStyle(.borderedProminent)
                if catalog.needsUpdate(book, local: downloaded) {
                    Button("Actualizar copia") { download(book) }.font(.caption).frame(minHeight: 44)
                }
            } else {
                Button { download(book) } label: {
                    Label("Descargar", systemImage: "arrow.down").frame(maxWidth: .infinity, minHeight: 32)
                }.buttonStyle(.bordered)
            }
        }
    }

    private func download(_ book: CREARemoteBook) {
        Task { await catalog.download(book, into: library); progress.promoteDownloadedBook(book.id, library: library) }
    }

    private func statusLabel(_ book: CREARemoteBook, downloaded: LocalBook?) -> some View {
        HStack(spacing: 6) {
            Text(book.isEPUB ? "EPUB" : "PDF").font(.caption2.weight(.semibold))
                .padding(.horizontal, 7).padding(.vertical, 4)
                .background(Color.primary.opacity(0.05), in: Capsule())
            Spacer(minLength: 0)
            Image(systemName: catalog.needsUpdate(book, local: downloaded) ? "arrow.triangle.2.circlepath" : downloaded == nil ? "cloud" : "checkmark.circle.fill")
                .foregroundStyle(downloaded == nil ? Color.secondary : CREAStyle.blue)
                .accessibilityLabel(catalog.needsUpdate(book, local: downloaded) ? "Actualización disponible" : downloaded == nil ? "En línea" : "Descargado")
        }.foregroundStyle(.secondary)
    }
}

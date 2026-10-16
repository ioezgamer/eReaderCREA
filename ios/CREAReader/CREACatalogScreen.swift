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
        return matches.sorted { a, b in
            if sort == "Autor" { return CREACatalogRules.folded(catalog.insight(for: a)?.author ?? a.authorFromName ?? "") < CREACatalogRules.folded(catalog.insight(for: b)?.author ?? b.authorFromName ?? "") }
            if sort == "Cambios recientes" { return CREACatalogRules.milliseconds(a.modifiedTime) > CREACatalogRules.milliseconds(b.modifiedTime) }
            return CREACatalogRules.folded(catalog.title(for: a)) < CREACatalogRules.folded(catalog.title(for: b))
        }
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
                                    Button(LocalizedStringKey(value)) { status = value }.buttonStyle(.bordered).tint(status == value ? CREAStyle.blue : Color.secondary)
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
                                Button { Task { await catalog.synchronizeInsights(ai: ai, generate: true) } } label: { Label(catalog.generatingInsights ? "Creando fichas…" : "Crear y compartir fichas faltantes", systemImage: "sparkles") }.disabled(catalog.generatingInsights)
                            }
                            if let message = catalog.insightStatus { Text(message).font(.caption) }
                            if visible.isEmpty { ContentUnavailableView("Sin resultados", systemImage: "books.vertical") }
                            if listMode == .list {
                                LazyVStack(spacing: 12) { ForEach(visible) { book in card(book, list: true) } }
                            } else {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: size.columnWidth(listMode)), spacing: 12)], spacing: 14) { ForEach(visible) { book in card(book, list: false) } }
                            }
                        }.padding()
                    }.refreshable { await refresh() }
                }
            }.background(CREAStyle.gradient).navigationTitle("Catálogo en línea")
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
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                Button { activate(book) } label: {
                    CREARemoteCoverView(book: book, catalog: catalog, height: list ? 88 : nil).aspectRatio(0.72, contentMode: .fit).frame(width: list ? 64 : nil).clipped()
                }.buttonStyle(.plain).accessibilityLabel(catalog.title(for: book))
                if list {
                    VStack(alignment: .leading, spacing: 8) { title(book, info: info); statusLabel(book, downloaded: downloaded) }
                    Spacer()
                }
                if selecting { Image(systemName: selected.contains(book.id) ? "checkmark.circle.fill" : "circle").accessibilityLabel(selected.contains(book.id) ? "Seleccionado" : "Sin seleccionar") }
            }
            if !list && listMode != .covers { title(book, info: info) }
            if !list { statusLabel(book, downloaded: downloaded) }
            if percent(book) > 0 { ProgressView(value: Double(percent(book)) / 100).tint(CREAStyle.green); Text("\(percent(book))%").font(.caption) }
            ForEach(Array(progress.devices.filter { $0.readings.contains { $0.driveFileId == book.id } }.prefix(3))) { device in
                if let point = device.readings.first(where: { $0.driveFileId == book.id }) { Text("\(device.name): \(point.percent)%").font(.caption2).foregroundStyle(.secondary) }
            }
            if let transfer = transfers.record(for: book.id), transfer.active {
                if let amount = transfer.progress { ProgressView(value: amount) } else { ProgressView() }
                Text(transfer.state == "processing" ? "Verificando y guardando…" : "\(ByteCountFormatter.string(fromByteCount: transfer.copied, countStyle: .file)) descargados").font(.caption)
                Button("Cancelar descarga") { transfers.cancel(book.id) }.disabled(transfer.state == "processing")
            } else {
                if let transfer = transfers.record(for: book.id), let error = transfer.error { Text(error).font(.caption).foregroundStyle(.red) }
                if !selecting {
                    HStack {
                        if let downloaded { Button("Leer") { onRead(downloaded) }.buttonStyle(.borderedProminent) }
                        if downloaded == nil || catalog.needsUpdate(book, local: downloaded) {
                            Button(downloaded == nil ? "Descargar" : "Actualizar copia") { Task { await catalog.download(book, into: library); progress.promoteDownloadedBook(book.id, library: library) } }.buttonStyle(.bordered)
                        }
                    }
                }
            }
        }.padding(12).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
            .contextMenu { Button("Seleccionar para descarga") { selecting = true; selected.insert(book.id) }; Button("Ficha y vista previa") { onDetails(book) } }
    }
    private func title(_ book: CREARemoteBook, info: CREAAIBookInfo?) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Button { activate(book) } label: { Text(catalog.title(for: book)).font(.headline).foregroundStyle(.primary).lineLimit(3) }.buttonStyle(.plain)
            if let author = info?.author ?? book.authorFromName, !author.isEmpty { Text(author).font(.caption).foregroundStyle(.secondary) }
            Text(catalog.categories(for: book).joined(separator: " · ")).font(.caption2).foregroundStyle(.secondary)
            if info != nil { Label("Ficha sugerida con IA", systemImage: "sparkles").font(.caption2) }
        }
    }
    private func statusLabel(_ book: CREARemoteBook, downloaded: LocalBook?) -> some View {
        HStack {
            Text(book.isEPUB ? "EPUB" : "PDF")
            Spacer()
            if catalog.needsUpdate(book, local: downloaded) { Label("Actualización disponible", systemImage: "arrow.triangle.2.circlepath") }
            else { Label(downloaded == nil ? "En línea" : "Descargado", systemImage: downloaded == nil ? "cloud" : "checkmark.circle") }
        }.font(.caption).foregroundStyle(.secondary)
    }
}

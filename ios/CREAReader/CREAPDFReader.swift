import SwiftUI
import PDFKit
import AVFoundation

@MainActor final class CREAPDFModel: ObservableObject {
    let view = PDFView()
    @Published var ready = false
    @Published var failure: String?
    @Published var matches: [PDFSelection] = []
    @Published var page = 1
    @Published var pageCount = 0
    @Published var hash = ""
    @Published var speaking = false
    private let voice = AVSpeechSynthesizer()
    private var observer: NSObjectProtocol?
    private var rendered: [PDFAnnotation] = []
    private var onProgress: ((Double) -> Void)?
    func load(_ url: URL, progress: Double, onProgress: @escaping (Double) -> Void) async {
        self.onProgress = onProgress
        hash = (try? await Task.detached { try CREABookIdentity.hash(url) }.value) ?? ""
        guard let document = PDFDocument(url: url), !document.isLocked, document.pageCount > 0 else {
            failure = "No se pudo abrir el PDF. Verifica que el archivo sea válido y no esté cifrado."; return
        }
        view.autoScales = true; view.displayMode = .singlePageContinuous; view.document = document
        pageCount = document.pageCount
        let fraction = progress.isFinite ? max(0, min(1, progress)) : 0
        let index = max(0, min(pageCount - 1, Int(ceil(fraction * Double(pageCount))) - 1))
        if let target = document.page(at: index) { view.go(to: target) }
        page = index + 1
        observer = NotificationCenter.default.addObserver(forName: .PDFViewPageChanged, object: view, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, let current = self.view.currentPage, let document = self.view.document else { return }
                self.page = document.index(for: current) + 1
                self.onProgress?(Double(self.page) / Double(document.pageCount))
            }
        }
        ready = true; refreshAnnotations()
    }
    func go(_ page: Int) {
        guard let target = view.document?.page(at: max(0, min(pageCount - 1, page - 1))) else { return }
        view.go(to: target)
    }
    func search(_ query: String) {
        matches = query.isEmpty ? [] : view.document?.findString(query, withOptions: .caseInsensitive) ?? []
    }
    func show(_ selection: PDFSelection) {
        view.setCurrentSelection(selection, animate: true); view.go(to: selection)
    }
    private func locator(page: Int, bounds: CGRect? = nil) -> String? {
        var value: [String: Any] = ["page": page]
        if let bounds { value["bounds"] = [bounds.minX, bounds.minY, bounds.width, bounds.height] }
        return (try? JSONSerialization.data(withJSONObject: value)).flatMap { String(data: $0, encoding: .utf8) }
    }
    func bookmark() {
        guard let json = locator(page: page) else { return }
        CREAAnnotationStore.shared.add(hash: hash, kind: "bookmark", locator: json, title: "Página \(page)")
    }
    func highlight(note: String) -> Bool {
        guard let selection = view.currentSelection, let text = selection.string, !text.isEmpty,
              let document = view.document else { return false }
        for target in selection.pages {
            // Store per-line rectangles to avoid highlighting the gaps between lines.
            for line in selection.selectionsByLine() where line.pages.contains(target) {
                let bounds = line.bounds(for: target)
                guard let json = locator(page: document.index(for: target) + 1, bounds: bounds) else { continue }
                CREAAnnotationStore.shared.add(hash: hash, kind: "highlight", locator: json, title: line.string ?? text, note: note)
            }
        }
        refreshAnnotations(); return true
    }
    func open(_ item: CREAAnnotation) {
        guard let data = item.locatorJSON.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let page = json["page"] as? Int else { return }
        go(page)
    }
    func refreshAnnotations() {
        for annotation in rendered { annotation.page?.removeAnnotation(annotation) }; rendered = []
        for item in CREAAnnotationStore.shared.items(for: hash) where item.kind != "bookmark" {
            guard let data = item.locatorJSON.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let page = json["page"] as? Int, let rect = json["bounds"] as? [Double], rect.count == 4,
                  let target = view.document?.page(at: page - 1) else { continue }
            let annotation = PDFAnnotation(bounds: CGRect(x: rect[0], y: rect[1], width: rect[2], height: rect[3]), forType: .highlight, withProperties: nil)
            annotation.color = (item.color == "green" ? UIColor.systemGreen : item.color == "pink" ? .systemPink : .systemYellow).withAlphaComponent(0.4)
            annotation.contents = item.note; target.addAnnotation(annotation); rendered.append(annotation)
        }
    }
    func speak() {
        if voice.isSpeaking { voice.pauseSpeaking(at: .word); speaking = false; return }
        if voice.isPaused { voice.continueSpeaking(); speaking = true; return }
        let text = view.currentSelection?.string ?? view.currentPage?.string ?? ""
        guard !text.isEmpty else { failure = "Esta página no contiene texto; no se aplica OCR a imágenes."; return }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "es-ES")
        CREAVoiceConfiguration.apply(to: utterance)
        voice.speak(utterance); speaking = true
    }
    func stop() { voice.stopSpeaking(at: .immediate); speaking = false }
    func close() {
        stop()
        if let observer { NotificationCenter.default.removeObserver(observer); self.observer = nil }
    }
    deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
}

struct PDFReaderScreen: View {
    let url: URL
    let title: String
    let onProgress: (Double) -> Void
    var initialProgress: Double = 0
    @StateObject private var model = CREAPDFModel()
    @ObservedObject private var annotations = CREAAnnotationStore.shared
    @State private var tools = false
    @State private var query = ""
    @State private var targetPage = "1"
    @State private var note = ""
    @State private var message: String?
    @State private var edit: CREAAnnotation?
    var body: some View {
        Group {
            if model.ready { CREAPDFContainer(view: model.view) }
            else if let issue = model.failure { ContentUnavailableView("No se pudo abrir el PDF", systemImage: "doc", description: Text(issue)) }
            else { ProgressView("Abriendo PDF…") }
        }.navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .task(id: url) { await model.load(url, progress: initialProgress, onProgress: onProgress) }
            .onDisappear { model.close() }
            .onReceive(annotations.$records) { _ in model.refreshAnnotations() }
            .toolbar {
                Menu {
                    Button("Buscar, páginas y notas", systemImage: "list.bullet") { tools = true }
                    Button("Guardar marcador", systemImage: "bookmark") { model.bookmark(); message = "Marcador guardado" }
                    Button("Leer página / selección", systemImage: "speaker.wave.2") { model.speak() }
                    Button("Detener voz", systemImage: "stop") { model.stop() }
                    Button("Página continua") { model.view.displayMode = .singlePageContinuous }
                    Button("Página individual") { model.view.displayMode = .singlePage }
                } label: { Label("Herramientas PDF", systemImage: "ellipsis.circle") }.disabled(!model.ready)
            }
            .safeAreaInset(edge: .bottom) {
                if model.ready { Text("Página \(model.page) de \(model.pageCount)").font(.caption).padding(8).frame(maxWidth: .infinity).background(.regularMaterial) }
            }
            .alert("CREA Reader", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("Aceptar") { message = nil }
            } message: { Text(message ?? "") }
            .sheet(isPresented: $tools) {
                NavigationStack {
                    List {
                        Section("Navegación") {
                            TextField("Número de página", text: $targetPage).keyboardType(.numberPad)
                            Button("Ir a página") { if let page = Int(targetPage) { model.go(page); tools = false } }
                            if let root = model.view.document?.outlineRoot { PDFOutlineRows(root: root) { destination in model.view.go(to: destination); tools = false } }
                        }
                        Section("Buscar texto") {
                            TextField("Texto a buscar", text: $query).onSubmit { model.search(query) }
                            Button("Buscar") { model.search(query) }
                            ForEach(Array(model.matches.enumerated()), id: \.offset) { _, selection in
                                Button(selection.string ?? "Resultado") { model.show(selection); tools = false }
                            }
                        }
                        Section("Resaltar selección") {
                            TextField("Nota opcional", text: $note, axis: .vertical)
                            Button("Guardar resaltado") {
                                message = model.highlight(note: note) ? "Resaltado guardado" : "Selecciona texto del PDF primero."
                                tools = false; note = ""
                            }
                        }
                        Section("Marcadores y notas") {
                            ForEach(annotations.items(for: model.hash)) { item in
                                VStack(alignment: .leading) {
                                    Button(item.title) { model.open(item); tools = false }
                                    if !item.note.isEmpty { Text(item.note).font(.caption) }
                                    Button("Editar") { edit = item }.font(.caption)
                                }.swipeActions { Button("Eliminar", role: .destructive) { annotations.remove(item) } }
                            }
                        }
                    }.navigationTitle("Herramientas PDF").toolbar { Button("Cerrar") { tools = false } }
                        .sheet(item: $edit) { item in CREAAnnotationEditor(item: item) { model.refreshAnnotations() } }
                }
            }
    }
}

struct CREAPDFContainer: UIViewRepresentable {
    let view: PDFView
    func makeUIView(context: Context) -> PDFView { view }
    func updateUIView(_ uiView: PDFView, context: Context) {}
}
struct PDFOutlineRows: View {
    let root: PDFOutline
    let onSelect: (PDFDestination) -> Void
    var body: some View {
        ForEach(0..<root.numberOfChildren, id: \.self) { index in
            if let child = root.child(at: index) {
                Button(child.label ?? "Sección") { if let destination = child.destination { onSelect(destination) } }
                if child.numberOfChildren > 0 { AnyView(PDFOutlineRows(root: child, onSelect: onSelect)).padding(.leading, 12) }
            }
        }
    }
}

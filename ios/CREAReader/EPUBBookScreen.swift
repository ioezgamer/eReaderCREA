import SwiftUI
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

@MainActor
final class EPUBBookModel: ObservableObject, PublicationSpeechSynthesizerDelegate {
    @Published var navigator: EPUBNavigatorViewController?
    @Published var message: String?
    private var locationObserver: CREALocationObserver?
    private var publication: Publication?
    private var preservedSelection: (text: String, context: String)?
    @Published var contents: [ReadiumShared.Link] = []
    @Published var results: [Locator] = []
    @Published var searching = false
    @Published var searchError: String?
    @Published var speaking = false
    @Published var speechStarted = false
    @Published var bookHash = ""
    @Published var selectedAnnotation: CREAAnnotation?
    @Published var metadata = ""
    private var speech: PublicationSpeechSynthesizer?
    private let voiceDelegate = CREAVoiceEngineDelegate()
    private var searchGeneration = UUID()

    func go(_ locator: Locator) async { _ = await navigator?.go(to: locator) }
    func go(_ link: ReadiumShared.Link) async { _ = await navigator?.go(to: link) }
    func goToPercent(_ fraction: Double) async {
        guard let publication, let positions = try? await publication.positions().get(), !positions.isEmpty else { return }
        let index = min(positions.count - 1, max(0, Int(fraction * Double(positions.count - 1))))
        await go(positions[index])
    }
    func search(_ query: String) async {
        let generation = UUID(); searchGeneration = generation
        results = []; searchError = nil
        guard let publication, !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        searching = true
        defer { if searchGeneration == generation { searching = false } }
        do {
            let iterator = try await publication.search(query: query).get()
            defer { iterator.close() }
            while !Task.isCancelled && searchGeneration == generation {
                guard let page = try await iterator.next().get() else { break }
                guard searchGeneration == generation else { return }
                results.append(contentsOf: page.locators)
                if results.count >= 500 { break }
            }
        } catch { if searchGeneration == generation { searchError = "No se pudo buscar: \(error.localizedDescription)" } }
    }
    func bookmark() {
        guard let location = navigator?.currentLocation, let json = location.jsonString else { return }
        CREAAnnotationStore.shared.add(hash: bookHash, kind: "bookmark", locator: json,
            title: location.title ?? "Posición \(Int((location.locations.totalProgression ?? 0) * 100))%")
    }
    func highlight(note: String, color: String, underline: Bool) -> Bool {
        guard let selection = navigator?.currentSelection?.locator,
              let text = selection.text.highlight, !text.isEmpty,
              let json = selection.jsonString else { return false }
        CREAAnnotationStore.shared.add(hash: bookHash, kind: underline ? "underline" : "highlight",
            locator: json, title: text, note: note, color: color)
        refreshDecorations(); return true
    }
    func refreshDecorations() {
        let decorations: [Decoration] = CREAAnnotationStore.shared.items(for: bookHash).compactMap { item in
            guard item.kind != "bookmark", let locator = try? Locator(jsonString: item.locatorJSON) else { return nil }
            let tint: UIColor = item.color == "green" ? .systemGreen : item.color == "pink" ? .systemPink : .systemYellow
            return Decoration(id: item.id.uuidString, locator: locator,
                style: item.kind == "underline" ? .underline(tint: tint) : .highlight(tint: tint))
        }
        navigator?.apply(decorations: decorations, in: "annotations")
    }
    func toggleSpeech() {
        guard let speech else { message = "Este libro no contiene texto compatible con lectura en voz alta."; return }
        if !speechStarted { speech.start(from: navigator?.currentLocation) }
        else { speech.pauseOrResume() }
    }
    func stopSpeech() { speech?.stop() }
    func nextSpeech() { speech?.next() }
    func previousSpeech() { speech?.previous() }
    func publicationSpeechSynthesizer(_ synthesizer: PublicationSpeechSynthesizer, stateDidChange state: PublicationSpeechSynthesizer.State) {
        switch state {
        case .stopped: speaking = false; speechStarted = false; navigator?.apply(decorations: [], in: "tts")
        case .paused: speaking = false; speechStarted = true
        case .playing(let utterance, let range):
            speaking = true; speechStarted = true
            let locator = range ?? utterance.locator
            navigator?.apply(decorations: [Decoration(id: "voice", locator: locator, style: .underline(tint: .systemBlue))], in: "tts")
            Task { await go(utterance.locator) }
        }
    }
    func publicationSpeechSynthesizer(_ synthesizer: PublicationSpeechSynthesizer, utterance: PublicationSpeechSynthesizer.Utterance, didFailWithError error: PublicationSpeechSynthesizer.Error) {
        message = "No se pudo reproducir la voz: \(error)"
    }

    struct AIChapter {
        let text: String
        let title: String?
    }

    func preservePassageSelection() {
        if let current = selectedPassage() {
            preservedSelection = current
        }
    }

    func selectedPassage() -> (text: String, context: String)? {
        guard let current = activePassage() else { return preservedSelection }
        return current
    }

    private func activePassage() -> (text: String, context: String)? {
        guard let text = navigator?.currentSelection?.locator.text,
              let picked = text.highlight?.trimmingCharacters(in: .whitespacesAndNewlines),
              !picked.isEmpty else { return nil }
        let before = String((text.before ?? "").suffix(300))
        let after = String((text.after ?? "").prefix(300))
        return (text: String(picked.prefix(3000)), context: before + picked + after)
    }

    func currentChapter() async -> AIChapter? {
        guard let publication,
              let location = navigator?.currentLocation,
              let resource = publication.get(location.href),
              let bytes = try? await resource.read().get() else { return nil }
        guard bytes.count <= 1_500_000,
              let raw = String(data: bytes, encoding: .utf8) else { return nil }
        var plain = raw
            .replacingOccurrences(of: "(?is)<(script|style|head|nav)[^>]*>.*?</\\1>",
                                  with: " ", options: .regularExpression)
            .replacingOccurrences(of: "(?is)<[^>]+>", with: " ", options: .regularExpression)
        for (entity, replacement) in [
            ("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"),
            ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'")
        ] {
            plain = plain.replacingOccurrences(of: entity, with: replacement)
        }
        plain = plain.components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }.joined(separator: " ")
        guard plain.count >= 200 else { return nil }
        return AIChapter(text: String(plain.prefix(30_000)), title: location.title)
    }

    func applyPreferences(fontSize: Double, scroll: Bool, darkMode: Bool, sepia: Bool = false, lineHeight: Double = 1.5, margins: Double = 1, font: String = "Serif") {
        navigator?.submitPreferences(EPUBPreferences(
            fontFamily: font == "Sans" ? .sansSerif : .serif,
            fontSize: 100 * fontSize,
            lineHeight: lineHeight,
            pageMargins: margins,
            publisherStyles: false,
            scroll: scroll,
            theme: darkMode ? .dark : sepia ? .sepia : .light
        ))
    }
    func load(url: URL, initialLocatorJSON: String?, onProgress: @escaping (Double) -> Void, onLocator: @escaping (String) -> Void, persistFileLocator: Bool = true) async {
        bookHash = (try? await Task.detached { try CREABookIdentity.hash(url) }.value) ?? ""
        let http = DefaultHTTPClient()
        let retriever = AssetRetriever(httpClient: http)
        let opener = PublicationOpener(
            parser: DefaultPublicationParser(
                httpClient: http,
                assetRetriever: retriever,
                pdfFactory: DefaultPDFDocumentFactory()
            )
        )
        guard let absolute = url.anyURL.absoluteURL else {
            message = "No se puede localizar este EPUB."
            return
        }
        switch await retriever.retrieve(url: absolute) {
        case .failure(let issue):
            message = "No se pudo leer el archivo EPUB: \(issue)"
        case .success(let asset):
            switch await opener.open(asset: asset, allowUserInteraction: false) {
            case .failure(let issue):
                message = "No se pudo abrir la publicación: \(issue)"
            case .success(let publication):
                self.publication = publication
                contents = publication.tableOfContents
                metadata = "\(publication.metadata.title ?? url.lastPathComponent)\n\(publication.metadata.authors.map { $0.name }.joined(separator: ", "))"
                let delegate = voiceDelegate
                speech = PublicationSpeechSynthesizer(publication: publication, engineFactory: { AVTTSEngine(delegate: delegate) })
                speech?.delegate = self
                do {
                    let lastLocation = initialLocatorJSON ?? (persistFileLocator ? UserDefaults.standard.string(forKey: "crea.epub.locator." + url.lastPathComponent) : nil)
                    let initial = lastLocation.flatMap { try? Locator(jsonString: $0) }
                    let reader = try EPUBNavigatorViewController(publication: publication, initialLocation: initial)
                    let observer = CREALocationObserver(url: url, onProgress: onProgress, onLocator: onLocator, persistFileLocator: persistFileLocator)
                    locationObserver = observer
                    reader.delegate = observer
                    navigator = reader
                    reader.observeDecorationInteractions(inGroup: "annotations") { [weak self] event in
                        self?.selectedAnnotation = CREAAnnotationStore.shared.records.first { $0.id.uuidString == event.decoration.id }
                    }
                    refreshDecorations()
                } catch {
                    message = error.localizedDescription
                }
            }
        }
    }
}

struct EPUBBookScreen: View {
    let url: URL
    let title: String
    let onProgress: (Double) -> Void
    let initialLocatorJSON: String?
    let onLocator: (String) -> Void
    @ObservedObject var ai: CREAReaderAI
    var isTemporaryOnline: Bool = false
    @State private var showAI = false
    @StateObject private var model = EPUBBookModel()
    @ObservedObject private var annotations = CREAAnnotationStore.shared
    @State private var showTools = false
    @State private var showHighlight = false
    @State private var annotationNote = ""
    @State private var annotationColor = "yellow"
    @State private var underline = false
    @State private var feedback: String?
    @AppStorage("crea.reader.epub.fontScale") private var fontSize = 1.1
    @AppStorage("crea.reader.epub.scroll") private var scroll = false
    @AppStorage("crea.reader.epub.dark") private var darkMode = false
    @AppStorage("crea.reader.epub.sepia") private var sepia = false
    @AppStorage("crea.reader.epub.lineHeight") private var lineHeight = 1.5
    @AppStorage("crea.reader.epub.margins") private var margins = 1.0
    @AppStorage("crea.reader.epub.font") private var font = "Serif"

    private func applyPreferences() {
        model.applyPreferences(fontSize: fontSize, scroll: scroll, darkMode: darkMode, sepia: sepia, lineHeight: lineHeight, margins: margins, font: font)
    }
    var body: some View {
        Group {
            if let navigator = model.navigator {
                EPUBNavigatorContainer(navigator: navigator)
            } else if let message = model.message {
                ContentUnavailableView("No se pudo abrir el EPUB", systemImage: "exclamationmark.triangle", description: Text(message))
            } else {
                ProgressView("Abriendo EPUB…")
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
                    .task(id: url) {
                await model.load(url: url, initialLocatorJSON: initialLocatorJSON, onProgress: onProgress, onLocator: onLocator, persistFileLocator: !isTemporaryOnline)
                applyPreferences()
            }
            .sheet(isPresented: $showAI) {
                CREAAIReaderSheet(ai: ai, reader: model, bookTitle: title)
            }
            .sheet(isPresented: $showTools) { EPUBToolsSheet(model: model) }
            .sheet(isPresented: $showHighlight) {
                NavigationStack {
                    Form {
                        Text("Selecciona texto en el libro antes de guardar un resaltado.")
                        TextField("Nota opcional", text: $annotationNote, axis: .vertical)
                        annotationColorPicker
                        Toggle("Subrayar", isOn: $underline)
                    }
                    .navigationTitle("Resaltado y nota")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { showHighlight = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Guardar") {
                                if model.highlight(note: annotationNote, color: annotationColor, underline: underline) {
                                    showHighlight = false; annotationNote = ""
                                } else { feedback = "Selecciona un fragmento de texto en el EPUB primero."; showHighlight = false }
                            }
                        }
                    }
                }
            }
            .sheet(item: $model.selectedAnnotation) { item in
                CREAAnnotationEditor(item: item) { model.refreshDecorations() }
            }
            .alert("CREA Reader", isPresented: Binding(get: { feedback != nil }, set: { if !$0 { feedback = nil } })) {
                Button("Aceptar") { feedback = nil }
            } message: { Text(feedback ?? "") }
            .onReceive(annotations.$records) { _ in model.refreshDecorations() }
            .onDisappear { model.stopSpeech() }
            .safeAreaInset(edge: .bottom) {
                if model.speechStarted {
                    HStack(spacing: 24) {
                        Button { model.previousSpeech() } label: { Label("Anterior", systemImage: "backward.end") }
                        Button { model.toggleSpeech() } label: { Label(model.speaking ? "Pausar" : "Continuar", systemImage: model.speaking ? "pause.fill" : "play.fill") }
                        Button { model.nextSpeech() } label: { Label("Siguiente", systemImage: "forward.end") }
                        Button { model.stopSpeech() } label: { Label("Detener", systemImage: "stop.fill") }
                    }.labelStyle(.iconOnly).frame(minHeight: 44).padding().background(.regularMaterial)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Índice, búsqueda y anotaciones", systemImage: "list.bullet") { showTools = true }
                        Button("Guardar marcador", systemImage: "bookmark") { model.bookmark(); feedback = "Marcador guardado" }
                        Button("Resaltar selección / añadir nota", systemImage: "highlighter") { showHighlight = true }
                        Button("Leer en voz alta", systemImage: "speaker.wave.2") { model.toggleSpeech() }
                    } label: { Label("Herramientas del libro", systemImage: "ellipsis.circle") }
                    .disabled(model.navigator == nil)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if ai.canUse {
                        Button {
                            model.preservePassageSelection()
                            showAI = true
                        } label: {
                            Label("Claude", systemImage: "sparkles")
                        }
                        .accessibilityLabel("Abrir asistente de Claude para el libro")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Reducir letra", systemImage: "textformat.size.smaller") {
                            fontSize = max(0.8, fontSize - 0.2)
                        }
                        Button("Aumentar letra", systemImage: "textformat.size.larger") {
                            fontSize = min(2.5, fontSize + 0.2)
                        }
                        Toggle("Desplazamiento vertical", isOn: $scroll)
                        Toggle("Modo oscuro del libro", isOn: $darkMode)
                        Toggle("Fondo sepia", isOn: $sepia)
                        Picker("Fuente", selection: $font) { Text("Serif").tag("Serif"); Text("Sans").tag("Sans") }
                        Button("Aumentar interlineado") { lineHeight = min(2.5, lineHeight + 0.2) }
                        Button("Reducir interlineado") { lineHeight = max(1, lineHeight - 0.2) }
                        Button("Aumentar márgenes") { margins = min(3, margins + 0.25) }
                        Button("Reducir márgenes") { margins = max(0.25, margins - 0.25) }
                    } label: {
                        Image(systemName: "textformat.size")
                    }
                }
            }
            .onChange(of: fontSize) { _, _ in applyPreferences() }
            .onChange(of: scroll) { _, _ in applyPreferences() }
            .onChange(of: darkMode) { _, _ in applyPreferences() }
            .onChange(of: sepia) { _, _ in applyPreferences() }
            .onChange(of: font) { _, _ in applyPreferences() }
            .onChange(of: lineHeight) { _, _ in applyPreferences() }
            .onChange(of: margins) { _, _ in applyPreferences() }
    }
    private var annotationColorPicker: some View {
        Picker("Color", selection: $annotationColor) {
            Text("Amarillo").tag("yellow"); Text("Verde").tag("green"); Text("Rosa").tag("pink")
        }
    }
}

struct CREAAnnotationEditor: View {
    let item: CREAAnnotation
    let onSave: () -> Void
    @State private var note: String
    @State private var color: String
    @Environment(\.dismiss) private var dismiss
    init(item: CREAAnnotation, onSave: @escaping () -> Void) {
        self.item = item; self.onSave = onSave
        _note = State(initialValue: item.note); _color = State(initialValue: item.color)
    }
    var body: some View {
        NavigationStack {
            Form {
                Text(item.title)
                TextField("Nota", text: $note, axis: .vertical)
                Picker("Color", selection: $color) {
                    Text("Amarillo").tag("yellow"); Text("Verde").tag("green"); Text("Rosa").tag("pink")
                }
                Button("Eliminar anotación", role: .destructive) {
                    CREAAnnotationStore.shared.remove(item); onSave(); dismiss()
                }
            }.navigationTitle("Editar anotación")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Guardar") { CREAAnnotationStore.shared.edit(item, note: note, color: color); onSave(); dismiss() }
                    }
                }
        }
    }
}

struct EPUBToolsSheet: View {
    @ObservedObject var model: EPUBBookModel
    @ObservedObject private var annotations = CREAAnnotationStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var tab = "Índice"
    @State private var percent = 0.0
    @State private var edit: CREAAnnotation?
    var body: some View {
        NavigationStack {
            VStack {
                Picker("Herramienta", selection: $tab) {
                    ForEach(["Índice", "Buscar", "Notas", "Ficha"], id: \.self) { Text($0) }
                }.pickerStyle(.segmented).padding(.horizontal)
                if tab == "Índice" {
                    List {
                        Section("Ir a una posición") {
                            Slider(value: $percent, in: 0...1)
                            Button("Ir al \(Int(percent * 100))%") { Task { await model.goToPercent(percent); dismiss() } }
                        }
                        EPUBContentsRows(links: model.contents) { link in Task { await model.go(link); dismiss() } }
                    }
                } else if tab == "Buscar" {
                    List {
                        TextField("Buscar texto en el libro", text: $query).onSubmit { Task { await model.search(query) } }
                        Button("Buscar") { Task { await model.search(query) } }.disabled(model.searching)
                        if model.searching { ProgressView("Buscando…") }
                        if let error = model.searchError { Text(error).foregroundStyle(.red) }
                        if !model.searching && model.results.isEmpty { Text("No hay resultados") }
                        ForEach(Array(model.results.enumerated()), id: \.offset) { _, locator in
                            Button { Task { await model.go(locator); dismiss() } } label: {
                                VStack(alignment: .leading) {
                                    Text(locator.title ?? "Resultado")
                                    Text((locator.text.before ?? "") + (locator.text.highlight ?? "") + (locator.text.after ?? "")).font(.caption).lineLimit(4)
                                }
                            }
                        }
                        if model.results.count >= 500 { Text("Se muestran los primeros 500 resultados. Usa un texto más específico.") }
                    }
                } else if tab == "Notas" {
                    List {
                        ForEach(annotations.items(for: model.bookHash)) { item in
                            VStack(alignment: .leading) {
                                Button {
                                    if let locator = try? Locator(jsonString: item.locatorJSON) { Task { await model.go(locator); dismiss() } }
                                } label: { Label(item.title, systemImage: item.kind == "bookmark" ? "bookmark" : "highlighter").lineLimit(3) }
                                if !item.note.isEmpty { Text(item.note).font(.caption) }
                                Button("Editar nota") { edit = item }.font(.caption)
                            }.swipeActions { Button("Eliminar", role: .destructive) { annotations.remove(item) } }
                        }
                        if annotations.items(for: model.bookHash).isEmpty { Text("Todavía no hay marcadores ni notas") }
                    }
                } else {
                    ScrollView { Text(model.metadata).frame(maxWidth: .infinity, alignment: .leading).padding() }
                }
            }.navigationTitle("Herramientas de lectura")
                .toolbar { Button("Cerrar") { dismiss() } }
                .sheet(item: $edit) { item in CREAAnnotationEditor(item: item) { model.refreshDecorations() } }
        }
    }
}

struct EPUBContentsRows: View {
    let links: [ReadiumShared.Link]
    let onSelect: (ReadiumShared.Link) -> Void
    var body: some View {
        ForEach(Array(links.enumerated()), id: \.offset) { _, link in
            Button(link.title ?? "Sección") { onSelect(link) }
            if !link.children.isEmpty {
                AnyView(EPUBContentsRows(links: link.children, onSelect: onSelect)).padding(.leading, 12)
            }
        }
    }
}

struct EPUBNavigatorContainer: UIViewControllerRepresentable {
    let navigator: EPUBNavigatorViewController
    func makeUIViewController(context: Context) -> EPUBNavigatorViewController { navigator }
    func updateUIViewController(_ controller: EPUBNavigatorViewController, context: Context) {}
}

@MainActor
final class CREALocationObserver: EPUBNavigatorDelegate {
    let url: URL
    let onProgress: (Double) -> Void
    let onLocator: (String) -> Void
    let persistFileLocator: Bool
    init(url: URL, onProgress: @escaping (Double) -> Void, onLocator: @escaping (String) -> Void, persistFileLocator: Bool) {
        self.url = url
        self.onProgress = onProgress
        self.onLocator = onLocator
        self.persistFileLocator = persistFileLocator
    }
    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {
        NSLog("CREA Reader: Readium navigation error: %@", String(describing: error))
    }
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        if let serialized = locator.jsonString {
            if persistFileLocator {
                UserDefaults.standard.set(serialized, forKey: "crea.epub.locator." + url.lastPathComponent)
            }
            onLocator(serialized)
        }
        if let progression = locator.locations.totalProgression {
            onProgress(max(0, min(1, progression)))
        }
    }
}

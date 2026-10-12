import SwiftUI
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

@MainActor final class CREAMediaModel: ObservableObject, AudioNavigatorDelegate {
    @Published var audio: AudioNavigator?
    @Published var image: UIImage?
    @Published var links: [ReadiumShared.Link] = []
    @Published var index = 0
    @Published var time = 0.0
    @Published var duration = 0.0
    @Published var playing = false
    @Published var error: String?
    @Published var ready = false
    private var publication: Publication?
    private var onProgress: ((Double) -> Void)?
    private var onLocator: ((String) -> Void)?
    func load(url: URL, audioMode: Bool, initialProgress: Double, locator: String?, onProgress: @escaping (Double) -> Void, onLocator: @escaping (String) -> Void) async {
        self.onProgress = onProgress; self.onLocator = onLocator
        if !audioMode, let bitmap = UIImage(contentsOfFile: url.path) {
            image = bitmap; ready = true; onProgress(1); return
        }
        let http = DefaultHTTPClient(), retriever = AssetRetriever(httpClient: DefaultHTTPClient())
        let opener = PublicationOpener(parser: DefaultPublicationParser(httpClient: http, assetRetriever: retriever, pdfFactory: DefaultPDFDocumentFactory()))
        guard let absolute = url.anyURL.absoluteURL else { error = "Archivo no disponible"; return }
        do {
            let asset = try await retriever.retrieve(url: absolute).get()
            let publication = try await opener.open(asset: asset, allowUserInteraction: false).get()
            self.publication = publication; links = publication.readingOrder
            guard !links.isEmpty else { error = "La publicación no contiene recursos legibles."; return }
            if audioMode {
                let initial = locator.flatMap { try? Locator(jsonString: $0) }
                let navigator = AudioNavigator(publication: publication, initialLocation: initial)
                navigator.delegate = self; audio = navigator
            } else {
                index = min(links.count - 1, max(0, Int(ceil(initialProgress * Double(links.count))) - 1))
                await showImage(index)
            }
            ready = true
        } catch { self.error = "No se pudo abrir la publicación: " + error.localizedDescription }
    }
    func showImage(_ target: Int) async {
        guard let publication, links.indices.contains(target) else { return }
        do {
            guard let resource = publication.get(links[target]) else { throw URLError(.fileDoesNotExist) }
            let data = try await resource.read().get()
            guard data.count <= 50_000_000, let bitmap = UIImage(data: data) else { throw URLError(.cannotDecodeContentData) }
            image = bitmap; index = target
            onProgress?(Double(target + 1) / Double(links.count))
            if let locator = await publication.locate(links[target]), let json = locator.jsonString { onLocator?(json) }
        } catch { self.error = "No se pudo leer esta imagen: " + error.localizedDescription }
    }
    func navigator(_ navigator: AudioNavigator, playbackDidChange info: MediaPlaybackInfo) {
        index = info.resourceIndex; time = info.time; duration = info.duration ?? 0; playing = info.state == .playing
    }
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        if let json = locator.jsonString { onLocator?(json) }
        if let fraction = locator.locations.totalProgression { onProgress?(fraction) }
    }
    func navigator(_ navigator: Navigator, presentError error: NavigatorError) { self.error = "Error de reproducción: \(error)" }
}

struct CREAMediaScreen: View {
    let url: URL
    let title: String
    let audioMode: Bool
    let initialProgress: Double
    let locator: String?
    let onProgress: (Double) -> Void
    let onLocator: (String) -> Void
    @StateObject private var model = CREAMediaModel()
    @State private var scale = 1.0
    @State private var speed = 1.0
    var body: some View {
        VStack(spacing: 20) {
            if let error = model.error { Text(error).foregroundStyle(.red).padding() }
            if !model.ready && model.error == nil { ProgressView("Abriendo…") }
            if audioMode, let navigator = model.audio {
                Image(systemName: "headphones").font(.system(size: 80)).foregroundStyle(CREAStyle.blue)
                Text(model.links.indices.contains(model.index) ? model.links[model.index].title ?? title : title).font(.headline)
                Slider(value: Binding(get: { model.time }, set: { target in Task { await navigator.seek(to: target) } }), in: 0...max(1, model.duration))
                    .accessibilityLabel("Posición del audio").padding(.horizontal)
                Text("\(Int(model.time)) s / \(Int(model.duration)) s").monospacedDigit()
                HStack(spacing: 24) {
                    Button { Task { await navigator.seek(by: -15) } } label: { Label("Retroceder 15 segundos", systemImage: "gobackward.15") }
                    Button { navigator.playPause() } label: { Label(model.playing ? "Pausar" : "Reproducir", systemImage: model.playing ? "pause.circle.fill" : "play.circle.fill") }
                    Button { Task { await navigator.seek(by: 15) } } label: { Label("Avanzar 15 segundos", systemImage: "goforward.15") }
                }.labelStyle(.iconOnly).font(.largeTitle)
                Picker("Velocidad", selection: $speed) { ForEach([0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { Text(String(format: "%.2gx", $0)) } }
                    .onChange(of: speed) { _, value in navigator.submitPreferences(AudioPreferences(speed: value)) }
                List(Array(model.links.enumerated()), id: \.offset) { _, link in
                    Button(link.title ?? "Pista") { Task { _ = await navigator.go(to: link); navigator.play() } }
                }
            } else if let image = model.image {
                ScrollView([.horizontal, .vertical]) {
                    Image(uiImage: image).resizable().scaledToFit().frame(width: UIScreen.main.bounds.width * scale)
                }.gesture(MagnifyGesture().onChanged { scale = max(1, min(5, $0.magnification)) })
                if !model.links.isEmpty {
                    HStack {
                        Button("Anterior") { Task { await model.showImage(model.index - 1) } }.disabled(model.index == 0)
                        Spacer(); Text("\(model.index + 1) / \(model.links.count)"); Spacer()
                        Button("Siguiente") { Task { await model.showImage(model.index + 1) } }.disabled(model.index >= model.links.count - 1)
                    }.padding()
                }
            }
        }.navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .task(id: url) { await model.load(url: url, audioMode: audioMode, initialProgress: initialProgress, locator: locator, onProgress: onProgress, onLocator: onLocator) }
            .onDisappear { model.audio?.pause() }
    }
}

import SwiftUI
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

@MainActor
final class EPUBBookModel: ObservableObject {
    @Published var navigator: EPUBNavigatorViewController?
    @Published var message: String?
    private var locationObserver: CREALocationObserver?

    func applyPreferences(fontSize: Double, scroll: Bool, darkMode: Bool) {
        navigator?.submitPreferences(EPUBPreferences(
            fontSize: fontSize,
            publisherStyles: false,
            scroll: scroll,
            theme: darkMode ? .dark : .light
        ))
    }
    func load(url: URL, initialLocatorJSON: String?, onProgress: @escaping (Double) -> Void, onLocator: @escaping (String) -> Void) async {
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
                do {
                    let lastLocation = initialLocatorJSON ?? UserDefaults.standard.string(forKey: "crea.epub.locator." + url.lastPathComponent)
                    let initial = lastLocation.flatMap { try? Locator(jsonString: $0) }
                    let reader = try EPUBNavigatorViewController(publication: publication, initialLocation: initial)
                    let observer = CREALocationObserver(url: url, onProgress: onProgress, onLocator: onLocator)
                    locationObserver = observer
                    reader.delegate = observer
                    navigator = reader
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
    @StateObject private var model = EPUBBookModel()
    @AppStorage("crea.reader.epub.fontScale") private var fontSize = 1.1
    @AppStorage("crea.reader.epub.scroll") private var scroll = false
    @AppStorage("crea.reader.epub.dark") private var darkMode = false

    private func applyPreferences() {
        model.applyPreferences(fontSize: fontSize, scroll: scroll, darkMode: darkMode)
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
                await model.load(url: url, initialLocatorJSON: initialLocatorJSON, onProgress: onProgress, onLocator: onLocator)
                applyPreferences()
            }
            .toolbar {
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
                    } label: {
                        Image(systemName: "textformat.size")
                    }
                }
            }
            .onChange(of: fontSize) { _, _ in applyPreferences() }
            .onChange(of: scroll) { _, _ in applyPreferences() }
            .onChange(of: darkMode) { _, _ in applyPreferences() }
    }
}

struct EPUBNavigatorContainer: UIViewControllerRepresentable {
    let navigator: EPUBNavigatorViewController
    func makeUIViewController(context: Context) -> EPUBNavigatorViewController { navigator }
    func updateUIViewController(_ controller: EPUBNavigatorViewController, context: Context) {}
}

@MainActor
final class CREALocationObserver: NavigatorDelegate {
    let url: URL
    let onProgress: (Double) -> Void
    let onLocator: (String) -> Void
    init(url: URL, onProgress: @escaping (Double) -> Void, onLocator: @escaping (String) -> Void) {
        self.url = url
        self.onProgress = onProgress
        self.onLocator = onLocator
    }
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        if let serialized = locator.jsonString {
            UserDefaults.standard.set(serialized, forKey: "crea.epub.locator." + url.lastPathComponent)
            onLocator(serialized)
        }
        if let progression = locator.locations.totalProgression {
            onProgress(max(0, min(1, progression)))
        }
    }
}

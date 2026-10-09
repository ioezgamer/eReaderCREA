import SwiftUI
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

@MainActor
final class EPUBBookModel: ObservableObject {
    @Published var navigator: EPUBNavigatorViewController?
    @Published var message: String?
    func load(url: URL) async {
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
                    navigator = try EPUBNavigatorViewController(publication: publication, initialLocation: nil)
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
    @StateObject private var model = EPUBBookModel()
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
        .task(id: url) { await model.load(url: url) }
    }
}

struct EPUBNavigatorContainer: UIViewControllerRepresentable {
    let navigator: EPUBNavigatorViewController
    func makeUIViewController(context: Context) -> EPUBNavigatorViewController { navigator }
    func updateUIViewController(_ controller: EPUBNavigatorViewController, context: Context) {}
}

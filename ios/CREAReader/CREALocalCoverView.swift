import SwiftUI
import PDFKit
import ReadiumShared
import ReadiumStreamer

struct CREALocalCoverView: View {
    let url: URL
    let kind: String
    var title: String? = nil
    @State private var cover: UIImage?

    var body: some View {
        ZStack {
            if let cover {
                Image(uiImage: cover).resizable().scaledToFill()
            } else {
                LinearGradient(colors: [.blue, .purple, .pink], startPoint: .topLeading, endPoint: .bottomTrailing)
                Color.black.opacity(0.2)
                VStack(spacing: 10) {
                    Image(systemName: kind == "PDF" ? "doc.richtext" : "book.closed.fill").font(.system(size: 30))
                    if let title { Text(title).font(.headline).multilineTextAlignment(.center).lineLimit(4) }
                }.foregroundStyle(.white).padding(10)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .task(id: url) {
            if kind == "PDF" {
                if let page = PDFDocument(url: url)?.page(at: 0) {
                    cover = page.thumbnail(of: CGSize(width: 300, height: 420), for: .mediaBox)
                }
                return
            }
            let http = DefaultHTTPClient()
            let retriever = AssetRetriever(httpClient: http)
            let opener = PublicationOpener(parser: DefaultPublicationParser(
                httpClient: http, assetRetriever: retriever,
                pdfFactory: DefaultPDFDocumentFactory()))
            guard let absolute = url.anyURL.absoluteURL else { return }
            if case let .success(asset) = await retriever.retrieve(url: absolute),
               case let .success(publication) = await opener.open(asset: asset, allowUserInteraction: false) {
                if case let .success(image) = await publication.cover(), let image { cover = image }
            }
        }
    }
}

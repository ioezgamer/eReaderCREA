import SwiftUI
import PDFKit

struct CREARemoteCoverView: View {
    let book: CREARemoteBook
    @ObservedObject var catalog: CREADriveCatalog
    var height: CGFloat? = 145
    @State private var cover: UIImage?
    var body: some View {
        Group {
            if let cover {
                Image(uiImage: cover).resizable().scaledToFit()
            } else {
                ZStack {
                    LinearGradient(colors: [CREAStyle.blue, CREAStyle.pink], startPoint: .topLeading, endPoint: .bottomTrailing)
                    Color.black.opacity(0.2)
                    VStack(spacing: 10) {
                        Image(systemName: book.isEPUB ? "book.closed.fill" : "doc.text.fill").font(.title2)
                        Text(catalog.title(for: book)).font(.headline).multilineTextAlignment(.center).lineLimit(4)
                    }.foregroundStyle(.white).padding(12)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .task(id: book.id + "|" + book.versionKey) {
            cover = nil
            if let data = await catalog.cover(for: book), let image = UIImage(data: data) {
                cover = image
            }
        }
    }
}

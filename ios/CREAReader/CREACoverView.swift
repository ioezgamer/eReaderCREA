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
                    LinearGradient(colors: [Color(red: 0.12, green: 0.24, blue: 0.36), Color(red: 0.19, green: 0.36, blue: 0.45)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    VStack(spacing: 10) {
                        Image(systemName: book.isEPUB ? "book.closed.fill" : "doc.text.fill").font(.title2)
                        Text(catalog.title(for: book)).font(.subheadline.weight(.medium)).multilineTextAlignment(.center).lineLimit(3)
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

import SwiftUI
import PDFKit

struct CREARemoteCoverView: View {
    let book: CREARemoteBook
    @ObservedObject var catalog: CREADriveCatalog
    @State private var cover: UIImage?
    var body: some View {
        Group {
            if let cover {
                Image(uiImage: cover).resizable().scaledToFit()
            } else {
                Image(systemName: book.isEPUB ? "book.closed.fill" : "doc.text.fill")
                    .font(.largeTitle).foregroundStyle(.blue)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 145)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .task(id: book.id) {
            if let data = await catalog.cover(for: book), let image = UIImage(data: data) {
                cover = image
            }
        }
    }
}

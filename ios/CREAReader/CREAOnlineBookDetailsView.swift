import SwiftUI
import PDFKit

/// A catalogue book is inspected before downloading; nothing is added to My Library
/// until the reader explicitly chooses "Descargar".
struct CREAOnlineBookDetailsView: View {
    let book: CREARemoteBook
    @ObservedObject var catalog: CREADriveCatalog
    @ObservedObject var library: LocalLibrary
    let onReadLocal: (LocalBook) -> Void
    let onReadOnline: (URL) -> Void
    let onDownloaded: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var metadata: CREABookMetadata?
    @State private var onlineFile: URL?
    @State private var transferredToReader = false
    @State private var busy = false
    @State private var errorMessage: String?
    @State private var previewOpen = false
    @State private var pdfPageCount: Int?
    @State private var pdfSamples: [UIImage] = []

    private var downloadedBook: LocalBook? {
        library.books.first(where: { $0.driveFileID == book.id })
    }

    private var formatLabel: String { book.isEPUB ? "EPUB" : "PDF" }

    private var pageLabel: String {
        if let pdfPageCount { return "\(pdfPageCount) páginas" }
        if book.isEPUB {
            if let pages = metadata?.editionPages { return "\(pages) páginas (edición)" }
            return "Paginación adaptable"
        }
        return "Páginas al consultar vista previa"
    }

    private var sizeLabel: String {
        guard let size = book.size.flatMap(Double.init) else { return "Tamaño no disponible" }
        return ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file)
    }

    private func temporaryFile() async throws -> URL {
        if let onlineFile, FileManager.default.fileExists(atPath: onlineFile.path) {
            return onlineFile
        }
        let downloaded = try await catalog.temporaryReadingCopy(book)
        onlineFile = downloaded
        return downloaded
    }

    private func capturePDFInformation(_ pdf: PDFDocument) {
        pdfPageCount = pdf.pageCount
        let attributes = pdf.documentAttributes ?? [:]
        var details = metadata ?? CREABookMetadata(
            title: nil, author: nil, summary: book.description,
            publisher: nil, language: nil, published: nil,
            editionPages: nil, excerpt: nil
        )
        if details.title == nil, let value = attributes[PDFDocumentAttribute.titleAttribute] as? String,
           !value.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).isEmpty {
            details.title = value
        }
        if details.author == nil, let value = attributes[PDFDocumentAttribute.authorAttribute] as? String,
           !value.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).isEmpty {
            details.author = value
        }
        if details.summary == nil, let value = attributes[PDFDocumentAttribute.subjectAttribute] as? String,
           !value.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).isEmpty {
            details.summary = value
        }
        metadata = details
    }

    private func preview() {
        if book.isEPUB {
            previewOpen.toggle()
            return
        }
        Task {
            busy = true
            defer { busy = false }
            do {
                let file = try await temporaryFile()
                guard let pdf = PDFDocument(url: file), pdf.pageCount > 0 else {
                    throw NSError(domain: "CREAReader", code: 4, userInfo: [
                        NSLocalizedDescriptionKey: "El archivo PDF no pudo abrirse."
                    ])
                }
                capturePDFInformation(pdf)
                if pdfSamples.isEmpty {
                    var images: [UIImage] = []
                    for index in 0..<min(pdf.pageCount, 3) {
                        if let page = pdf.page(at: index) {
                            images.append(page.thumbnail(of: CGSize(width: 720, height: 1000),
                                                         for: .mediaBox))
                        }
                    }
                    pdfSamples = images
                }
                previewOpen = true
                errorMessage = nil
            } catch { errorMessage = error.localizedDescription }
        }
    }

    private func readOnline() {
        Task {
            busy = true
            defer { busy = false }
            do {
                let file = try await temporaryFile()
                transferredToReader = true
                onReadOnline(file)
            } catch { errorMessage = error.localizedDescription }
        }
    }

    private func downloadToLibrary() {
        Task {
            busy = true
            defer { busy = false }
            await catalog.download(book, into: library)
            if library.books.contains(where: { $0.driveFileID == book.id }) {
                errorMessage = nil
                onDownloaded()
            } else {
                errorMessage = catalog.errorMessage ?? "No se pudo descargar este libro."
            }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top, spacing: 18) {
                    CREARemoteCoverView(book: book, catalog: catalog)
                        .frame(width: 125, height: 178)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    VStack(alignment: .leading, spacing: 9) {
                        Text(metadata?.title ?? book.displayName)
                            .font(.title3.bold())
                            .fixedSize(horizontal: false, vertical: true)
                        if let author = metadata?.author, !author.isEmpty {
                            Label(author, systemImage: "person")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                        Label(formatLabel, systemImage: book.isEPUB ? "book.closed" : "doc")
                            .font(.caption).foregroundStyle(.secondary)
                        Label(pageLabel, systemImage: "text.book.closed")
                            .font(.caption).foregroundStyle(.secondary)
                        Label(sizeLabel, systemImage: "externaldrive")
                            .font(.caption).foregroundStyle(.secondary)
                        if let category = book.category {
                            Text(category).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Acerca de este libro").font(.headline)
                    if let text = metadata?.summary, !text.isEmpty {
                        Text(text).font(.subheadline).textSelection(.enabled)
                    } else {
                        Text("Este archivo no incluye una sinopsis. Puedes consultar un fragmento o leerlo antes de descargarlo.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    if let publisher = metadata?.publisher, !publisher.isEmpty {
                        Label("Editorial: " + publisher, systemImage: "building.2")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    if let published = metadata?.published, !published.isEmpty {
                        Text("Fecha de publicación: " + published)
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))

                VStack(spacing: 10) {
                    Button {
                        if let downloadedBook { onReadLocal(downloadedBook) }
                        else { readOnline() }
                    } label: {
                        Label(downloadedBook == nil ? "Leer en línea" : "Leer libro descargado",
                              systemImage: "book.pages")
                            .frame(maxWidth: .infinity, minHeight: 46)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy)

                    Button(action: preview) {
                        Label(previewOpen ? "Ocultar vista previa" : "Vista previa del libro",
                              systemImage: "eye")
                            .frame(maxWidth: .infinity, minHeight: 46)
                    }
                    .buttonStyle(.bordered)
                    .disabled(busy)

                    if downloadedBook == nil {
                        Button(action: downloadToLibrary) {
                            Label("Descargar a Mi biblioteca", systemImage: "arrow.down.circle")
                                .frame(maxWidth: .infinity, minHeight: 46)
                        }
                        .buttonStyle(.bordered)
                        .disabled(busy || catalog.downloading.contains(book.id))
                    } else {
                        Label("Disponible sin conexión en Mi biblioteca", systemImage: "checkmark.circle.fill")
                            .font(.footnote).foregroundStyle(.green)
                    }
                }

                if busy || catalog.downloading.contains(book.id) {
                    ProgressView("Preparando el libro…")
                        .frame(maxWidth: .infinity)
                }
                if let errorMessage {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .font(.footnote).foregroundStyle(.red)
                }

                if previewOpen {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Vista previa").font(.title3.bold())
                        if book.isEPUB {
                            if let excerpt = metadata?.excerpt, !excerpt.isEmpty {
                                Text("Fragmento auténtico de las primeras secciones del EPUB")
                                    .font(.caption).foregroundStyle(.secondary)
                                Text(excerpt).font(.body).textSelection(.enabled)
                            } else {
                                Text("Este EPUB no permite extraer un fragmento breve. Puedes abrirlo con «Leer en línea».")
                                    .foregroundStyle(.secondary)
                            }
                        } else if pdfSamples.isEmpty {
                            Text("No hay páginas disponibles para la vista previa.")
                                .foregroundStyle(.secondary)
                        } else {
                            Text("Primeras \(pdfSamples.count) páginas de \(pdfPageCount ?? 0)")
                                .font(.caption).foregroundStyle(.secondary)
                            ForEach(pdfSamples.indices, id: \.self) { index in
                                Image(uiImage: pdfSamples[index])
                                    .resizable().scaledToFit()
                                    .frame(maxWidth: .infinity)
                                    .background(.white, in: RoundedRectangle(cornerRadius: 8))
                                    .accessibilityLabel("Página \(index + 1) del libro")
                            }
                        }
                    }
                    .padding(16)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
                }

                Text("La lectura en línea utiliza una copia temporal, no descarga el libro a Mi biblioteca. Necesitas conexión para abrirlo por primera vez.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            .padding(18)
        }
        .background(CREAStyle.gradient)
        .navigationTitle("Información del libro")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Cerrar") { dismiss() }
            }
        }
        .task(id: book.id) {
            metadata = await catalog.inspect(book)
            // PDFKit must load the PDF to give an accurate page count.
            // Only preload small PDFs after the user enters this detail page.
            if !book.isEPUB, let length = book.size.flatMap(Int64.init),
               length <= 15 * 1024 * 1024 {
                do {
                    let url = try await temporaryFile()
                    if let pdf = PDFDocument(url: url) { capturePDFInformation(pdf) }
                } catch { /* The explicit preview action offers a retry. */ }
            }
        }
        .onDisappear {
            if !transferredToReader, let onlineFile {
                try? FileManager.default.removeItem(at: onlineFile)
            }
        }
    }
}

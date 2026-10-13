import SwiftUI

struct CREAFreeRepository: Identifiable {
    let name: String
    let description: String
    let group: String
    let website: String
    let feed: String?
    var id: String { name }
    static let all: [Self] = [
        Self(name: "textos.info", description: "Obras clásicas en español", group: "En español", website: "https://www.textos.info", feed: "https://www.textos.info/populares.atom"),
        Self(name: "Project Gutenberg · español", description: "Clásicos de dominio público", group: "En español", website: "https://www.gutenberg.org/browse/languages/es", feed: "https://www.gutenberg.org/ebooks/search.opds/?query=l.es&sort_order=downloads"),
        Self(name: "Elejandría", description: "Literatura clásica en EPUB", group: "En español", website: "https://www.elejandria.com", feed: nil),
        Self(name: "Biblioteca Virtual Miguel de Cervantes", description: "Literatura española e hispanoamericana", group: "En español", website: "https://www.cervantesvirtual.com", feed: nil),
        Self(name: "Wikisource en español", description: "Textos clásicos exportables", group: "En español", website: "https://es.wikisource.org", feed: nil),
        Self(name: "Libroteca", description: "Literatura universal", group: "En español", website: "https://libroteca.net", feed: nil),
        Self(name: "Freeditorial", description: "Autores y literatura independiente", group: "En español", website: "https://www.freeditorial.com", feed: nil),
        Self(name: "Biblioteca Nacional de España", description: "Patrimonio digitalizado", group: "En español", website: "https://www.bne.es", feed: nil),
        Self(name: "Project Gutenberg · todos los idiomas", description: "Libros electrónicos de dominio público", group: "Clásicos y literatura universal", website: "https://www.gutenberg.org", feed: "https://www.gutenberg.org/ebooks.opds/"),
        Self(name: "Unglue.it", description: "Libros con licencias abiertas", group: "Clásicos y literatura universal", website: "https://unglue.it", feed: "https://unglue.it/api/opds/epub/"),
        Self(name: "Standard Ebooks", description: "Ediciones cuidadas de clásicos", group: "Clásicos y literatura universal", website: "https://standardebooks.org", feed: nil),
        Self(name: "Global Grey Ebooks", description: "Clásicos, historia y filosofía", group: "Clásicos y literatura universal", website: "https://www.globalgreyebooks.com", feed: nil),
        Self(name: "epubBooks", description: "Clásicos, principalmente en inglés", group: "Clásicos y literatura universal", website: "https://www.epubbooks.com", feed: nil),
        Self(name: "Loyal Books", description: "Libros y audiolibros clásicos", group: "Clásicos y literatura universal", website: "https://www.loyalbooks.com", feed: nil),
        Self(name: "Faded Page", description: "Patrimonio literario canadiense", group: "Clásicos y literatura universal", website: "https://fadedpage.com", feed: nil),
        Self(name: "Planet eBook", description: "Novelas clásicas", group: "Clásicos y literatura universal", website: "https://planetebook.com", feed: nil),
        Self(name: "Open Library", description: "Clásicos y biblioteca digital; algunos préstamos requieren otras aplicaciones", group: "Clásicos y literatura universal", website: "https://openlibrary.org", feed: nil),
        Self(name: "StoryWeaver", description: "Cuentos ilustrados en varios idiomas", group: "Infantil y educativo", website: "https://storyweaver.org.in", feed: nil),
        Self(name: "Book Dash", description: "Libros infantiles de licencia abierta", group: "Infantil y educativo", website: "https://bookdash.org", feed: nil),
        Self(name: "SciELO Libros", description: "Investigación y ciencias sociales", group: "Académico y científico", website: "https://books.scielo.org", feed: nil),
        Self(name: "Big Ten Open Books", description: "Editoriales universitarias y EPUB sin DRM", group: "Académico y científico", website: "https://bigtenopenbooks.org", feed: nil),
        Self(name: "OAPEN Library", description: "Investigación de acceso abierto", group: "Académico y científico", website: "https://oapen.org", feed: nil),
        Self(name: "Directory of Open Access Books", description: "Directorio académico abierto", group: "Académico y científico", website: "https://www.doabooks.org", feed: nil)
    ]
}
struct CREARepositoriesSheet: View {
    let added: Set<String>
    let add: (CREAFreeRepository) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                ForEach(["En español", "Clásicos y literatura universal", "Infantil y educativo", "Académico y científico"], id: \.self) { group in
                    Section(group) {
                        ForEach(CREAFreeRepository.all.filter { $0.group == group }) { repo in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(repo.name).font(.headline)
                                Text(repo.description).font(.caption).foregroundStyle(.secondary)
                                if let feed = repo.feed {
                                    Button(added.contains(feed) ? "Ya agregado" : "Agregar catálogo OPDS") { add(repo) }.disabled(added.contains(feed))
                                } else if let website = URL(string: repo.website) {
                                    Link("Abrir sitio web", destination: website)
                                }
                            }
                        }
                    }
                }
            }.navigationTitle("Repositorios de EPUB gratis").toolbar { Button("Cerrar") { dismiss() } }
        }
    }
}
struct CREAWebImportSheet: View {
    @ObservedObject var library: LocalLibrary
    @StateObject private var model = CREAOPDSModel()
    @State private var address = ""
    @State private var format = "EPUB"
    @Environment(\.dismiss) private var dismiss
    private var url: URL? { guard let value = URL(string: address.trimmingCharacters(in: .whitespacesAndNewlines)), value.scheme == "https", value.host != nil, value.user == nil, value.password == nil else { return nil }; return value }
    var body: some View {
        NavigationStack {
            Form {
                Section("Dirección directa del archivo") {
                    TextField("https://sitio/libro.epub", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    Picker("Formato", selection: $format) { Text("EPUB").tag("EPUB"); Text("PDF").tag("PDF") }
                    Text("Usa el enlace de descarga del archivo, no la dirección de la página del libro.").font(.footnote)
                }
                Button {
                    guard let url else { return }
                    let name = url.deletingPathExtension().lastPathComponent.isEmpty ? "Libro de la web" : url.deletingPathExtension().lastPathComponent
                    let entry = CREAOPDSEntry(title: name, links: [CREAOPDSLink(url: url, rel: "http://opds-spec.org/acquisition", type: format == "PDF" ? "application/pdf" : "application/epub+zip")])
                    Task { await model.download(entry, library: library) }
                } label: { if model.downloading != nil { ProgressView("Descargando…") } else { Label("Descargar e importar", systemImage: "arrow.down.circle") } }.disabled(url == nil || model.downloading != nil)
                if let error = model.error { Text(error).foregroundStyle(.red) }
                if let message = model.message { Text(message).foregroundStyle(.secondary) }
            }.navigationTitle("Agregar desde la web").toolbar { Button("Cerrar") { dismiss() } }
        }
    }
}

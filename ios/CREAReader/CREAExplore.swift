import SwiftUI
import Foundation

struct CREAOPDSEntry: Identifiable {
    let id = UUID()
    var title = ""
    var author = ""
    var summary = ""
    var links: [CREAOPDSLink] = []
    var acquisition: CREAOPDSLink? {
        links.first { $0.rel.contains("acquisition") && ($0.type.contains("epub") || $0.type.contains("pdf")) }
    }
    var navigation: CREAOPDSLink? { links.first { ($0.type.contains("atom") || $0.type.contains("opds+json")) && !$0.rel.contains("acquisition") } }
}
struct CREAOPDSLink {
    let url: URL
    let rel: String
    let type: String
}

/// Namespace-aware Atom/OPDS 1 parser. External XML entities are never resolved.
final class CREAOPDSParser: NSObject, XMLParserDelegate {
    let base: URL
    var entries: [CREAOPDSEntry] = []
    var next: URL?
    private var entry: CREAOPDSEntry?
    private var stack: [String] = []
    private var text = ""
    init(base: URL) { self.base = base }
    func parse(_ data: Data) throws {
        if data.first(where: { ![9, 10, 13, 32].contains($0) }) == 123 { try parseJSON(data); return }
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true; parser.shouldResolveExternalEntities = false; parser.delegate = self
        guard parser.parse() else { throw parser.parserError ?? URLError(.cannotParseResponse) }
    }
    private func parseJSON(_ data: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["publications"] != nil || root["navigation"] != nil || root["groups"] != nil else { throw URLError(.cannotParseResponse) }
        func links(_ values: [[String: Any]]) -> [CREAOPDSLink] {
            values.compactMap { value in
                guard let href = value["href"] as? String, let url = URL(string: href, relativeTo: base)?.absoluteURL,
                      ["https", "http"].contains(url.scheme ?? "") else { return nil }
                let relation = value["rel"] as? String ?? (value["rel"] as? [String])?.joined(separator: " ") ?? "alternate"
                return CREAOPDSLink(url: url, rel: relation, type: value["type"] as? String ?? "")
            }
        }
        func read(_ feed: [String: Any]) {
            for book in feed["publications"] as? [[String: Any]] ?? [] {
                let metadata = book["metadata"] as? [String: Any] ?? [:]
                var author = metadata["author"] as? String ?? ""
                if let people = metadata["author"] as? [[String: Any]] { author = people.compactMap { $0["name"] as? String }.joined(separator: ", ") }
                if let person = metadata["author"] as? [String: Any] { author = person["name"] as? String ?? "" }
                entries.append(CREAOPDSEntry(title: metadata["title"] as? String ?? "Libro", author: author,
                    summary: metadata["description"] as? String ?? "", links: links(book["links"] as? [[String: Any]] ?? [])))
            }
            for nav in feed["navigation"] as? [[String: Any]] ?? [] {
                entries.append(CREAOPDSEntry(title: nav["title"] as? String ?? "Sección", links: links([nav])))
            }
            for group in feed["groups"] as? [[String: Any]] ?? [] { read(group) }
        }
        read(root)
        next = links(root["links"] as? [[String: Any]] ?? []).first { $0.rel == "next" }?.url
    }
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes: [String: String]) {
        stack.append(elementName); text = ""
        if elementName == "entry" { entry = CREAOPDSEntry() }
        if elementName == "link", let href = attributes["href"],
           let url = URL(string: href, relativeTo: base)?.absoluteURL, ["https", "http"].contains(url.scheme ?? "") {
            let link = CREAOPDSLink(url: url, rel: attributes["rel"] ?? "alternate", type: attributes["type"] ?? "")
            if entry != nil { entry?.links.append(link) }
            else if link.rel == "next" { next = url }
        }
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }
    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) { text += String(data: CDATABlock, encoding: .utf8) ?? "" }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if entry != nil {
            if elementName == "title" { entry?.title = clean }
            if elementName == "name", stack.dropLast().last == "author" { entry?.author = clean }
            if elementName == "summary" || elementName == "content" {
                entry?.summary = clean.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            }
            if elementName == "entry", let finished = entry { entries.append(finished); entry = nil }
        }
        if !stack.isEmpty { stack.removeLast() }
        text = ""
    }
}

@MainActor final class CREAOPDSModel: ObservableObject {
    @Published var entries: [CREAOPDSEntry] = []
    @Published var loading = false
    @Published var downloading: UUID?
    @Published var error: String?
    @Published var message: String?
    @Published var next: URL?
    func load(_ url: URL, append: Bool = false) async {
        guard !loading else { return }
        loading = true; error = nil
        defer { loading = false }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode), data.count <= 10_000_000 else { throw URLError(.badServerResponse) }
            let parser = CREAOPDSParser(base: response.url ?? url); try parser.parse(data)
            entries = append ? entries + parser.entries : parser.entries; next = parser.next
        } catch { self.error = "No se pudo cargar el catálogo: " + error.localizedDescription }
    }
    func download(_ entry: CREAOPDSEntry, library: LocalLibrary) async {
        guard downloading == nil, let acquisition = entry.acquisition else { return }
        downloading = entry.id; error = nil
        defer { downloading = nil }
        do {
            let (file, response) = try await URLSession.shared.download(from: acquisition.url)
            defer { try? FileManager.default.removeItem(at: file) }
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  size > 0, size <= 250_000_000 else { throw URLError(.badServerResponse) }
            let handle = try FileHandle(forReadingFrom: file); defer { try? handle.close() }
            let magic = try handle.read(upToCount: 4) ?? Data()
            let pdf = acquisition.type.contains("pdf")
            guard pdf ? magic.starts(with: Data("%PDF".utf8)) : magic.starts(with: Data([0x50, 0x4b])) else { throw URLError(.cannotDecodeContentData) }
            let named = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + (pdf ? ".pdf" : ".epub"))
            defer { try? FileManager.default.removeItem(at: named) }
            try FileManager.default.moveItem(at: file, to: named)
            try library.add(url: named, displayName: entry.title)
            message = "Libro añadido a Mi biblioteca"
        } catch { self.error = "No se pudo descargar el libro: " + error.localizedDescription }
    }
}

struct CREAExploreScreen: View {
    @ObservedObject var library: LocalLibrary
    private let sources: [(String, String)] = [
        ("textos.info · Más populares", "https://www.textos.info/populares.atom"),
        ("textos.info · Novedades", "https://www.textos.info/novedades.atom"),
        ("textos.info · Autores", "https://www.textos.info/autores.atom"),
        ("textos.info · Temas y etiquetas", "https://www.textos.info/etiquetas.atom"),
        ("Gutenberg · Más descargados", "https://www.gutenberg.org/ebooks/search.opds/?query=l.es&sort_order=downloads"),
        ("Gutenberg · Novedades", "https://www.gutenberg.org/ebooks/search.opds/?query=l.es&sort_order=release_date")
    ]
    private let topics = [("Novelas y cuentos", "s.fiction"), ("Poesía", "s.poetry"), ("Teatro", "s.drama"), ("Aventuras", "s.adventure"), ("Historia", "s.history"), ("Filosofía", "s.philosophy"), ("Viajes", "s.travel"), ("Infantil y juvenil", "s.children"), ("Miguel de Cervantes", "cervantes"), ("Rubén Darío", "a.darío"), ("Benito Pérez Galdós", "a.galdós")]
    @State private var custom = ""
    var body: some View {
        NavigationStack {
            List {
                Section("Bibliotecas en español") {
                    ForEach(sources, id: \.0) { title, href in
                        if let url = URL(string: href) { NavigationLink(title) { CREAOPDSFeedScreen(title: title, url: url, library: library) } }
                    }
                }
                Section("Temas y autores") {
                    ForEach(topics, id: \.0) { title, topic in
                        let url = URL(string: "https://www.gutenberg.org/ebooks/search.opds/?query=l.es+" + topic.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)! + "&sort_order=downloads")!
                        NavigationLink(title) { CREAOPDSFeedScreen(title: title, url: url, library: library) }
                    }
                }
                Section("Catálogo OPDS propio") {
                    TextField("Dirección HTTPS del catálogo", text: $custom).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    if let url = URL(string: custom), url.scheme == "https", url.host != nil {
                        NavigationLink("Abrir catálogo") { CREAOPDSFeedScreen(title: "Catálogo OPDS", url: url, library: library) }
                    }
                }
            }.navigationTitle("Explorar")
        }
    }
}

struct CREAOPDSFeedScreen: View {
    let title: String
    let url: URL
    @ObservedObject var library: LocalLibrary
    @StateObject private var model = CREAOPDSModel()
    @State private var search = ""
    var body: some View {
        List {
            if let error = model.error { Text(error).foregroundStyle(.red); Button("Reintentar") { Task { await model.load(url) } } }
            if let message = model.message { Text(message).foregroundStyle(.green) }
            ForEach(model.entries.filter { search.isEmpty || ($0.title + " " + $0.author).localizedStandardContains(search) }) { entry in
                if let target = entry.navigation, entry.acquisition == nil {
                    NavigationLink(entry.title) { CREAOPDSFeedScreen(title: entry.title, url: target.url, library: library) }
                } else {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(entry.title).font(.headline)
                        if !entry.author.isEmpty { Text(entry.author).font(.subheadline).foregroundStyle(.secondary) }
                        if !entry.summary.isEmpty { Text(entry.summary).font(.caption).lineLimit(5) }
                        if entry.acquisition != nil {
                            Button { Task { await model.download(entry, library: library) } } label: {
                                Label(model.downloading == entry.id ? "Descargando…" : "Descargar libro", systemImage: "arrow.down.circle")
                            }.disabled(model.downloading != nil)
                        }
                    }.padding(.vertical, 6)
                }
            }
            if model.loading { ProgressView("Cargando catálogo…") }
            if let next = model.next { Button("Cargar más") { Task { await model.load(next, append: true) } }.disabled(model.loading) }
        }.navigationTitle(title).searchable(text: $search, prompt: "Buscar en esta página")
            .task { await model.load(url) }.refreshable { await model.load(url) }
    }
}

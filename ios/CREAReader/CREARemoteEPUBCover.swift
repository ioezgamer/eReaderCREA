import Foundation
import Compression

struct CREABookMetadata: Codable {
    var title: String?
    var author: String?
    var summary: String?
    var publisher: String?
    var language: String?
    var published: String?
    var editionPages: Int?
    var excerpt: String?
}


/// Small, range-only ZIP reader for EPUB cover extraction.
/// It never downloads an entire EPUB. All reads have explicit byte limits.
enum CREARemoteEPUBCover {
    private static let maxEntry = 4 * 1024 * 1024
    private static let maxDirectory = 2 * 1024 * 1024

    private struct Entry {
        let method: Int
        let compressed: Int
        let uncompressed: Int
        let offset: Int64
        let nameLength: Int
        let extraLength: Int
    }

    private struct Archive {
        let size: Int64
        let entries: [String: Entry]
        let readRange: (Int64, Int64) async -> Data?

        func read(_ name: String) async -> Data? {
            guard let entry = entries[name],
                  entry.compressed >= 0, entry.compressed <= CREARemoteEPUBCover.maxEntry,
                  entry.uncompressed >= 0, entry.uncompressed <= CREARemoteEPUBCover.maxEntry else { return nil }
            let extra = min(512, max(0, entry.extraLength))
            let estimated = 30 + entry.nameLength + extra + entry.compressed
            let last = min(size - 1, entry.offset + Int64(estimated))
            guard let blob = await readRange(entry.offset, last),
                  CREARemoteEPUBCover.number(blob, 0, length: 4) == 0x04034b50,
                  let nameLength = CREARemoteEPUBCover.number(blob, 26, length: 2),
                  let extraLength = CREARemoteEPUBCover.number(blob, 28, length: 2) else { return nil }
            let dataStart = 30 + nameLength + extraLength
            guard dataStart <= blob.count, entry.compressed <= blob.count - dataStart else { return nil }
            let compressedData = blob.subdata(in: dataStart..<(dataStart + entry.compressed))
            if entry.method == 0 { return compressedData }
            guard entry.method == 8, entry.uncompressed > 0 else { return nil }
            var output = Data(count: entry.uncompressed)
            let decoded = output.withUnsafeMutableBytes { destination in
                compressedData.withUnsafeBytes { source in
                    guard let dst = destination.bindMemory(to: UInt8.self).baseAddress,
                          let src = source.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                    return compression_decode_buffer(dst, entry.uncompressed, src, compressedData.count,
                                                     nil, COMPRESSION_ZLIB)
                }
            }
            guard decoded == entry.uncompressed else { return nil }
            return output
        }
    }

    private static func number(_ data: Data, _ position: Int, length: Int) -> Int? {
        guard position >= 0, length > 0, length <= 4, position <= data.count - length else { return nil }
        var value = 0
        for i in 0..<length { value |= Int(data[position + i]) << (i * 8) }
        return value
    }

    private static func open(size: Int64, readRange: @escaping (Int64, Int64) async -> Data?) async -> Archive? {
        guard size >= 22 else { return nil }
        let tailStart = max(0, size - 66 * 1024)
        guard let tail = await readRange(tailStart, size - 1), tail.count >= 22 else { return nil }
        var eocd: Int?
        for offset in stride(from: tail.count - 22, through: 0, by: -1) {
            if number(tail, offset, length: 4) == 0x06054b50 {
                eocd = offset
                break
            }
        }
        guard let index = eocd,
              let count = number(tail, index + 10, length: 2), count != 65535,
              let directorySize = number(tail, index + 12, length: 4),
              let directoryOffset = number(tail, index + 16, length: 4),
              directorySize > 0, directorySize <= maxDirectory,
              directoryOffset >= 0, Int64(directoryOffset) + Int64(directorySize) <= size else { return nil }
        let central: Data
        if Int64(directoryOffset) >= tailStart {
            let relative = Int64(directoryOffset) - tailStart
            guard relative <= Int64(tail.count),
                  directorySize <= tail.count - Int(relative) else { return nil }
            central = tail.subdata(in: Int(relative)..<(Int(relative) + directorySize))
        } else {
            guard let bytes = await readRange(Int64(directoryOffset),
                                              Int64(directoryOffset + directorySize - 1)),
                  bytes.count == directorySize else { return nil }
            central = bytes
        }
        var position = 0
        var entries: [String: Entry] = [:]
        while position + 46 <= central.count && entries.count < 5000 {
            guard number(central, position, length: 4) == 0x02014b50,
                  let method = number(central, position + 10, length: 2),
                  let compressed = number(central, position + 20, length: 4),
                  let uncompressed = number(central, position + 24, length: 4),
                  let nameLength = number(central, position + 28, length: 2),
                  let extraLength = number(central, position + 30, length: 2),
                  let commentLength = number(central, position + 32, length: 2),
                  let offset = number(central, position + 42, length: 4),
                  position + 46 + nameLength + extraLength + commentLength <= central.count else { break }
            let nameData = central.subdata(in: (position + 46)..<(position + 46 + nameLength))
            if let name = String(data: nameData, encoding: .utf8), !name.contains("../") {
                entries[name] = Entry(method: method, compressed: compressed,
                                      uncompressed: uncompressed, offset: Int64(offset),
                                      nameLength: nameLength, extraLength: extraLength)
            }
            position += 46 + nameLength + extraLength + commentLength
        }
        return entries.isEmpty ? nil : Archive(size: size, entries: entries, readRange: readRange)
    }

    private final class Scanner: NSObject, XMLParserDelegate {
        var containerPath: String?
        var coverID: String?
        var epub3Cover: String?
        var fallbackCover: String?
        var itemByID: [String: String] = [:]
        var spineIDs: [String] = []
        var title: String?
        var author: String?
        var summary: String?
        var publisher: String?
        var language: String?
        var published: String?
        var editionPages: Int?
        private var insideMetadata = false
        private var activeField: String?
        private var textBuffer = ""
        private var metaPageProperty = false

        private func clean(_ value: String) -> String {
            var plain = value.replacingOccurrences(of: "<[^>]*>", with: " ", options: .regularExpression)
            for (entity, character) in [("&nbsp;", " "), ("&amp;", "&"), ("&quot;", "\""),
                                        ("&lt;", "<"), ("&gt;", ">"), ("&#39;", "'")] {
                plain = plain.replacingOccurrences(of: entity, with: character)
            }
            return plain.components(separatedBy: .whitespacesAndNewlines)
                .filter { !$0.isEmpty }.joined(separator: " ")
        }

        func parser(_ parser: XMLParser, didStartElement elementName: String,
                    namespaceURI: String?, qualifiedName qName: String?,
                    attributes: [String: String]) {
            let name = elementName.split(separator: ":").last.map(String.init) ?? elementName
            if name == "metadata" { insideMetadata = true }
            if name == "itemref", let id = attributes["idref"] { spineIDs.append(id) }
            if insideMetadata {
                if ["title", "creator", "description", "publisher", "language", "date", "extent"].contains(name) {
                    activeField = name
                    textBuffer = ""
                } else if name == "meta" {
                    let value = (attributes["property"] ?? attributes["name"] ?? "").lowercased()
                    if value.contains("numberofpages") || value == "calibre:pages" {
                        activeField = "pages"
                        metaPageProperty = true
                        textBuffer = attributes["content"] ?? ""
                    }
                }
            }
            if name == "rootfile", let path = attributes["full-path"] { containerPath = path }
            if name == "meta", attributes["name"] == "cover" { coverID = attributes["content"] }
            if name == "item", let href = attributes["href"] {
                if let id = attributes["id"] { itemByID[id] = href }
                if (attributes["properties"] ?? "").split(separator: " ").contains("cover-image") {
                    epub3Cover = href
                }
                if attributes["media-type"]?.hasPrefix("image/") == true,
                   (attributes["id"] ?? "").localizedCaseInsensitiveContains("cover") {
                    fallbackCover = href
                }
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            if activeField != nil { textBuffer += string }
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String,
                    namespaceURI: String?, qualifiedName qName: String?) {
            let name = elementName.split(separator: ":").last.map(String.init) ?? elementName
            if let field = activeField, name == field || (name == "meta" && metaPageProperty) {
                let value = clean(textBuffer)
                if !value.isEmpty {
                    switch field {
                    case "title": title = title ?? value
                    case "creator": author = author ?? value
                    case "description": summary = summary ?? value
                    case "publisher": publisher = publisher ?? value
                    case "language": language = language ?? value
                    case "date": published = published ?? value
                    case "extent", "pages":
                        let number = value.components(separatedBy: CharacterSet.decimalDigits.inverted).first {
                            !$0.isEmpty
                        }.flatMap(Int.init)
                        if let number, (1...20000).contains(number) { editionPages = number }
                    default: break
                    }
                }
                activeField = nil
                metaPageProperty = false
                textBuffer = ""
            }
            if name == "metadata" { insideMetadata = false }
        }

        static func parse(_ data: Data) -> Scanner? {
            let handler = Scanner()
            let parser = XMLParser(data: data)
            parser.delegate = handler
            return parser.parse() ? handler : nil
        }
    }

    private static func resolved(path: String, relativeTo opf: String) -> String {
        let folder = opf.split(separator: "/").dropLast().map(String.init)
        var parts = folder
        for component in (path.split(separator: "#").first.map(String.init) ?? path)
            .removingPercentEncoding?.split(separator: "/").map(String.init) ?? [] {
            if component == "." { continue }
            if component == ".." { if !parts.isEmpty { parts.removeLast() } }
            else { parts.append(component) }
        }
        return parts.joined(separator: "/")
    }

    private static func firstExcerpt(in archive: Archive, opfPath: String, package: Scanner) async -> String? {
        for item in package.spineIDs.prefix(8) {
            guard let relative = package.itemByID[item],
                  !relative.lowercased().contains("nav"),
                  let data = await archive.read(resolved(path: relative, relativeTo: opfPath)),
                  let raw = String(data: data, encoding: .utf8) else { continue }
            var plain = raw
                .replacingOccurrences(of: "(?is)<(script|style|head|nav)[^>]*>.*?</\\1>", with: " ", options: .regularExpression)
                .replacingOccurrences(of: "(?is)<[^>]+>", with: " ", options: .regularExpression)
            for (a, b) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"),
                           ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'")] {
                plain = plain.replacingOccurrences(of: a, with: b)
            }
            plain = plain.components(separatedBy: .whitespacesAndNewlines)
                .filter { !$0.isEmpty }.joined(separator: " ")
            if plain.count >= 100 {
                return String(plain.prefix(1400))
            }
        }
        return nil
    }

    /// Reads genuine EPUB package metadata and an initial excerpt using bounded byte ranges.
    /// It does not invent descriptions or assume a fixed page count for reflowable text.
    static func inspect(size: Int64,
                        readRange: @escaping (Int64, Int64) async -> Data?) async -> CREABookMetadata? {
        guard let zip = await open(size: size, readRange: readRange),
              let container = await zip.read("META-INF/container.xml"),
              let opfPath = Scanner.parse(container)?.containerPath,
              let package = await zip.read(opfPath),
              let scanner = Scanner.parse(package) else { return nil }
        let excerpt = await firstExcerpt(in: zip, opfPath: opfPath, package: scanner)
        return CREABookMetadata(title: scanner.title, author: scanner.author,
                                summary: scanner.summary, publisher: scanner.publisher,
                                language: scanner.language, published: scanner.published,
                                editionPages: scanner.editionPages, excerpt: excerpt)
    }

    static func extract(size: Int64,
                        readRange: @escaping (Int64, Int64) async -> Data?) async -> Data? {
        guard let zip = await open(size: size, readRange: readRange),
              let container = await zip.read("META-INF/container.xml"),
              let opfPath = Scanner.parse(container)?.containerPath,
              let manifest = await zip.read(opfPath),
              let metadata = Scanner.parse(manifest) else { return nil }
        let href = metadata.epub3Cover
            ?? metadata.coverID.flatMap { metadata.itemByID[$0] }
            ?? metadata.fallbackCover
        let selected: String?
        if let href { selected = resolved(path: href, relativeTo: opfPath) }
        else {
            selected = zip.entries.keys.sorted().first(where: { name in
                let file = (name as NSString).lastPathComponent.lowercased()
                return file.hasPrefix("cover") &&
                    (file.hasSuffix(".jpg") || file.hasSuffix(".jpeg") || file.hasSuffix(".png"))
            })
        }
        guard let selected, let bytes = await zip.read(selected),
              bytes.count < maxEntry else { return nil }
        return bytes
    }
}

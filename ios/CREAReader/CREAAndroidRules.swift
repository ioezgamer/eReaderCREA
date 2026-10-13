import Foundation
import SwiftUI

enum CREAListMode: String, CaseIterable { case list, grid, covers
    var title: String { self == .list ? "Lista" : self == .grid ? "Cuadrícula" : "Solo portadas" }
}
enum CREACoverSize: String, CaseIterable { case small, medium, large
    var title: String { self == .small ? "Pequeñas" : self == .medium ? "Medianas" : "Grandes" }
    func columnWidth(_ mode: CREAListMode) -> Double {
        if mode == .list { return 360 }
        switch (mode, self) {
        case (.covers, .small): return 88
        case (.covers, .medium): return 120
        case (.covers, .large): return 176
        case (_, .small): return 112
        case (_, .medium): return 150
        default: return 210
        }
    }
}
struct CREAViewMenu: View {
    @Binding var mode: String
    @Binding var size: String
    var body: some View {
        Menu {
            Picker("Vista", selection: $mode) { ForEach(CREAListMode.allCases, id: \.rawValue) { Text(LocalizedStringKey($0.title)).tag($0.rawValue) } }
            Picker("Tamaño de portadas", selection: $size) { ForEach(CREACoverSize.allCases, id: \.rawValue) { Text(LocalizedStringKey($0.title)).tag($0.rawValue) } }
        } label: { Label("Vista", systemImage: "rectangle.grid.2x2") }
    }
}

enum CREACatalogRules {
    static func folded(_ text: String) -> String { text.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "es")) }
    static func matches(_ text: String, query: String) -> Bool {
        let key = folded(text)
        return folded(query).split(whereSeparator: { $0.isWhitespace }).allSatisfy { key.contains($0) }
    }
    static func milliseconds(_ value: String?) -> Int64 {
        guard let value else { return 0 }
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
        return Int64((date?.timeIntervalSince1970 ?? 0) * 1000)
    }
    static func recent(_ value: String?, now: Date = Date()) -> Bool { milliseconds(value) >= Int64(now.addingTimeInterval(-14 * 86400).timeIntervalSince1970 * 1000) }
}

enum CREACategoryPaths {
    static let separator = " / "
    static func join(_ parent: String?, _ name: String) -> String { [parent, name.trimmingCharacters(in: .whitespacesAndNewlines)].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: separator) }
    static func matches(_ path: String?, selected: String?) -> Bool { selected.map { path == $0 || path?.hasPrefix($0 + separator) == true } ?? true }
    static func ancestors(_ path: String) -> [String] { let parts = path.components(separatedBy: separator); return parts.indices.map { parts.prefix($0 + 1).joined(separator: separator) } }
    static func parent(_ path: String) -> String? { let parts = path.components(separatedBy: separator).dropLast(); return parts.isEmpty ? nil : parts.joined(separator: separator) }
    static func chips(_ paths: [String], selected: String?) -> [String] {
        let all = Set(paths.flatMap { ancestors($0) }); let open = Set(selected.map { ancestors($0) } ?? [])
        var result: [String] = []
        func append(_ root: String?) {
            for child in all.filter({ parent($0) == root }).sorted(by: { CREACatalogRules.folded($0) < CREACatalogRules.folded($1) }) {
                result.append(child); if open.contains(child) { append(child) }
            }
        }
        append(nil); return result
    }
}
struct CREACategoryPicker: View {
    let paths: [String]
    @Binding var selected: String?
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                Button("Todas las categorías") { selected = nil }.buttonStyle(.bordered)
                ForEach(CREACategoryPaths.chips(paths, selected: selected), id: \.self) { path in
                    Button(path) { selected = path }.buttonStyle(.bordered).tint(selected == path ? CREAStyle.blue : Color.secondary)
                }
            }
        }
    }
}

enum CREADriveNames {
    static func describe(_ filename: String, createdByApp: Bool) -> (title: String, author: String?) {
        let base = (filename as NSString).deletingPathExtension.replacingOccurrences(of: "_", with: " ")
        let clean = base.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
        if createdByApp, let range = clean.range(of: " - ", options: .backwards) {
            return (String(clean[..<range.lowerBound]), String(clean[range.upperBound...]))
        }
        var tidy = clean.replacingOccurrences(of: "(?i)^(?:libros?\\s+)?epub\\s+", with: "", options: .regularExpression)
        if tidy.isEmpty { tidy = clean }
        let pattern = "(?<=\\S)-(?=\\S)"
        if let expression = try? NSRegularExpression(pattern: pattern), expression.numberOfMatches(in: tidy, range: NSRange(tidy.startIndex..., in: tidy)) >= 2 {
            tidy = tidy.replacingOccurrences(of: pattern, with: " ", options: .regularExpression)
        }
        return (tidy.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines), nil)
    }
}
enum CREADuplicateRules {
    static func similar(title: String, author: String?, identifier: String?, kind: String, to book: LocalBook) -> Bool {
        guard book.kind == kind else { return false }
        if let identifier = identifier?.trimmingCharacters(in: .whitespacesAndNewlines), identifier.count >= 6,
           identifier.caseInsensitiveCompare(book.publicationIdentifier ?? "") == .orderedSame { return true }
        return sameNames(title, author, book.name, book.author)
    }
    static func sameNames(_ title: String, _ author: String?, _ otherTitle: String, _ otherAuthor: String?) -> Bool {
        let a = CREACatalogRules.folded(title).trimmingCharacters(in: .whitespacesAndNewlines)
        return a.count >= 3 && a == CREACatalogRules.folded(otherTitle).trimmingCharacters(in: .whitespacesAndNewlines) && CREACatalogRules.folded(author ?? "") == CREACatalogRules.folded(otherAuthor ?? "")
    }
}

struct CREACommunityInterest: Identifiable {
    let id: String
    var readers = 0
    var finishers = 0
    var score = 0
    var similar = false
}
struct CREACommunityNow: Identifiable {
    let userID: String
    let nickname: String
    let bookID: String
    let percent: Int
    let updatedAt: Int64
    var id: String { userID + "|" + bookID }
}
struct CREACommunityFeed {
    let recommended: [CREACommunityInterest]
    let popular: [CREACommunityInterest]
    let readingNow: [CREACommunityNow]
    static func build(members: [CREACommunityProfile], titles: [String: String], mine: Set<String>, now: Date = Date()) -> Self {
        var tallies: [String: CREACommunityInterest] = [:]
        var current: [CREACommunityNow] = []
        let since = Int64(now.addingTimeInterval(-30 * 86400).timeIntervalSince1970 * 1000)
        for member in members where member.active {
            let readings = Dictionary(member.readings.map { ($0.driveFileId, $0) }, uniquingKeysWith: { a, b in (a.updatedAt ?? 0) >= (b.updatedAt ?? 0) ? a : b }).values
            let weight = 1 + readings.filter { $0.percent > 0 && mine.contains($0.driveFileId) }.count
            for reading in readings where reading.percent > 0 && titles[reading.driveFileId] != nil {
                var tally = tallies[reading.driveFileId] ?? CREACommunityInterest(id: reading.driveFileId)
                tally.readers += 1; tally.finishers += reading.percent >= 98 ? 1 : 0
                tally.score += weight * (reading.percent >= 98 ? 2 : 1); tally.similar = tally.similar || weight > 1
                tallies[tally.id] = tally
                let stamp = reading.updatedAt ?? member.updatedAt ?? 0
                if reading.percent < 98 && stamp >= since { current.append(CREACommunityNow(userID: member.userId, nickname: member.nickname, bookID: reading.driveFileId, percent: reading.percent, updatedAt: stamp)) }
            }
        }
        func titleOrder(_ a: CREACommunityInterest, _ b: CREACommunityInterest) -> Bool { foldedTitle(a.id) < foldedTitle(b.id) }
        func foldedTitle(_ id: String) -> String { CREACatalogRules.folded(titles[id] ?? id) }
        let popular = tallies.values.sorted { a, b in
            if a.readers != b.readers { return a.readers > b.readers }; if a.finishers != b.finishers { return a.finishers > b.finishers }; return titleOrder(a, b)
        }
        let recommended = tallies.values.filter { !mine.contains($0.id) }.sorted { a, b in
            if a.score != b.score { return a.score > b.score }; if a.readers != b.readers { return a.readers > b.readers }; return titleOrder(a, b)
        }
        return Self(recommended: Array(recommended.prefix(15)), popular: Array(popular.prefix(20)), readingNow: Array(current.sorted { $0.updatedAt > $1.updatedAt }.prefix(30)))
    }
}

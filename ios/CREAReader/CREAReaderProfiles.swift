import Foundation

// Reader profiles: what a person reads and liked, independent of the books stored on any device.
// The JSON is the same Android uses (`crea-perfil-<id>.json` in the shared Drive folder), so a person
// continues on any device. Only a nickname and an avatar are kept: no names, e-mails or photos.

/// One book this reader opened. `key` is "drive:<id>" for Drive books, "local:<hash>" otherwise.
struct CREAReadEntry: Codable, Equatable {
    var key: String
    var title: String
    var author: String?
    var driveFileId: String?
    var percent: Int
    /// Readium locator (JSON) where this reader stopped, to continue there on any device.
    var progression: String?
    var openedAt: Int64
    var progressAt: Int64
    var finishedAt: Int64?

    init(key: String, title: String, author: String? = nil, driveFileId: String? = nil, percent: Int = 0,
         progression: String? = nil, openedAt: Int64, progressAt: Int64 = 0, finishedAt: Int64? = nil) {
        self.key = key; self.title = title; self.author = author; self.driveFileId = driveFileId; self.percent = percent
        self.progression = progression; self.openedAt = openedAt; self.progressAt = progressAt; self.finishedAt = finishedAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        author = try c.decodeIfPresent(String.self, forKey: .author)
        driveFileId = try c.decodeIfPresent(String.self, forKey: .driveFileId)
        percent = try c.decodeIfPresent(Int.self, forKey: .percent) ?? 0
        progression = try c.decodeIfPresent(String.self, forKey: .progression)
        openedAt = try c.decodeIfPresent(Int64.self, forKey: .openedAt) ?? 0
        progressAt = try c.decodeIfPresent(Int64.self, forKey: .progressAt) ?? 0
        finishedAt = try c.decodeIfPresent(Int64.self, forKey: .finishedAt)
    }
}

/// Favourite on/off with the time it changed, so the latest change wins on every device.
struct CREAFavoriteMark: Codable, Equatable {
    var key: String
    var on: Bool
    var at: Int64
    var title: String
    var author: String?
    var driveFileId: String?

    init(key: String, on: Bool, at: Int64, title: String = "", author: String? = nil, driveFileId: String? = nil) {
        self.key = key; self.on = on; self.at = at; self.title = title; self.author = author; self.driveFileId = driveFileId
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key)
        on = try c.decodeIfPresent(Bool.self, forKey: .on) ?? false
        at = try c.decodeIfPresent(Int64.self, forKey: .at) ?? 0
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        author = try c.decodeIfPresent(String.self, forKey: .author)
        driveFileId = try c.decodeIfPresent(String.self, forKey: .driveFileId)
    }
}

struct CREAReaderProfile: Codable, Equatable, Identifiable {
    var schema = 1
    var id: String
    var nickname: String
    /// Index in `CREAReaderAvatars.emojis`.
    var avatar = 0
    /// Index in `CREAReaderAvatars.colors`.
    var color = 0
    var createdAt: Int64
    /// Last change of nickname, avatar, privacy or deletion (not of the reading itself).
    var updatedAt: Int64
    /// The person asked not to appear in "what my group reads".
    var hidden = false
    /// Reading group ("Club martes"), chosen by the reader.
    var group: String?
    /// Days with reading (ISO dates, most recent last), for streaks and achievements.
    var days: [String] = []
    /// Removed: kept as a marker so other devices drop it too.
    var deleted = false
    var history: [CREAReadEntry] = []
    var favorites: [CREAFavoriteMark] = []

    init(id: String, nickname: String, avatar: Int = 0, color: Int = 0, createdAt: Int64, updatedAt: Int64, group: String? = nil) {
        self.id = id; self.nickname = nickname; self.avatar = avatar; self.color = color
        self.createdAt = createdAt; self.updatedAt = updatedAt; self.group = group
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decodeIfPresent(Int.self, forKey: .schema) ?? 1
        id = try c.decode(String.self, forKey: .id)
        nickname = try c.decodeIfPresent(String.self, forKey: .nickname) ?? ""
        avatar = try c.decodeIfPresent(Int.self, forKey: .avatar) ?? 0
        color = try c.decodeIfPresent(Int.self, forKey: .color) ?? 0
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        hidden = try c.decodeIfPresent(Bool.self, forKey: .hidden) ?? false
        group = try c.decodeIfPresent(String.self, forKey: .group)
        days = try c.decodeIfPresent([String].self, forKey: .days) ?? []
        deleted = try c.decodeIfPresent(Bool.self, forKey: .deleted) ?? false
        history = try c.decodeIfPresent([CREAReadEntry].self, forKey: .history) ?? []
        favorites = try c.decodeIfPresent([CREAFavoriteMark].self, forKey: .favorites) ?? []
    }
}

/// Data of a book needed to show it in a profile without opening it.
struct CREABookRef: Equatable {
    let key: String
    let title: String
    let author: String?
    let driveFileId: String?
}

enum CREAReaderRules {
    /// Read up to here counts as finished.
    static let finishedPercent = 95
    static let maxHistory = 300
    static let maxDays = 400

    static func keyFor(driveFileId: String?, contentHash: String?) -> String? {
        if let id = driveFileId, !id.isEmpty { return "drive:" + id }
        if let hash = contentHash, !hash.isEmpty { return "local:" + hash }
        return nil
    }

    static func opened(_ profile: CREAReaderProfile, book: CREABookRef, now: Int64) -> CREAReaderProfile {
        var next = profile
        var entry: CREAReadEntry
        if var current = profile.history.first(where: { $0.key == book.key }) {
            if !book.title.isEmpty { current.title = book.title }
            current.author = book.author ?? current.author
            current.driveFileId = book.driveFileId ?? current.driveFileId
            current.openedAt = now
            entry = current
        } else {
            entry = CREAReadEntry(key: book.key, title: book.title, author: book.author, driveFileId: book.driveFileId, openedAt: now)
        }
        next.history = upsert(profile.history, entry)
        return next
    }

    static func progressed(_ profile: CREAReaderProfile, book: CREABookRef, progression: String, percent: Int, now: Int64) -> CREAReaderProfile {
        var entry = profile.history.first(where: { $0.key == book.key })
            ?? CREAReadEntry(key: book.key, title: book.title, author: book.author, driveFileId: book.driveFileId, openedAt: now)
        let finished = entry.finishedAt ?? (percent >= finishedPercent ? now : nil)
        entry.percent = max(0, min(100, percent))
        entry.progression = progression
        entry.progressAt = now
        entry.openedAt = max(entry.openedAt, now)
        entry.finishedAt = finished
        var next = profile
        next.history = upsert(profile.history, entry)
        next.days = withDay(profile.days, today(now))
        return next
    }

    static func favorite(_ profile: CREAReaderProfile, book: CREABookRef, on: Bool, now: Int64) -> CREAReaderProfile {
        var next = profile
        next.favorites = profile.favorites.filter { $0.key != book.key } + [CREAFavoriteMark(key: book.key, on: on, at: now, title: book.title, author: book.author, driveFileId: book.driveFileId)]
        return next
    }

    static func isFavorite(_ profile: CREAReaderProfile?, key: String) -> Bool {
        profile?.favorites.last(where: { $0.key == key })?.on == true
    }

    /// Joins two copies of the same profile (this device's and Drive's). Nothing read on either is lost: per book the
    /// latest position wins, finishing is kept, and per favourite the latest change.
    static func merge(_ a: CREAReaderProfile, _ b: CREAReaderProfile) -> CREAReaderProfile {
        let newer = b.updatedAt > a.updatedAt ? b : a
        var entries: [String: CREAReadEntry] = [:]
        for entry in a.history + b.history { entries[entry.key] = entries[entry.key].map { mergeEntry($0, entry) } ?? entry }
        var marks: [String: CREAFavoriteMark] = [:]
        for mark in a.favorites + b.favorites where mark.at >= (marks[mark.key]?.at ?? Int64.min) { marks[mark.key] = mark }
        var merged = newer
        merged.createdAt = min(a.createdAt, b.createdAt)
        merged.deleted = a.deleted || b.deleted
        merged.history = trim(Array(entries.values))
        merged.favorites = marks.values.sorted { $0.at < $1.at }
        merged.days = Array(Set(a.days + b.days)).sorted().suffix(maxDays).map { $0 }
        return merged
    }

    /// Guest reading moves into a profile chosen afterwards.
    static func adopt(_ profile: CREAReaderProfile, guest: CREAReaderProfile) -> CREAReaderProfile {
        var copy = guest; copy.id = profile.id; copy.updatedAt = 0
        return merge(profile, copy)
    }

    private static func mergeEntry(_ x: CREAReadEntry, _ y: CREAReadEntry) -> CREAReadEntry {
        var position = y.progressAt > x.progressAt ? y : x
        if position.title.isEmpty { position.title = x.title.isEmpty ? y.title : x.title }
        position.author = position.author ?? x.author ?? y.author
        position.driveFileId = position.driveFileId ?? x.driveFileId ?? y.driveFileId
        position.openedAt = max(x.openedAt, y.openedAt)
        position.finishedAt = [x.finishedAt, y.finishedAt].compactMap { $0 }.min()
        return position
    }

    private static func withDay(_ days: [String], _ day: String) -> [String] {
        if days.last == day { return days }
        return Array(Set(days + [day])).sorted().suffix(maxDays).map { $0 }
    }

    /// The local date of `millis` as "2026-10-22".
    static func today(_ millis: Int64) -> String {
        CREAReadingStatsData.key(Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    private static func upsert(_ history: [CREAReadEntry], _ entry: CREAReadEntry) -> [CREAReadEntry] {
        trim(history.filter { $0.key != entry.key } + [entry])
    }

    private static func trim(_ history: [CREAReadEntry]) -> [CREAReadEntry] {
        Array(history.sorted { $0.openedAt > $1.openedAt }.prefix(maxHistory))
    }

    /// "Seguir leyendo": started, not finished, the last opened first.
    static func continueReading(_ profile: CREAReaderProfile?) -> [CREAReadEntry] {
        (profile?.history ?? []).filter { $0.finishedAt == nil && $0.percent < finishedPercent }.sorted { $0.openedAt > $1.openedAt }
    }
    static func recent(_ profile: CREAReaderProfile?) -> [CREAReadEntry] { (profile?.history ?? []).sorted { $0.openedAt > $1.openedAt } }
    static func finished(_ profile: CREAReaderProfile?) -> [CREAReadEntry] {
        (profile?.history ?? []).filter { $0.finishedAt != nil }.sorted { ($0.finishedAt ?? 0) > ($1.finishedAt ?? 0) }
    }
    static func favorites(_ profile: CREAReaderProfile?) -> [CREAFavoriteMark] {
        (profile?.favorites ?? []).filter { $0.on }.sorted { $0.at > $1.at }
    }

    /// Groups are written by hand: "Club Martes" and "club martes " are the same group.
    static func sameGroup(_ a: String?, _ b: String?) -> Bool {
        guard let a, let b, !a.trimmingCharacters(in: .whitespaces).isEmpty, !b.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        return normalize(a) == normalize(b)
    }

    /// Nicknames are compared without case or accents, so "Ana" and "ana" are the same person.
    static func normalize(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines).folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased()
    }

    /// Percent (0-100) from a Readium locator JSON, or 0.
    static func percent(fromLocator json: String) -> Int {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let locations = object["locations"] as? [String: Any],
              let value = (locations["totalProgression"] as? Double) ?? (locations["progression"] as? Double) else { return 0 }
        return max(0, min(100, Int((value * 100).rounded())))
    }
}

/// The avatars a reader can pick: drawings only, never photos.
enum CREAReaderAvatars {
    static let emojis = ["🦊", "🐼", "🦉", "🐢", "🐬", "🦁", "🐸", "🐙", "🦋", "🐝", "🐨", "🦄",
                         "🐧", "🐯", "🐰", "🐳", "🦜", "🐞", "🌻", "🌙", "⭐", "🚀", "⚽", "🎸"]
    /// Background colours (RGB) readable with the emoji on top.
    static let colors: [UInt32] = [0xFFD6A5, 0xCAFFBF, 0x9BF6FF, 0xBDB2FF, 0xFFC6FF, 0xFDFFB6, 0xA0C4FF, 0xFFADAD, 0xB5EAD7, 0xE2F0CB]
    static func emoji(_ index: Int) -> String { emojis[((index % emojis.count) + emojis.count) % emojis.count] }
    static func color(_ index: Int) -> UInt32 { colors[((index % colors.count) + colors.count) % colors.count] }
}

/// A book of the Drive catalog, as the recommendations need it.
struct CREACandidateBook: Equatable {
    let key: String
    let title: String
    let author: String?
    let categories: [String]
    let driveFileId: String
    let addedAt: Int64
}

enum CREAReason: Equatable {
    case sameAuthor(String), sameCategory(String), groupFinished(Int), popular(Int), new
    var text: String {
        switch self {
        case .sameAuthor(let author): return "Porque leíste a \(author)"
        case .sameCategory(let category): return "Te gusta: \(category)"
        case .groupFinished(let count): return count == 1 ? "Lo terminó 1 de tu grupo" : "Lo terminaron \(count) de tu grupo"
        case .popular(let count): return "Lo terminaron \(count) lectores"
        case .new: return "Nuevo en el catálogo"
        }
    }
}

struct CREARecommendation: Equatable { let book: CREACandidateBook; let reason: CREAReason }
struct CREAGroupReading { let profile: CREAReaderProfile; let entry: CREAReadEntry }

enum CREAAchievementID: String, CaseIterable { case firstBook, fiveBooks, tenBooks, streak3, streak7, threeAuthors, firstFavorite, explorer
    var title: String {
        switch self {
        case .firstBook: "Primer libro"; case .fiveBooks: "5 libros terminados"; case .tenBooks: "10 libros terminados"
        case .streak3: "3 días seguidos"; case .streak7: "7 días seguidos"; case .threeAuthors: "3 autores distintos"
        case .firstFavorite: "Primer favorito"; case .explorer: "10 libros abiertos"
        }
    }
    var icon: String {
        switch self {
        case .firstBook: "book.closed"; case .fiveBooks: "books.vertical"; case .tenBooks: "trophy"
        case .streak3: "flame"; case .streak7: "flame.fill"; case .threeAuthors: "person.3"
        case .firstFavorite: "heart"; case .explorer: "binoculars"
        }
    }
}
struct CREAAchievement { let id: CREAAchievementID; let progress: Int; let goal: Int; var unlocked: Bool { progress >= goal } }

enum CREAReaderInsights {
    /// Readings of the group older than this are not "now" anymore.
    private static let groupWindow: Int64 = 30 * 24 * 3600 * 1000

    /// What the other people of `me`'s group are reading now (one book each, the latest first).
    static func groupReading(me: CREAReaderProfile?, everyone: [CREAReaderProfile], now: Int64) -> [CREAGroupReading] {
        guard let me, let group = me.group else { return [] }
        return everyone
            .filter { $0.id != me.id && !$0.hidden && !$0.deleted && CREAReaderRules.sameGroup($0.group, group) }
            .compactMap { profile -> CREAGroupReading? in
                CREAReaderRules.recent(profile).first(where: { now - $0.openedAt <= groupWindow }).map { CREAGroupReading(profile: profile, entry: $0) }
            }
            .sorted { $0.entry.openedAt > $1.entry.openedAt }
    }

    /// Up to `limit` books to read next, each with a reason, from the Drive catalog. Without AI and without a
    /// connection: same author as a book `me` finished or liked, same category, what the group finished, what many
    /// readers finished, what is new. Books already started are left out.
    static func recommend(me: CREAReaderProfile?, everyone: [CREAReaderProfile], catalog: [CREACandidateBook], now: Int64, limit: Int = 8) -> [CREARecommendation] {
        guard !catalog.isEmpty else { return [] }
        let read = Set((me?.history ?? []).map(\.key))
        let available = catalog.filter { !read.contains($0.key) }
        guard !available.isEmpty else { return [] }
        let byKey = Dictionary(catalog.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })

        // What `me` enjoyed: finished books and favourites weigh most, then what they opened.
        var likedKeys: [String] = []
        likedKeys += CREAReaderRules.finished(me).map(\.key)
        likedKeys += CREAReaderRules.favorites(me).map(\.key)
        likedKeys += CREAReaderRules.recent(me).map(\.key)
        var seen = Set<String>()
        let liked = likedKeys.filter { seen.insert($0).inserted }.compactMap { byKey[$0] }
        var seenAuthors = Set<String>()
        let authors = liked.compactMap { $0.author?.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && seenAuthors.insert($0.lowercased()).inserted }
        var categoryCount: [String: Int] = [:]
        for book in liked { for category in book.categories { categoryCount[category, default: 0] += 1 } }
        let categories = categoryCount.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.map(\.key)

        let others = everyone.filter { $0.id != me?.id && !$0.deleted }
        func finishedBy(_ profiles: [CREAReaderProfile], _ key: String) -> Int {
            profiles.filter { p in p.history.contains { $0.key == key && $0.finishedAt != nil } }.count
        }
        let group = others.filter { me != nil && CREAReaderRules.sameGroup($0.group, me?.group) }

        var picks: [String: CREARecommendation] = [:]
        var order: [String] = []
        func add(_ book: CREACandidateBook, _ reason: CREAReason) {
            if picks.count < limit && picks[book.key] == nil { picks[book.key] = CREARecommendation(book: book, reason: reason); order.append(book.key) }
        }
        for author in authors {
            available.filter { ($0.author ?? "").trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(author) == .orderedSame }.prefix(2).forEach { add($0, .sameAuthor(author)) }
        }
        for category in categories.prefix(3) {
            available.filter { $0.categories.contains(category) }.prefix(2).forEach { add($0, .sameCategory(category)) }
        }
        available.map { ($0, finishedBy(group, $0.key)) }.filter { $0.1 > 0 }.sorted { $0.1 > $1.1 }.prefix(3).forEach { add($0.0, .groupFinished($0.1)) }
        available.map { ($0, finishedBy(others, $0.key)) }.filter { $0.1 >= 2 }.sorted { $0.1 > $1.1 }.prefix(3).forEach { add($0.0, .popular($0.1)) }
        available.sorted { $0.addedAt > $1.addedAt }.prefix(limit).forEach { add($0, .new) }
        return order.compactMap { picks[$0] }
    }

    private static func parse(_ days: [String]) -> [Date] {
        let formatter = DateFormatter(); formatter.dateFormat = "yyyy-MM-dd"; formatter.calendar = Calendar.current
        return days.compactMap { formatter.date(from: $0) }
    }

    /// Days in a row with reading, ending today or yesterday (a streak is not lost before bedtime).
    static func streak(days: [String], today: Date = Date()) -> Int {
        let calendar = Calendar.current
        let set = Set(days)
        var day = set.contains(CREAReadingStatsData.key(today)) ? today : (calendar.date(byAdding: .day, value: -1, to: today) ?? today)
        var count = 0
        while set.contains(CREAReadingStatsData.key(day)) {
            count += 1
            day = calendar.date(byAdding: .day, value: -1, to: day) ?? day.addingTimeInterval(-86400)
        }
        return count
    }

    /// The longest streak ever, for achievements that stay unlocked.
    static func bestStreak(days: [String]) -> Int {
        let calendar = Calendar.current
        let sorted = Array(Set(parse(days))).sorted()
        var best = 0, current = 0
        var previous: Date?
        for day in sorted {
            if let previous, let next = calendar.date(byAdding: .day, value: 1, to: previous), calendar.isDate(next, inSameDayAs: day) { current += 1 } else { current = 1 }
            best = max(best, current); previous = day
        }
        return best
    }

    /// Small achievements to encourage reading (no points, no ranking).
    static func achievements(_ profile: CREAReaderProfile?) -> [CREAAchievement] {
        let finished = CREAReaderRules.finished(profile).count
        let best = bestStreak(days: profile?.days ?? [])
        let authors = Set((profile?.history ?? []).filter { $0.finishedAt != nil }.compactMap { $0.author?.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty }).count
        let favorites = CREAReaderRules.favorites(profile).count
        let opened = profile?.history.count ?? 0
        let items: [(CREAAchievementID, Int, Int)] = [
            (.firstBook, finished, 1), (.fiveBooks, finished, 5), (.tenBooks, finished, 10), (.streak3, best, 3),
            (.streak7, best, 7), (.threeAuthors, authors, 3), (.firstFavorite, favorites, 1), (.explorer, opened, 10)
        ]
        return items.map { CREAAchievement(id: $0.0, progress: min($0.1, $0.2), goal: $0.2) }
    }
}

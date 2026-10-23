import Foundation
import CryptoKit
import GoogleSignIn

/// Reader profiles kept on this device, who is reading right now, and what they read.
///
/// Two ways of working, chosen automatically:
/// - **Group device** (several devices share the Google account): whoever holds the device may pick their
///   nickname; until they do they read as a guest, and that reading moves into their profile if they pick it later.
///   The session ends after 30 minutes without use, at the end of the day or when they hand the device back.
/// - **Personal account**: one profile for the account, always active, nothing to choose.
///
/// Everything here is optional: reading never waits for a profile.
@MainActor
final class CREAReaderStore: ObservableObject {
    static let shared = CREAReaderStore()
    static let groupDevices = 3
    static let idleSeconds: TimeInterval = 30 * 60
    static let maxNickname = 24
    static let maxGroup = 32
    static let guestID = "guest"
    static let personalPrefix = "cuenta-"

    @Published private(set) var profiles: [CREAReaderProfile] = []
    @Published private(set) var activeID: String?
    @Published private(set) var guest: CREAReaderProfile?
    /// nil: decide by the number of devices; true/false: chosen in Ajustes.
    @Published private(set) var modeOverride: Bool?

    private let defaults = UserDefaults.standard
    private let directory: URL
    private var guestFile: URL { directory.appendingPathComponent("guest.json.local") }
    private let clock: () -> Int64

    private enum Key {
        static let active = "crea.readers.active", lastUse = "crea.readers.lastUse", day = "crea.readers.day"
        static let recent = "crea.readers.recentHere", mode = "crea.readers.groupMode", lastGroup = "crea.readers.lastGroup"
        static let dirty = "crea.readers.dirty", versionPrefix = "crea.readers.version.", clearedPrefix = "crea.readers.cleared."
        static let devices = "crea.readers.accountDevices"
    }

    init(directory: URL? = nil, clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.clock = clock
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("readers", isDirectory: true)
        self.directory = base
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        activeID = defaults.string(forKey: Key.active)
        modeOverride = defaults.object(forKey: Key.mode) as? Bool
        profiles = loadAll()
        guest = loadFile(guestFile)
    }

    // MARK: Mode

    /// Devices that use this Google account (from the last Drive sync); 0 when unknown.
    var accountDevices: Int {
        get { defaults.integer(forKey: Key.devices) }
        set { defaults.set(newValue, forKey: Key.devices); objectWillChange.send() }
    }
    /// Several people use this device: offer the "who is reading?" choice.
    var isGroupDevice: Bool { modeOverride ?? (accountDevices >= Self.groupDevices) }

    func setModeOverride(_ group: Bool?) {
        if let group { defaults.set(group, forKey: Key.mode) } else { defaults.removeObject(forKey: Key.mode) }
        modeOverride = group
        if group == false { endSession() }
    }

    // MARK: Session

    /// The profile reading right now: the chosen reader, or the account's profile on a personal device.
    func current() -> CREAReaderProfile? {
        expireIfIdle()
        if !isGroupDevice { return personalProfile() }
        return activeID.flatMap(byID)
    }

    private func target() -> CREAReaderProfile? { current() ?? (isGroupDevice ? guestOrNew() : nil) }
    func byID(_ id: String) -> CREAReaderProfile? { profiles.first { $0.id == id } }

    /// Profiles most recently read on this device first, then the rest by most recent activity.
    func choices() -> [CREAReaderProfile] {
        let here = recentHere()
        return profiles.filter { !$0.id.hasPrefix(Self.personalPrefix) }.sorted { a, b in
            let ia = here.firstIndex(of: a.id) ?? Int.max, ib = here.firstIndex(of: b.id) ?? Int.max
            if ia != ib { return ia < ib }
            return (a.history.map(\.openedAt).max() ?? a.createdAt) > (b.history.map(\.openedAt).max() ?? b.createdAt)
        }
    }

    func recentHere() -> [String] { (defaults.string(forKey: Key.recent) ?? "").split(separator: ",").map(String.init) }

    /// `id` starts reading; any guest reading of this session goes into their profile.
    func select(_ id: String) {
        guard let profile = byID(id) else { return }
        if let guest, !guest.history.isEmpty || !guest.favorites.isEmpty { save(CREAReaderRules.adopt(profile, guest: guest)) }
        clearGuest()
        let recent = Array(([id] + recentHere().filter { $0 != id }).prefix(12))
        defaults.set(id, forKey: Key.active)
        defaults.set(recent.joined(separator: ","), forKey: Key.recent)
        touch()
        activeID = id
    }

    /// "Soy nuevo": creates the profile and starts reading with it.
    @discardableResult
    func create(nickname: String, avatar: Int, color: Int, group: String? = nil) -> CREAReaderProfile {
        let now = clock()
        let clean = cleanGroup(group)
        let profile = CREAReaderProfile(id: String(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(16)),
                                        nickname: String(nickname.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxNickname)),
                                        avatar: avatar, color: color, createdAt: now, updatedAt: now, group: clean)
        if let clean { defaults.set(clean, forKey: Key.lastGroup) }
        save(profile)
        select(profile.id)
        return profile
    }

    /// Profiles whose nickname is the same as `nickname` (to warn before creating a second "Ana").
    func sameNickname(_ nickname: String) -> [CREAReaderProfile] {
        let wanted = CREAReaderRules.normalize(nickname)
        return profiles.filter { CREAReaderRules.normalize($0.nickname) == wanted }
    }

    /// Reading groups known on these devices, the one used last on this device first, written as the first person who used it wrote it.
    func groups() -> [String] {
        var byKey: [(String, String)] = []
        for profile in profiles.sorted(by: { $0.createdAt < $1.createdAt }) {
            guard let name = profile.group, !name.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
            let key = CREAReaderRules.normalize(name)
            if !byKey.contains(where: { $0.0 == key }) { byKey.append((key, name)) }
        }
        let last = defaults.string(forKey: Key.lastGroup).map(CREAReaderRules.normalize)
        return byKey.sorted { a, b in
            if (a.0 == last) != (b.0 == last) { return a.0 == last }
            return a.1.lowercased() < b.1.lowercased()
        }.map(\.1)
    }

    /// A typed group becomes the existing one when they only differ in case or accents.
    private func cleanGroup(_ group: String?) -> String? {
        guard let typed = group?.trimmingCharacters(in: .whitespacesAndNewlines), !typed.isEmpty else { return nil }
        let limited = String(typed.prefix(Self.maxGroup))
        return groups().first { CREAReaderRules.sameGroup($0, limited) } ?? limited
    }

    /// "Terminé, devolver el dispositivo": the next person starts fresh.
    func endSession() {
        defaults.removeObject(forKey: Key.active); defaults.removeObject(forKey: Key.lastUse)
        activeID = nil
        clearGuest()
    }

    /// Called when the app comes back: a session left alone ends by itself.
    func expireIfIdle() {
        guard isGroupDevice else { return }
        let lastUse = Int64(defaults.double(forKey: Key.lastUse))
        let day = defaults.string(forKey: Key.day)
        let active = activeID != nil || guest != nil
        if active && (Double(clock() - lastUse) / 1000 > Self.idleSeconds || day != CREAReadingStatsData.key(Date())) { endSession() }
    }

    private func touch() {
        defaults.set(Double(clock()), forKey: Key.lastUse)
        defaults.set(CREAReadingStatsData.key(Date()), forKey: Key.day)
    }

    // MARK: Recording

    /// Where the current reader stopped in `key`, to open the book there (nil: use this device's position).
    func progression(for key: String) -> String? { current()?.history.first { $0.key == key }?.progression }
    func opened(_ book: CREABookRef) { record { CREAReaderRules.opened($0, book: book, now: self.clock()) } }
    func progressed(_ book: CREABookRef, progression: String, percent: Int) {
        record { CREAReaderRules.progressed($0, book: book, progression: progression, percent: percent, now: self.clock()) }
    }
    func setFavorite(_ book: CREABookRef, on: Bool) { record { CREAReaderRules.favorite($0, book: book, on: on, now: self.clock()) } }
    func isFavorite(_ key: String) -> Bool { CREAReaderRules.isFavorite(current(), key: key) }

    private func record(_ change: (CREAReaderProfile) -> CREAReaderProfile) {
        guard let profile = target() else { return }
        touch()
        let changed = change(profile)
        if profile.id == Self.guestID { saveGuest(changed) } else { save(changed) }
    }

    // MARK: Profile settings

    func update(_ id: String, nickname: String? = nil, avatar: Int? = nil, color: Int? = nil, hidden: Bool? = nil, group: String? = nil, clearGroup: Bool = false) {
        guard var profile = byID(id) else { return }
        let newGroup: String? = clearGroup ? nil : (group != nil ? cleanGroup(group) : profile.group)
        if let newGroup { defaults.set(newGroup, forKey: Key.lastGroup) }
        if let nickname, !nickname.trimmingCharacters(in: .whitespaces).isEmpty {
            profile.nickname = String(nickname.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxNickname))
        }
        profile.avatar = avatar ?? profile.avatar
        profile.color = color ?? profile.color
        profile.hidden = hidden ?? profile.hidden
        profile.group = newGroup
        profile.updatedAt = clock()
        save(profile)
    }

    /// "Borrar mi historial": keeps the profile, forgets what was read.
    func clearHistory(_ id: String) {
        guard var profile = byID(id) else { return }
        profile.history = []; profile.favorites = []; profile.updatedAt = clock()
        save(profile)
        defaults.set(Double(clock()), forKey: Key.clearedPrefix + id)
    }

    /// Removes the profile here and, after the next sync, on every device.
    func delete(_ id: String) {
        guard var profile = byID(id) else { return }
        profile.deleted = true; profile.history = []; profile.favorites = []; profile.updatedAt = clock()
        save(profile)
        if activeID == id { endSession() }
    }

    // MARK: Sync support

    func dirtyIDs() -> Set<String> { Set(defaults.stringArray(forKey: Key.dirty) ?? []) }
    func markUploaded(_ id: String, version: String) {
        defaults.set(Array(dirtyIDs().subtracting([id])), forKey: Key.dirty)
        defaults.set(version, forKey: Key.versionPrefix + id)
    }
    func knownVersion(_ id: String) -> String? { defaults.string(forKey: Key.versionPrefix + id) }

    /// A profile came from Drive: joined with the local copy without losing anything.
    func mergeRemote(_ remote: CREAReaderProfile, version: String) {
        let local = loadFile(fileURL(remote.id))
        var incoming = remote
        let cleared = Int64(defaults.double(forKey: Key.clearedPrefix + remote.id))
        if cleared > 0 { incoming.history = remote.history.filter { $0.openedAt > cleared } }
        let merged = local.map { CREAReaderRules.merge($0, incoming) } ?? incoming
        writeFile(merged)
        defaults.set(version, forKey: Key.versionPrefix + remote.id)
        if local != nil && merged != incoming { markDirty(merged.id) }
        profiles = loadAll()
        if merged.deleted && activeID == merged.id { endSession() }
    }

    /// The full local copy (also deleted ones), for uploading.
    func localCopy(_ id: String) -> CREAReaderProfile? { loadFile(fileURL(id)) }

    // MARK: Storage

    private func personalProfile() -> CREAReaderProfile {
        let email = GIDSignIn.sharedInstance.currentUser?.profile?.email
        let id = Self.personalPrefix + (email.map(shortHash) ?? "local")
        if let existing = byID(id) { return existing }
        let now = clock()
        let local = email.map { String($0.split(separator: "@").first ?? "Yo") } ?? "Yo"
        let name = local.prefix(1).uppercased() + String(local.dropFirst())
        let profile = CREAReaderProfile(id: id, nickname: name, createdAt: now, updatedAt: now)
        save(profile)
        return profile
    }

    private func guestOrNew() -> CREAReaderProfile {
        guest ?? CREAReaderProfile(id: Self.guestID, nickname: "", createdAt: clock(), updatedAt: clock())
    }

    private func save(_ profile: CREAReaderProfile) {
        writeFile(profile); markDirty(profile.id); profiles = loadAll()
    }
    private func markDirty(_ id: String) { defaults.set(Array(dirtyIDs().union([id])), forKey: Key.dirty) }

    private func saveGuest(_ profile: CREAReaderProfile) {
        if let data = try? JSONEncoder().encode(profile) { try? data.write(to: guestFile, options: .atomic) }
        guest = profile
    }
    private func clearGuest() { try? FileManager.default.removeItem(at: guestFile); guest = nil }

    private func fileURL(_ id: String) -> URL { directory.appendingPathComponent("\(id).json") }
    private func writeFile(_ profile: CREAReaderProfile) {
        if let data = try? JSONEncoder().encode(profile) { try? data.write(to: fileURL(profile.id), options: .atomic) }
    }
    private func loadFile(_ url: URL) -> CREAReaderProfile? {
        (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode(CREAReaderProfile.self, from: $0) }
    }
    private func loadAll() -> [CREAReaderProfile] {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }.compactMap(loadFile).filter { !$0.deleted }
    }
    private func shortHash(_ text: String) -> String {
        SHA256.hash(data: Data(text.lowercased().utf8)).map { String(format: "%02x", $0) }.joined().prefix(16).description
    }
}

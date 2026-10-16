import Foundation

/// Copies made to read a Drive book online without adding it to the library.
enum CREATemporaryStorage {
    static var directory: URL { FileManager.default.temporaryDirectory.appendingPathComponent("CREAReaderOnline", isDirectory: true) }

    static func size() -> Int64 {
        guard let files = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        return files.compactMap { ($0 as? URL)?.resourceValues(forKeys: [.fileSizeKey]).fileSize }.reduce(0) { $0 + Int64($1) }
    }

    static func clear() { try? FileManager.default.removeItem(at: directory) }
}

import Foundation
import UIKit
import GoogleSignIn
import UserNotifications

struct CREATransferRecord: Codable, Identifiable {
    var id: Int
    let book: CREARemoteBook
    let ownerID: String
    let accountEmail: String?
    var state = "queued"
    var copied: Int64 = 0
    var expected: Int64 = 0
    var stagedFile: String?
    var error: String?
    var order: Int64 = 0
    var active: Bool { ["queued", "running", "processing"].contains(state) }
    var progress: Double? { expected > 0 ? min(1, Double(copied) / Double(expected)) : nil }
}

enum CREATransferValidation {
    static func validate(_ file: URL, book: CREARemoteBook) throws -> (hash: String, md5: String) {
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, book.size.flatMap(Int.init).map({ $0 == size }) ?? true else { throw issue("La descarga tiene un tamaño incorrecto.") }
        let handle = try FileHandle(forReadingFrom: file); defer { try? handle.close() }
        let prefix = try handle.read(upToCount: 4) ?? Data()
        guard book.isEPUB ? prefix.starts(with: Data([0x50, 0x4b])) : prefix == Data("%PDF".utf8) else { throw issue("El archivo descargado no es un EPUB/PDF válido.") }
        let md5 = try CREABookIdentity.md5(file)
        if let expected = book.md5Checksum, !expected.isEmpty, expected.lowercased() != md5 { throw issue("La verificación de integridad MD5 falló. Se conserva la copia anterior.") }
        return (try CREABookIdentity.hash(file), md5)
    }
    static func requiredSpace(_ books: [CREARemoteBook]) throws -> Int64 {
        var total: Int64 = 0
        for book in books {
            let amount = max(0, book.size.flatMap(Int64.init) ?? 0)
            let doubled = amount.multipliedReportingOverflow(by: 2)
            let sum = total.addingReportingOverflow(doubled.partialValue)
            guard !doubled.overflow, !sum.overflow else { throw issue("La selección supera el tamaño permitido.") }
            total = sum.partialValue
        }
        return total
    }
    static func issue(_ message: String) -> NSError { NSError(domain: "CREATransfer", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
}

/// System-managed background downloads; the journal contains metadata, never OAuth tokens.
@MainActor final class CREABackgroundDownloads: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = CREABackgroundDownloads()
    static let identifier = "org.crea.reader.ios.downloads"
    @Published private(set) var records: [Int: CREATransferRecord] = [:]
    private let key = "crea.reader.backgroundDownloads.v1"
    private var waiters: [Int: [CheckedContinuation<Void, Never>]] = [:]
    private var completion: (() -> Void)?
    private var eventsFinished = false
    private var importing = Set<Int>()
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.waitsForConnectivity = true
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()
    private static var staging: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CREATransfers", isDirectory: true) }
    override init() {
        super.init()
        if let data = UserDefaults.standard.data(forKey: key), let saved = try? JSONDecoder().decode([Int: CREATransferRecord].self, from: data) { records = saved }
    }
    func activate() {
        session.getAllTasks { [weak self] tasks in
            Task { @MainActor in
                guard let self else { return }
                let live = Set(tasks.map { $0.taskIdentifier })
                // Background completion callbacks may arrive just after reconnecting.
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                for (id, record) in self.records where record.active && !live.contains(id) && record.stagedFile == nil {
                    self.fail(id, message: "La descarga se interrumpió. Puedes reintentar.")
                }
                await self.resumeReadyForCurrentAccount()
            }
        }
    }
    func handleEvents(_ completion: @escaping () -> Void) { self.completion = completion; eventsFinished = false; activate() }
    func enqueue(_ book: CREARemoteBook, token: String, ownerID: String, accountEmail: String?) -> Int {
        if let ready = record(for: book.id), ready.state == "ready", ready.stagedFile != nil {
            records[ready.id]?.state = "processing"; persist(); Task { await finish(ready.id) }; return ready.id
        }
        if let existing = records.values.first(where: { $0.book.id == book.id && $0.ownerID == ownerID && $0.active }) { return existing.id }
        var request = URLRequest(url: URL(string: "https://www.googleapis.com/drive/v3/files/\(book.id)?alt=media")!)
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        let task = session.downloadTask(with: request)
        var record = CREATransferRecord(id: task.taskIdentifier, book: book, ownerID: ownerID, accountEmail: accountEmail, expected: book.size.flatMap(Int64.init) ?? 0)
        record.order = (records.values.map { $0.order }.max() ?? 0) + 1
        records[task.taskIdentifier] = record
        persist(); task.resume()
        return task.taskIdentifier
    }
    func wait(_ id: Int) async {
        guard records[id]?.active == true || importing.contains(id) else { return }
        await withCheckedContinuation { waiters[id, default: []].append($0) }
    }
    func record(for bookID: String) -> CREATransferRecord? {
        records.values.filter { $0.book.id == bookID && $0.ownerID == GIDSignIn.sharedInstance.currentUser?.userID }.max { $0.order < $1.order }
    }
    func cancel(_ bookID: String) {
        guard let record = record(for: bookID), ["queued", "running"].contains(record.state) else { return }
        records[record.id]?.state = "cancelled"; persist(); release(record.id)
        session.getAllTasks { tasks in tasks.first { $0.taskIdentifier == record.id }?.cancel() }
    }
    func resumeReadyForCurrentAccount() async {
        for (id, record) in records where record.stagedFile != nil && ["ready", "processing", "running", "queued"].contains(record.state) {
            if record.ownerID == GIDSignIn.sharedInstance.currentUser?.userID { await finish(id) }
        }
    }
    private func persist() { if let data = try? JSONEncoder().encode(records) { UserDefaults.standard.set(data, forKey: key) } }
    private func release(_ id: Int) {
        waiters.removeValue(forKey: id)?.forEach { $0.resume() }
        completeBackgroundEvents()
    }
    private func completeBackgroundEvents() {
        if eventsFinished && !records.values.contains(where: { $0.active }) { completion?(); completion = nil }
    }
    private func fail(_ id: Int, message: String) { records[id]?.state = "failed"; records[id]?.error = message; persist(); release(id); if let record = records[id] { notify(record, failed: true) } }
    private func finish(_ id: Int) async {
        guard importing.insert(id).inserted else { return }
        defer { importing.remove(id) }
        guard let record = records[id], let filename = record.stagedFile else { return }
        guard record.ownerID == GIDSignIn.sharedInstance.currentUser?.userID else {
            records[id]?.state = "ready"; persist(); release(id); return
        }
        if CREAImportCoordinator.shared.processing { records[id]?.state = "ready"; persist(); release(id); return }
        records[id]?.state = "processing"; persist()
        let file = Self.staging.appendingPathComponent(filename)
        do {
            let validation = try await Task.detached { try CREATransferValidation.validate(file, book: record.book) }.value
            guard record.ownerID == GIDSignIn.sharedInstance.currentUser?.userID else { records[id]?.state = "ready"; persist(); release(id); return }
            if LocalLibrary.shared.books.contains(where: { $0.driveFileID == record.book.id }) {
                try LocalLibrary.shared.installRemote(record.book, file: file, hash: validation.hash, md5: validation.md5, accountEmail: record.accountEmail)
            } else {
                let outcome = await CREAImportCoordinator.shared.run([file], fallbackNames: [file: record.book.displayName], remote: record.book, ownerID: record.ownerID, accountEmail: record.accountEmail) { waiting in
                    self.records[id]?.state = waiting ? "ready" : "processing"; self.persist(); self.completeBackgroundEvents()
                }
                if case .failed(let message) = outcome { throw CREATransferValidation.issue(message) }
                if case .busy = outcome { records[id]?.state = "ready"; persist(); release(id); return }
                if case .approvalInForeground = outcome { records[id]?.state = "ready"; persist(); release(id); return }
                if case .omitted = outcome {
                    records[id]?.state = "cancelled"; records[id]?.stagedFile = nil
                    try? FileManager.default.removeItem(at: file); persist(); release(id); return
                }
            }
            records[id]?.state = "completed"; records[id]?.error = nil; records[id]?.stagedFile = nil
            try? FileManager.default.removeItem(at: file)
            persist(); release(id)
            if let completed = records[id] { notify(completed, failed: false) }
        } catch { try? FileManager.default.removeItem(at: file); records[id]?.stagedFile = nil; fail(id, message: error.localizedDescription) }
    }
    private func notify(_ record: CREATransferRecord, failed: Bool) {
        guard UserDefaults.standard.bool(forKey: "crea.reader.download.notifications") else { return }
        let content = UNMutableNotificationContent()
        content.title = failed ? "No se completó la descarga" : "Libro descargado"
        content.body = record.book.displayName + (failed ? "\n" + (record.error ?? "Reintenta desde el catálogo") : " · Disponible sin conexión")
        content.sound = .default; content.threadIdentifier = "crea.downloads"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "crea.download.\(record.id)", content: content, trigger: nil), withCompletionHandler: { _ in })
    }
    func cancelForAccount(_ owner: String) {
        let ids = records.values.filter { $0.ownerID == owner && ["queued", "running"].contains($0.state) }.map { $0.id }
        for id in ids { records[id]?.state = "cancelled"; release(id) }; persist()
        session.getAllTasks { tasks in for task in tasks where ids.contains(task.taskIdentifier) { task.cancel() } }
    }
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let id = downloadTask.taskIdentifier
        Task { @MainActor in
            guard let previous = self.records[id], ["queued", "running"].contains(previous.state) else { return }
            let total = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : previous.expected
            if total > 0 && totalBytesWritten < total && totalBytesWritten - previous.copied < max(1, total / 100) { return }
            self.records[id]?.copied = totalBytesWritten; self.records[id]?.expected = total; self.records[id]?.state = "running"; self.persist()
        }
    }
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let id = downloadTask.taskIdentifier
        do {
            guard let response = downloadTask.response as? HTTPURLResponse, (200...299).contains(response.statusCode) else { throw CREATransferValidation.issue("Google Drive rechazó la descarga. Revisa sesión y permisos.") }
            // Copy synchronously while URLSession still owns the callback's temporary URL.
            let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CREATransfers", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let filename = UUID().uuidString + ".download"
            let destination = root.appendingPathComponent(filename)
            try FileManager.default.copyItem(at: location, to: destination)
            Task { @MainActor in
                guard let record = self.records[id], record.state != "cancelled" else { try? FileManager.default.removeItem(at: destination); return }
                // LocalLibrary's importer uses the file extension; keep the staged name deterministic in the journal.
                let named = destination.deletingPathExtension().appendingPathExtension(record.book.isEPUB ? "epub" : "pdf")
                do {
                    try FileManager.default.moveItem(at: destination, to: named)
                    self.records[id]?.stagedFile = named.lastPathComponent; self.persist(); await self.finish(id)
                } catch { self.fail(id, message: error.localizedDescription) }
            }
        } catch { Task { @MainActor in self.fail(id, message: error.localizedDescription) } }
    }
    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        let id = task.taskIdentifier
        Task { @MainActor in if self.records[id]?.state != "cancelled" { self.fail(id, message: error.localizedDescription) } }
    }
    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in self.eventsFinished = true; if !self.records.values.contains(where: { $0.active }) { self.completion?(); self.completion = nil } }
    }
}

final class CREADownloadAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == CREABackgroundDownloads.identifier else { completionHandler(); return }
        Task { @MainActor in CREABackgroundDownloads.shared.handleEvents(completionHandler) }
    }
}

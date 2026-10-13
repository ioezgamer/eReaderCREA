import SwiftUI
import ReadiumShared
import ReadiumStreamer
import GoogleSignIn

struct CREAPreparedImport: Identifiable {
    let id = UUID()
    let file: URL
    let hash: String
    let title: String
    let author: String?
    let identifier: String?
    let kind: String
}
struct CREAImportDecision: Identifiable {
    let candidate: CREAPreparedImport
    let existing: LocalBook
    var id: UUID { candidate.id }
}
enum CREAImportResult { case completed, omitted, busy, failed(String) }

/// File metadata is inspected before committing; a similar edition never replaces a book silently.
@MainActor final class CREAImportCoordinator: ObservableObject {
    static let shared = CREAImportCoordinator()
    @Published private(set) var pending: CREAImportDecision?
    @Published private(set) var processing = false
    @Published var report: String?
    private let library: LocalLibrary
    private var continuation: CheckedContinuation<Bool, Never>?
    private var decision = false
    init(library: LocalLibrary? = nil) { self.library = library ?? .shared }
    func choose(_ accepted: Bool) { guard pending != nil else { return }; decision = accepted; pending = nil }
    func confirmationClosed() { continuation?.resume(returning: decision); continuation = nil }
    @discardableResult func run(_ urls: [URL], fallbackNames: [URL: String] = [:], remote: CREARemoteBook? = nil, ownerID: String? = nil, accountEmail: String? = nil, approval: ((Bool) -> Void)? = nil) async -> CREAImportResult {
        guard !processing else { report = "Espera a que termine la importación actual."; return .busy }
        processing = true; defer { processing = false }
        var added = 0, repeated = 0, ignored = 0
        var failures: [String] = []
        for source in urls {
            var staged: URL?
            do {
                let file = try await Task.detached { try Self.stage(source) }.value; staged = file
                let candidate = try await Self.inspect(file, fallback: fallbackNames[source] ?? source.deletingPathExtension().lastPathComponent)
                var sameFile: LocalBook?
                for book in library.books {
                    let hash: String?
                    if let stored = book.contentHash { hash = stored }
                    else { let url = library.url(for: book); hash = try? await Task.detached { try CREABookIdentity.hash(url) }.value }
                    if hash == candidate.hash { sameFile = book; break }
                }
                if sameFile != nil {
                    if let remote {
                        guard ownerID == GIDSignIn.sharedInstance.currentUser?.userID else { throw CREATransferValidation.issue("La cuenta cambió durante la importación.") }
                        try library.installRemote(remote, file: file, hash: candidate.hash, md5: CREABookIdentity.md5(file), accountEmail: accountEmail)
                    }
                    repeated += 1
                }
                else {
                    var accepted = true
                    if let existing = library.books.first(where: { CREADuplicateRules.similar(title: candidate.title, author: candidate.author, identifier: candidate.identifier, kind: candidate.kind, to: $0) }) {
                        decision = false
                        approval?(true)
                        accepted = await withCheckedContinuation { callback in continuation = callback; pending = CREAImportDecision(candidate: candidate, existing: existing) }
                        approval?(false)
                    }
                    if accepted {
                        if let remote {
                            guard ownerID == GIDSignIn.sharedInstance.currentUser?.userID else { throw CREATransferValidation.issue("La cuenta cambió durante la importación.") }
                            try library.installRemote(remote, file: file, hash: candidate.hash, md5: CREABookIdentity.md5(file), accountEmail: accountEmail)
                        }
                        let book: LocalBook
                        if let remote, let installed = library.books.first(where: { $0.driveFileID == remote.id }) { book = installed }
                        else { book = try library.add(url: file, displayName: candidate.title) }
                        library.update(book) { $0.author = candidate.author; $0.publicationIdentifier = candidate.identifier }
                        added += 1
                    } else { ignored += 1 }
                }
            } catch { failures.append(source.lastPathComponent + ": " + error.localizedDescription) }
            if let staged { try? FileManager.default.removeItem(at: staged) }
        }
        if remote == nil { report = "\(added) importados · \(repeated) ya estaban en tu biblioteca · \(ignored) omitidos" + (failures.isEmpty ? "" : "\n" + failures.joined(separator: "\n")) }
        if !failures.isEmpty { return .failed(failures.joined(separator: "\n")) }
        return ignored == 0 ? .completed : .omitted
    }
    nonisolated private static func stage(_ source: URL) throws -> URL {
        let access = source.startAccessingSecurityScopedResource(); defer { if access { source.stopAccessingSecurityScopedResource() } }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("CREAImports", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let file = root.appendingPathComponent(UUID().uuidString).appendingPathExtension(source.pathExtension.lowercased())
        var issue: NSError?; var copyError: Error?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: source, options: [], error: &issue) { readable in
            do { try FileManager.default.copyItem(at: readable, to: file) } catch { copyError = error }
        }
        if let error = issue ?? (copyError as NSError?) { try? FileManager.default.removeItem(at: file); throw error }
        return file
    }
    static func inspect(_ file: URL, fallback: String) async throws -> CREAPreparedImport {
        let ext = file.pathExtension.lowercased()
        let kind = ["mp3", "m4a", "m4b", "aac", "wav", "audiobook"].contains(ext) ? "AUDIO" : ["cbz", "jpg", "jpeg", "png", "gif", "webp"].contains(ext) ? "IMAGEN" : ext.uppercased()
        guard ["AUDIO", "IMAGEN", "EPUB", "PDF"].contains(kind) else { throw CREATransferValidation.issue("Formato no compatible") }
        let hash = try await Task.detached { try CREABookIdentity.hash(file) }.value
        if kind == "IMAGEN", ext != "cbz" {
            guard UIImage(contentsOfFile: file.path) != nil else { throw CREATransferValidation.issue("La imagen no es válida.") }
            return CREAPreparedImport(file: file, hash: hash, title: fallback, author: nil, identifier: nil, kind: kind)
        }
        let http = DefaultHTTPClient(), retriever = AssetRetriever(httpClient: DefaultHTTPClient())
        let opener = PublicationOpener(parser: DefaultPublicationParser(httpClient: http, assetRetriever: retriever, pdfFactory: DefaultPDFDocumentFactory()))
        guard let absolute = file.anyURL.absoluteURL else { throw CREATransferValidation.issue("Archivo no disponible") }
        let asset = try await retriever.retrieve(url: absolute).get()
        let publication = try await opener.open(asset: asset, allowUserInteraction: false).get()
        let declared = publication.metadata.title?.trimmingCharacters(in: .whitespacesAndNewlines)
        let title: String
        if let declared, !declared.isEmpty, declared != file.deletingPathExtension().lastPathComponent { title = declared } else { title = fallback }
        return CREAPreparedImport(file: file, hash: hash, title: title,
            author: publication.metadata.authors.map { $0.name }.joined(separator: ", "), identifier: publication.metadata.identifier, kind: kind)
    }
}

@MainActor enum CREAImportPresenter {
    static func present(_ decision: CREAImportDecision, coordinator: CREAImportCoordinator) {
        guard UIApplication.shared.applicationState == .active,
              let root = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive })?.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        guard top.restorationIdentifier != decision.id.uuidString else { return }
        if top.isBeingPresented || top.isBeingDismissed {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { if coordinator.pending?.id == decision.id { present(decision, coordinator: coordinator) } }; return
        }
        let alert = UIAlertController(title: "Parece el mismo libro con otro archivo", message: "Ya guardado: \(decision.existing.name)\nNuevo: \(decision.candidate.title)\nEl libro existente conservará sus notas, favorito y posición.", preferredStyle: .alert)
        alert.restorationIdentifier = decision.id.uuidString
        func close(_ accepted: Bool) { alert.dismiss(animated: true) { coordinator.choose(accepted); coordinator.confirmationClosed() } }
        alert.addAction(UIAlertAction(title: "Importar otra copia", style: .default) { _ in close(true) })
        alert.addAction(UIAlertAction(title: "Omitir este archivo", style: .cancel) { _ in close(false) })
        top.present(alert, animated: true)
    }
}

struct CREAImportConfirmation: View {
    let decision: CREAImportDecision
    let choose: (Bool) -> Void
    var body: some View {
        NavigationStack {
            Form {
                Section("Parece el mismo libro con otro archivo") {
                    LabeledContent("Ya guardado", value: decision.existing.name)
                    LabeledContent("Archivo nuevo", value: decision.candidate.title)
                    if let author = decision.candidate.author { LabeledContent("Autor", value: author) }
                    Text("Puedes conservar otra edición. El libro existente conserva sus notas, marcadores, favorito y posición.")
                }
                Button("Importar otra copia") { choose(true) }.buttonStyle(.borderedProminent)
                Button("Omitir este archivo", role: .cancel) { choose(false) }
            }.navigationTitle("Libro parecido")
        }
    }
}

import Foundation
import SwiftUI
import GoogleSignIn

struct CREAAIStatus: Decodable {
    let available: Bool
    let reason: String?
    let remaining: [String: Int]?
}

struct CREAAIBookInfo: Codable {
    let title: String
    let author: String?
    let summary: String
    let categories: [String]
    let level: String
    let confidence: String
    var shareable: Bool { !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !summary.hasPrefix("(Ejemplo)") }
}

struct CREAAITeacherQuestion: Decodable {
    let type: String
    let question: String
    let answer_hint: String
}

struct CREAAIVocabulary: Decodable {
    let word: String
    let meaning: String
}

struct CREAAITeacherGuide: Decodable {
    let questions: [CREAAITeacherQuestion]
    let activities: [String]
    let vocabulary: [CREAAIVocabulary]

    var readableText: String {
        var parts: [String] = []
        if !questions.isEmpty {
            parts.append("PREGUNTAS DE COMPRENSIÓN")
            for (index, q) in questions.enumerated() {
                let kind: String
                switch q.type {
                case "literal": kind = "Literal"
                case "inferencial": kind = "Inferencial"
                default: kind = "Crítica"
                }
                parts.append("\(index + 1). [\(kind)] \(q.question)\n   Pista: \(q.answer_hint)")
            }
        }
        if !activities.isEmpty {
            parts.append("ACTIVIDADES")
            for activity in activities { parts.append("• \(activity)") }
        }
        if !vocabulary.isEmpty {
            parts.append("VOCABULARIO")
            for word in vocabulary { parts.append("• \(word.word): \(word.meaning)") }
        }
        return parts.joined(separator: "\n\n")
    }
}

enum CREAAIMode: String, CaseIterable, Identifiable {
    case explain, simplify, translate, define

    var id: String { rawValue }
    var label: String {
        switch self {
        case .explain: return "Explicar"
        case .simplify: return "Simplificar"
        case .translate: return "Traducir"
        case .define: return "Definir"
        }
    }
}

enum CREAAIError: LocalizedError {
    case configuration
    case authentication
    case unavailable
    case dailyLimit
    case busy
    case refused
    case noConnection
    case response
    case emptyText

    var errorDescription: String? {
        switch self {
        case .configuration: return "Configura la URL HTTPS del servidor de IA de CREA en Ajustes."
        case .authentication: return "Inicia sesión con Google en una cuenta autorizada de CREA."
        case .unavailable: return "Claude no está disponible (servidor apagado, créditos agotados o presupuesto alcanzado)."
        case .dailyLimit: return "Alcanzaste el límite diario para esta función. La lectura sigue disponible."
        case .busy: return "El servicio está ocupado. Inténtalo nuevamente."
        case .refused: return "El servicio no puede procesar este contenido."
        case .noConnection: return "No se pudo conectar al servidor de CREA. Revisa internet y prueba de nuevo."
        case .response: return "No se pudo procesar la respuesta del asistente."
        case .emptyText: return "Selecciona o escribe un texto para analizar."
        }
    }
}

/// CREA uses exactly the existing Android proxy, not the Anthropic SDK on devices.
/// No Anthropic API key is shipped, stored or requested by this iOS application.
@MainActor
final class CREAReaderAI: ObservableObject {
    @Published private(set) var enabled: Bool
    @Published private(set) var serverURL: String
    @Published private(set) var available = false
    @Published private(set) var loadingStatus = false
    @Published private(set) var remaining: [String: Int] = [:]
    @Published private(set) var availabilityMessage: String?
    private var lastCheck: Date = .distantPast
    private var lastSuccessfulCheck = false

    private static let enabledKey = "crea.reader.ai.enabled"
    private static let urlKey = "crea.reader.ai.baseURL"

    init() {
        enabled = UserDefaults.standard.bool(forKey: Self.enabledKey)
        let compiled = Bundle.main.object(forInfoDictionaryKey: "CREAAIBaseURL") as? String ?? ""
        serverURL = UserDefaults.standard.string(forKey: Self.urlKey) ?? compiled
    }

    var isConfigured: Bool { endpoint != nil }
    var canUse: Bool { enabled && isConfigured && GIDSignIn.sharedInstance.currentUser != nil && available }

    /// Restrict the OAuth token destination. Only the organization's hostname or a
    /// CREA Reader AI deployment on Render/Cloud Run is accepted. Never follow redirects.
    private var endpoint: URL? {
        guard let url = URL(string: serverURL.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme?.lowercased() == "https",
              url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil,
              url.port == nil || url.port == 443,
              let host = url.host?.lowercased() else { return nil }
        let approved = host == "ai.creanicaragua.org" ||
            host == "crea-reader-ai.onrender.com" ||
            (host.hasPrefix("crea-reader-ai-") && host.hasSuffix(".onrender.com")) ||
            (host.hasPrefix("crea-reader-ai-") && host.hasSuffix(".run.app"))
        guard approved, url.path.isEmpty || url.path == "/" else { return nil }
        return URL(string: "https://" + host)
    }

    func setEnabled(_ value: Bool) {
        enabled = value
        UserDefaults.standard.set(value, forKey: Self.enabledKey)
        available = false
        availabilityMessage = nil
        lastCheck = .distantPast
    }

    func setServerURL(_ value: String) {
        serverURL = value
        UserDefaults.standard.set(value, forKey: Self.urlKey)
        available = false
        availabilityMessage = nil
        lastCheck = .distantPast
    }

    func refresh(force: Bool = false) async {
        guard enabled else {
            available = false
            availabilityMessage = "Activa el asistente de IA cuando quieras usarlo."
            return
        }
        guard endpoint != nil else {
            available = false
            availabilityMessage = "Introduce la URL HTTPS de tu servidor CREA Reader AI en Ajustes."
            return
        }
        guard GIDSignIn.sharedInstance.currentUser != nil else {
            available = false
            availabilityMessage = "Inicia sesión con Google para utilizar Claude."
            return
        }
        let seconds = Date().timeIntervalSince(lastCheck)
        if !force && seconds < (lastSuccessfulCheck ? 600 : 120) { return }
        guard !loadingStatus else { return }
        loadingStatus = true
        defer { loadingStatus = false }
        lastCheck = Date()
        do {
            let body = try await request(method: "GET", path: "/v1/status", input: nil)
            let status = try JSONDecoder().decode(CREAAIStatus.self, from: body)
            available = status.available
            remaining = status.remaining ?? [:]
            lastSuccessfulCheck = status.available
            availabilityMessage = status.available ? nil : statusMessage(status.reason)
        } catch {
            available = false
            lastSuccessfulCheck = false
            availabilityMessage = error.localizedDescription
        }
    }

    private func statusMessage(_ reason: String?) -> String {
        switch reason {
        case "budget": return "Claude alcanzó el presupuesto mensual configurado en CREA."
        case "suspended": return "El servidor suspendió temporalmente las peticiones de IA."
        case "disabled": return "El administrador desactivó temporalmente Claude."
        default: return "Claude no está disponible ahora. Puedes seguir leyendo normalmente."
        }
    }

    func explain(_ mode: CREAAIMode, text: String, context: String?,
                 bookTitle: String?, targetLanguage: String = "inglés") async throws -> String {
        let selection = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(3000))
        guard !selection.isEmpty else { throw CREAAIError.emptyText }
        let input = compact(["mode": mode.rawValue,
                             "text": selection,
                             "context": context.map { String($0.prefix(1500)) },
                             "bookTitle": bookTitle.map { String($0.prefix(200)) },
                             "targetLanguage": mode == .translate ? targetLanguage : nil])
        return try await askText(task: "explain", input: input)
    }

    func chapterSummary(text: String, bookTitle: String?, chapterTitle: String?) async throws -> String {
        guard text.count >= 20 else { throw CREAAIError.emptyText }
        return try await askText(task: "chapter_summary",
                                 input: compact(["text": String(text.prefix(30_000)),
                                                 "bookTitle": bookTitle.map { String($0.prefix(200)) },
                                                 "chapterTitle": chapterTitle.map { String($0.prefix(200)) }]))
    }

    func teacherQuestions(text: String, bookTitle: String?,
                          chapterTitle: String?, grade: String?) async throws -> CREAAITeacherGuide {
        guard text.count >= 20 else { throw CREAAIError.emptyText }
        let value = try await ask(task: "teacher_questions",
                                  input: compact(["text": String(text.prefix(30_000)),
                                                  "bookTitle": bookTitle.map { String($0.prefix(200)) },
                                                  "chapterTitle": chapterTitle.map { String($0.prefix(200)) },
                                                  "grade": grade.map { String($0.prefix(60)) }]))
        return try JSONDecoder().decode(CREAAITeacherGuide.self, from: value)
    }

    func bookInfo(fileName: String, excerpt: String?) async throws -> CREAAIBookInfo {
        let value = try await ask(task: "book_info",
                                  input: compact(["fileName": String(fileName.prefix(300)),
                                                  "excerpt": excerpt.map { String($0.prefix(6000)) }]))
        let info = try JSONDecoder().decode(CREAAIBookInfo.self, from: value)
        guard info.shareable else { throw CREAAIError.response }
        return info
    }

    private func compact(_ values: [String: String?]) -> [String: String] {
        values.compactMapValues { $0 }
    }

    private func askText(task: String, input: [String: String]) async throws -> String {
        let result = try await ask(task: task, input: input)
        do { return try JSONDecoder().decode(String.self, from: result) }
        catch { throw CREAAIError.response }
    }

    private func ask(task: String, input: [String: String]) async throws -> Data {
        guard enabled, available else { throw CREAAIError.unavailable }
        guard remaining[task, default: 1] > 0 else { throw CREAAIError.dailyLimit }
        let body = try JSONSerialization.data(withJSONObject: ["task": task, "input": input])
        let response = try await request(method: "POST", path: "/v1/ask", input: body)
        guard let root = try JSONSerialization.jsonObject(with: response) as? [String: Any],
              let result = root["result"] else { throw CREAAIError.response }
        if let quota = remaining[task], quota > 0 { remaining[task] = quota - 1 }
        return try JSONSerialization.data(withJSONObject: result, options: [.fragmentsAllowed])
    }

    private func googleToken() async throws -> String {
        guard let user = GIDSignIn.sharedInstance.currentUser else { throw CREAAIError.authentication }
        return try await withCheckedThrowingContinuation { continuation in
            user.refreshTokensIfNeeded { refreshed, error in
                if error != nil { continuation.resume(throwing: CREAAIError.authentication) }
                else if let token = refreshed?.accessToken.tokenString {
                    continuation.resume(returning: token)
                } else {
                    continuation.resume(throwing: CREAAIError.authentication)
                }
            }
        }
    }

    private func request(method: String, path: String, input: Data?) async throws -> Data {
        guard let endpoint,
              let url = URL(string: endpoint.absoluteString + path) else { throw CREAAIError.configuration }
        let token = try await googleToken()
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        var identifier = UserDefaults.standard.string(forKey: "crea.reader.syncDeviceID")
        if identifier == nil {
            identifier = UUID().uuidString
            UserDefaults.standard.set(identifier, forKey: "crea.reader.syncDeviceID")
        }
        request.setValue(identifier, forHTTPHeaderField: "X-Device-Id")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 90 // Render's free tier can need a cold start.
        if let input {
            request.httpBody = input
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForResource = 95
        let session = URLSession(configuration: configuration, delegate: CREAAINoRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        do {
            let (data, rawResponse) = try await session.data(for: request)
            guard let response = rawResponse as? HTTPURLResponse else { throw CREAAIError.response }
            if (200...299).contains(response.statusCode) { return data }
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let code = json?["code"] as? String
            let failure: CREAAIError
            switch response.statusCode {
            case 401, 403: failure = .authentication
            case 429: failure = code == "daily_limit" ? .dailyLimit : .busy
            case 422: failure = .refused
            case 503: failure = .unavailable
            default: failure = .response
            }
            if case .unavailable = failure {
                available = false
                availabilityMessage = statusMessage(json?["reason"] as? String)
            }
            throw failure
        } catch is CancellationError {
            throw CancellationError()
        } catch let issue as CREAAIError {
            throw issue
        } catch {
            throw CREAAIError.noConnection
        }
    }
}

/// Never forward CREA members' Google access tokens to a redirect target.
private final class CREAAINoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

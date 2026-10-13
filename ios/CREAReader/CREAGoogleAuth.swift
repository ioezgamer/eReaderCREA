import SwiftUI
import GoogleSignIn

@MainActor
final class CREAAuthSession: ObservableObject {
    @Published private(set) var accountEmail: String?
    @Published private(set) var progressAuthorized = false
    @Published private(set) var communityAuthorized = false
    private let privateScope = "https://www.googleapis.com/auth/drive.appdata"
    @Published var message: String?
    var configured: Bool {
        guard let id = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String else { return false }
        return !id.isEmpty && !id.contains("REPLACE") 
    }
    init() {
        guard configured else { return }
        if let clientID = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        }
        GIDSignIn.sharedInstance.restorePreviousSignIn { [weak self] user, _ in
            Task { @MainActor in
                self?.accountEmail = user?.profile?.email
                self?.progressAuthorized = user?.grantedScopes?.contains("https://www.googleapis.com/auth/drive.appdata") ?? false
                self?.communityAuthorized = user?.grantedScopes?.contains("https://www.googleapis.com/auth/drive") ?? false
            }
        }
    }
    func signIn() {
        guard configured else {
            message = "Primero configura el cliente OAuth iOS en Google Cloud y las variables del repositorio."
            return
        }
        guard let controller = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive })?
            .windows.first(where: { $0.isKeyWindow })?.rootViewController else {
            message = "No se encontró una ventana activa para iniciar sesión."
            return
        }
        GIDSignIn.sharedInstance.signIn(withPresenting: controller, hint: nil, additionalScopes: ["https://www.googleapis.com/auth/drive.readonly", "https://www.googleapis.com/auth/drive.appdata"]) { [weak self] result, error in
            Task { @MainActor in
                if let error { self?.message = error.localizedDescription }
                else {
                    self?.accountEmail = result?.user.profile?.email
                    self?.progressAuthorized = result?.user.grantedScopes?.contains("https://www.googleapis.com/auth/drive.appdata") ?? false
                    self?.communityAuthorized = result?.user.grantedScopes?.contains("https://www.googleapis.com/auth/drive") ?? false
                }
            }
        }
    }
    func requestPrivateProgressAccess() {
        guard let user = GIDSignIn.sharedInstance.currentUser else {
            message = "Inicia sesión con Google primero."
            return
        }
        if user.grantedScopes?.contains(privateScope) == true {
            progressAuthorized = true
            return
        }
        guard let controller = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive })?
            .windows.first(where: { $0.isKeyWindow })?.rootViewController else {
            message = "No se encontró una ventana activa para solicitar el permiso."
            return
        }
        user.addScopes([privateScope], presenting: controller) { [weak self] result, error in
            Task { @MainActor in
                if let error { self?.message = error.localizedDescription }
                else {
                    self?.progressAuthorized = result?.user.grantedScopes?.contains("https://www.googleapis.com/auth/drive.appdata") ?? false
                    self?.message = self?.progressAuthorized == true ? nil : "Google no autorizó el acceso privado al progreso."
                }
            }
        }
    }
    /// Requires explicit user action: full Drive scope is used only when enabling
    /// community sharing. The regular catalog works with drive.readonly.
    func requestCommunityAccess() {
        guard let user = GIDSignIn.sharedInstance.currentUser else {
            message = "Primero inicia sesión con Google."
            return
        }
        let scope = "https://www.googleapis.com/auth/drive"
        if user.grantedScopes?.contains(scope) == true {
            communityAuthorized = true
            return
        }
        guard let controller = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive })?
            .windows.first(where: { $0.isKeyWindow })?.rootViewController else {
            message = "No se encontró una ventana activa."
            return
        }
        user.addScopes([scope], presenting: controller) { [weak self] result, error in
            Task { @MainActor in
                if let error { self?.message = error.localizedDescription }
                else {
                    self?.communityAuthorized = result?.user.grantedScopes?.contains(scope) ?? false
                    self?.message = self?.communityAuthorized == true ? nil : "No se autorizó compartir lecturas."
                }
            }
        }
    }

    func signOut() {
        if let owner = GIDSignIn.sharedInstance.currentUser?.userID { CREABackgroundDownloads.shared.cancelForAccount(owner) }
        GIDSignIn.sharedInstance.signOut()
        accountEmail = nil
        progressAuthorized = false
        communityAuthorized = false
    }
}

struct CREAGoogleAccountView: View {
    @ObservedObject var auth: CREAAuthSession
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let email = auth.accountEmail {
                Label(email, systemImage: "person.crop.circle.fill")
                if auth.progressAuthorized {
                    Label("Progreso privado autorizado", systemImage: "checkmark.shield.fill")
                        .font(.caption).foregroundStyle(.green)
                } else {
                    Button("Autorizar sincronización de progreso") {
                        auth.requestPrivateProgressAccess()
                    }
                    .font(.subheadline)
                }
                Button("Cerrar sesión de Google") { auth.signOut() }
            } else {
                Button {
                    auth.signIn()
                } label: {
                    Label("Iniciar sesión con Google", systemImage: "person.crop.circle")
                }
                .buttonStyle(.borderedProminent)
                .disabled(!auth.configured)
                if !auth.configured {
                    Text("Falta configurar el cliente OAuth para iOS. Consulta ios/README.md.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            if let message = auth.message {
                Text(message).font(.footnote).foregroundStyle(.red)
            }
            Text("Drive permite consultar el catálogo y acceder al progreso privado de la biblioteca. Los libros se descargan manualmente.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

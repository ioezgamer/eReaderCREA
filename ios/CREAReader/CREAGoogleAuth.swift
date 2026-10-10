import SwiftUI
import GoogleSignIn

@MainActor
final class CREAAuthSession: ObservableObject {
    @Published private(set) var accountEmail: String?
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
            Task { @MainActor in self?.accountEmail = user?.profile?.email }
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
        GIDSignIn.sharedInstance.signIn(withPresenting: controller, hint: nil, additionalScopes: ["https://www.googleapis.com/auth/drive.readonly"]) { [weak self] result, error in
            Task { @MainActor in
                if let error { self?.message = error.localizedDescription }
                else { self?.accountEmail = result?.user.profile?.email }
            }
        }
    }
    func signOut() {
        GIDSignIn.sharedInstance.signOut()
        accountEmail = nil
    }
}

struct CREAGoogleAccountView: View {
    @ObservedObject var auth: CREAAuthSession
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let email = auth.accountEmail {
                Label(email, systemImage: "person.crop.circle.fill")
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
            Text("El catálogo solo consulta libros; descargas manuales. Google Drive solicita permiso de lectura.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

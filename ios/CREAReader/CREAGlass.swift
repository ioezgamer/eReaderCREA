import SwiftUI

/// Hybrid "Liquid Glass": real glass (iOS 26 SDK) on floating chrome, translucent material elsewhere.
extension View {
    /// Plain translucent card used across the app.
    func creaCard(radius: CGFloat = 18) -> some View {
        background(.regularMaterial, in: RoundedRectangle(cornerRadius: radius))
    }

    /// Floating surface (reader bars, panels): blurred glass, real Liquid Glass when the OS has it.
    @ViewBuilder func creaGlass(radius: CGFloat = 20) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: RoundedRectangle(cornerRadius: radius))
        } else {
            self.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: radius))
                .overlay(RoundedRectangle(cornerRadius: radius).stroke(Color.white.opacity(0.25), lineWidth: 0.5))
        }
        #else
        self.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: radius))
            .overlay(RoundedRectangle(cornerRadius: radius).stroke(Color.white.opacity(0.25), lineWidth: 0.5))
        #endif
    }

    /// Nunito for the interface (variable font: weight is set explicitly; falls back to the system font if the file is missing).
    func creaInterfaceFont() -> some View { environment(\.font, .custom("Nunito", size: 17, relativeTo: .body).weight(.regular)) }
}

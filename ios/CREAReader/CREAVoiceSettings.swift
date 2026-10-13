import SwiftUI
import AVFoundation
import ReadiumNavigator

struct CREAVoiceChoice: Identifiable, Equatable {
    let id: String
    let name: String
    let language: String
    let quality: Int
    static func ranked(_ choices: [Self]) -> [Self] {
        choices.sorted {
            if $0.quality != $1.quality { return $0.quality > $1.quality }
            if ($0.language.lowercased() == "es-es") != ($1.language.lowercased() == "es-es") { return $0.language.lowercased() != "es-es" }
            return $0.id < $1.id
        }
    }
    static func best(_ choices: [Self], language: String) -> Self? {
        let base = language.lowercased().replacingOccurrences(of: "_", with: "-").split(separator: "-").first
        return ranked(choices.filter { $0.language.lowercased().split(separator: "-").first == base }).first
    }
    var qualityLabel: String { quality >= 3 ? "Premium" : quality >= 2 ? "Mejorada" : "Estándar" }
    var label: String { "\(name) · \(qualityLabel) · \(language)" }
    static var installed: [Self] {
        ranked(AVSpeechSynthesisVoice.speechVoices().filter { !$0.voiceTraits.contains(.isNoveltyVoice) && !$0.voiceTraits.contains(.isPersonalVoice) }.map {
            Self(id: $0.identifier, name: $0.name, language: $0.language, quality: $0.quality.rawValue)
        })
    }
}

/// Applied to each utterance, including after a voice or speed change.
final class CREAVoiceEngineDelegate: AVTTSEngineDelegate {
    func avTTSEngine(_ engine: AVTTSEngine, didCreateUtterance utterance: AVSpeechUtterance) {
        CREAVoiceConfiguration.apply(to: utterance)
    }
}
enum CREAVoiceConfiguration {
    static func apply(to utterance: AVSpeechUtterance) {
        let defaults = UserDefaults.standard
        let rate = defaults.object(forKey: "crea.voice.rate") as? Double ?? 0.5
        let pitch = defaults.object(forKey: "crea.voice.pitch") as? Double ?? 1
        utterance.rate = Float(max(0.1, min(0.65, rate)))
        utterance.pitchMultiplier = Float(max(0.5, min(2, pitch)))
        if let id = defaults.string(forKey: "crea.voice.id"), !id.isEmpty, let voice = AVSpeechSynthesisVoice(identifier: id) { utterance.voice = voice }
        else if let best = CREAVoiceChoice.best(CREAVoiceChoice.installed, language: utterance.voice?.language ?? "es"),
                let voice = AVSpeechSynthesisVoice(identifier: best.id) { utterance.voice = voice }
    }
}
struct CREAVoiceSettings: View {
    @AppStorage("crea.voice.id") private var selected = ""
    @AppStorage("crea.voice.rate") private var rate = 0.5
    @AppStorage("crea.voice.pitch") private var pitch = 1.0
    @State private var voices = CREAVoiceChoice.installed
    @Environment(\.openURL) private var openURL
    var body: some View {
        Form {
            Section("Voz instalada") {
                Picker("Voz", selection: $selected) {
                    Text("Automática: mejor voz instalada del idioma").tag("")
                    ForEach(voices) { voice in
                        Text(voice.label).tag(voice.id)
                    }
                    if !selected.isEmpty && !voices.contains(where: { $0.id == selected }) { Text("Voz guardada no disponible").tag(selected) }
                }
                Text("Puedes instalar más voces en Ajustes de iOS → Accesibilidad → Contenido leído.").font(.footnote)
                Button("Actualizar voces instaladas") { voices = CREAVoiceChoice.installed }
                Button("Abrir ajustes de la aplicación") { if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) } }
            }
            Section("Velocidad y tono") {
                LabeledContent("Velocidad", value: String(format: "%.2f", rate))
                Slider(value: $rate, in: 0.1...0.65).accessibilityLabel("Velocidad de lectura")
                LabeledContent("Tono", value: String(format: "%.1f", pitch))
                Slider(value: $pitch, in: 0.5...2).accessibilityLabel("Tono de voz")
                Button("Restablecer") { rate = 0.5; pitch = 1; selected = "" }
            }
        }.navigationTitle("Lectura en voz alta")
    }
}

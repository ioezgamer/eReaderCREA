import SwiftUI
import AVFoundation
import ReadiumNavigator

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
        if let id = defaults.string(forKey: "crea.voice.id"), let voice = AVSpeechSynthesisVoice(identifier: id) { utterance.voice = voice }
    }
}
struct CREAVoiceSettings: View {
    @AppStorage("crea.voice.id") private var selected = ""
    @AppStorage("crea.voice.rate") private var rate = 0.5
    @AppStorage("crea.voice.pitch") private var pitch = 1.0
    var body: some View {
        Form {
            Section("Voz instalada") {
                Picker("Voz", selection: $selected) {
                    Text("Automática según el libro").tag("")
                    ForEach(AVSpeechSynthesisVoice.speechVoices().sorted { $0.language < $1.language }, id: \.identifier) { voice in
                        Text("\(voice.name) · \(voice.language)").tag(voice.identifier)
                    }
                }
                Text("Puedes instalar más voces en Ajustes de iOS → Accesibilidad → Contenido leído.").font(.footnote)
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

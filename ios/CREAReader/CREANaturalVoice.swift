import SwiftUI
import AVFoundation
import CryptoKit
import GoogleSignIn
import ReadiumShared
import ReadiumNavigator

/// Natural voice (ElevenLabs, through the CREA server) for reading aloud.
///
/// It is an extra on top of the device voice, never a replacement: when it is switched off, when there is
/// no connection or Google account, or when the credits run out, `audioFor` returns nil and the reader keeps
/// going with the device voice. After a failure it stays out of the way for a while so that every sentence
/// does not wait for a server that is not answering.
@MainActor
final class CREANaturalVoice: ObservableObject {
    static let shared = CREANaturalVoice()

    enum ServerState { case unknown, checking, available, unavailable, unreachable }

    @Published private(set) var enabled: Bool
    @Published private(set) var serverState: ServerState = .unknown
    @Published private(set) var serverReason: String?
    @Published private(set) var lastProblem: String?
    @Published private(set) var voices: [CREAVoiceOption] = []
    @Published private(set) var selectedVoice: String?

    private let ai = CREAReaderAI.shared
    private var pausedUntil = Date.distantPast
    private static let enabledKey = "crea.reader.naturalVoice"
    private static let voiceKey = "crea.reader.naturalVoice.id"
    private static let maxCacheBytes: Int64 = 60 * 1024 * 1024

    private var cacheDirectory: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("natural-voice", isDirectory: true)
    }

    init() {
        enabled = UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true
        selectedVoice = UserDefaults.standard.string(forKey: Self.voiceKey)
    }

    var configured: Bool { ai.hasServer }
    var signedIn: Bool { GIDSignIn.sharedInstance.currentUser != nil }
    /// The voice that will really be used: the chosen one if the server still offers it, else the main one.
    var currentVoice: CREAVoiceOption? { voices.first { $0.id == selectedVoice } ?? voices.first }

    func setEnabled(_ value: Bool) {
        enabled = value
        UserDefaults.standard.set(value, forKey: Self.enabledKey)
        if value { retryNow() }
    }

    func selectVoice(_ id: String?) {
        selectedVoice = id
        if let id { UserDefaults.standard.set(id, forKey: Self.voiceKey) } else { UserDefaults.standard.removeObject(forKey: Self.voiceKey) }
        retryNow()
    }

    /// Asks the server whether it can read aloud now (for Ajustes).
    func check() async {
        guard configured, signedIn else { return }
        serverState = .checking
        guard let status = await ai.serverStatus() else { serverState = .unreachable; return }
        voices = status.voice?.voices ?? []
        serverReason = status.voice?.reason
        if status.voice?.available == true { serverState = .available; retryNow() } else { serverState = .unavailable }
    }

    func retryNow() { pausedUntil = .distantPast; lastProblem = nil }

    /// Whether it is worth asking the server for the next sentence.
    func shouldTry() -> Bool { enabled && configured && signedIn && Date() >= pausedUntil }

    /// A short sample of the current voice, to try it in Ajustes (nil if it cannot be heard now).
    func sample() async -> URL? {
        retryNow()
        return await audioFor("Hola, esta es la voz natural de CREA Reader.", language: "es", previous: nil)
    }

    /// MP3 file with `text` read aloud, or nil to read it with the device voice. `previous` is the sentence read
    /// just before (if the reading is continuous), so the voice keeps its intonation.
    func audioFor(_ text: String, language: String?, previous: String?) async -> URL? {
        let voice = currentVoice?.id
        let file = cacheDirectory.appendingPathComponent(key(text, language: language, voice: voice) + ".mp3")
        if let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 0 {
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
            return file
        }
        switch await ai.speech(text: text, language: language, voice: voice, previous: previous) {
        case .audio(let data):
            try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
            guard (try? data.write(to: file, options: .atomic)) != nil else { return nil }
            lastProblem = nil
            trimCache()
            return file
        case .unavailable(let reason):
            serverReason = reason; serverState = .unavailable
            // Out of credits or over budget lasts; a key or voice to fix in Render, less.
            let long = ["no_credits", "suspended", "budget"].contains(reason ?? "")
            return pause(long ? 30 * 60 : 10 * 60, reason ?? "unavailable")
        case .limitReached: return pause(30 * 60, "daily_limit")
        case .failed: return pause(2 * 60, "no_connection")
        }
    }

    /// Called when an audio file could not be played: use the device voice for a little while.
    func playbackFailed() { _ = pause(2 * 60, "playback") }

    private func pause(_ seconds: TimeInterval, _ reason: String) -> URL? {
        pausedUntil = Date().addingTimeInterval(seconds)
        lastProblem = reason
        return nil
    }

    private func key(_ text: String, language: String?, voice: String?) -> String {
        let raw = "v2|\(ai.serverHost)|\(voice ?? "")|\(language ?? "")|\(text)"
        return SHA256.hash(data: Data(raw.utf8)).map { String(format: "%02x", $0) }.joined().prefix(40).description
    }

    /// Keeps the cache under 60 MB, removing the files played longest ago.
    private func trimCache() {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: keys) else { return }
        var entries = files.filter { $0.pathExtension == "mp3" }.map { url -> (URL, Int64, Date) in
            let values = try? url.resourceValues(forKeys: Set(keys))
            return (url, Int64(values?.fileSize ?? 0), values?.contentModificationDate ?? .distantPast)
        }
        var total = entries.reduce(0) { $0 + $1.1 }
        guard total > Self.maxCacheBytes else { return }
        entries.sort { $0.2 < $1.2 }
        for entry in entries {
            try? FileManager.default.removeItem(at: entry.0)
            total -= entry.1
            if total <= Self.maxCacheBytes { break }
        }
    }

    /// Plain-language reason why the device voice is being used.
    static func explanation(_ reason: String?) -> String {
        switch reason {
        case "no_credits": return "La voz natural no tiene créditos ahora. Se lee con la voz del dispositivo."
        case "budget": return "La voz natural alcanzó el tope mensual. Se lee con la voz del dispositivo."
        case "suspended": return "El servidor pausó la voz natural un rato. Se lee con la voz del dispositivo."
        case "misconfigured": return "La clave de la voz natural no es válida en el servidor. Se lee con la voz del dispositivo."
        case "voice_not_found": return "La voz principal no está en «Mis voces» de ElevenLabs. Se lee con la voz del dispositivo."
        case "disabled": return "El administrador desactivó la voz natural."
        case "daily_limit": return "Se alcanzó el límite diario de voz natural en este dispositivo."
        case "not_configured", "needs_drive": return "Inicia sesión con Google para usar la voz natural."
        case .some: return "La voz natural no está disponible ahora. Se lee con la voz del dispositivo."
        case .none: return "La voz natural está lista."
        }
    }
}

/// Reads each sentence with the natural voice when it can, and with the device voice otherwise, so reading
/// never stops. Everything else (voices, speed, highlighting) is the device engine's.
final class CREAHybridTTSEngine: TTSEngine {
    private let device: AVTTSEngine
    /// The sentence read just before, sent as context so the voice continues the reading's intonation.
    private var lastSpoken: String?

    init(device: AVTTSEngine) { self.device = device }

    var availableVoices: [TTSVoice] { device.availableVoices }
    func voiceWithIdentifier(_ identifier: String) -> TTSVoice? { device.voiceWithIdentifier(identifier) }

    @MainActor
    func speak(_ utterance: TTSUtterance, onSpeakRange: @escaping (Range<String.Index>) -> Void) async -> Result<Void, TTSError> {
        let previous = lastSpoken
        lastSpoken = utterance.text
        let natural = CREANaturalVoice.shared
        guard natural.shouldTry(), !utterance.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return await device.speak(utterance, onSpeakRange: onSpeakRange)
        }
        if utterance.delay > 0 { try? await Task.sleep(nanoseconds: UInt64(utterance.delay * 1_000_000_000)) }
        let language = utterance.language.code.bcp47.split(separator: "-").first.map { String($0).lowercased() }
        let file = await natural.audioFor(utterance.text, language: language, previous: previous)
        if Task.isCancelled { lastSpoken = nil; return .success(()) }
        guard let file else { return await device.speak(utterance, onSpeakRange: onSpeakRange) }
        switch await CREAAudioFilePlayer.play(file) {
        case .finished, .cancelled: return .success(())
        case .failed:
            natural.playbackFailed()
            return await device.speak(utterance, onSpeakRange: onSpeakRange)
        }
    }
}

/// Plays one MP3 and returns when it ends; stops at once if the task is cancelled (pause, jump, exit).
@MainActor
private final class CREAAudioFilePlayer: NSObject, AVAudioPlayerDelegate {
    enum Outcome { case finished, cancelled, failed }
    private var player: AVAudioPlayer?
    private var continuation: CheckedContinuation<Outcome, Never>?

    static func play(_ url: URL) async -> Outcome {
        let job = CREAAudioFilePlayer()
        return await withTaskCancellationHandler {
            await job.run(url)
        } onCancel: {
            Task { @MainActor in job.finish(.cancelled) }
        }
    }

    private func run(_ url: URL) async -> Outcome {
        if Task.isCancelled { return .cancelled }
        guard let player = try? AVAudioPlayer(contentsOf: url) else { return .failed }
        self.player = player
        player.delegate = self
        player.enableRate = true
        let rate = UserDefaults.standard.object(forKey: "crea.voice.rate") as? Double ?? 0.5
        player.rate = Float(max(0.75, min(1.5, rate / 0.5)))
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            if Task.isCancelled { finish(.cancelled) }
            else if !player.play() { finish(.failed) }
        }
    }

    private func finish(_ outcome: Outcome) {
        player?.stop()
        player = nil
        continuation?.resume(returning: outcome)
        continuation = nil
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.finish(flag ? .finished : .failed) }
    }
    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        Task { @MainActor in self.finish(.failed) }
    }
}

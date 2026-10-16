import SwiftUI
import UIKit

/// Size of the reader's bars: small, medium or large.
enum CREABarSize: String, CaseIterable, Identifiable {
    case small, medium, large
    var id: String { rawValue }
    var title: String { switch self { case .small: "Pequeñas"; case .medium: "Medianas"; case .large: "Grandes" } }
    var imageScale: Image.Scale { switch self { case .small: .small; case .medium: .medium; case .large: .large } }
    var barHeight: CGFloat { switch self { case .small: 28; case .medium: 36; case .large: 46 } }
    var barFont: Font { switch self { case .small: .caption2; case .medium: .caption; case .large: .footnote } }
}

/// Reader brightness (Auto leaves the system value alone) and bar size. Brightness is restored on exit.
@MainActor
final class CREAReaderLook: ObservableObject {
    static let shared = CREAReaderLook()
    private enum Key { static let auto = "crea.reader.brightness.auto", value = "crea.reader.brightness.value", bars = "crea.reader.barSize" }
    @Published var autoBrightness: Bool { didSet { UserDefaults.standard.set(autoBrightness, forKey: Key.auto); apply() } }
    @Published var brightness: Double { didSet { UserDefaults.standard.set(brightness, forKey: Key.value); apply() } }
    @Published var barSize: CREABarSize { didSet { UserDefaults.standard.set(barSize.rawValue, forKey: Key.bars) } }
    private var original: CGFloat?

    init() {
        let d = UserDefaults.standard
        autoBrightness = d.object(forKey: Key.auto) as? Bool ?? true
        brightness = d.object(forKey: Key.value) as? Double ?? Double(UIScreen.main.brightness)
        barSize = CREABarSize(rawValue: d.string(forKey: Key.bars) ?? "") ?? .medium
    }

    func enter() { if original == nil { original = UIScreen.main.brightness }; apply() }
    func exit() { if let original { UIScreen.main.brightness = original }; original = nil }
    private func apply() {
        guard let original else { return }
        UIScreen.main.brightness = autoBrightness ? original : CGFloat(max(0.05, min(1, brightness)))
    }
}

enum CREAReaderShortcut { case listen, addNote, summarize, teacherGuide, exportNotes, stats }

/// The reader's "Aspecto" card: compact, top-right, with collapsible groups and shortcuts.
struct CREAAppearancePanel: View {
    @Binding var fontSize: Double
    @Binding var margins: Double
    @Binding var lineHeight: Double
    @Binding var darkMode: Bool
    @Binding var sepia: Bool
    @Binding var font: String
    @Binding var scroll: Bool
    @ObservedObject var look: CREAReaderLook
    let canUseAI: Bool
    let onShortcut: (CREAReaderShortcut) -> Void
    @State private var open: Set<String> = ["Texto"]

    private var paper: String { darkMode ? "dark" : sepia ? "sepia" : "light" }
    private let marginOptions: [(String, Double)] = [("Estrechos", 0.6), ("Normales", 1.0), ("Amplios", 1.6)]

    var body: some View {
        ScrollView {
            VStack(spacing: 6) {
                Text("ASPECTO").font(.footnote.bold()).foregroundStyle(CREAStyle.blue).padding(.vertical, 4)
                group("Texto", "textformat.size") {
                    HStack {
                        stepper("minus") { fontSize = max(0.8, fontSize - 0.1) }
                        Spacer(); Text("\(Int((fontSize * 100).rounded()))%").font(.headline.monospacedDigit()); Spacer()
                        stepper("plus") { fontSize = min(2.5, fontSize + 0.1) }
                    }
                    HStack {
                        Label("Interlineado", systemImage: "text.alignleft").font(.subheadline)
                        Spacer()
                        stepper("minus") { lineHeight = max(1, lineHeight - 0.1) }
                        stepper("plus") { lineHeight = min(2.5, lineHeight + 0.1) }
                    }
                    Toggle("Desplazamiento vertical", isOn: $scroll).font(.subheadline)
                }
                group("Márgenes", "arrow.left.and.right") {
                    Picker("Márgenes", selection: Binding(get: { marginOptions.min { abs($0.1 - margins) < abs($1.1 - margins) }?.1 ?? 1 }, set: { margins = $0 })) {
                        ForEach(marginOptions, id: \.1) { Text(LocalizedStringKey($0.0)).tag($0.1) }
                    }.pickerStyle(.segmented)
                }
                group("Papel", "doc.plaintext") {
                    Picker("Papel", selection: Binding(get: { paper }, set: { value in darkMode = value == "dark"; sepia = value == "sepia" })) {
                        Text("Claro").tag("light"); Text("Sepia").tag("sepia"); Text("Oscuro").tag("dark")
                    }.pickerStyle(.segmented)
                }
                group("Tipo de letra", "textformat") {
                    Picker("Tipo de letra", selection: $font) { Text("Serif").tag("Serif"); Text("Sans").tag("Sans") }.pickerStyle(.segmented)
                }
                group("Brillo", "sun.max") {
                    Toggle("Automático", isOn: $look.autoBrightness).font(.subheadline)
                    if !look.autoBrightness {
                        HStack { Image(systemName: "sun.min"); Slider(value: $look.brightness, in: 0.05...1); Image(systemName: "sun.max") }
                    }
                }
                group("Tamaño de barras", "rectangle.bottomthird.inset.filled") {
                    Picker("Barras", selection: $look.barSize) {
                        ForEach(CREABarSize.allCases) { Text(LocalizedStringKey($0.title)).tag($0) }
                    }.pickerStyle(.segmented)
                }
                group("Atajos", "bolt") {
                    shortcut("Escuchar", "speaker.wave.2", .listen)
                    shortcut("Agregar nota", "square.and.pencil", .addNote)
                    if canUseAI {
                        shortcut("Resumir capítulo", "text.book.closed", .summarize)
                        shortcut("Guía docente", "graduationcap", .teacherGuide)
                    }
                    shortcut("Exportar notas", "square.and.arrow.up", .exportNotes)
                    shortcut("Estadísticas", "chart.bar", .stats)
                }
            }.padding(10)
        }
        .frame(width: 320).frame(maxHeight: 520)
        .fixedSize(horizontal: false, vertical: true)
        .creaGlass(radius: 22)
        .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Aspecto del lector")
    }

    private func group<Content: View>(_ title: String, _ icon: String, @ViewBuilder content: @escaping () -> Content) -> some View {
        DisclosureGroup(isExpanded: Binding(get: { open.contains(title) }, set: { if $0 { open.insert(title) } else { open.remove(title) } })) {
            VStack(alignment: .leading, spacing: 8) { content() }.padding(.top, 6)
        } label: { Label(LocalizedStringKey(title), systemImage: icon).font(.subheadline.weight(.semibold)) }
        .padding(10).background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
    }
    private func stepper(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).frame(width: 44, height: 44).background(Color.primary.opacity(0.08), in: Circle()) }
            .buttonStyle(.plain).accessibilityLabel(symbol == "plus" ? "Aumentar" : "Reducir")
    }
    private func shortcut(_ title: String, _ icon: String, _ kind: CREAReaderShortcut) -> some View {
        Button { onShortcut(kind) } label: { Label(LocalizedStringKey(title), systemImage: icon).frame(maxWidth: .infinity, minHeight: 36, alignment: .leading) }
            .buttonStyle(.plain)
    }
}

/// Thin, flat bottom bar: current page and a slider to jump anywhere.
struct CREAReaderBottomBar: View {
    @ObservedObject var model: EPUBBookModel
    let size: CREABarSize
    @State private var drag: Double?
    var body: some View {
        if model.positionCount > 1 {
            let shown = Int(drag ?? Double(model.currentPosition))
            HStack(spacing: 10) {
                Text("Página \(shown) de \(model.positionCount)").font(size.barFont.monospacedDigit()).foregroundStyle(.secondary).fixedSize()
                Slider(value: Binding(get: { drag ?? Double(max(1, model.currentPosition)) }, set: { drag = $0 }),
                       in: 1...Double(model.positionCount), step: 1) { editing in
                    if !editing, let target = drag { drag = nil; Task { await model.goToPosition(Int(target)) } }
                }.controlSize(.mini).accessibilityLabel("Ir a otra página")
            }
            .padding(.horizontal, 16).frame(height: size.barHeight)
            .background(.bar)
        }
    }
}

enum CREANotesExport {
    /// Markdown with every bookmark, highlight and note of the book, oldest first.
    static func text(bookTitle: String, items: [CREAAnnotation]) -> String {
        let formatter = DateFormatter(); formatter.dateStyle = .medium; formatter.timeStyle = .none
        var out = "# \(bookTitle)\n\n"
        for item in items.sorted(by: { $0.updatedAt < $1.updatedAt }) {
            let kind = item.kind == "bookmark" ? "Marcador" : item.kind == "underline" ? "Subrayado" : "Resaltado"
            out += "## \(kind) · \(formatter.string(from: item.updatedAt))\n"
            if !item.title.isEmpty { out += item.kind == "bookmark" ? "\(item.title)\n" : "> \(item.title)\n" }
            if !item.note.isEmpty { out += "\nNota: \(item.note)\n" }
            out += "\n"
        }
        return out
    }

    static func file(bookTitle: String, items: [CREAAnnotation]) -> URL? {
        let safe = bookTitle.components(separatedBy: CharacterSet(charactersIn: "/\\:?%*|\"<>")).joined(separator: " ")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Notas - \(safe.prefix(60)).md")
        do { try text(bookTitle: bookTitle, items: items).write(to: url, atomically: true, encoding: .utf8); return url }
        catch { return nil }
    }
}

struct CREAShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
extension URL: @retroactive Identifiable { public var id: String { absoluteString } }

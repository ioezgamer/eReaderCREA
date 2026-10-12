import SwiftUI

/// Android-parity reading tools. Text is sent only after tapping "Consultar Claude".
/// Uses the same CREA proxy as Android; no API secrets are present on iPhone.
struct CREAAIReaderSheet: View {
    @ObservedObject var ai: CREAReaderAI
    @ObservedObject var reader: EPUBBookModel
    let bookTitle: String

    @Environment(\.dismiss) private var dismiss
    @State private var tool = "pasaje"
    @State private var mode: CREAAIMode = .explain
    @State private var inputText = ""
    @State private var chapterText = ""
    @State private var chapterTitle: String?
    @State private var grade = ""
    @State private var targetLanguage = "inglés"
    @State private var resultText = ""
    @State private var message: String?
    @State private var running = false
    @State private var loadingChapter = false

    private var readyToAsk: Bool {
        ai.canUse && !running && !loadingChapter &&
            (tool == "pasaje" ? !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                             : chapterText.count >= 200)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 17) {
                    Label("Asistente de lectura con Claude", systemImage: "sparkles")
                        .font(.title3.bold()).foregroundStyle(CREAStyle.blue)
                    Text("Las consultas utilizan el servicio de IA de CREA. Solo se envía el fragmento o capítulo cuando presionas Consultar Claude.")
                        .font(.footnote).foregroundStyle(.secondary)

                    Picker("Herramienta", selection: $tool) {
                        Text("Pasaje").tag("pasaje")
                        Text("Resumen").tag("resumen")
                        Text("Docentes").tag("docentes")
                    }
                    .pickerStyle(.segmented)
                    .accessibilityLabel("Función de Claude")

                    if tool == "pasaje" {
                        Picker("Acción", selection: $mode) {
                            ForEach(CREAAIMode.allCases) { option in
                                Text(option.label).tag(option)
                            }
                        }
                        .pickerStyle(.menu)
                        .padding(.vertical, 4)
                        Text("Fragmento seleccionado o escrito")
                            .font(.subheadline.bold())
                        TextEditor(text: $inputText)
                            .frame(minHeight: 125)
                            .padding(6)
                            .scrollContentBackground(.hidden)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                            .accessibilityLabel("Texto para analizar")
                        if mode == .translate {
                            Picker("Traducir a", selection: $targetLanguage) {
                                Text("inglés").tag("inglés")
                                Text("español").tag("español")
                            }
                        }
                        Button("Usar selección actual", systemImage: "text.quote") {
                            if let picked = reader.selectedPassage() {
                                inputText = picked.text
                            } else {
                                message = "Selecciona palabras dentro del libro y vuelve a esta herramienta."
                            }
                        }.font(.footnote)
                    } else {
                        Label(
                            tool == "resumen" ? "Resumen del capítulo actual" : "Actividades para docentes",
                            systemImage: tool == "resumen" ? "text.book.closed" : "graduationcap"
                        ).font(.headline)
                        if loadingChapter {
                            ProgressView("Leyendo el capítulo…")
                        } else if chapterText.count >= 200 {
                            Text(chapterTitle ?? "Capítulo actual")
                                .font(.subheadline.bold())
                            Text("\(chapterText.count) caracteres extraídos del EPUB")
                                .font(.caption).foregroundStyle(.secondary)
                            Text(String(chapterText.prefix(300)) + "…")
                                .font(.footnote).foregroundStyle(.secondary)
                                .lineLimit(6)
                        } else {
                            ContentUnavailableView("Capítulo no disponible",
                                systemImage: "text.book.closed",
                                description: Text("Este EPUB no contiene texto de capítulo accesible. Avanza a una sección de texto y pulsa Reintentar."))
                            Button("Reintentar lectura del capítulo", systemImage: "arrow.clockwise") {
                                Task { await loadChapter() }
                            }
                        }
                        if tool == "docentes" {
                            TextField("Grado escolar (opcional)", text: $grade)
                                .textFieldStyle(.roundedBorder)
                        }
                    }

                    if let message {
                        Text(message)
                            .font(.footnote).foregroundStyle(.red)
                    }

                    if !ai.canUse {
                        Text(ai.availabilityMessage ?? "Activa y comprueba Claude desde Ajustes.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }

                    Button {
                        Task { await generate() }
                    } label: {
                        HStack {
                            if running { ProgressView().tint(.white) }
                            else { Image(systemName: "sparkles") }
                            Text(running ? "Consultando Claude…" : "Consultar Claude")
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!readyToAsk)
                    if let remaining = ai.remaining[tool == "pasaje" ? "explain" :
                                                         tool == "resumen" ? "chapter_summary" : "teacher_questions"] {
                        Text("\(remaining) consultas disponibles para esta función hoy")
                            .font(.caption).foregroundStyle(.secondary)
                    }

                    if !resultText.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Resultado").font(.headline)
                            Text(resultText).textSelection(.enabled)
                            ShareLink(item: resultText) {
                                Label("Compartir resultado", systemImage: "square.and.arrow.up")
                            }
                            Button("Copiar", systemImage: "doc.on.doc") {
                                UIPasteboard.general.string = resultText
                            }
                            .accessibilityLabel("Copiar resultado al portapapeles")
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                    }
                }
                .padding(16)
            }
            .background(CREAStyle.gradient)
            .navigationTitle("Claude · CREA Reader")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cerrar") { dismiss() }
                }
            }
            .task {
                if let picked = reader.selectedPassage() { inputText = picked.text }
                await ai.refresh()
            }
            .onChange(of: tool) { _, selected in
                resultText = ""
                message = nil
                if selected != "pasaje" && chapterText.isEmpty {
                    Task { await loadChapter() }
                }
            }
            .onChange(of: mode) { _, _ in
                resultText = ""
                message = nil
            }
        }
    }

    private func loadChapter() async {
        guard !loadingChapter else { return }
        loadingChapter = true
        defer { loadingChapter = false }
        if let chapter = await reader.currentChapter() {
            chapterText = chapter.text
            chapterTitle = chapter.title
            message = nil
        } else {
            chapterText = ""
            message = "No se pudo extraer el texto de este capítulo. Revisa que sea un EPUB de texto."
        }
    }

    private func generate() async {
        guard readyToAsk else { return }
        running = true
        resultText = ""
        message = nil
        defer { running = false }
        do {
            switch tool {
            case "pasaje":
                let selection = reader.selectedPassage()
                resultText = try await ai.explain(mode, text: inputText,
                    context: selection?.context, bookTitle: bookTitle,
                    targetLanguage: targetLanguage)
            case "resumen":
                resultText = try await ai.chapterSummary(
                    text: chapterText, bookTitle: bookTitle, chapterTitle: chapterTitle)
            default:
                let guide = try await ai.teacherQuestions(
                    text: chapterText, bookTitle: bookTitle, chapterTitle: chapterTitle,
                    grade: grade.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : grade)
                resultText = guide.readableText
            }
        } catch {
            message = error.localizedDescription
        }
    }
}

struct CREAAISettingsView: View {
    @ObservedObject var ai: CREAReaderAI
    @State private var enteredURL = ""
    @State private var enabled = false

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            Toggle("Asistente de Claude", isOn: Binding(
                get: { ai.enabled },
                set: { newValue in
                    ai.setEnabled(newValue)
                    if newValue { Task { await ai.refresh(force: true) } }
                }
            ))
            Text("La IA es opcional. Se conecta al servidor seguro de CREA, nunca directamente a Anthropic. No guarda claves API en el iPhone.")
                .font(.footnote).foregroundStyle(.secondary)
            TextField("https://crea-reader-ai-xxxx.onrender.com", text: $enteredURL)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel("Dirección HTTPS del servidor CREA Reader AI")
            Button("Guardar dirección del servidor", systemImage: "checkmark.circle") {
                ai.setServerURL(enteredURL.trimmingCharacters(in: .whitespacesAndNewlines))
                if ai.enabled { Task { await ai.refresh(force: true) } }
            }
            .disabled(enteredURL == ai.serverURL)
            Button {
                Task { await ai.refresh(force: true) }
            } label: {
                Label("Comprobar conexión con Claude", systemImage: "arrow.clockwise")
            }
            .disabled(!ai.enabled || ai.loadingStatus)
            if ai.loadingStatus { ProgressView("Consultando disponibilidad…") }
            if ai.available {
                Label("Claude disponible", systemImage: "checkmark.shield")
                    .font(.footnote).foregroundStyle(.green)
            } else if let reason = ai.availabilityMessage {
                Text(reason).font(.footnote).foregroundStyle(.secondary)
            }
            Text("Necesitas iniciar sesión con una cuenta Google autorizada por CREA. El administrador del servidor controla modelos, créditos y límites de uso.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .onAppear { enteredURL = ai.serverURL }
    }
}

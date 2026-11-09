import SwiftUI
import Foundation
import UniformTypeIdentifiers

struct StudioPreset: Identifiable {
    let id: String
    let title: String
    let detail: String
    let keys: [String]
    let risky: Bool
}

struct StudioGroup: Identifiable {
    let id: String
    let title: String
    let symbol: String
    let detail: String
    let presets: [StudioPreset]
}

enum StudioPresets {
    static let groups: [StudioGroup] = [
        .init(id: "display", title: "Pantalla", symbol: "display", detail: "Opciones de visualización", presets: [
            .init(id: "dynamic-island", title: "Dynamic Island", detail: "Marca de compatibilidad de Dynamic Island.", keys: ["YlEtTtHlNesRBMal1CqRaA"], risky: false),
            .init(id: "always-on", title: "Pantalla siempre activa", detail: "Solo compatible con determinados paneles. Puede causar consumo adicional.", keys: ["2OOJf1VhaM7NxfRok3HbWQ", "j8/Omm6s1lsmTDFsXjsBfA"], risky: true),
            .init(id: "aod-vibrancy", title: "AOD Vibrancy", detail: "Ajuste de representación de pantalla siempre activa.", keys: ["ykpu7qyhqFweVMKtxNylWA"], risky: true),
            .init(id: "glass-power", title: "Liquid Glass de bajo consumo", detail: "Indicador de renderizado de bajo consumo.", keys: ["SAGvsp6O6kAQ4fEfDJpC4Q"], risky: false)
        ]),
        .init(id: "device", title: "Dispositivo", symbol: "iphone", detail: "Capacidades de hardware y ajustes", presets: [
            .init(id: "boot-chime", title: "Sonido de arranque", detail: "Compatibilidad con sonido de inicio y apagado.", keys: ["QHxt+hGLaBPbQJbXiUJX3w"], risky: false),
            .init(id: "charge-limit", title: "Límite de carga", detail: "Visualización del menú; no añade limitación de hardware.", keys: ["37NVydb//GP/GrhuTN+exg"], risky: false),
            .init(id: "tap-wake", title: "Tocar para despertar", detail: "Marcador de capacidad del sistema.", keys: ["yZf3GTRMGTuwSV/lD7Cagw"], risky: false),
            .init(id: "camera", title: "Camera Control", detail: "Opciones de control de cámara; no añade un botón físico.", keys: ["CwvKxM2cEogD3p+HYgaW0Q", "oOV1jhJbdV3AddkcCg0AEA"], risky: false),
            .init(id: "action-button", title: "Botón de acción", detail: "Visibilidad de la configuración del botón de acción.", keys: ["cT44WE1EohiwRzhsZ8xEsw"], risky: false),
            .init(id: "collision", title: "Detección de choques", detail: "Marcador de compatibilidad para los ajustes de SOS.", keys: ["HCzWusHQwZDea6nNhaKndw"], risky: true)
        ]),
        .init(id: "ipad", title: "iPad", symbol: "ipad", detail: "Indicadores de compatibilidad", presets: [
            .init(id: "stage-manager", title: "Stage Manager", detail: "Marcador de compatibilidad; no habilita hardware adicional.", keys: ["qeaj75wk3HF4DwQ8qbIi7g"], risky: true)
        ]),
        .init(id: "advanced", title: "Avanzado", symbol: "wrench.and.screwdriver", detail: "Marcadores experimentales", presets: [
            .init(id: "internal-install", title: "Instalación interna", detail: "Indicador experimental. Puede causar incompatibilidades.", keys: ["EqrsVvjcYDdxHBiQmGhAWw"], risky: true),
            .init(id: "internal-storage", title: "Almacenamiento interno", detail: "Indicador experimental para ciertas opciones de almacenamiento.", keys: ["LBJfwOEzExRxzlAnSuI7eg"], risky: true)
        ])
    ]
}

struct StudioToolsView: View {
    @EnvironmentObject private var store: EditorStore
    @State private var exportDocument: ExportPlistDocument?
    @State private var showingExport = false
    @State private var showDiscard = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label("MobileGestalt leído directamente", systemImage: "iphone.gen3")
                        .font(.headline)
                    LabeledContent("Claves", value: "\(store.keyCount)")
                    LabeledContent("Cambios preparados", value: "\(store.changeCount)")
                    Text(store.status).font(.subheadline).foregroundStyle(.secondary)
                } header: { Text("Dispositivo") } footer: {
                    Text("Las opciones se preparan en una copia de trabajo. Guardar una copia no modifica la caché protegida de iOS.")
                }

                Section("Herramientas") {
                    Button {
                        store.loadSystemSnapshot()
                    } label: {
                        Label("Volver a leer MobileGestalt", systemImage: "arrow.clockwise")
                    }
                    .disabled(store.working || store.hasChanges)
                }
                Section("Ajustes preparados") {
                    ForEach(StudioPresets.groups) { group in
                        NavigationLink {
                            StudioPresetGroupView(group: group)
                        } label: {
                            Label {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(group.title)
                                    Text(group.detail).font(.caption).foregroundStyle(.secondary)
                                }
                            } icon: { Image(systemName: group.symbol).foregroundStyle(.tint) }
                        }
                        .disabled(store.engine == nil)
                    }
                    NavigationLink {
                        StudioIntelligenceInfo()
                    } label: {
                        Label("Inteligencia", systemImage: "sparkles")
                    }
                }

                Section("Acciones") {
                    Button {
                        store.saveWorkingCopy()
                    } label: {
                        Label("Aplicar cambios a una copia y verificar", systemImage: "checkmark.circle")
                    }
                    .disabled(!store.hasChanges || store.working)

                    Button {
                        do {
                            let url = try store.createLocalBackup()
                            store.successMessage = "Respaldo creado: \(url.lastPathComponent)"
                        } catch { store.errorMessage = error.localizedDescription }
                    } label: {
                        Label("Crear respaldo del original", systemImage: "externaldrive.badge.plus")
                    }
                    .disabled(store.engine == nil || store.working)

                    Button {
                        do {
                            exportDocument = try store.exportDocument()
                            showingExport = true
                        } catch { store.errorMessage = error.localizedDescription }
                    } label: {
                        Label("Exportar plist modificado", systemImage: "square.and.arrow.up")
                    }
                    .disabled(store.engine == nil)

                    if store.hasChanges {
                        Button(role: .destructive) { showDiscard = true } label: {
                            Label("Descartar cambios", systemImage: "arrow.uturn.backward")
                        }
                    }
                }
                if let saved = store.lastSavedURL {
                    Section("Último archivo modificado") {
                        Text(saved.lastPathComponent).font(.caption).textSelection(.enabled)
                        ShareLink(item: saved) {
                            Label("Compartir plist modificado", systemImage: "square.and.arrow.up")
                        }
                    }
                }
            }
            .navigationTitle("Herramientas")
            .confirmationDialog("¿Descartar cambios pendientes?", isPresented: $showDiscard) {
                Button("Descartar cambios", role: .destructive) { store.resetAll() }
            }
            .fileExporter(isPresented: $showingExport, document: exportDocument,
                          contentType: .propertyList, defaultFilename: "MobileGestalt-modificado") { result in
                switch result {
                case .success(let url): store.successMessage = "Exportado: \(url.lastPathComponent)"
                case .failure(let error): store.errorMessage = error.localizedDescription
                }
                exportDocument = nil
            }
            .alert("Operación no completada", isPresented: Binding(
                get: { store.errorMessage != nil },
                set: { if !$0 { store.errorMessage = nil } }
            )) { Button("Cerrar") { store.errorMessage = nil } } message: {
                Text(store.errorMessage ?? "")
            }
            .alert("Operación completada", isPresented: Binding(
                get: { store.successMessage != nil },
                set: { if !$0 { store.successMessage = nil } }
            )) { Button("Aceptar") { store.successMessage = nil } } message: {
                Text(store.successMessage ?? "")
            }
        }
    }
}

struct StudioPresetGroupView: View {
    @EnvironmentObject private var store: EditorStore
    let group: StudioGroup

    var body: some View {
        List {
            Section {
                Text("Mantené el valor original o prepará un cambio. Las opciones agregan o modifican indicadores en CacheExtra de la copia de trabajo.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Ajustes") {
                ForEach(group.presets) { option in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .top, spacing: 10) {
                            VStack(alignment: .leading, spacing: 5) {
                                HStack(spacing: 6) {
                                    Text(option.title).font(.body)
                                    if option.risky {
                                        Image(systemName: "exclamationmark.triangle")
                                            .foregroundStyle(.orange)
                                            .accessibilityLabel("Opción experimental")
                                    }
                                }
                                Text(option.detail).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 4)
                            Menu {
                                Button("Mantener actual") { store.choosePreset(option, mode: 0) }
                                Button("Activar en copia") { store.choosePreset(option, mode: 1) }
                                Button("Desactivar en copia") { store.choosePreset(option, mode: 2) }
                            } label: {
                                HStack(spacing: 3) {
                                    Text(store.presetModeName(option))
                                        .font(.subheadline)
                                    Image(systemName: "chevron.up.chevron.down")
                                        .font(.caption2)
                                }
                                .frame(minHeight: 44)
                            }
                            .disabled(store.engine == nil || store.working)
                        }
                    }
                    .padding(.vertical, 5)
                }
            }
        }
        .navigationTitle(group.title)
        .navigationBarTitleDisplayMode(.large)
    }
}

struct StudioIntelligenceInfo: View {
    var body: some View {
        List {
            ContentUnavailableView("Sin ajuste comprobado", systemImage: "sparkles",
                description: Text("No se ha verificado un mapeo de claves de Inteligencia para este modelo y versión. No se añadieron interruptores que prometan activar funciones inexistentes."))
        }
        .navigationTitle("Inteligencia")
    }
}

struct StudioBackupsView: View {
    @EnvironmentObject private var store: EditorStore
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        do {
                            let url = try store.createLocalBackup()
                            store.successMessage = "Respaldo creado: \(url.lastPathComponent)"
                        } catch { store.errorMessage = error.localizedDescription }
                    } label: {
                        Label("Crear respaldo verificado", systemImage: "externaldrive.badge.plus")
                    }
                    .disabled(store.engine == nil)
                }
                if let url = store.lastBackupURL {
                    Section("Último respaldo original") {
                        Text(url.lastPathComponent)
                        ShareLink(item: url) { Label("Compartir respaldo", systemImage: "square.and.arrow.up") }
                    }
                }
                if let url = store.lastSavedURL {
                    Section("Última copia modificada") {
                        Text(url.lastPathComponent)
                        ShareLink(item: url) { Label("Compartir copia", systemImage: "square.and.arrow.up") }
                    }
                }
                Section {
                    Text("Los respaldos y copias se almacenan en Documentos de GestaltStudio. No sustituyen los datos de la caché protegida del dispositivo.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Respaldos")
            .alert("GestaltStudio", isPresented: Binding(
                get: { store.successMessage != nil || store.errorMessage != nil },
                set: { if !$0 { store.successMessage = nil; store.errorMessage = nil } }
            )) { Button("Cerrar") { store.successMessage = nil; store.errorMessage = nil } } message: {
                Text(store.errorMessage ?? store.successMessage ?? "")
            }
        }
    }
}

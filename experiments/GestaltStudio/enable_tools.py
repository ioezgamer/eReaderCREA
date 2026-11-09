#!/usr/bin/env python3
"""Add a grouped, locally applied MobileGestalt editor without protected-file writes."""
from pathlib import Path

def edit(file_name: str, before: str, after: str) -> None:
    path = Path(file_name)
    value = path.read_text(encoding="utf-8")
    if before not in value:
        raise SystemExit(f"Missing expected patch anchor in {file_name}: {before[:100]!r}")
    path.write_text(value.replace(before, after, 1), encoding="utf-8")

edit("Sources/ContentView.swift",
'''struct GestaltStudioRootView: View {
    @EnvironmentObject private var store: EditorStore
    @State private var selectedTab = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            FieldsScreen()
                .tabItem { Label("Campos", systemImage: "square.stack.3d.up") }
                .tag(0)
            ChangesScreen()
                .tabItem { Label("Cambios", systemImage: "square.and.pencil") }
                .badge(store.changeCount)
                .tag(1)
            AboutScreen()
                .tabItem { Label("Acerca de", systemImage: "info.circle") }
                .tag(2)
        }
    }
}
''',
'''struct GestaltStudioRootView: View {
    @EnvironmentObject private var store: EditorStore
    @State private var selectedTab = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            StudioToolsView()
                .tabItem { Label("Herramientas", systemImage: "square.grid.2x2.fill") }
                .tag(0)
            FieldsScreen()
                .tabItem { Label("Campos", systemImage: "slider.horizontal.3") }
                .tag(1)
            StudioBackupsView()
                .tabItem { Label("Respaldos", systemImage: "clock.arrow.circlepath") }
                .tag(2)
            AboutScreen()
                .tabItem { Label("Acerca de", systemImage: "info.circle") }
                .tag(3)
        }
    }
}
''')

edit("Sources/PlistCore.swift",
'''    mutating func restoreAll() {
''',
'''    // Prepare a preset in the working snapshot. Existing scalar types remain unchanged.
    // Missing flags may be added to CacheExtra, but no protected iOS file is written.
    mutating func setPresetFlags(keys: [String], mode: Int) throws {
        guard [0, 1, 2].contains(mode) else {
            throw EditorError.invalidValue("Selección de preset no válida.")
        }
        guard var extra = root["CacheExtra"] as? [String: Any],
              let original = originalRoot["CacheExtra"] as? [String: Any] else {
            throw EditorError.missingCacheExtra
        }
        var changed = changedKeys
        for key in keys {
            if mode == 0 {
                if let originalValue = original[key] {
                    extra[key] = originalValue
                } else {
                    extra.removeValue(forKey: key)
                }
                changed.remove(key)
                continue
            }

            let enabled = mode == 1
            let newValue: Any
            if let current = extra[key] {
                switch Self.fieldType(current) {
                case .boolean: newValue = NSNumber(value: enabled)
                case .integer: newValue = NSNumber(value: enabled ? 1 : 0)
                case .real: newValue = NSNumber(value: enabled ? 1.0 : 0.0)
                default:
                    throw EditorError.invalidValue("La clave \(key) no es numérica ni booleana. No se modificó.")
                }
            } else {
                newValue = NSNumber(value: enabled ? 1 : 0)
            }
            extra[key] = newValue
            if let originalValue = original[key], Self.valuesEqual(originalValue, newValue) {
                changed.remove(key)
            } else {
                changed.insert(key)
            }
        }
        root["CacheExtra"] = extra
        changedKeys = changed
    }

    mutating func restoreAll() {
''')

edit("Sources/EditorStore.swift",
'''    func resetAll() {
''',
'''    func choosePreset(_ option: StudioPreset, mode: Int) {
        guard var edited = engine else {
            errorMessage = "Todavía no se ha leído MobileGestalt."
            return
        }
        do {
            try edited.setPresetFlags(keys: option.keys, mode: mode)
            engine = edited
            status = edited.hasChanges ? "Cambios preparados" : "Sin cambios pendientes"
            message = "\(edited.modifiedCount) claves modificadas en la copia de trabajo. No se ha modificado iOS."
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func presetModeName(_ option: StudioPreset) -> String {
        guard let engine else { return "Sin lectura" }
        guard option.keys.contains(where: { engine.changedKeys.contains($0) }) else { return "Mantener" }
        let flags = option.keys.compactMap { engine.value(for: $0) as? NSNumber }
        guard flags.count == option.keys.count else { return "Personalizado" }
        if flags.allSatisfy({ $0.boolValue }) { return "Activado" }
        if flags.allSatisfy({ !$0.boolValue }) { return "Desactivado" }
        return "Personalizado"
    }

    func saveWorkingCopy() {
        guard !working, let currentEngine = engine, currentEngine.hasChanges else { return }
        working = true
        defer { working = false }
        do {
            let originalBackup = try createLocalBackup()
            let bytes = try currentEngine.serialized()
            guard let documents = FileManager.default.urls(
                for: .documentDirectory, in: .userDomainMask).first else {
                throw EditorError.invalidPlist
            }
            let folder = documents.appendingPathComponent("GestaltStudio-Edits", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let time = ISO8601DateFormatter().string(from: Date())
                .replacingOccurrences(of: ":", with: "-")
            let file = folder.appendingPathComponent(
                "MobileGestalt-editado-\(time)-\(UUID().uuidString.prefix(6)).plist")
            try bytes.write(to: file, options: .atomic)
            let written = try Data(contentsOf: file)
            guard written == bytes else { throw EditorError.unacceptableFormat }
            _ = try PlistEditorEngine(data: written)
            lastSavedURL = file
            status = "Copia modificada y verificada"
            message = "Guardados \(written.count) bytes en Documentos. El sistema iOS permanece sin cambios."
            successMessage = "Copia verificada. Respaldo original: \(originalBackup.lastPathComponent). Podés compartir la copia editada."
        } catch {
            errorMessage = "No se pudo guardar la copia: \(error.localizedDescription)"
        }
    }

    func availableBackupURLs() -> [URL] {
        guard let folder = try? backupDirectory(),
              let items = try? FileManager.default.contentsOfDirectory(
                at: folder, includingPropertiesForKeys: [.contentModificationDateKey]) else {
            return []
        }
        return items.filter { $0.pathExtension == "plist" }.sorted {
            (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ??
            .distantPast >
            (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ??
            .distantPast
        }
    }

    func restoreBackupIntoEditor(_ url: URL) {
        guard !working else { return }
        do {
            let bytes = try Data(contentsOf: url)
            engine = try PlistEditorEngine(data: bytes)
            source = .imported(url)
            status = "Respaldo abierto en el editor"
            message = "Se abrió el respaldo local. No se restauró ninguna caché protegida del dispositivo."
            successMessage = "El respaldo está cargado en el editor. Podés editarlo y exportarlo."
        } catch {
            errorMessage = "No se pudo abrir el respaldo: \(error.localizedDescription)"
        }
    }

    func resetAll() {
''')

edit("Sources/ContentView.swift",
'''                    Text("La aplicación no fuerza escritura en rutas del sistema ni obtiene privilegios root o Kernel Read/Write. La edición y el guardado se aplican a documentos seleccionados mediante Files, y las instantáneas protegidas se pueden exportar.")
''',
'''                    Text("La lectura directa de MobileGestalt permite preparar presets y editar valores. El botón Aplicar guarda un plist verificado en Documentos, pero no modifica la caché protegida de iOS.")
''')

print("Prepared grouped presets, local save verification and backups.")

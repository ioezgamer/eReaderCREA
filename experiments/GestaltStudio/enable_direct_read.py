#!/usr/bin/env python3
"""Adapt the embedded GestaltStudio source for direct MobileGestalt loading."""
from pathlib import Path

def edit(filename, before, after):
    path = Path(filename)
    text = path.read_text(encoding="utf-8")
    if before not in text:
        raise SystemExit(f"Expected build source not found: {filename}: {before[:90]!r}")
    path.write_text(text.replace(before, after), encoding="utf-8")

edit("Sources/GestaltStudioApp.swift",
     ".environmentObject(editor)",
     ".environmentObject(editor)\n                .task { editor.startAutomaticReadIfNeeded() }")

edit("Sources/EditorStore.swift",
     "import UniformTypeIdentifiers",
     "import UniformTypeIdentifiers\nimport Security")
edit("Sources/EditorStore.swift",
     '    static let appGroup = "group.org.artease.gestaltstudio"\n',
     "")
edit("Sources/EditorStore.swift",
     '    @Published var status = "Importá un plist o leé una instantánea"',
     '    @Published var status = "Preparando lectura del dispositivo"')
edit("Sources/EditorStore.swift",
     '    @Published var message = "Editá claves de CacheExtra y guardá los cambios en un archivo autorizado."',
     '    @Published var message = "MobileGestalt se carga automáticamente al abrir GestaltStudio."')

edit("Sources/EditorStore.swift", "    @Published var lastSavedURL: URL?\n",
'''    @Published var lastSavedURL: URL?

    private var automaticReadAttempted = false
    func startAutomaticReadIfNeeded() {
        guard !automaticReadAttempted else { return }
        automaticReadAttempted = true
        loadSystemSnapshot()
    }

    // Use an App Group actually granted by the signing profile.
    private nonisolated static func findSignedAppGroup() -> String? {
        let entitlement = "com.apple.security.application-groups" as CFString
        guard let task = SecTaskCreateFromSelf(kCFAllocatorDefault),
              let value = SecTaskCopyValueForEntitlement(task, entitlement, nil),
              let signedGroups = value as? [String] else { return nil }
        return signedGroups.first { group in
            FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) != nil
        }
    }
''')

edit("Sources/EditorStore.swift",
'''        guard !working else { return }
        working = true
        status = "Leyendo instantánea…"
        let dir = GestaltPaths.systemDirectory
        let file = GestaltPaths.systemFile
        let group = GestaltPaths.appGroup
        DispatchQueue.global(qos: .userInitiated).async {
            let handle = worse_query(dir, group, false, false)''',
'''        guard !working else { return }
        guard !hasChanges else {
            errorMessage = "Hay cambios pendientes. Exportalos o descartalos antes de volver a leer para no perderlos."
            return
        }
        working = true
        status = "Leyendo MobileGestalt directamente…"
        message = "Buscando un App Group firmado y obteniendo la caché del dispositivo."
        errorMessage = nil
        let dir = GestaltPaths.systemDirectory
        let file = GestaltPaths.systemFile
        DispatchQueue.global(qos: .userInitiated).async {
            let group = Self.findSignedAppGroup()
            let handle: Int64 = group.map { worse_query(dir, $0, false, false) } ?? -1''')
edit("Sources/EditorStore.swift",
'''                readFailure = "La extensión no está disponible (código \(handle)). Se necesita una firma con App Groups válida."''',
'''                readFailure = group == nil
                    ? "No hay un App Group firmado y accesible en esta instalación. Firmá la IPA con la capacidad App Groups."
                    : "El dispositivo rechazó la extensión de lectura (código \(handle))."''')
edit("Sources/EditorStore.swift",
'''                        self.status = "Instantánea cargada"
                        self.message = "\(candidate.count) claves, \(resultData.count) bytes. Podés editar y exportar una copia."''',
'''                        self.status = "MobileGestalt leído del dispositivo"
                        self.message = "Lectura directa: \(candidate.count) claves, \(resultData.count) bytes. Podés editar y exportar una copia."''')
edit("Sources/EditorStore.swift",
     '                    self.status = "Lectura no disponible"',
     '                    self.status = "No se pudo leer MobileGestalt"')

edit("Sources/ContentView.swift", '    @State private var showImporter = false\n', '')
edit("Sources/ContentView.swift",
'''                Section("Documento") {
                    Button { showImporter = true } label: {
                        Label("Importar archivo plist", systemImage: "square.and.arrow.down")
                    }
                    Button { store.loadSystemSnapshot() } label: {''',
'''                Section("Dispositivo") {
                    Button { store.loadSystemSnapshot() } label: {''')
edit("Sources/ContentView.swift",
     'Label("Leer instantánea del dispositivo", systemImage: "iphone")',
     'Label("Volver a leer MobileGestalt", systemImage: "arrow.clockwise")')
edit("Sources/ContentView.swift",
'''                    }.disabled(store.working)
                    if store.engine != nil {''',
'''                    }.disabled(store.working || store.hasChanges)
                    if store.engine != nil {''')
edit("Sources/ContentView.swift",
'''                        ContentUnavailableView("Sin archivo cargado", systemImage: "doc.badge.plus",
                                               description: Text("Importá un plist de MobileGestalt para empezar a editarlo."))''',
'''                        ContentUnavailableView(store.working ? "Leyendo dispositivo" : "Sin lectura disponible",
                                               systemImage: "iphone.gen3",
                                               description: Text(store.working
                                                   ? "La lectura directa está en curso. No necesitás importar nada."
                                                   : "GestaltStudio intenta cargar MobileGestalt automáticamente. Verificá la firma App Groups o pulsá Volver a leer."))''')
edit("Sources/ContentView.swift",
'''            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.propertyList], allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first { store.loadImported(url: url) }
                case .failure(let error): store.errorMessage = error.localizedDescription
                }
            }
''',
'')
print("Direct-read source patch completed.")

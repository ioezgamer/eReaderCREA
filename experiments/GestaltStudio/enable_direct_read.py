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

    // Read App Groups from this process's effective code signature.
    // A re-signing service may replace the original App Group identifiers.
    private nonisolated static func findSignedAppGroup() -> String? {
        for group in GSGetSignedAppGroups() {
            if group.hasPrefix("group."),
               FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) != nil {
                return group
            }
        }
        return nil
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
                    ? "La firma instalada no expone ningún App Group accesible. Comprobá que tu perfil de FlareStore incluye App Groups."
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

# Use dynamic Security.framework lookups to inspect effective signed entitlements
# without adding dependencies on private functions absent from the iOS SDK.
Path("Sources/SigningGroups.h").write_text(r'''#import <Foundation/Foundation.h>
NS_ASSUME_NONNULL_BEGIN
FOUNDATION_EXPORT NSArray<NSString *> *GSGetSignedAppGroups(void);
NS_ASSUME_NONNULL_END
''', encoding="utf-8")
Path("Sources/SigningGroups.m").write_text(r'''#import "SigningGroups.h"
#import <Security/Security.h>
#import <dlfcn.h>

typedef CFTypeRef (*GSCreateTaskFn)(CFAllocatorRef);
typedef CFTypeRef (*GSCopyEntitlementFn)(CFTypeRef, CFStringRef, CFErrorRef *);

NSArray<NSString *> *GSGetSignedAppGroups(void) {
    void *security = dlopen("/System/Library/Frameworks/Security.framework/Security", RTLD_NOW | RTLD_LOCAL);
    if (!security) return @[];
    GSCreateTaskFn createTask = (GSCreateTaskFn)dlsym(security, "SecTaskCreateFromSelf");
    GSCopyEntitlementFn copyEntitlement =
        (GSCopyEntitlementFn)dlsym(security, "SecTaskCopyValueForEntitlement");
    if (!createTask || !copyEntitlement) {
        dlclose(security);
        return @[];
    }

    CFTypeRef task = createTask(kCFAllocatorDefault);
    if (!task) {
        dlclose(security);
        return @[];
    }
    CFTypeRef found = copyEntitlement(task, CFSTR("com.apple.security.application-groups"), NULL);
    CFRelease(task);
    dlclose(security);
    if (!found) return @[];
    if (CFGetTypeID(found) != CFArrayGetTypeID()) {
        CFRelease(found);
        return @[];
    }
    NSArray *values = CFBridgingRelease(found);
    NSMutableArray<NSString *> *groups = [NSMutableArray array];
    for (id value in values) {
        if ([value isKindOfClass:[NSString class]] &&
            [value hasPrefix:@"group."]) {
            [groups addObject:value];
        }
    }
    return [groups copy];
}
''', encoding="utf-8")
bridge = Path("Sources/BridgingHeader.h")
bridge.write_text(bridge.read_text(encoding="utf-8") + '\n#import "SigningGroups.h"\n', encoding="utf-8")

print("Direct-read source patch completed.")

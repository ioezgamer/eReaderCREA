import SwiftUI

struct CREACollectionsSheet: View {
    @ObservedObject var library: LocalLibrary
    let book: LocalBook?
    @State private var name = ""
    @State private var renaming: String?
    @State private var issue: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                if let book { Text(book.name).font(.headline) }
                Section("Colecciones") {
                    ForEach(library.collections, id: \.self) { collection in
                        Group {
                            if let book {
                                Toggle(collection, isOn: Binding(
                                    get: { library.books.first { $0.id == book.id }.map { library.memberships($0).contains(collection) } ?? false },
                                    set: { library.setMembership(book, collection: collection, included: $0) }))
                            } else {
                                LabeledContent(collection, value: "\(library.books.filter { library.memberships($0).contains(collection) }.count)")
                            }
                        }.contextMenu {
                            Button("Renombrar") { renaming = collection; name = collection }
                            Button("Eliminar colección", role: .destructive) { library.deleteCollection(collection) }
                        }
                    }
                    if library.collections.isEmpty { Text("Todavía no hay colecciones") }
                }
                Section(renaming == nil ? "Crear colección" : "Renombrar colección") {
                    TextField("Nombre", text: $name)
                    Button("Guardar") {
                        do {
                            if let old = renaming { try library.renameCollection(old, to: name) }
                            else {
                                try library.createCollection(name)
                                if let book { library.setMembership(book, collection: name.trimmingCharacters(in: .whitespacesAndNewlines), included: true) }
                            }
                            name = ""; renaming = nil; issue = nil
                        } catch { issue = error.localizedDescription }
                    }
                    if renaming != nil { Button("Cancelar") { renaming = nil; name = "" } }
                    if let issue { Text(issue).foregroundStyle(.red) }
                }
                Text("Un libro puede pertenecer a varias colecciones. Eliminar una colección conserva los libros.").font(.footnote)
            }.navigationTitle("Colecciones").toolbar { Button("Cerrar") { dismiss() } }
        }
    }
}

import SwiftUI

struct CREAAvatarView: View {
    let avatar: Int
    let color: Int
    var size: CGFloat = 44
    var body: some View {
        let rgb = CREAReaderAvatars.color(color)
        Text(CREAReaderAvatars.emoji(avatar))
            .font(.system(size: size * 0.55))
            .frame(width: size, height: size)
            .background(Color(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255), in: Circle())
            .accessibilityHidden(true)
    }
}

/// "Perfil": what each person has read, independent of the books stored on this device.
struct CREAProfileScreen: View {
    @ObservedObject var store: CREAReaderStore
    @ObservedObject var library: LocalLibrary
    @ObservedObject var catalog: CREADriveCatalog
    let community: AnyView
    let onOpenLocal: (LocalBook) -> Void
    let onOpenRemote: (CREARemoteBook) -> Void
    @State private var editor: CREAProfileEditor.Mode?
    @State private var confirmReturn = false

    private var me: CREAReaderProfile? { store.current() }
    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private var candidates: [CREACandidateBook] {
        catalog.books.map { book in
            CREACandidateBook(key: "drive:" + book.id, title: catalog.title(for: book),
                              author: catalog.insight(for: book)?.author ?? book.authorFromName,
                              categories: catalog.categories(for: book), driveFileId: book.id,
                              addedAt: CREACatalogRules.milliseconds(book.modifiedTime))
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if store.isGroupDevice { sessionCard }
                    if let me { header(me) }
                    if me == nil && store.isGroupDevice {
                        Text("Puedes leer sin elegir tu nombre: lo que leas pasará a tu perfil si lo eliges después.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    shelf("Seguir leyendo", "book", Array(CREAReaderRules.continueReading(me).prefix(6)))
                    groupSection
                    recommendedSection
                    shelf("Leídos recientemente", "clock", Array(CREAReaderRules.recent(me).prefix(6)))
                    favoritesSection
                    shelf("Terminados", "checkmark.seal", Array(CREAReaderRules.finished(me).prefix(10)))
                    achievementsSection
                    DisclosureGroup { community.padding(.top, 8) } label: { Label("Qué leen los demás", systemImage: "person.2").font(.headline) }
                        .padding().creaCard()
                }.padding()
            }
            .background(CREAStyle.gradient)
            .onAppear { _ = store.current() }
            .navigationTitle("Perfil")
            .toolbar {
                if let me {
                    ToolbarItem(placement: .topBarTrailing) { Button { editor = .edit(me.id) } label: { Label("Editar perfil", systemImage: "slider.horizontal.3") } }
                }
            }
            .sheet(item: $editor) { mode in CREAProfileEditor(store: store, mode: mode) }
            .confirmationDialog("¿Terminaste de leer?", isPresented: $confirmReturn, titleVisibility: .visible) {
                Button("Terminé, devolver el dispositivo", role: .destructive) { store.endSession() }
            } message: { Text("La siguiente persona empezará de cero. Tu perfil queda guardado.") }
        }
    }

    // MARK: Session

    private var sessionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let me {
                HStack {
                    CREAAvatarView(avatar: me.avatar, color: me.color, size: 36)
                    Text("Lee: \(me.nickname)").font(.headline)
                    Spacer()
                    Button("Terminé") { confirmReturn = true }.buttonStyle(.borderedProminent)
                }
            } else {
                Text("¿Quién lee hoy?").font(.headline)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(store.choices()) { profile in
                            Button { store.select(profile.id) } label: {
                                VStack(spacing: 4) {
                                    CREAAvatarView(avatar: profile.avatar, color: profile.color, size: 54)
                                    Text(profile.nickname).font(.caption).lineLimit(1).frame(maxWidth: 70)
                                }
                            }.buttonStyle(.plain)
                        }
                        Button { editor = .create } label: {
                            VStack(spacing: 4) {
                                Image(systemName: "plus").frame(width: 54, height: 54).background(Color.primary.opacity(0.08), in: Circle())
                                Text("Soy nuevo").font(.caption)
                            }
                        }.buttonStyle(.plain)
                    }
                }
            }
        }
        .padding().frame(maxWidth: .infinity, alignment: .leading).creaCard()
    }

    private func header(_ me: CREAReaderProfile) -> some View {
        let streak = CREAReaderInsights.streak(days: me.days)
        return HStack(spacing: 14) {
            CREAAvatarView(avatar: me.avatar, color: me.color, size: 60)
            VStack(alignment: .leading, spacing: 3) {
                Text(me.nickname).font(.title3.bold())
                if let group = me.group { Label(group, systemImage: "person.3").font(.subheadline).foregroundStyle(.secondary) }
                Label(streak == 1 ? "1 día seguido" : "\(streak) días seguidos", systemImage: "flame.fill").font(.subheadline).foregroundStyle(.orange)
            }
            Spacer()
        }.padding().creaCard()
    }

    // MARK: Shelves

    private func open(key: String, driveFileId: String?) {
        if let local = library.books.first(where: { CREAReaderRules.keyFor(driveFileId: $0.driveFileID, contentHash: $0.contentHash) == key
                                                     || (driveFileId != nil && $0.driveFileID == driveFileId) }) { onOpenLocal(local); return }
        if let id = driveFileId, let remote = catalog.books.first(where: { $0.id == id }) { onOpenRemote(remote) }
    }

    private func shelf(_ title: String, _ icon: String, _ entries: [CREAReadEntry]) -> some View {
        Group {
            if !entries.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Label(LocalizedStringKey(title), systemImage: icon).font(.headline)
                    ForEach(entries, id: \.key) { entry in
                        Button { open(key: entry.key, driveFileId: entry.driveFileId) } label: { entryRow(title: entry.title, author: entry.author, percent: entry.percent) }
                            .buttonStyle(.plain)
                    }
                }.padding().frame(maxWidth: .infinity, alignment: .leading).creaCard()
            }
        }
    }

    private func entryRow(title: String, author: String?, percent: Int?, detail: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title.isEmpty ? "Libro" : title).font(.subheadline.bold()).lineLimit(2)
            if let author, !author.isEmpty { Text(author).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            if let detail { Text(detail).font(.caption).foregroundStyle(CREAStyle.blue) }
            if let percent { ProgressView(value: Double(percent) / 100).tint(CREAStyle.green) }
        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
    }

    private var favoritesSection: some View {
        let marks = CREAReaderRules.favorites(me)
        return Group {
            if !marks.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Label("Favoritos", systemImage: "heart").font(.headline)
                    ForEach(Array(marks.prefix(10)), id: \.key) { mark in
                        Button { open(key: mark.key, driveFileId: mark.driveFileId) } label: { entryRow(title: mark.title, author: mark.author, percent: nil) }.buttonStyle(.plain)
                    }
                }.padding().frame(maxWidth: .infinity, alignment: .leading).creaCard()
            }
        }
    }

    private var groupSection: some View {
        let items = CREAReaderInsights.groupReading(me: me, everyone: store.profiles, now: now)
        return Group {
            if !items.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Label("Lo que lee mi grupo", systemImage: "person.3").font(.headline)
                    ForEach(items, id: \.profile.id) { item in
                        Button { open(key: item.entry.key, driveFileId: item.entry.driveFileId) } label: {
                            HStack(spacing: 10) {
                                CREAAvatarView(avatar: item.profile.avatar, color: item.profile.color, size: 36)
                                entryRow(title: item.entry.title, author: item.profile.nickname, percent: item.entry.percent)
                            }
                        }.buttonStyle(.plain)
                    }
                }.padding().frame(maxWidth: .infinity, alignment: .leading).creaCard()
            }
        }
    }

    private var recommendedSection: some View {
        let items = CREAReaderInsights.recommend(me: me, everyone: store.profiles, catalog: candidates, now: now)
        return Group {
            if !items.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Label("Recomendado para ti", systemImage: "sparkles").font(.headline)
                    ForEach(items, id: \.book.key) { item in
                        Button { open(key: item.book.key, driveFileId: item.book.driveFileId) } label: {
                            entryRow(title: item.book.title, author: item.book.author, percent: nil, detail: item.reason.text)
                        }.buttonStyle(.plain)
                    }
                }.padding().frame(maxWidth: .infinity, alignment: .leading).creaCard()
            }
        }
    }

    private var achievementsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Logros", systemImage: "rosette").font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 10)], spacing: 10) {
                ForEach(CREAReaderInsights.achievements(me), id: \.id) { item in
                    HStack(spacing: 8) {
                        Image(systemName: item.id.icon).foregroundStyle(item.unlocked ? CREAStyle.orange : Color.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(LocalizedStringKey(item.id.title)).font(.caption.bold()).lineLimit(2)
                            Text(item.unlocked ? "Logrado" : "\(item.progress) de \(item.goal)").font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(10).background(Color.primary.opacity(item.unlocked ? 0.08 : 0.03), in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityElement(children: .combine)
                }
            }
        }.padding().frame(maxWidth: .infinity, alignment: .leading).creaCard()
    }
}

/// Create ("Soy nuevo") or edit a reader profile: nickname, drawing, colour and group.
struct CREAProfileEditor: View {
    enum Mode: Identifiable, Hashable {
        case create, edit(String)
        var id: String { switch self { case .create: "create"; case .edit(let id): "edit-" + id } }
    }
    @ObservedObject var store: CREAReaderStore
    let mode: Mode
    @Environment(\.dismiss) private var dismiss
    @State private var nickname = ""
    @State private var avatar = 0
    @State private var color = 0
    @State private var group = ""
    @State private var hidden = false
    @State private var confirmClear = false
    @State private var confirmDelete = false
    @State private var duplicate = false

    private var editingID: String? { if case .edit(let id) = mode { return id }; return nil }
    private var valid: Bool { nickname.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2 }

    var body: some View {
        NavigationStack {
            Form {
                Section("Apodo y dibujo") {
                    TextField("Apodo (sin apellidos)", text: $nickname).textInputAutocapitalization(.words)
                    HStack { Spacer(); CREAAvatarView(avatar: avatar, color: color, size: 64); Spacer() }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 44))], spacing: 8) {
                        ForEach(0..<CREAReaderAvatars.emojis.count, id: \.self) { index in
                            Button { avatar = index } label: {
                                Text(CREAReaderAvatars.emoji(index)).font(.title2).frame(width: 44, height: 44)
                                    .background(avatar == index ? CREAStyle.blue.opacity(0.25) : Color.clear, in: Circle())
                            }.buttonStyle(.plain)
                        }
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(0..<CREAReaderAvatars.colors.count, id: \.self) { index in
                                let rgb = CREAReaderAvatars.color(index)
                                Button { color = index } label: {
                                    Circle().fill(Color(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255))
                                        .frame(width: 34, height: 34).overlay(Circle().stroke(color == index ? CREAStyle.blue : .clear, lineWidth: 3))
                                }.buttonStyle(.plain).accessibilityLabel("Color \(index + 1)")
                            }
                        }
                    }
                }
                Section("Grupo de lectura") {
                    let groups = store.groups()
                    if !groups.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack { ForEach(groups, id: \.self) { name in
                                Button(name) { group = name }.buttonStyle(.bordered).tint(CREAReaderRules.sameGroup(group, name) ? CREAStyle.blue : .secondary)
                            } }
                        }
                    }
                    TextField("Grupo (opcional), por ejemplo «Club martes»", text: $group)
                    Text("El grupo existe en cuanto alguien lo usa. «club martes» y «Club Martes» son el mismo.").font(.footnote).foregroundStyle(.secondary)
                }
                if editingID != nil {
                    Section("Privacidad") {
                        Toggle("Ocultar mi lectura a los demás", isOn: $hidden)
                        Button("Borrar mi historial", role: .destructive) { confirmClear = true }
                        Button("Eliminar mi perfil", role: .destructive) { confirmDelete = true }
                    }
                }
            }
            .navigationTitle(editingID == nil ? "Soy nuevo" : "Mi perfil")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Guardar") { save(force: false) }.disabled(!valid) }
            }
            .onAppear(perform: load)
            .alert("Ya hay alguien con ese apodo", isPresented: $duplicate) {
                Button("Usar otro apodo", role: .cancel) {}
                Button("Crear de todos modos") { save(force: true) }
            } message: { Text("Si eres esa persona, elígela en «¿Quién lee hoy?».") }
            .confirmationDialog("¿Borrar tu historial?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Borrar historial", role: .destructive) { if let id = editingID { store.clearHistory(id) }; dismiss() }
            }
            .confirmationDialog("¿Eliminar tu perfil?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Eliminar perfil", role: .destructive) { if let id = editingID { store.delete(id) }; dismiss() }
            } message: { Text("Se elimina aquí y, tras la próxima sincronización, en todos los dispositivos.") }
        }
    }

    private func load() {
        guard let id = editingID, let profile = store.byID(id) else { return }
        nickname = profile.nickname; avatar = profile.avatar; color = profile.color; group = profile.group ?? ""; hidden = profile.hidden
    }

    private func save(force: Bool) {
        let name = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        if let id = editingID {
            let trimmedGroup = group.trimmingCharacters(in: .whitespacesAndNewlines)
            store.update(id, nickname: name, avatar: avatar, color: color, hidden: hidden, group: trimmedGroup.isEmpty ? nil : trimmedGroup, clearGroup: trimmedGroup.isEmpty)
        } else {
            if !force && !store.sameNickname(name).isEmpty { duplicate = true; return }
            store.create(nickname: name, avatar: avatar, color: color, group: group)
        }
        dismiss()
    }
}

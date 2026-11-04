import SwiftUI

/// Shared shelf geometry: covers always occupy the same rectangle before and after loading.
struct CREABookCoverFrame<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        Color.clear
            .aspectRatio(0.72, contentMode: .fit)
            .overlay { content().frame(maxWidth: .infinity, maxHeight: .infinity) }
            .background(Color(uiColor: .tertiarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(.primary.opacity(0.06)))
    }
}

/// A quiet, opaque surface keeps metadata legible in both appearance modes.
struct CREAShelfSurface: ViewModifier {
    func body(content: Content) -> some View {
        content.padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(.primary.opacity(0.06)))
    }
}

struct CREAFilterChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.subheadline.weight(selected ? .semibold : .medium))
                .lineLimit(1).fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 16).frame(minHeight: 44)
                .foregroundStyle(selected ? Color.white : Color.primary)
                .background(selected ? CREAStyle.blue : Color(uiColor: .secondarySystemGroupedBackground), in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// Reserve text lines in a grid, but let list rows grow at accessibility sizes.
struct CREABookHeading: View {
    let title: String
    var author: String? = nil
    var category: String? = nil
    var grid = true
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Group {
                if grid { Text(title).lineLimit(2, reservesSpace: true) }
                else { Text(title).fixedSize(horizontal: false, vertical: true) }
            }.font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Group {
                if grid { Text(authorText).lineLimit(1, reservesSpace: true) }
                else { Text(authorText).fixedSize(horizontal: false, vertical: true) }
            }.font(.caption).foregroundStyle(.secondary)
            if !grid, let category, !category.isEmpty {
                Text(category).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
        }.multilineTextAlignment(.leading)
    }
    private var authorText: String {
        guard let author, !author.isEmpty else { return "Autor no indicado" }
        return author
    }
}

struct CREABookProgress: View {
    let percent: Int
    var body: some View {
        HStack(spacing: 8) {
            ProgressView(value: Double(min(100, max(0, percent))), total: 100).tint(CREAStyle.green)
            Text("\(percent)%").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                .fixedSize()
        }.accessibilityElement(children: .ignore)
            .accessibilityLabel("Progreso de lectura: \(percent) por ciento")
    }
}

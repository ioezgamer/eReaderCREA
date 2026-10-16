import SwiftUI
import WidgetKit

private let widgetBlue = Color(red: 0.14, green: 0.47, blue: 0.88)
private let widgetGreen = Color(red: 0.29, green: 0.70, blue: 0.40)

struct CREAEntry: TimelineEntry {
    let date: Date
    let snapshot: CREAWidgetSnapshot
}

struct CREAProvider: TimelineProvider {
    func placeholder(in context: Context) -> CREAEntry {
        CREAEntry(date: Date(), snapshot: CREAWidgetSnapshot(
            reading: [.init(id: "x", title: "Mi libro", author: "Autor", percent: 40, coverFile: nil)],
            minutesToday: 25, pagesToday: 18, streak: 3, finished: 5))
    }
    func getSnapshot(in context: Context, completion: @escaping (CREAEntry) -> Void) {
        completion(CREAEntry(date: Date(), snapshot: CREAWidgetSnapshot.load()))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<CREAEntry>) -> Void) {
        // The app reloads timelines on every progress change; this refresh only rolls the "today" counters at midnight.
        let next = Calendar.current.nextDate(after: Date(), matching: DateComponents(hour: 0, minute: 5), matchingPolicy: .nextTime) ?? Date().addingTimeInterval(3600)
        completion(Timeline(entries: [CREAEntry(date: Date(), snapshot: CREAWidgetSnapshot.load())], policy: .after(next)))
    }
}

private func coverImage(_ book: CREAWidgetSnapshot.Book) -> UIImage? {
    guard let name = book.coverFile,
          let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: CREAShared.groupID) else { return nil }
    return UIImage(contentsOfFile: directory.appendingPathComponent("widget-covers").appendingPathComponent(name).path)
}

private func bookLink(_ book: CREAWidgetSnapshot.Book) -> URL { URL(string: "creareader://book/\(book.id)") ?? URL(string: "creareader://")! }

private struct CoverView: View {
    let book: CREAWidgetSnapshot.Book
    var body: some View {
        Group {
            if let image = coverImage(book) { Image(uiImage: image).resizable().scaledToFill() }
            else {
                ZStack {
                    LinearGradient(colors: [widgetBlue, .pink], startPoint: .topLeading, endPoint: .bottomTrailing)
                    Image(systemName: "book.closed.fill").foregroundStyle(.white)
                }
            }
        }
        .aspectRatio(0.7, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

private struct ContinueRow: View {
    let book: CREAWidgetSnapshot.Book
    var body: some View {
        Link(destination: bookLink(book)) {
            HStack(spacing: 10) {
                CoverView(book: book)
                VStack(alignment: .leading, spacing: 3) {
                    Text(book.title).font(.subheadline.bold()).lineLimit(2)
                    if let author = book.author, !author.isEmpty { Text(author).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                    ProgressView(value: Double(book.percent) / 100).tint(widgetGreen)
                    Text("\(book.percent)%").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
        }
    }
}

struct ContinueReadingView: View {
    @Environment(\.widgetFamily) private var family
    let entry: CREAEntry
    var body: some View {
        let books = entry.snapshot.reading
        Group {
            if books.isEmpty {
                VStack(spacing: 6) { Image(systemName: "books.vertical.fill").font(.title2).foregroundStyle(widgetBlue); Text("Elige un libro para empezar a leer").font(.caption).multilineTextAlignment(.center) }
            } else if family == .systemSmall, let book = books.first {
                CoverView(book: book).frame(maxWidth: .infinity, maxHeight: .infinity).widgetURL(bookLink(book))
            } else if family == .systemMedium, let book = books.first {
                ContinueRow(book: book)
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Continuar leyendo").font(.caption.bold()).foregroundStyle(widgetBlue)
                    ForEach(Array(books.prefix(3))) { ContinueRow(book: $0) }
                    Spacer(minLength: 0)
                }
            }
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }
}

struct ContinueReadingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CREAContinueReading", provider: CREAProvider()) { ContinueReadingView(entry: $0) }
            .configurationDisplayName("Continuar leyendo")
            .description("Vuelve a los libros que estás leyendo. Cambia el tamaño para ver más.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct ReadingStatsView: View {
    @Environment(\.widgetFamily) private var family
    let entry: CREAEntry
    var body: some View {
        let s = entry.snapshot
        Group {
            if family == .systemSmall {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Hoy").font(.caption.bold()).foregroundStyle(widgetBlue)
                    Text("\(s.minutesToday)").font(.system(size: 40, weight: .bold, design: .rounded))
                    Text("min").font(.caption).foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                    Label(s.streak == 1 ? "1 día" : "\(s.streak) días", systemImage: "flame.fill").font(.caption.bold()).foregroundStyle(.orange)
                }.frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(spacing: 0) {
                    stat("Hoy", "\(s.minutesToday)", "min")
                    stat("Páginas hoy", "\(s.pagesToday)", "")
                    stat("Racha", "\(s.streak)", s.streak == 1 ? "día" : "días")
                    stat("Terminados", "\(s.finished)", "libros")
                }
            }
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }
    private func stat(_ label: String, _ value: String, _ unit: String) -> some View {
        VStack(spacing: 2) {
            Text(LocalizedStringKey(label)).font(.caption2.bold()).foregroundStyle(widgetBlue).lineLimit(1)
            Text(value).font(.system(size: 30, weight: .bold, design: .rounded))
            Text(unit).font(.caption2).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity)
    }
}

struct ReadingStatsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CREAReadingStats", provider: CREAProvider()) { ReadingStatsView(entry: $0) }
            .configurationDisplayName("Estadísticas de lectura")
            .description("Tu tiempo de lectura, páginas y racha.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct CREAWidgetsBundle: WidgetBundle {
    var body: some Widget {
        ContinueReadingWidget()
        ReadingStatsWidget()
    }
}

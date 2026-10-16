import SwiftUI
import WidgetKit

/// Reading statistics. They never leave the device.
@MainActor
final class CREAReadingStats: ObservableObject {
    static let shared = CREAReadingStats()
    @Published private(set) var data: CREAReadingStatsData

    init() {
        let saved = CREAShared.defaults.data(forKey: CREAShared.statsKey)
            .flatMap { try? JSONDecoder().decode([String: CREADayStat].self, from: $0) }
        data = CREAReadingStatsData(days: saved ?? [:])
    }

    func addSeconds(_ seconds: Int) { add { $0.seconds += seconds }; if seconds > 0 { CREAWidgetBridge.refresh() } }
    func addPages(_ pages: Int) { add { $0.pages += pages }; if pages > 0 { CREAWidgetBridge.refresh() } }

    private func add(_ mutate: (inout CREADayStat) -> Void) {
        let key = CREAReadingStatsData.key(Date())
        var day = data.days[key] ?? CREADayStat()
        mutate(&day)
        data.days[key] = day
        if let encoded = try? JSONEncoder().encode(data.days) { CREAShared.defaults.set(encoded, forKey: CREAShared.statsKey) }
    }
}

/// Counts the time the reader is on screen. Visits under 3 s or over 4 h (a forgotten screen) are dropped.
@MainActor
final class CREAReadingSession {
    private var startedAt: Date?
    func start() { startedAt = Date() }
    func stop() {
        guard let startedAt else { return }
        self.startedAt = nil
        let seconds = Int(Date().timeIntervalSince(startedAt))
        if seconds > 3 && seconds < 4 * 3600 { CREAReadingStats.shared.addSeconds(seconds) }
    }
}

/// One page counts per turn forward; jumps and going back do not.
@MainActor
final class CREAPageTurnCounter {
    private var last = -1
    func onPosition(_ position: Int) {
        if last >= 0 && position == last + 1 { CREAReadingStats.shared.addPages(1) }
        last = position
    }
}

struct CREAStatsView: View {
    @ObservedObject private var stats = CREAReadingStats.shared
    @ObservedObject private var library = LocalLibrary.shared

    var body: some View {
        let week = stats.data.last(7)
        let today = week.last ?? CREADayStat()
        let total = stats.data.totals
        let streak = stats.data.streak()
        let finished = library.books.filter { $0.progress >= 0.99 }.count
        ScrollView {
            VStack(spacing: 12) {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                    tile("Hoy", creaFormatReadingTime(today.seconds), pages(today.pages))
                    tile("Racha", days(streak), "Con al menos 1 minuto de lectura al día")
                    tile("Total", creaFormatReadingTime(total.seconds), pages(total.pages))
                    tile("Libros terminados", "\(finished)", "de \(library.books.count) en tu biblioteca")
                }
                VStack(alignment: .leading, spacing: 10) {
                    Text("Esta semana").font(.headline)
                    Text("\(creaFormatReadingTime(week.reduce(0) { $0 + $1.seconds })) · \(pages(week.reduce(0) { $0 + $1.pages }))")
                        .font(.footnote).foregroundStyle(.secondary)
                    CREAWeekChart(week: week)
                }
                .padding().frame(maxWidth: .infinity, alignment: .leading)
                .creaCard()
                Text("Las estadísticas se guardan solo en este dispositivo.").font(.footnote).foregroundStyle(.secondary)
            }.padding()
        }
        .background(CREAStyle.gradient)
        .navigationTitle("Estadísticas de lectura")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func pages(_ count: Int) -> String { count == 1 ? "1 página" : "\(count) páginas" }
    private func days(_ count: Int) -> String { count == 1 ? "1 día" : "\(count) días" }
    private func tile(_ label: String, _ value: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedStringKey(label)).font(.caption.bold()).foregroundStyle(CREAStyle.blue)
            Text(value).font(.title2.bold())
            Text(detail).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(14)
        .creaCard()
        .accessibilityElement(children: .combine)
    }
}

private struct CREAWeekChart: View {
    let week: [CREADayStat]
    var body: some View {
        let peak = max(1, week.map(\.seconds).max() ?? 1)
        let letters = ["L", "M", "X", "J", "V", "S", "D"]
        HStack(alignment: .bottom, spacing: 10) {
            ForEach(Array(week.enumerated()), id: \.offset) { index, day in
                let date = Calendar.current.date(byAdding: .day, value: index - (week.count - 1), to: Date()) ?? Date()
                let weekday = (Calendar.current.component(.weekday, from: date) + 5) % 7
                VStack(spacing: 6) {
                    Text("\(day.seconds / 60)").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                    RoundedRectangle(cornerRadius: 6)
                        .fill(index == week.count - 1 ? CREAStyle.blue : CREAStyle.blue.opacity(0.45))
                        .frame(height: max(4, 90 * CGFloat(day.seconds) / CGFloat(peak)))
                    Text(letters[weekday]).font(.caption2)
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(letters[weekday]): \(creaFormatReadingTime(day.seconds))")
            }
        }
        .frame(height: 130, alignment: .bottom)
    }
}

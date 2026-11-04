import XCTest
import SwiftUI
import UIKit
@testable import CREAReader

@MainActor
final class BookPresentationTests: XCTestCase {
    func testCoverGeometryDoesNotDependOnImageDimensions() {
        for width: CGFloat in [140, 164, 210, 280] {
            let portrait = UIHostingController(rootView: CREABookCoverFrame {
                Image(systemName: "book.closed").resizable().scaledToFit()
            })
            let landscape = UIHostingController(rootView: CREABookCoverFrame {
                Color.blue.overlay(Text("Un título muy largo que ocupa varias líneas"))
            })
            let a = portrait.sizeThatFits(in: CGSize(width: width, height: 2000))
            let b = landscape.sizeThatFits(in: CGSize(width: width, height: 2000))
            XCTAssertEqual(a.height, width / 0.72, accuracy: 1)
            XCTAssertEqual(a.height, b.height, accuracy: 1)
        }
    }

    func testLongAndShortBookHeadingsReserveEqualGridHeight() {
        let short = UIHostingController(rootView: CREABookHeading(title: "Amor", author: nil))
        let long = UIHostingController(rootView: CREABookHeading(title: "Cuentos de mi tía Panchita y otros relatos para compartir", author: "Un nombre de autor de varias palabras"))
        let proposal = CGSize(width: 140, height: 2000)
        XCTAssertEqual(short.sizeThatFits(in: proposal).height, long.sizeThatFits(in: proposal).height, accuracy: 1)
    }

    func testShelfRendersInPhoneTabletDarkAndAccessibilityLayouts() async throws {
        let scenarios: [(String, CGFloat, ColorScheme, DynamicTypeSize)] = [
            ("phone-375-light", 375, .light, .large),
            ("phone-393-dark", 393, .dark, .large),
            ("phone-landscape", 812, .light, .large),
            ("ipad-834-light", 834, .light, .large),
            ("phone-accessibility", 375, .light, .accessibility5)
        ]
        for (name, width, scheme, textSize) in scenarios {
            let shelf = ShelfFixture()
                .environment(\.colorScheme, scheme)
                .environment(\.dynamicTypeSize, textSize)
                .frame(width: width)
            // UIKit-backed controls (including ProgressView) need a real hosting window.
            let height: CGFloat = name == "phone-landscape" ? 375 : name.hasPrefix("ipad") ? 1194 : 812
            let controller = UIHostingController(rootView: shelf)
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: height))
            window.rootViewController = controller
            window.isHidden = false
            controller.view.frame = window.bounds
            controller.view.setNeedsLayout()
            controller.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(150))
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                controller.view.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            window.isHidden = true
            XCTAssertGreaterThan(image.size.height, 100)
            let attachment = XCTAttachment(image: image)
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("crea-design-snapshots")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try image.pngData()?.write(to: directory.appendingPathComponent(name + ".png"))
            print("CREA_SNAPSHOT \(directory.path)/\(name).png")
        }
    }
}

private struct ShelfFixture: View {
    @Environment(\.dynamicTypeSize) private var textSize
    let titles = ["Cuentos de la selva", "Amor de madre", "Corazón de perro", "Cuentos de mi tía Panchita"]
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Catálogo").font(.title2.bold())
            HStack { Text("33 libros en Drive").font(.headline); Spacer(); Image(systemName: "arrow.clockwise") }
            HStack { CREAFilterChip(title: "Todos", selected: true) {}; CREAFilterChip(title: "Descargados", selected: false) {} }
            LazyVGrid(columns: textSize.isAccessibilitySize ? [GridItem(.flexible())] : [GridItem(.adaptive(minimum: 160), spacing: 16, alignment: .top)], spacing: 16) {
                ForEach(Array(titles.enumerated()), id: \.offset) { index, title in
                    VStack(alignment: .leading, spacing: 12) {
                        CREABookCoverFrame {
                            Color(red: 0.12, green: 0.24, blue: 0.36)
                                .overlay(Image(systemName: "book.closed").font(.largeTitle).foregroundStyle(.white))
                        }.frame(maxHeight: textSize.isAccessibilitySize ? 180 : nil)
                        CREABookHeading(title: title, author: index == 0 ? "Horacio Quiroga" : "Un autor con nombre largo", grid: !textSize.isAccessibilitySize)
                        Text("EPUB").font(.caption)
                        CREABookProgress(percent: index * 27)
                        Button {} label: {
                            Label("Descargar", systemImage: "arrow.down").frame(maxWidth: .infinity, minHeight: 32)
                        }.buttonStyle(.bordered)
                    }.modifier(CREAShelfSurface())
                }
            }
        }.padding(16).background(Color(uiColor: .systemGroupedBackground))
    }
}

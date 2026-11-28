import AppKit
import CoreGraphics
import XCTest
@testable import KomadoCore

final class DisplayCatalogTests: XCTestCase {
    @MainActor
    func testActiveDisplayUsesSystemName() throws {
        _ = NSApplication.shared
        let screen = try XCTUnwrap(NSScreen.screens.first { screen in
            (screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == CGMainDisplayID()
        })
        let display = try XCTUnwrap(DisplayCatalog.active().first { $0.id == CGMainDisplayID() })
        XCTAssertEqual(display.name, catalogLabel(screen.localizedName, fallback: "ディスプレイ"))
    }

    func testSelectionRequiresListedAndCurrentlyActiveDisplay() {
        let first = DisplayInfo(id: 1, width: 1920, height: 1080, name: "内蔵")
        let selected = DisplayInfo(id: 2, width: 1280, height: 720, name: "外部")
        let resized = DisplayInfo(id: 2, width: 1920, height: 1080, name: "外部画面")
        let added = DisplayInfo(id: 3, width: 2560, height: 1440, name: "追加")
        let sent = [first, selected]
        let current = [added, resized]

        XCTAssertTrue(DisplayCatalog.containsSelected(2, in: sent, current: current))
        XCTAssertFalse(DisplayCatalog.containsSelected(1, in: sent, current: current))
        XCTAssertFalse(DisplayCatalog.containsSelected(3, in: sent, current: current))
        XCTAssertFalse(DisplayCatalog.containsSelected(2, in: sent, current: []))
    }
}

import XCTest
@testable import KomadoCore

final class DisplayCatalogTests: XCTestCase {
    func testSelectionRequiresListedAndCurrentlyActiveDisplay() {
        let first = DisplayInfo(id: 1, width: 1920, height: 1080)
        let selected = DisplayInfo(id: 2, width: 1280, height: 720)
        let resized = DisplayInfo(id: 2, width: 1920, height: 1080)
        let added = DisplayInfo(id: 3, width: 2560, height: 1440)
        let sent = [first, selected]
        let current = [added, resized]

        XCTAssertTrue(DisplayCatalog.containsSelected(2, in: sent, current: current))
        XCTAssertFalse(DisplayCatalog.containsSelected(1, in: sent, current: current))
        XCTAssertFalse(DisplayCatalog.containsSelected(3, in: sent, current: current))
        XCTAssertFalse(DisplayCatalog.containsSelected(2, in: sent, current: []))
    }
}

import Foundation
import XCTest
@testable import KomadoCore

final class PointerScrollTests: XCTestCase {
    func testSharedMessages() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/pointer-scroll.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String]])
        for value in try XCTUnwrap(fixture["valid"]) {
            let scroll = try PointerScroll.decode(Data(value.utf8))
            XCTAssertEqual(try PointerScroll.decode(scroll.encode()), scroll)
        }
        for value in try XCTUnwrap(fixture["invalid"]) { XCTAssertThrowsError(try PointerScroll.decode(Data(value.utf8))) }
        XCTAssertThrowsError(try PointerScroll.decode(Data(repeating: 0, count: 1025)))
    }
}

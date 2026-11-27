import Foundation
import XCTest
@testable import KomadoCore

final class PointerDragTests: XCTestCase {
    func testSharedMessages() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/pointer-drag.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String]])
        for value in try XCTUnwrap(fixture["valid"]) {
            let drag = try PointerDrag.decode(Data(value.utf8))
            XCTAssertEqual(try PointerDrag.decode(drag.encode()), drag)
        }
        for value in try XCTUnwrap(fixture["invalid"]) { XCTAssertThrowsError(try PointerDrag.decode(Data(value.utf8))) }
        XCTAssertThrowsError(try PointerDrag.decode(Data(repeating: 0, count: 1025)))
    }
}

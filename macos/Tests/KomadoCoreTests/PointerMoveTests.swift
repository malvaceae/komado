import Foundation
import XCTest
@testable import KomadoCore

final class PointerMoveTests: XCTestCase {
    func testSharedMessages() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/pointer-move.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String]])
        for value in try XCTUnwrap(fixture["valid"]) {
            let move = try PointerMove.decode(Data(value.utf8))
            XCTAssertEqual(try PointerMove.decode(move.encode()), move)
        }
        for value in try XCTUnwrap(fixture["invalid"]) { XCTAssertThrowsError(try PointerMove.decode(Data(value.utf8))) }
        XCTAssertThrowsError(try PointerMove.decode(Data(repeating: 0, count: 1025)))
    }
}

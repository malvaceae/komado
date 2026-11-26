import Foundation
import CoreGraphics
import XCTest
@testable import KomadoCore

final class PointerClickTests: XCTestCase {
    func testSharedMessages() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/pointer-click.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String]])
        let valid = try XCTUnwrap(fixture["valid"]), invalid = try XCTUnwrap(fixture["invalid"])
        XCTAssertFalse(valid.isEmpty)
        XCTAssertFalse(invalid.isEmpty)
        for value in valid {
            let click = try PointerClick.decode(Data(value.utf8))
            XCTAssertEqual(try PointerClick.decode(click.encode()), click)
        }
        for value in invalid { XCTAssertThrowsError(try PointerClick.decode(Data(value.utf8))) }
        XCTAssertThrowsError(try PointerClick.decode(Data(repeating: 0, count: 1025)))
    }

    func testRejectsStaleRevisionAndReplayedSequence() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 4, bounds: CGRect(x: -100, y: 20, width: 200, height: 100)))
        var validator = PointerClickValidator()
        func click(_ sequence: UInt64, _ revision: UInt64) -> PointerClick {
            PointerClick(sequence: sequence, captureRevision: revision, x: 500_000, y: 250_000, button: .left)
        }
        XCTAssertNil(validator.point(for: click(1, 3), in: geometry))
        XCTAssertEqual(validator.point(for: click(1, 4), in: geometry), CGPoint(x: 0, y: 45))
        XCTAssertNil(validator.point(for: click(1, 4), in: geometry))
        XCTAssertEqual(validator.point(for: click(3, 4), in: geometry), CGPoint(x: 0, y: 45))
        XCTAssertNil(validator.point(for: click(2, 4), in: geometry))
    }
}

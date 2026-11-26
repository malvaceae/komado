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
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 200, height: 100), contentRect: CGRect(x: 0, y: 0, width: 200, height: 100)))
        var validator = PointerClickValidator()
        func click(_ sequence: UInt64, _ revision: UInt64) -> PointerClick {
            PointerClick(sequence: sequence, captureRevision: revision, x: 500_000, y: 250_000, button: .left)
        }
        XCTAssertNil(validator.point(for: click(1, 3), in: geometry, mapping: mapping))
        XCTAssertEqual(validator.point(for: click(1, 4), in: geometry, mapping: mapping), CGPoint(x: 0, y: 45))
        XCTAssertNil(validator.point(for: click(1, 4), in: geometry, mapping: mapping))
        XCTAssertEqual(validator.point(for: click(3, 4), in: geometry, mapping: mapping), CGPoint(x: 0, y: 45))
        XCTAssertNil(validator.point(for: click(2, 4), in: geometry, mapping: mapping))
    }

    func testClickInFrameMarginIsNotAppliedOrConsumed() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 1, bounds: CGRect(x: 0, y: 0, width: 640, height: 400)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 640, height: 640), contentRect: CGRect(x: 0, y: 120, width: 640, height: 400)))
        var validator = PointerClickValidator()
        let margin = PointerClick(sequence: 1, captureRevision: 1, x: 500_000, y: 100_000, button: .left)
        let center = PointerClick(sequence: 1, captureRevision: 1, x: 500_000, y: 500_000, button: .left)
        XCTAssertNil(validator.point(for: margin, in: geometry, mapping: mapping))
        XCTAssertEqual(validator.point(for: center, in: geometry, mapping: mapping), CGPoint(x: 320, y: 200))
    }
}

import CoreGraphics
import XCTest
@testable import KomadoCore

final class CaptureGeometryTests: XCTestCase {
    func testMapsNormalizedCoordinatesOnDisplayWithNegativeOrigin() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(
            revision: 7,
            bounds: CGRect(x: -1200, y: -300, width: 800, height: 600)
        ))
        XCTAssertEqual(geometry.point(x: 0, y: 0, captureRevision: 7), CGPoint(x: -1200, y: -300))
        XCTAssertEqual(geometry.point(x: 0.5, y: 0.25, captureRevision: 7), CGPoint(x: -800, y: -150))
        let bottomRight = try XCTUnwrap(geometry.point(x: 1, y: 1, captureRevision: 7))
        XCTAssertTrue(geometry.bounds.contains(bottomRight))
        XCTAssertEqual(bottomRight.x, geometry.bounds.maxX.nextDown)
        XCTAssertEqual(bottomRight.y, geometry.bounds.maxY.nextDown)
    }

    func testRejectsStaleAndInvalidInput() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 7, bounds: CGRect(x: 0, y: 0, width: 10, height: 10)))
        XCTAssertNil(geometry.point(x: 0.5, y: 0.5, captureRevision: 6))
        for value in [-0.001, 1.001, .nan, .infinity, -.infinity] {
            XCTAssertNil(geometry.point(x: value, y: 0.5, captureRevision: 7))
            XCTAssertNil(geometry.point(x: 0.5, y: value, captureRevision: 7))
        }
    }

    func testRejectsInvalidGeometry() {
        for bounds in [
            CGRect(x: 0, y: 0, width: 0, height: 1),
            CGRect(x: 0, y: 0, width: -1, height: 1),
            CGRect(x: CGFloat.infinity, y: 0, width: 1, height: 1),
            CGRect(x: 0, y: 0, width: CGFloat.nan, height: 1),
            CGRect(x: CGFloat.greatestFiniteMagnitude, y: 0, width: CGFloat.greatestFiniteMagnitude, height: 1),
        ] {
            XCTAssertNil(CaptureGeometry(revision: 1, bounds: bounds))
        }
    }
}

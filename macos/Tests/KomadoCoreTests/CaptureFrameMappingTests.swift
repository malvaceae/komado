import CoreGraphics
import XCTest
@testable import KomadoCore

final class CaptureFrameMappingTests: XCTestCase {
    func testLetterboxingMapsContentAndRejectsMargins() throws {
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 640, height: 640),
                                                        contentRect: CGRect(x: 0, y: 120, width: 640, height: 400)))
        XCTAssertNil(mapping.normalizedContentPoint(x: 0.5, y: 0.1))
        XCTAssertNil(mapping.normalizedContentPoint(x: 0.5, y: 0.9))
        XCTAssertEqual(mapping.normalizedContentPoint(x: 0.5, y: 0.5), CGPoint(x: 0.5, y: 0.5))
        XCTAssertEqual(mapping.normalizedContentPoint(x: 0, y: 0.1875), CGPoint(x: 0, y: 0))
        XCTAssertEqual(mapping.normalizedContentPoint(x: 1, y: 0.8125), CGPoint(x: 1, y: 1))
        XCTAssertNil(mapping.normalizedContentPoint(x: .nan, y: 0.5))
    }

    func testRejectsInvalidContentRectangles() {
        for rect in [CGRect(x: -1, y: 0, width: 10, height: 10), CGRect(x: 0, y: 0, width: 0, height: 10),
                     CGRect(x: 0, y: 0, width: 101, height: 10), CGRect(x: 0, y: 0, width: CGFloat.infinity, height: 10)] {
            XCTAssertNil(CaptureFrameMapping(frameSize: CGSize(width: 100, height: 100), contentRect: rect))
        }
    }
}

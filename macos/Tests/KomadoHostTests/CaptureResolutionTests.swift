import XCTest
@testable import KomadoHost

final class CaptureResolutionTests: XCTestCase {
    @MainActor
    func testDimensionsPreserveAspectAndDoNotUpscale() throws {
        let wide = try DisplayCapture.dimensions(width: 3840, height: 2160)
        XCTAssertEqual(wide.width, 1280)
        XCTAssertEqual(wide.height, 720)
        let tall = try DisplayCapture.dimensions(width: 800, height: 1000)
        XCTAssertEqual(tall.width, 576)
        XCTAssertEqual(tall.height, 720)
        let small = try DisplayCapture.dimensions(width: 640, height: 480)
        XCTAssertEqual(small.width, 640)
        XCTAssertEqual(small.height, 480)
        let reduced = try DisplayCapture.dimensions(width: 3840, height: 2160, resolution: .reduced)
        XCTAssertEqual(reduced.width, 852)
        XCTAssertEqual(reduced.height, 480)
        let reducedPortrait = try DisplayCapture.dimensions(width: 800, height: 1000, resolution: .reduced)
        XCTAssertEqual(reducedPortrait.width, 384)
        XCTAssertEqual(reducedPortrait.height, 480)
        let reducedSmall = try DisplayCapture.dimensions(width: 300, height: 200, resolution: .reduced)
        XCTAssertEqual(reducedSmall.width, 300)
        XCTAssertEqual(reducedSmall.height, 200)
        XCTAssertThrowsError(try DisplayCapture.dimensions(width: 0, height: 480))
    }

}

import XCTest
@testable import KomadoHost

final class CaptureResolutionTests: XCTestCase {
    @MainActor
    func testDimensionsPreserveAspectAndDoNotUpscale() throws {
        let wide = try DisplayCapture.dimensions(width: 3840, height: 2160)
        XCTAssertEqual(wide.width, 1280)
        XCTAssertEqual(wide.height, 720)
        let tall = try DisplayCapture.dimensions(width: 800, height: 1000)
        XCTAssertEqual(tall.width, 720)
        XCTAssertEqual(tall.height, 900)
        let portrait = try DisplayCapture.dimensions(width: 2160, height: 3840)
        XCTAssertEqual(portrait.width, 720)
        XCTAssertEqual(portrait.height, 1280)
        let small = try DisplayCapture.dimensions(width: 640, height: 480)
        XCTAssertEqual(small.width, 640)
        XCTAssertEqual(small.height, 480)
        let reduced = try DisplayCapture.dimensions(width: 3840, height: 2160, resolution: .reduced)
        XCTAssertEqual(reduced.width, 852)
        XCTAssertEqual(reduced.height, 480)
        let reducedPortrait = try DisplayCapture.dimensions(width: 800, height: 1000, resolution: .reduced)
        XCTAssertEqual(reducedPortrait.width, 480)
        XCTAssertEqual(reducedPortrait.height, 600)
        let reducedTall = try DisplayCapture.dimensions(width: 2160, height: 3840, resolution: .reduced)
        XCTAssertEqual(reducedTall.width, 480)
        XCTAssertEqual(reducedTall.height, 852)
        let reducedSmall = try DisplayCapture.dimensions(width: 300, height: 200, resolution: .reduced)
        XCTAssertEqual(reducedSmall.width, 300)
        XCTAssertEqual(reducedSmall.height, 200)
        XCTAssertThrowsError(try DisplayCapture.dimensions(width: 0, height: 480))
    }

}

import XCTest
@testable import KomadoHost

final class CaptureResolutionTests: XCTestCase {
    @MainActor
    func testDimensionsPreserveAspectAndDoNotUpscale() throws {
        let wide = try DisplayCapture.dimensions(width: 3840, height: 2160)
        XCTAssertEqual(wide.width, 1280)
        XCTAssertEqual(wide.height, 720)
        let tall = try DisplayCapture.dimensions(width: 800, height: 1000)
        XCTAssertEqual(tall.width, 800)
        XCTAssertEqual(tall.height, 1000)
        let portrait = try DisplayCapture.dimensions(width: 2160, height: 3840)
        XCTAssertEqual(portrait.width, 720)
        XCTAssertEqual(portrait.height, 1280)
        let small = try DisplayCapture.dimensions(width: 640, height: 480)
        XCTAssertEqual(small.width, 640)
        XCTAssertEqual(small.height, 480)
        let reduced = try DisplayCapture.dimensions(width: 3840, height: 2160, resolution: .reduced)
        XCTAssertEqual(reduced.width, 856)
        XCTAssertEqual(reduced.height, 480)
        let reducedPortrait = try DisplayCapture.dimensions(width: 800, height: 1000, resolution: .reduced)
        XCTAssertEqual(reducedPortrait.width, 576)
        XCTAssertEqual(reducedPortrait.height, 720)
        let reducedTall = try DisplayCapture.dimensions(width: 2160, height: 3840, resolution: .reduced)
        XCTAssertEqual(reducedTall.width, 480)
        XCTAssertEqual(reducedTall.height, 856)
        let reducedSmall = try DisplayCapture.dimensions(width: 300, height: 200, resolution: .reduced)
        XCTAssertEqual(reducedSmall.width, 300)
        XCTAssertEqual(reducedSmall.height, 200)
        XCTAssertThrowsError(try DisplayCapture.dimensions(width: 0, height: 480))
    }

    @MainActor
    func testDimensionsRespectMacroblockLimitsForDifferentAspectRatios() throws {
        for (width, height) in [(1280, 800), (4096, 4096), (4096, 1024), (1024, 4096), (1024, 768)] {
            let result = try DisplayCapture.dimensions(width: width, height: height)
            let blocksWide = (result.width + 15) / 16, blocksHigh = (result.height + 15) / 16
            XCTAssertLessThanOrEqual(blocksWide * blocksHigh, 3600)
            XCTAssertLessThanOrEqual(max(blocksWide, blocksHigh), 169)
            XCTAssertLessThanOrEqual(result.width, width)
            XCTAssertLessThanOrEqual(result.height, height)
            XCTAssertEqual(result.width % 2, 0)
            XCTAssertEqual(result.height % 2, 0)
            XCTAssertEqual(Double(result.width) / Double(result.height), Double(width) / Double(height), accuracy: 0.01)
        }
        let fourThree = try DisplayCapture.dimensions(width: 1024, height: 768)
        XCTAssertEqual(fourThree.width, 1024)
        XCTAssertEqual(fourThree.height, 768)
        let macDisplay = try DisplayCapture.dimensions(width: 1280, height: 800)
        XCTAssertGreaterThan(macDisplay.width, 1152)
        XCTAssertGreaterThan(macDisplay.height, 720)
        let reduced = try DisplayCapture.dimensions(width: 1280, height: 800, resolution: .reduced)
        XCTAssertLessThanOrEqual(((reduced.width + 15) / 16) * ((reduced.height + 15) / 16), 1620)
        XCTAssertGreaterThan(reduced.width, 768)
        XCTAssertGreaterThan(reduced.height, 480)
        let narrow = try DisplayCapture.dimensions(width: 2560, height: 32, resolution: .reduced)
        XCTAssertEqual(narrow.width, 2560)
        XCTAssertEqual(narrow.height, 32)
    }
}

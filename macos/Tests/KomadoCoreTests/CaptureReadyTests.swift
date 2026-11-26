import Foundation
import XCTest
@testable import KomadoCore

final class CaptureReadyTests: XCTestCase {
    func testSharedMessages() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/capture-ready.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String]])
        for value in try XCTUnwrap(fixture["valid"]) {
            let message = try CaptureReady.decode(Data(value.utf8))
            XCTAssertEqual(try CaptureReady.decode(message.encode()), message)
        }
        for value in try XCTUnwrap(fixture["invalid"]) { XCTAssertThrowsError(try CaptureReady.decode(Data(value.utf8))) }
        XCTAssertThrowsError(try CaptureReady(revision: 0).encode())
    }
}

import Foundation
import XCTest
@testable import KomadoCore

final class ClipboardMessageTests: XCTestCase {
    func testSharedRequestsAndResponses() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/clipboard-messages.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String: [String]]])
        for raw in try XCTUnwrap(fixture["requests"]?["valid"]) {
            let value = try ClipboardRequest.decode(Data(raw.utf8))
            XCTAssertEqual(try ClipboardRequest.decode(value.encode()), value)
        }
        for raw in try XCTUnwrap(fixture["requests"]?["invalid"]) { XCTAssertThrowsError(try ClipboardRequest.decode(Data(raw.utf8))) }
        for raw in try XCTUnwrap(fixture["responses"]?["valid"]) {
            let value = try ClipboardResponse.decode(Data(raw.utf8))
            XCTAssertEqual(try ClipboardResponse.decode(value.encode()), value)
        }
        for raw in try XCTUnwrap(fixture["responses"]?["invalid"]) { XCTAssertThrowsError(try ClipboardResponse.decode(Data(raw.utf8))) }
        XCTAssertNoThrow(try ClipboardRequest(sequence: 1, captureRevision: 1, operation: .set(String(repeating: "a", count: 16_384))).encode())
        XCTAssertThrowsError(try ClipboardRequest(sequence: 1, captureRevision: 1, operation: .set(String(repeating: "a", count: 16_385))).encode())
        XCTAssertThrowsError(try ClipboardResponse(sequence: 1, result: .text(String(repeating: "a", count: 16_385))).encode())
        XCTAssertThrowsError(try ClipboardResponse.decode(Data(repeating: 0, count: 65_537)))
    }
}

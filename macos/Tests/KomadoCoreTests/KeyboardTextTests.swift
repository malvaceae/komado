import Foundation
import XCTest
@testable import KomadoCore

final class KeyboardTextTests: XCTestCase {
    func testSharedMessagesAndLimits() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/keyboard-text.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String]])
        let valid = try XCTUnwrap(fixture["valid"]), invalid = try XCTUnwrap(fixture["invalid"])
        XCTAssertFalse(valid.isEmpty)
        XCTAssertFalse(invalid.isEmpty)
        for value in valid {
            let message = try KeyboardText.decode(Data(value.utf8))
            XCTAssertEqual(try KeyboardText.decode(message.encode()), message)
        }
        for value in invalid { XCTAssertThrowsError(try KeyboardText.decode(Data(value.utf8))) }
        XCTAssertTrue(try KeyboardText.decode(Data("[\"1\",\"keyboard.text.checked\",\"2\",\"1\",\"a\"]".utf8)).confirm)
        XCTAssertFalse(try KeyboardText.decode(Data(valid[0].utf8)).confirm)
        XCTAssertNoThrow(try KeyboardText(sequence: 1, captureRevision: 1, text: String(repeating: "あ", count: 170)).encode())
        XCTAssertThrowsError(try KeyboardText(sequence: 1, captureRevision: 1, text: String(repeating: "あ", count: 171)).encode())
        XCTAssertThrowsError(try KeyboardText.decode(Data(repeating: 0, count: 2049)))
    }

    func testSharedPostingConfirmations() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/keyboard-text-posted.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String]])
        for value in try XCTUnwrap(fixture["valid"]) {
            let message = try KeyboardTextPosted.decode(Data(value.utf8))
            XCTAssertEqual(try KeyboardTextPosted.decode(message.encode()), message)
        }
        for value in try XCTUnwrap(fixture["invalid"]) { XCTAssertThrowsError(try KeyboardTextPosted.decode(Data(value.utf8))) }
        XCTAssertThrowsError(try KeyboardTextPosted(sequence: 0).encode())
        XCTAssertThrowsError(try KeyboardTextPosted.decode(Data(repeating: 0, count: 257)))
    }
}

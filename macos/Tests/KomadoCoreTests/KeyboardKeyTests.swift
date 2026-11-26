import Foundation
import XCTest
@testable import KomadoCore

final class KeyboardKeyTests: XCTestCase {
    func testSharedMessagesAndLimits() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/keyboard-key.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String]])
        let valid = try XCTUnwrap(fixture["valid"]), invalid = try XCTUnwrap(fixture["invalid"])
        XCTAssertFalse(valid.isEmpty)
        XCTAssertFalse(invalid.isEmpty)
        for value in valid {
            let message = try KeyboardKey.decode(Data(value.utf8))
            XCTAssertEqual(try KeyboardKey.decode(message.encode()), message)
        }
        for value in invalid { XCTAssertThrowsError(try KeyboardKey.decode(Data(value.utf8))) }
        for key in KeyboardKey.Key.allCases {
            let message = KeyboardKey(sequence: 1, captureRevision: 2, key: key, modifiers: 15)
            XCTAssertEqual(try KeyboardKey.decode(message.encode()), message)
        }
        XCTAssertThrowsError(try KeyboardKey(sequence: 1, captureRevision: 1, key: .tab, modifiers: 16).encode())
        XCTAssertThrowsError(try KeyboardKey.decode(Data(repeating: 0, count: 1025)))
    }
}

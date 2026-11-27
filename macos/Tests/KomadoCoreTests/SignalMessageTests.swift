import Foundation
import XCTest
@testable import KomadoCore

final class SignalMessageTests: XCTestCase {
    func testSharedValidAndInvalidMessages() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/signal-messages.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String]])
        let valid = try XCTUnwrap(fixture["valid"]), invalid = try XCTUnwrap(fixture["invalid"])
        XCTAssertFalse(valid.isEmpty)
        XCTAssertFalse(invalid.isEmpty)
        for value in valid {
            let message = try SignalMessage.decode(Data(value.utf8))
            XCTAssertEqual(try SignalMessage.decode(message.encode()), message)
        }
        for (index, value) in invalid.enumerated() {
            XCTAssertThrowsError(try SignalMessage.decode(Data(value.utf8)), "Invalid fixture \(index)")
        }
    }

    func testRejectsInvalidValuesAtEncodingAndMalformedBytes() throws {
        XCTAssertThrowsError(try SignalMessage.challenge(Data(repeating: 0, count: 31)).encode())
        XCTAssertThrowsError(try SignalMessage.proof(Data()).encode())
        XCTAssertThrowsError(try SignalMessage.pair(name: "Android", invitation: Data(repeating: 0, count: 32), publicKeyDER: Data()).encode())
        XCTAssertThrowsError(try SignalMessage.decode(Data([0xff])))
        XCTAssertThrowsError(try SignalMessage.decode(Data(repeating: 0x20, count: SignalFrame.maximumPayload + 1)))
        XCTAssertThrowsError(try SignalMessage.features(Array(repeating: "pointer.click", count: 2)).encode())
        XCTAssertThrowsError(try SignalMessage.features((0...32).map { "feature.\($0)" }).encode())
    }

    func testDisplayListBoundariesAndDuplicateIDs() throws {
        let displays = (1...64).map { DisplayInfo(id: UInt32($0), width: 1920, height: 1080) }
        let message = SignalMessage.displays(displays)
        XCTAssertEqual(try SignalMessage.decode(message.encode()), message)
        XCTAssertThrowsError(try SignalMessage.displays([]).encode())
        XCTAssertThrowsError(try SignalMessage.displays(displays + [DisplayInfo(id: 65, width: 1, height: 1)]).encode())
        XCTAssertThrowsError(try SignalMessage.displays([displays[0], displays[0]]).encode())
        XCTAssertThrowsError(try SignalMessage.displays([DisplayInfo(id: 1, width: 65_536, height: 1)]).encode())
        XCTAssertThrowsError(try SignalMessage.startDisplay(0).encode())
    }

    func testWindowPageBoundariesAndLabels() throws {
        let windows = (1...32).map { WindowInfo(id: UInt32($0), width: 800, height: 600,
                                                  application: String(repeating: "ア", count: 32), title: "ウインドウ") }
        let page = SignalMessage.windows(next: 32, values: windows)
        XCTAssertEqual(try SignalMessage.decode(page.encode()), page)
        XCTAssertEqual(try SignalMessage.decode(SignalMessage.windows(next: 0, values: []).encode()), .windows(next: 0, values: []))
        XCTAssertThrowsError(try SignalMessage.windows(next: 1, values: []).encode())
        XCTAssertThrowsError(try SignalMessage.windows(next: 0, values: windows + [windows[0]]).encode())
        XCTAssertThrowsError(try SignalMessage.windows(next: 0, values: [windows[0], windows[0]]).encode())
        XCTAssertThrowsError(try SignalMessage.windows(next: 0, values: [WindowInfo(id: 1, width: 1, height: 1,
                                                                                    application: "a", title: String(repeating: "あ", count: 33))]).encode())
        XCTAssertThrowsError(try SignalMessage.listWindows(513).encode())
    }
}

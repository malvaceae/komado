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
}

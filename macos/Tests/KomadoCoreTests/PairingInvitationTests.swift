import Foundation
import XCTest
@testable import KomadoCore

final class PairingInvitationTests: XCTestCase {
    func testSharedMessagesAndLimits() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/pairing-invitations.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: [String]])
        let valid = try XCTUnwrap(fixture["valid"]), invalid = try XCTUnwrap(fixture["invalid"])
        XCTAssertFalse(valid.isEmpty)
        XCTAssertFalse(invalid.isEmpty)
        for value in valid {
            let invitation = try PairingInvitation.decode(Data(value.utf8))
            XCTAssertEqual(try PairingInvitation.decode(invitation.encode()), invitation)
        }
        for value in invalid { XCTAssertThrowsError(try PairingInvitation.decode(Data(value.utf8))) }
        XCTAssertThrowsError(try PairingInvitation.decode(Data(repeating: 0, count: 1025)))
    }
}

import CryptoKit
import Foundation
import XCTest
@testable import KomadoCore

final class DeviceSignatureVerifierTests: XCTestCase {
    func testSharedFixtureAndTamperedChallenge() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/device-auth-valid.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
        let publicKey = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(fixture["publicKeyDer"])))
        let signature = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(fixture["signatureDer"])))
        var challenge = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(fixture["challenge"])))
        XCTAssertTrue(DeviceSignatureVerifier.verify(publicKeyDER: publicKey, challenge: challenge, signatureDER: signature))
        challenge[0] ^= 1
        XCTAssertFalse(DeviceSignatureVerifier.verify(publicKeyDER: publicKey, challenge: challenge, signatureDER: signature))
    }

    func testRejectsMalformedInputsAndWrongKey() throws {
        let key = P256.Signing.PrivateKey()
        let other = P256.Signing.PrivateKey()
        let challenge = Data(repeating: 2, count: 32)
        var message = Data("komado.device-auth.v1\0".utf8)
        message.append(challenge)
        let signature = try key.signature(for: message).derRepresentation
        XCTAssertFalse(DeviceSignatureVerifier.verify(publicKeyDER: other.publicKey.derRepresentation, challenge: challenge, signatureDER: signature))
        XCTAssertFalse(DeviceSignatureVerifier.verify(publicKeyDER: key.publicKey.derRepresentation, challenge: Data(), signatureDER: signature))
        XCTAssertFalse(DeviceSignatureVerifier.verify(publicKeyDER: Data(), challenge: challenge, signatureDER: signature))
        XCTAssertFalse(DeviceSignatureVerifier.verify(publicKeyDER: Data(repeating: 1, count: 129), challenge: challenge, signatureDER: signature))
        XCTAssertFalse(DeviceSignatureVerifier.verify(publicKeyDER: key.publicKey.derRepresentation, challenge: challenge, signatureDER: Data(repeating: 1, count: 73)))
        XCTAssertFalse(DeviceSignatureVerifier.verify(publicKeyDER: key.publicKey.derRepresentation, challenge: challenge, signatureDER: Data([0])))
    }
}

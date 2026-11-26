import CryptoKit
import Foundation
import Security
import XCTest
@testable import KomadoCore

final class ServerHandshakeTests: XCTestCase {
    private func sign(_ key: P256.Signing.PrivateKey, _ challenge: Data) throws -> Data {
        try key.signature(for: Data("komado.device-auth.v1\0".utf8) + challenge).derRepresentation
    }

    private func clean(_ service: String) {
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary)
    }

    func testPairAndReconnectUsingRegisteredKey() async throws {
        let service = "me.mallows.komado.test.handshake.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service)
        let pairing = PairingCoordinator(registry: registry, now: { .zero })
        let auth = DeviceAuthenticationCoordinator(registry: registry, now: { .zero })
        let key = P256.Signing.PrivateKey(), token = try await pairing.issueInvitation()
        let first = ServerHandshake(sessionID: UUID(), pairing: pairing, authentication: auth)
        let initial = await first.receive(.pair(name: "Android", invitation: token, publicKeyDER: key.publicKey.derRepresentation))
        guard case let .challenge(challenge) = initial else { return XCTFail("Expected challenge") }
        let result = await first.receive(.proof(try sign(key, challenge)))
        guard case let .accepted(id) = result else { return XCTFail("Expected acceptance") }
        let registered = try XCTUnwrap(registry.find(id: id))
        XCTAssertEqual(registered.publicKeyDER, key.publicKey.derRepresentation)
        let firstDevice = await first.authenticatedDevice()
        XCTAssertEqual(firstDevice, registered)
        await first.disconnect()

        let second = ServerHandshake(sessionID: UUID(), pairing: pairing, authentication: auth)
        let reconnect = await second.receive(.authenticate(deviceID: id))
        guard case let .challenge(secondChallenge) = reconnect else { return XCTFail("Expected challenge") }
        let response = await second.receive(.proof(try sign(key, secondChallenge)))
        XCTAssertEqual(response, .accepted(deviceID: id))
        await second.disconnect()
    }

    func testUnexpectedMessagesAndWrongProofCloseSession() async throws {
        let service = "me.mallows.komado.test.handshake.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service)
        let pairing = PairingCoordinator(registry: registry, now: { .zero })
        let auth = DeviceAuthenticationCoordinator(registry: registry, now: { .zero })
        let key = P256.Signing.PrivateKey(), token = try await pairing.issueInvitation()
        let session = ServerHandshake(sessionID: UUID(), pairing: pairing, authentication: auth)
        let result = await session.receive(.proof(Data([1])))
        XCTAssertEqual(result, .rejected)
        let closed = await session.receive(.pair(name: "Android", invitation: token, publicKeyDER: key.publicKey.derRepresentation))
        XCTAssertNil(closed)
        XCTAssertEqual(try registry.all(), [])

        let second = ServerHandshake(sessionID: UUID(), pairing: pairing, authentication: auth)
        let begin = await second.receive(.pair(name: "Android", invitation: token, publicKeyDER: key.publicKey.derRepresentation))
        guard case let .challenge(challenge) = begin else { return XCTFail("Expected challenge") }
        let bad = await second.receive(.proof(try sign(P256.Signing.PrivateKey(), challenge)))
        XCTAssertEqual(bad, .rejected)
        let retry = await second.receive(.proof(try sign(key, challenge)))
        XCTAssertNil(retry)
        let authenticated = await second.authenticatedDevice()
        XCTAssertNil(authenticated)
        XCTAssertEqual(try registry.all(), [])
    }

    func testDisconnectInvalidatesPendingProof() async throws {
        let service = "me.mallows.komado.test.handshake.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service)
        let pairing = PairingCoordinator(registry: registry, now: { .zero })
        let auth = DeviceAuthenticationCoordinator(registry: registry, now: { .zero })
        let key = P256.Signing.PrivateKey(), token = try await pairing.issueInvitation()
        let session = ServerHandshake(sessionID: UUID(), pairing: pairing, authentication: auth)
        let initial = await session.receive(.pair(name: "Android", invitation: token, publicKeyDER: key.publicKey.derRepresentation))
        guard case let .challenge(challenge) = initial else { return XCTFail("Expected challenge") }
        await session.disconnect()
        let result = await session.receive(.proof(try sign(key, challenge)))
        XCTAssertNil(result)
        XCTAssertEqual(try registry.all(), [])
    }
}

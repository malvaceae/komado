import CryptoKit
import Foundation
import Security
import XCTest
@testable import KomadoCore

private final class AuthenticationTestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Duration = .zero
    func now() -> Duration { lock.withLock { value } }
    func advance(_ duration: Duration) { lock.withLock { value += duration } }
}

final class DeviceAuthenticationCoordinatorTests: XCTestCase {
    private func signature(_ key: P256.Signing.PrivateKey, _ challenge: Data) throws -> Data {
        try key.signature(for: Data("komado.device-auth.v1\0".utf8) + challenge).derRepresentation
    }

    private func clean(_ service: String) {
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary)
    }

    func testOnlyRegisteredKeyCanAuthenticateOnce() async throws {
        let service = "me.mallows.komado.test.auth.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service), clock = AuthenticationTestClock()
        let auth = DeviceAuthenticationCoordinator(registry: registry, now: { clock.now() })
        let key = P256.Signing.PrivateKey()
        let device = PairedDevice(id: UUID(), name: "Android", publicKeyDER: key.publicKey.derRepresentation)
        let session = UUID()
        let missing = try await auth.issue(sessionID: session, deviceID: device.id)
        XCTAssertNil(missing)
        try registry.add(device)
        let challengeValue = try await auth.issue(sessionID: session, deviceID: device.id)
        let challenge = try XCTUnwrap(challengeValue)
        let signed = try signature(key, challenge)
        async let first = auth.authenticate(sessionID: session, signatureDER: signed)
        async let second = auth.authenticate(sessionID: session, signatureDER: signed)
        let results = try await [first, second].compactMap { $0 }
        XCTAssertEqual(results, [device])
    }

    func testWrongSignatureAndRevocationInvalidateAttempts() async throws {
        let service = "me.mallows.komado.test.auth.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service), clock = AuthenticationTestClock()
        let auth = DeviceAuthenticationCoordinator(registry: registry, now: { clock.now() })
        let key = P256.Signing.PrivateKey()
        let device = PairedDevice(id: UUID(), name: "Android", publicKeyDER: key.publicKey.derRepresentation)
        try registry.add(device)
        let invalidSession = UUID(), cancelledSession = UUID(), revokedSession = UUID()
        let invalidValue = try await auth.issue(sessionID: invalidSession, deviceID: device.id)
        let cancelledValue = try await auth.issue(sessionID: cancelledSession, deviceID: device.id)
        let revokedValue = try await auth.issue(sessionID: revokedSession, deviceID: device.id)
        let invalidChallenge = try XCTUnwrap(invalidValue)
        let cancelledChallenge = try XCTUnwrap(cancelledValue)
        let revokedChallenge = try XCTUnwrap(revokedValue)
        let rejected = try await auth.authenticate(sessionID: invalidSession, signatureDER: signature(P256.Signing.PrivateKey(), invalidChallenge))
        let replayed = try await auth.authenticate(sessionID: invalidSession, signatureDER: signature(key, invalidChallenge))
        XCTAssertNil(rejected)
        XCTAssertNil(replayed)
        await auth.cancel(sessionID: cancelledSession)
        let cancelled = try await auth.authenticate(sessionID: cancelledSession, signatureDER: signature(key, cancelledChallenge))
        XCTAssertNil(cancelled)
        let removed = try await auth.removeDevice(id: device.id)
        XCTAssertTrue(removed)
        let revoked = try await auth.authenticate(sessionID: revokedSession, signatureDER: signature(key, revokedChallenge))
        let unavailable = try await auth.issue(sessionID: UUID(), deviceID: device.id)
        XCTAssertNil(revoked)
        XCTAssertNil(unavailable)
    }

    func testExpiryAndExternalRemovalAreRejected() async throws {
        let service = "me.mallows.komado.test.auth.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service), clock = AuthenticationTestClock()
        let auth = DeviceAuthenticationCoordinator(registry: registry, now: { clock.now() })
        let key = P256.Signing.PrivateKey()
        let device = PairedDevice(id: UUID(), name: "Android", publicKeyDER: key.publicKey.derRepresentation)
        try registry.add(device)
        let expiredSession = UUID()
        let expiredValue = try await auth.issue(sessionID: expiredSession, deviceID: device.id)
        let challenge = try XCTUnwrap(expiredValue)
        clock.advance(.seconds(30))
        let expired = try await auth.authenticate(sessionID: expiredSession, signatureDER: signature(key, challenge))
        XCTAssertNil(expired)
        let removedSession = UUID()
        let secondValue = try await auth.issue(sessionID: removedSession, deviceID: device.id)
        let secondChallenge = try XCTUnwrap(secondValue)
        XCTAssertTrue(try registry.remove(id: device.id))
        let removed = try await auth.authenticate(sessionID: removedSession, signatureDER: signature(key, secondChallenge))
        XCTAssertNil(removed)
    }
}

import CryptoKit
import Foundation
import Security
import XCTest
@testable import KomadoCore

private final class PairingTestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Duration = .zero
    func now() -> Duration { lock.withLock { value } }
    func advance(_ duration: Duration) { lock.withLock { value += duration } }
}

final class PairingCoordinatorTests: XCTestCase {
    private func signature(_ key: P256.Signing.PrivateKey, challenge: Data) throws -> Data {
        try key.signature(for: Data("komado.device-auth.v1\0".utf8) + challenge).derRepresentation
    }

    private func begin(_ pairing: PairingCoordinator, session: UUID, invitation: Data, key: P256.Signing.PrivateKey) async throws -> Data {
        let result = try await pairing.begin(sessionID: session, invitation: invitation,
                                             name: "Android", publicKeyDER: key.publicKey.derRepresentation)
        return try XCTUnwrap(result)
    }

    private func clean(_ service: String) {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        SecItemDelete(query as CFDictionary)
    }

    func testInvitationAndProofRegisterExactlyOnce() async throws {
        let service = "me.mallows.komado.test.pair.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service), clock = PairingTestClock()
        let pairing = PairingCoordinator(registry: registry, now: { clock.now() })
        let key = P256.Signing.PrivateKey(), session = UUID()
        let invitation = try await pairing.issueInvitation()
        let challenge = try await begin(pairing, session: session, invitation: invitation, key: key)
        XCTAssertEqual(try registry.all(), [])
        let signed = try signature(key, challenge: challenge)
        async let first = pairing.finish(sessionID: session, signatureDER: signed)
        async let second = pairing.finish(sessionID: session, signatureDER: signed)
        let results = try await [first, second].compactMap { $0 }
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(try registry.all(), results)
        let retry = try await pairing.begin(sessionID: UUID(), invitation: invitation,
                                            name: "Android", publicKeyDER: key.publicKey.derRepresentation)
        XCTAssertNil(retry)
    }

    func testBadProofCancellationAndReissueDoNotRegister() async throws {
        let service = "me.mallows.komado.test.pair.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service), clock = PairingTestClock()
        let pairing = PairingCoordinator(registry: registry, now: { clock.now() })
        let key = P256.Signing.PrivateKey(), session = UUID()
        var invitation = try await pairing.issueInvitation()
        var challenge = try await begin(pairing, session: session, invitation: invitation, key: key)
        let invalid = try signature(P256.Signing.PrivateKey(), challenge: challenge)
        let rejected = try await pairing.finish(sessionID: session, signatureDER: invalid)
        let retried = try await pairing.finish(sessionID: session, signatureDER: signature(key, challenge: challenge))
        XCTAssertNil(rejected)
        XCTAssertNil(retried)
        invitation = try await pairing.issueInvitation()
        challenge = try await begin(pairing, session: session, invitation: invitation, key: key)
        await pairing.cancel(sessionID: session)
        let cancelled = try await pairing.finish(sessionID: session, signatureDER: signature(key, challenge: challenge))
        XCTAssertNil(cancelled)
        invitation = try await pairing.issueInvitation()
        challenge = try await begin(pairing, session: session, invitation: invitation, key: key)
        _ = try await pairing.issueInvitation()
        let superseded = try await pairing.finish(sessionID: session, signatureDER: signature(key, challenge: challenge))
        XCTAssertNil(superseded)
        XCTAssertEqual(try registry.all(), [])
    }

    func testExpiredAttemptIsRejected() async throws {
        let service = "me.mallows.komado.test.pair.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service), clock = PairingTestClock()
        let pairing = PairingCoordinator(registry: registry, now: { clock.now() })
        let key = P256.Signing.PrivateKey(), session = UUID()
        let invitation = try await pairing.issueInvitation()
        let challenge = try await begin(pairing, session: session, invitation: invitation, key: key)
        clock.advance(.seconds(30))
        let expired = try await pairing.finish(sessionID: session, signatureDER: signature(key, challenge: challenge))
        XCTAssertNil(expired)
        XCTAssertEqual(try registry.all(), [])
    }

    func testConcurrentAttemptsCannotReuseInvitation() async throws {
        let service = "me.mallows.komado.test.pair.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service), clock = PairingTestClock()
        let pairing = PairingCoordinator(registry: registry, now: { clock.now() })
        let key = P256.Signing.PrivateKey(), keyDER = key.publicKey.derRepresentation
        let invitation = try await pairing.issueInvitation()
        let firstSession = UUID(), secondSession = UUID()
        async let first = pairing.begin(sessionID: firstSession, invitation: invitation, name: "Android", publicKeyDER: keyDER)
        async let second = pairing.begin(sessionID: secondSession, invitation: invitation, name: "Android", publicKeyDER: keyDER)
        let results = try await [first, second]
        XCTAssertEqual(results.compactMap { $0 }.count, 1)
        let session = results[0] == nil ? secondSession : firstSession
        let challenge = try XCTUnwrap(results.compactMap { $0 }.first)
        let device = try await pairing.finish(sessionID: session, signatureDER: signature(key, challenge: challenge))
        XCTAssertNotNil(device)
        XCTAssertEqual(try registry.all().count, 1)
    }

    func testCancellingInvitationRejectsPendingAndUnusedTokens() async throws {
        let service = "me.mallows.komado.test.pair.\(UUID().uuidString)"
        defer { clean(service) }
        let registry = PairedDeviceRegistry(service: service), clock = PairingTestClock()
        let pairing = PairingCoordinator(registry: registry, now: { clock.now() })
        let key = P256.Signing.PrivateKey(), session = UUID()
        let token = try await pairing.issueInvitation()
        let challenge = try await begin(pairing, session: session, invitation: token, key: key)
        try await pairing.cancelInvitation()
        let result = try await pairing.finish(sessionID: session, signatureDER: signature(key, challenge: challenge))
        XCTAssertNil(result)
        let unused = try await pairing.issueInvitation()
        try await pairing.cancelInvitation()
        let rejected = try await pairing.begin(sessionID: UUID(), invitation: unused,
                                                name: "Android", publicKeyDER: key.publicKey.derRepresentation)
        XCTAssertNil(rejected)
        XCTAssertEqual(try registry.all(), [])
    }
}

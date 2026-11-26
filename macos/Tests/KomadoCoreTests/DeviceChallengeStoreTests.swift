import CryptoKit
import Foundation
import XCTest
@testable import KomadoCore

private final class TestTime: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Duration = .zero
    func now() -> Duration { lock.withLock { value } }
    func advance(_ duration: Duration) { lock.withLock { value += duration } }
}

final class DeviceChallengeStoreTests: XCTestCase {
    private func signature(_ key: P256.Signing.PrivateKey, challenge: Data) throws -> Data {
        var message = Data("komado.device-auth.v1\0".utf8)
        message.append(challenge)
        return try key.signature(for: message).derRepresentation
    }

    func testValidSignatureIsBoundToSessionAndConsumedOnce() async throws {
        let clock = TestTime()
        let store = DeviceChallengeStore(now: { clock.now() }, makeChallenge: { Data(repeating: 3, count: 32) })
        let key = P256.Signing.PrivateKey(), device = UUID(), session = UUID()
        let challenge = try await store.issue(sessionID: session, deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
        let sig = try signature(key, challenge: challenge)
        let otherSession = await store.verifyAndConsume(sessionID: UUID(), signatureDER: sig)
        let accepted = await store.verifyAndConsume(sessionID: session, signatureDER: sig)
        let replayed = await store.verifyAndConsume(sessionID: session, signatureDER: sig)
        XCTAssertNil(otherSession)
        XCTAssertEqual(accepted, device)
        XCTAssertNil(replayed)
    }

    func testConcurrentVerificationAcceptsOnlyOnce() async throws {
        let clock = TestTime()
        let store = DeviceChallengeStore(now: { clock.now() }, makeChallenge: { Data(repeating: 7, count: 32) })
        let key = P256.Signing.PrivateKey(), session = UUID(), device = UUID()
        let challenge = try await store.issue(sessionID: session, deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
        let sig = try signature(key, challenge: challenge)
        async let first = store.verifyAndConsume(sessionID: session, signatureDER: sig)
        async let second = store.verifyAndConsume(sessionID: session, signatureDER: sig)
        let results = await [first, second]
        XCTAssertEqual(results.filter { $0 == device }.count, 1)
    }

    func testProductionRandomChallengesAreDistinct() async throws {
        let store = DeviceChallengeStore()
        let key = P256.Signing.PrivateKey(), device = UUID()
        let first = try await store.issue(sessionID: UUID(), deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
        let second = try await store.issue(sessionID: UUID(), deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
        XCTAssertEqual(first.count, 32)
        XCTAssertEqual(second.count, 32)
        XCTAssertNotEqual(first, second)
    }

    func testInvalidSignatureConsumesChallenge() async throws {
        let clock = TestTime()
        let store = DeviceChallengeStore(now: { clock.now() }, makeChallenge: { Data(repeating: 4, count: 32) })
        let key = P256.Signing.PrivateKey(), session = UUID()
        let challenge = try await store.issue(sessionID: session, deviceID: UUID(), registeredPublicKeyDER: key.publicKey.derRepresentation)
        let wrong = try signature(P256.Signing.PrivateKey(), challenge: challenge)
        let correct = try signature(key, challenge: challenge)
        let rejected = await store.verifyAndConsume(sessionID: session, signatureDER: wrong)
        let retry = await store.verifyAndConsume(sessionID: session, signatureDER: correct)
        XCTAssertNil(rejected)
        XCTAssertNil(retry)
    }

    func testExpiryDisconnectAndRevocationInvalidateChallenges() async throws {
        let clock = TestTime()
        let store = DeviceChallengeStore(now: { clock.now() }, makeChallenge: { Data(repeating: 5, count: 32) })
        let key = P256.Signing.PrivateKey(), device = UUID()
        let expired = UUID(), disconnected = UUID(), revoked = UUID()
        let challenge = try await store.issue(sessionID: expired, deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
        _ = try await store.issue(sessionID: disconnected, deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
        _ = try await store.issue(sessionID: revoked, deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
        let sig = try signature(key, challenge: challenge)
        await store.invalidate(sessionID: disconnected)
        let disconnectedResult = await store.verifyAndConsume(sessionID: disconnected, signatureDER: sig)
        clock.advance(.seconds(30))
        let expiredResult = await store.verifyAndConsume(sessionID: expired, signatureDER: sig)
        // New challenge is still live when the device is revoked.
        _ = try await store.issue(sessionID: revoked, deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
        await store.invalidate(deviceID: device)
        let revokedResult = await store.verifyAndConsume(sessionID: revoked, signatureDER: sig)
        XCTAssertNil(disconnectedResult)
        XCTAssertNil(expiredResult)
        XCTAssertNil(revokedResult)
    }

    func testDuplicateSessionCapacityAndInvalidKeyAreRejected() async throws {
        let clock = TestTime()
        let store = DeviceChallengeStore(now: { clock.now() }, makeChallenge: { Data(repeating: 6, count: 32) })
        let key = P256.Signing.PrivateKey(), session = UUID(), device = UUID()
        do {
            _ = try await store.issue(sessionID: session, deviceID: device, registeredPublicKeyDER: Data())
            XCTFail("Invalid key accepted")
        } catch { XCTAssertEqual(error as? DeviceChallengeStore.IssueError, .invalidPublicKey) }
        _ = try await store.issue(sessionID: session, deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
        do {
            _ = try await store.issue(sessionID: session, deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
            XCTFail("Duplicate session accepted")
        } catch { XCTAssertEqual(error as? DeviceChallengeStore.IssueError, .sessionAlreadyPending) }
        for _ in 0..<31 {
            _ = try await store.issue(sessionID: UUID(), deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
        }
        do {
            _ = try await store.issue(sessionID: UUID(), deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
            XCTFail("Capacity limit not enforced")
        } catch { XCTAssertEqual(error as? DeviceChallengeStore.IssueError, .capacityReached) }
        clock.advance(.seconds(30))
        _ = try await store.issue(sessionID: UUID(), deviceID: device, registeredPublicKeyDER: key.publicKey.derRepresentation)
    }
}

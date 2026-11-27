import CryptoKit
import Foundation
import Security

/// Tracks outstanding device-authentication challenges for local host sessions.
/// Callers must supply a server-created session ID and a public key from the
/// paired-device registry over a host-authenticated TLS session. Registry and
/// TLS handling are outside this type. A pairing coordinator may instead use a
/// candidate key after checking an invitation, solely to prove possession;
/// the key must not become trusted until that proof succeeds.
public actor DeviceChallengeStore {
    public enum IssueError: Error, Equatable {
        case invalidPublicKey
        case sessionAlreadyPending
        case capacityReached
        case randomGenerationFailed
    }

    private struct Pending {
        let deviceID: UUID
        let publicKeyDER: Data
        let challenge: Data
        let expiresAt: Duration
    }

    private let now: @Sendable () -> Duration
    private let makeChallenge: @Sendable () throws -> Data
    private var pending: [UUID: Pending] = [:]
    private static let lifetime: Duration = .seconds(30)
    private static let capacity = 32

    public init() {
        let clock = ContinuousClock()
        let start = clock.now
        self.now = { start.duration(to: clock.now) }
        self.makeChallenge = {
            var bytes = [UInt8](repeating: 0, count: 32)
            guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
                throw IssueError.randomGenerationFailed
            }
            return Data(bytes)
        }
    }

    // Injected only by tests; production always uses the monotonic clock and
    // Security.framework's random number generator above.
    init(now: @escaping @Sendable () -> Duration, makeChallenge: @escaping @Sendable () throws -> Data) {
        self.now = now
        self.makeChallenge = makeChallenge
    }

    public func issue(sessionID: UUID, deviceID: UUID, registeredPublicKeyDER: Data) throws -> Data {
        guard (1...128).contains(registeredPublicKeyDER.count),
              (try? P256.Signing.PublicKey(derRepresentation: registeredPublicKeyDER)) != nil else {
            throw IssueError.invalidPublicKey
        }
        let currentTime = now()
        pending = pending.filter { $0.value.expiresAt > currentTime }
        guard pending[sessionID] == nil else { throw IssueError.sessionAlreadyPending }
        guard pending.count < Self.capacity else { throw IssueError.capacityReached }
        let challenge = try makeChallenge()
        guard challenge.count == 32 else { throw IssueError.randomGenerationFailed }
        pending[sessionID] = Pending(deviceID: deviceID, publicKeyDER: registeredPublicKeyDER,
                                     challenge: challenge, expiresAt: currentTime + Self.lifetime)
        return challenge
    }

    /// Consumes the challenge even when verification fails. Nil means the
    /// session has no live challenge or the supplied signature is invalid.
    public func verifyAndConsume(sessionID: UUID, signatureDER: Data) -> UUID? {
        guard let entry = pending.removeValue(forKey: sessionID), entry.expiresAt > now(),
              DeviceSignatureVerifier.verify(publicKeyDER: entry.publicKeyDER,
                                             challenge: entry.challenge, signatureDER: signatureDER) else {
            return nil
        }
        return entry.deviceID
    }

    public func invalidate(sessionID: UUID) {
        pending.removeValue(forKey: sessionID)
    }

}

import CryptoKit
import Foundation
import Security

/// Holds one explicit, short-lived pairing invitation. Transport authentication,
/// device registration, and persistent storage are the caller's responsibility.
public actor PairingTokenStore {
    public enum IssueError: Error, Equatable {
        case randomGenerationFailed
    }

    private struct Pending {
        let authenticator: HMAC<SHA256>.MAC
        let expiresAt: Duration
        var failures: Int
    }

    private static let domain = Data("komado.pairing-token.v1\0".utf8)
    private static let lifetime: Duration = .seconds(120)
    private static let maximumFailures = 5
    private let now: @Sendable () -> Duration
    private let makeToken: @Sendable () throws -> Data
    private var pending: Pending?

    public init() {
        let clock = ContinuousClock()
        let start = clock.now
        self.now = { start.duration(to: clock.now) }
        self.makeToken = {
            var bytes = [UInt8](repeating: 0, count: 32)
            guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
                throw IssueError.randomGenerationFailed
            }
            return Data(bytes)
        }
    }

    init(now: @escaping @Sendable () -> Duration, makeToken: @escaping @Sendable () throws -> Data) {
        self.now = now
        self.makeToken = makeToken
    }

    /// Invalidates any previous invitation, including when issuing fails.
    /// The returned token must be shared
    /// only through the explicit pairing flow, never through logs.
    public func issue() throws -> Data {
        pending = nil
        let token = try makeToken()
        guard token.count == 32 else { throw IssueError.randomGenerationFailed }
        let key = SymmetricKey(data: token)
        let authenticator = HMAC<SHA256>.authenticationCode(for: Self.domain, using: key)
        pending = Pending(authenticator: authenticator, expiresAt: now() + Self.lifetime, failures: 0)
        return token
    }

    /// Verifies and consumes an invitation atomically. A failed attempt counts
    /// toward the limit, including a token with an invalid length.
    public func consume(_ candidate: Data) -> Bool {
        guard var invitation = pending else { return false }
        guard invitation.expiresAt > now() else { pending = nil; return false }
        let valid: Bool
        if candidate.count == 32 {
            valid = HMAC<SHA256>.isValidAuthenticationCode(
                invitation.authenticator, authenticating: Self.domain, using: SymmetricKey(data: candidate)
            )
        } else {
            valid = false
        }
        if valid {
            pending = nil
            return true
        }
        invitation.failures += 1
        pending = invitation.failures >= Self.maximumFailures ? nil : invitation
        return false
    }

    public func invalidate() {
        pending = nil
    }
}

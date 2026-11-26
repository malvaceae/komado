import Foundation

/// Coordinates a one-time invitation, proof of possession, and key registration.
/// The transport must authenticate the host with TLS before transmitting an
/// invitation and must provide a unique server-created ID for each connection.
/// Disconnects must be reported to cancel(sessionID:). This type does not
/// implement TLS, wire framing, connection limits, or active-session revocation.
public actor PairingCoordinator {
    public enum PairingError: Error, Equatable {
        case invitationBusy
        case sessionAlreadyPending
        case capacityReached
        case invalidDevice
    }

    private enum Phase { case checkingToken, issuingChallenge, awaitingSignature, verifying }
    private struct Attempt {
        let id: UUID
        let generation: UUID
        let device: PairedDevice
        let expiresAt: Duration
        var phase: Phase
    }

    private let tokens: PairingTokenStore
    private let challenges: DeviceChallengeStore
    private let registry: PairedDeviceRegistry
    private let now: @Sendable () -> Duration
    private var attempts: [UUID: Attempt] = [:]
    private var generation = UUID()
    private var isIssuing = false
    private static let lifetime: Duration = .seconds(30)
    private static let capacity = 32

    public init() {
        let clock = ContinuousClock()
        let start = clock.now
        self.now = { start.duration(to: clock.now) }
        self.tokens = PairingTokenStore()
        self.challenges = DeviceChallengeStore()
        self.registry = PairedDeviceRegistry()
    }

    init(registry: PairedDeviceRegistry, tokens: PairingTokenStore = PairingTokenStore(),
         challenges: DeviceChallengeStore = DeviceChallengeStore(), now: @escaping @Sendable () -> Duration) {
        self.registry = registry
        self.tokens = tokens
        self.challenges = challenges
        self.now = now
    }

    /// Reissuing an invitation invalidates all pairing attempts in progress.
    public func issueInvitation() async throws -> Data {
        guard !isIssuing else { throw PairingError.invitationBusy }
        isIssuing = true
        defer { isIssuing = false }
        generation = UUID()
        let previous = attempts.values.map(\.id)
        attempts.removeAll()
        for id in previous { await challenges.invalidate(sessionID: id) }
        return try await tokens.issue()
    }

    public func cancelInvitation() async throws {
        guard !isIssuing else { throw PairingError.invitationBusy }
        isIssuing = true
        defer { isIssuing = false }
        generation = UUID()
        let previous = attempts.values.map(\.id)
        attempts.removeAll()
        await tokens.invalidate()
        for id in previous { await challenges.invalidate(sessionID: id) }
    }

    /// Returns a challenge only for a valid invitation. The candidate key is
    /// not registered or trusted until finish succeeds on this same session.
    public func begin(sessionID: UUID, invitation: Data, name: String, publicKeyDER: Data) async throws -> Data? {
        await expireAttempts()
        guard !isIssuing else { throw PairingError.invitationBusy }
        guard attempts[sessionID] == nil else { throw PairingError.sessionAlreadyPending }
        guard attempts.count < Self.capacity else { throw PairingError.capacityReached }
        let device = PairedDevice(id: UUID(), name: name, publicKeyDER: publicKeyDER)
        guard registry.isValid(device) else { throw PairingError.invalidDevice }
        let id = UUID(), currentGeneration = generation
        attempts[sessionID] = Attempt(id: id, generation: currentGeneration, device: device,
                                      expiresAt: now() + Self.lifetime, phase: .checkingToken)
        let accepted = await tokens.consume(invitation)
        guard isCurrent(sessionID: sessionID, attemptID: id, phase: .checkingToken) else { return nil }
        guard accepted else { attempts.removeValue(forKey: sessionID); return nil }
        attempts[sessionID]?.phase = .issuingChallenge
        let challenge: Data
        do {
            challenge = try await challenges.issue(sessionID: id, deviceID: device.id, registeredPublicKeyDER: publicKeyDER)
        } catch {
            if attempts[sessionID]?.id == id { attempts.removeValue(forKey: sessionID) }
            throw error
        }
        guard isCurrent(sessionID: sessionID, attemptID: id, phase: .issuingChallenge) else {
            if attempts[sessionID]?.id == id { attempts.removeValue(forKey: sessionID) }
            await challenges.invalidate(sessionID: id)
            return nil
        }
        attempts[sessionID]?.phase = .awaitingSignature
        return challenge
    }

    /// A valid proof is consumed once; Keychain failure requires a new invitation.
    public func finish(sessionID: UUID, signatureDER: Data) async throws -> PairedDevice? {
        await expireAttempts()
        guard let attempt = attempts[sessionID],
              isCurrent(sessionID: sessionID, attemptID: attempt.id, phase: .awaitingSignature) else { return nil }
        attempts[sessionID]?.phase = .verifying
        let verified = await challenges.verifyAndConsume(sessionID: attempt.id, signatureDER: signatureDER)
        guard isCurrent(sessionID: sessionID, attemptID: attempt.id, phase: .verifying) else {
            if attempts[sessionID]?.id == attempt.id { attempts.removeValue(forKey: sessionID) }
            return nil
        }
        attempts.removeValue(forKey: sessionID)
        guard verified == attempt.device.id else { return nil }
        try registry.add(attempt.device)
        return attempt.device
    }

    public func cancel(sessionID: UUID) async {
        guard let attempt = attempts.removeValue(forKey: sessionID) else { return }
        await challenges.invalidate(sessionID: attempt.id)
    }

    private func isCurrent(sessionID: UUID, attemptID: UUID, phase: Phase) -> Bool {
        guard let attempt = attempts[sessionID] else { return false }
        return attempt.id == attemptID && attempt.generation == generation && attempt.phase == phase && attempt.expiresAt > now()
    }

    private func expireAttempts() async {
        let expired = attempts.filter { $0.value.expiresAt <= now() }
        for (sessionID, _) in expired { attempts.removeValue(forKey: sessionID) }
        for attempt in expired.values { await challenges.invalidate(sessionID: attempt.id) }
    }
}

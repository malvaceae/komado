import Foundation

/// Authenticates connections against the currently registered public key.
/// The caller must establish host-authenticated TLS, provide server-generated
/// session IDs, cancel disconnected sessions, and close active sessions when a
/// registration is removed. This type does not perform those operations.
public actor DeviceAuthenticationCoordinator {
    public enum AuthenticationError: Error, Equatable {
        case sessionAlreadyPending
        case capacityReached
    }

    private enum Phase { case issuing, awaitingSignature, verifying }
    private struct Attempt {
        let id: UUID
        let device: PairedDevice
        let expiresAt: Duration
        var phase: Phase
    }

    private let registry: PairedDeviceRegistry
    private let challenges: DeviceChallengeStore
    private let now: @Sendable () -> Duration
    private var attempts: [UUID: Attempt] = [:]
    private static let lifetime: Duration = .seconds(30)
    private static let capacity = 32

    public init() {
        let clock = ContinuousClock()
        let start = clock.now
        self.now = { start.duration(to: clock.now) }
        self.registry = PairedDeviceRegistry()
        self.challenges = DeviceChallengeStore()
    }

    init(registry: PairedDeviceRegistry, challenges: DeviceChallengeStore = DeviceChallengeStore(),
         now: @escaping @Sendable () -> Duration) {
        self.registry = registry
        self.challenges = challenges
        self.now = now
    }

    /// The public key is always obtained from the host registry, never the peer.
    public func issue(sessionID: UUID, deviceID: UUID) async throws -> Data? {
        await expireAttempts()
        guard attempts[sessionID] == nil else { throw AuthenticationError.sessionAlreadyPending }
        guard attempts.count < Self.capacity else { throw AuthenticationError.capacityReached }
        guard let device = try registry.find(id: deviceID) else { return nil }
        let id = UUID()
        attempts[sessionID] = Attempt(id: id, device: device, expiresAt: now() + Self.lifetime, phase: .issuing)
        let challenge: Data
        do {
            challenge = try await challenges.issue(sessionID: id, deviceID: device.id,
                                                   registeredPublicKeyDER: device.publicKeyDER)
        } catch {
            if attempts[sessionID]?.id == id { attempts.removeValue(forKey: sessionID) }
            throw error
        }
        guard isCurrent(sessionID: sessionID, id: id, phase: .issuing) else {
            if attempts[sessionID]?.id == id { attempts.removeValue(forKey: sessionID) }
            await challenges.invalidate(sessionID: id)
            return nil
        }
        do {
            guard try registry.find(id: deviceID) == device else {
                attempts.removeValue(forKey: sessionID)
                await challenges.invalidate(sessionID: id)
                return nil
            }
        } catch {
            attempts.removeValue(forKey: sessionID)
            await challenges.invalidate(sessionID: id)
            throw error
        }
        attempts[sessionID]?.phase = .awaitingSignature
        return challenge
    }

    public func authenticate(sessionID: UUID, signatureDER: Data) async throws -> PairedDevice? {
        await expireAttempts()
        guard let attempt = attempts[sessionID], attempt.phase == .awaitingSignature else { return nil }
        attempts[sessionID]?.phase = .verifying
        let deviceID = await challenges.verifyAndConsume(sessionID: attempt.id, signatureDER: signatureDER)
        guard isCurrent(sessionID: sessionID, id: attempt.id, phase: .verifying) else {
            if attempts[sessionID]?.id == attempt.id { attempts.removeValue(forKey: sessionID) }
            return nil
        }
        attempts.removeValue(forKey: sessionID)
        guard deviceID == attempt.device.id, try registry.find(id: attempt.device.id) == attempt.device else { return nil }
        return attempt.device
    }

    public func cancel(sessionID: UUID) async {
        guard let attempt = attempts.removeValue(forKey: sessionID) else { return }
        await challenges.invalidate(sessionID: attempt.id)
    }

    /// Removes the key and invalidates pending authentication. The caller must
    /// separately disconnect any sessions that were already authenticated.
    @discardableResult
    public func removeDevice(id: UUID) async throws -> Bool {
        let removed = try registry.remove(id: id)
        let affected = attempts.filter { $0.value.device.id == id }
        for (sessionID, _) in affected { attempts.removeValue(forKey: sessionID) }
        for attempt in affected.values { await challenges.invalidate(sessionID: attempt.id) }
        return removed
    }

    private func isCurrent(sessionID: UUID, id: UUID, phase: Phase) -> Bool {
        guard let attempt = attempts[sessionID] else { return false }
        return attempt.id == id && attempt.phase == phase && attempt.expiresAt > now()
    }

    private func expireAttempts() async {
        let expired = attempts.filter { $0.value.expiresAt <= now() }
        for (sessionID, _) in expired { attempts.removeValue(forKey: sessionID) }
        for attempt in expired.values { await challenges.invalidate(sessionID: attempt.id) }
    }
}

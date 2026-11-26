import Foundation

/// Per-connection handshake state. The caller must establish host-authenticated
/// TLS, enforce connection and time limits, close the socket on rejection or
/// disconnect, and never send sensitive messages before TLS is authenticated.
public actor ServerHandshake {
    private enum Mode { case pairing, authentication }
    private enum State {
        case initial
        case processing(UUID)
        case awaitingProof(Mode)
        case authenticated(PairedDevice)
        case closed
    }

    private let sessionID: UUID
    private let pairing: PairingCoordinator
    private let authentication: DeviceAuthenticationCoordinator
    private var state: State = .initial

    /// Coordinators must be shared by all connections of the host.
    public init(sessionID: UUID, pairing: PairingCoordinator, authentication: DeviceAuthenticationCoordinator) {
        self.sessionID = sessionID
        self.pairing = pairing
        self.authentication = authentication
    }

    /// Nil means the connection was already closed while processing. A rejected
    /// result terminates the handshake and the caller must close the socket.
    public func receive(_ message: SignalMessage) async -> SignalMessage? {
        switch (state, message) {
        case (.initial, let .pair(name, invitation, publicKey)):
            let operation = UUID()
            state = .processing(operation)
            do {
                let challenge = try await pairing.begin(sessionID: sessionID, invitation: invitation,
                                                        name: name, publicKeyDER: publicKey)
                guard isProcessing(operation) else { await pairing.cancel(sessionID: sessionID); return nil }
                guard let challenge else { await close(); return .rejected }
                state = .awaitingProof(.pairing)
                return .challenge(challenge)
            } catch {
                guard isProcessing(operation) else { await pairing.cancel(sessionID: sessionID); return nil }
                await close()
                return .rejected
            }
        case (.initial, let .authenticate(deviceID)):
            let operation = UUID()
            state = .processing(operation)
            do {
                let challenge = try await authentication.issue(sessionID: sessionID, deviceID: deviceID)
                guard isProcessing(operation) else { await authentication.cancel(sessionID: sessionID); return nil }
                guard let challenge else { await close(); return .rejected }
                state = .awaitingProof(.authentication)
                return .challenge(challenge)
            } catch {
                guard isProcessing(operation) else { await authentication.cancel(sessionID: sessionID); return nil }
                await close()
                return .rejected
            }
        case let (.awaitingProof(mode), .proof(signature)):
            let operation = UUID()
            state = .processing(operation)
            do {
                let device: PairedDevice?
                switch mode {
                case .pairing: device = try await pairing.finish(sessionID: sessionID, signatureDER: signature)
                case .authentication: device = try await authentication.authenticate(sessionID: sessionID, signatureDER: signature)
                }
                guard isProcessing(operation) else { await close(); return nil }
                guard let device else { await close(); return .rejected }
                state = .authenticated(device)
                return .accepted(deviceID: device.id)
            } catch {
                guard isProcessing(operation) else { await close(); return nil }
                await close()
                return .rejected
            }
        case (.closed, _):
            return nil
        default:
            await close()
            return .rejected
        }
    }

    public func authenticatedDevice() -> PairedDevice? {
        if case let .authenticated(device) = state { return device }
        return nil
    }

    public func disconnect() async { await close() }

    private func isProcessing(_ operation: UUID) -> Bool {
        if case let .processing(current) = state { return current == operation }
        return false
    }

    private func close() async {
        state = .closed
        await pairing.cancel(sessionID: sessionID)
        await authentication.cancel(sessionID: sessionID)
    }
}

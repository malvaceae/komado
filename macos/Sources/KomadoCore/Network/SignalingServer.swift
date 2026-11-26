import Foundation
import Network
import Security

public struct ListenerBinding {
    public let scope: ListenerAccess.Scope
    public let interface: NWInterface
    public let address: NWEndpoint.Host

    public init(scope: ListenerAccess.Scope, interface: NWInterface, address: NWEndpoint.Host) {
        self.scope = scope
        self.interface = interface
        self.address = address
    }
}

/// A bounded TLS listener for the initial device handshake. All listeners share
/// one active connection and the same pairing and authentication coordinators.
/// Later WebRTC signaling is not yet implemented; unexpected messages close
/// the connection. Callers must stop and recreate the server on path changes.
public actor SignalingServer {
    public enum ServerError: Error, Equatable { case invalidBindings, alreadyStarted, removalBusy }
    public enum Event: Sendable {
        case listening(interfaceName: String, port: UInt16)
        case authenticated(PairedDevice)
        case disconnected
        case throttled
        case failed
        case stopped
    }

    private struct Slot {
        let interfaceName: String
        let listener: NWListener
    }

    private let listeners: [Slot]
    private let pairing: PairingCoordinator
    private let authentication: DeviceAuthenticationCoordinator
    private let onEvent: @Sendable (Event) -> Void
    private let queue = DispatchQueue(label: "me.mallows.komado.signaling")
    private let clock = ContinuousClock()
    private let startedAt: ContinuousClock.Instant
    private var limiter = ConnectionRateLimit()
    private var active: (id: UUID, session: SignalingSession)?
    private var retry: Task<Void, Never>?
    private var started = false
    private var stopped = false
    private var removing = false

    public init(identity: SecIdentity, bindings: [ListenerBinding], port: NWEndpoint.Port,
                pairing: PairingCoordinator, authentication: DeviceAuthenticationCoordinator,
                onEvent: @escaping @Sendable (Event) -> Void) throws {
        guard (1...8).contains(bindings.count),
              Set(bindings.map { "\($0.interface.index)|\($0.address)" }).count == bindings.count else {
            throw ServerError.invalidBindings
        }
        self.listeners = try bindings.map { binding in
            let parameters = try ListenerAccess.parameters(identity: identity, scope: binding.scope,
                                                            interface: binding.interface, address: binding.address, port: port)
            let listener = try NWListener(using: parameters)
            listener.newConnectionLimit = 1
            return Slot(interfaceName: binding.interface.name, listener: listener)
        }
        self.pairing = pairing
        self.authentication = authentication
        self.onEvent = onEvent
        self.startedAt = clock.now
    }

    public func start() throws {
        guard !started && !stopped else { throw ServerError.alreadyStarted }
        started = true
        for (index, slot) in listeners.enumerated() {
            slot.listener.stateUpdateHandler = { [weak self] state in
                Task { await self?.listenerState(index: index, state: state) }
            }
            slot.listener.newConnectionHandler = { [weak self] connection in
                Task { await self?.accept(connection) }
            }
            slot.listener.start(queue: queue)
        }
    }

    public func stop() async {
        guard !stopped else { return }
        stopped = true
        retry?.cancel()
        retry = nil
        for slot in listeners { slot.listener.cancel() }
        let current = active
        active = nil
        if let current { await current.session.close() }
        onEvent(.stopped)
    }

    /// Closing an authenticated connection must also be done on device removal.
    public func disconnectCurrent() async {
        if let current = active { await current.session.close() }
    }

    /// Blocks new sessions and disconnects the current one before removing the
    /// device. All registration removal must use this while the server is active.
    @discardableResult
    public func removeDevice(id: UUID) async throws -> Bool {
        guard !removing else { throw ServerError.removalBusy }
        removing = true
        for slot in listeners { slot.listener.newConnectionLimit = 0 }
        defer {
            removing = false
            if started && !stopped && active == nil && retry == nil {
                for slot in listeners { slot.listener.newConnectionLimit = 1 }
            }
        }
        if let current = active { await current.session.close() }
        return try await authentication.removeDevice(id: id)
    }

    private func listenerState(index: Int, state: NWListener.State) async {
        guard !stopped else { return }
        switch state {
        case .ready:
            if let port = listeners[index].listener.port {
                onEvent(.listening(interfaceName: listeners[index].interfaceName, port: port.rawValue))
            }
        case .failed:
            onEvent(.failed)
            await stop()
        default: break
        }
    }

    private func accept(_ connection: NWConnection) async {
        guard started && !stopped && !removing && active == nil && retry == nil else { connection.cancel(); return }
        for slot in listeners { slot.listener.newConnectionLimit = 0 }
        let now = startedAt.duration(to: clock.now)
        if let delay = limiter.admit(at: now) {
            connection.cancel()
            onEvent(.throttled)
            retry = Task { [weak self] in
                do { try await Task.sleep(for: delay) } catch { return }
                await self?.resumeAccepting()
            }
            return
        }
        let id = UUID()
        let handshake = ServerHandshake(sessionID: id, pairing: pairing, authentication: authentication)
        let session = SignalingSession(connection: connection, handshake: handshake, queue: queue,
                                       onAuthenticated: { [weak self] device in await self?.authenticated(id: id, device: device) },
                                       onClosed: { [weak self] in await self?.sessionClosed(id: id) })
        active = (id, session)
        await session.start()
    }

    private func authenticated(id: UUID, device: PairedDevice) {
        guard !stopped, active?.id == id else { return }
        onEvent(.authenticated(device))
    }

    private func sessionClosed(id: UUID) {
        guard active?.id == id else { return }
        active = nil
        guard !stopped else { return }
        onEvent(.disconnected)
        if !removing && retry == nil {
            for slot in listeners { slot.listener.newConnectionLimit = 1 }
        }
    }

    private func resumeAccepting() {
        retry = nil
        guard !stopped && !removing && active == nil else { return }
        for slot in listeners { slot.listener.newConnectionLimit = 1 }
    }
}

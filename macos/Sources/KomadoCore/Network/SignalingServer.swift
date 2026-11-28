import Foundation
import Network
import Security

public struct ListenerBinding: Sendable {
    public let scope: ListenerAccess.Scope
    public let interface: NWInterface
    public let address: NWEndpoint.Host
    public let port: NWEndpoint.Port?

    public init(scope: ListenerAccess.Scope, interface: NWInterface, address: NWEndpoint.Host, port: NWEndpoint.Port? = nil) {
        self.scope = scope
        self.interface = interface
        self.address = address
        self.port = port
    }
}

/// A bounded TLS listener for the initial device handshake. All listeners share
/// one active connection and the same pairing and authentication coordinators.
/// A single authenticated SDP offer/answer exchange is supported; unexpected
/// messages close the connection. Unavailable listeners are retired separately.
public actor SignalingServer {
    // SecIdentity is an immutable Security framework reference shared by listeners.
    private struct Identity: @unchecked Sendable { let value: SecIdentity }
    public enum ServerError: Error, Equatable { case invalidBindings, alreadyStarted, removalBusy, noConnection }
    public struct Endpoint: Sendable {
        public let interfaceName: String
        public let address: String
        public let port: UInt16
    }
    public enum Event: Sendable {
        case listenersChanged(revision: UInt64, endpoints: [Endpoint], failed: Bool)
        case authenticated(UUID, PairedDevice)
        case mediaRequested(UUID, PairedDevice, MediaTarget, resuming: Bool)
        case mediaStopRequested(UUID, PairedDevice)
        case windowActionRequested(UUID, PairedDevice, WindowInfo, Int32, WindowAction)
        case applicationLaunchRequested(UUID, PairedDevice, UInt32, URL)
        case answer(UUID, PairedDevice, String)
        case disconnected(UUID)
        case throttled
        case stopped
    }

    private struct Slot: Sendable {
        let key: String
        let interfaceName: String
        let address: String
        let port: UInt16
        let listener: NWListener
        var ready = false
    }

    private var listeners: [UUID: Slot]
    private let identity: Identity
    private let port: NWEndpoint.Port
    private var listenerRevision: UInt64 = 0
    private let pairing: PairingCoordinator
    private let authentication: DeviceAuthenticationCoordinator
    private let onEvent: @Sendable (Event) -> Void
    private let queue = DispatchQueue(label: "me.mallows.komado.signaling")
    private let clock = ContinuousClock()
    private let startedAt: ContinuousClock.Instant
    private var limiter = ConnectionRateLimit()
    private var active: (id: UUID, session: SignalingSession)?
    private var resumeDeviceID: UUID?
    private var retry: Task<Void, Never>?
    private var started = false
    private var stopped = false
    private var removing = false

    public nonisolated static func create(identity: SecIdentity, bindings: [ListenerBinding], port: NWEndpoint.Port,
                                         pairing: PairingCoordinator, authentication: DeviceAuthenticationCoordinator,
                                         resumeDeviceID: UUID? = nil,
                                         onEvent: @escaping @Sendable (Event) -> Void) throws -> SignalingServer {
        guard !bindings.isEmpty,
              Set(bindings.map { "\($0.interface.index)|\($0.address)" }).count == bindings.count else {
            throw ServerError.invalidBindings
        }
        let listeners = try Dictionary(uniqueKeysWithValues: bindings.map { (UUID(), try makeSlot(identity: identity, binding: $0, port: port)) })
        return SignalingServer(identity: Identity(value: identity), port: port, listeners: listeners, pairing: pairing, authentication: authentication,
                               resumeDeviceID: resumeDeviceID, onEvent: onEvent)
    }

    private nonisolated static func makeSlot(identity: SecIdentity, binding: ListenerBinding, port: NWEndpoint.Port) throws -> Slot {
        let parameters = try ListenerAccess.parameters(identity: identity, scope: binding.scope,
                                                        interface: binding.interface, address: binding.address, port: binding.port ?? port)
        let listener = try NWListener(using: parameters)
        listener.newConnectionLimit = 1
        let displayedAddress: String
        if case let .ipv6(address) = binding.address, let unscoped = IPv6Address(address.rawValue) {
            displayedAddress = "\(unscoped)"
        } else { displayedAddress = "\(binding.address)" }
        return Slot(key: "\(binding.interface.index)|\(binding.address)", interfaceName: binding.interface.name, address: displayedAddress,
                    port: (binding.port ?? port).rawValue, listener: listener)
    }

    private init(identity: Identity, port: NWEndpoint.Port, listeners: [UUID: Slot], pairing: PairingCoordinator, authentication: DeviceAuthenticationCoordinator,
                 resumeDeviceID: UUID?,
                 onEvent: @escaping @Sendable (Event) -> Void) {
        self.identity = identity
        self.port = port
        self.listeners = listeners
        self.pairing = pairing
        self.authentication = authentication
        self.resumeDeviceID = resumeDeviceID
        self.onEvent = onEvent
        self.startedAt = clock.now
    }

    public func start() throws {
        guard !started && !stopped else { throw ServerError.alreadyStarted }
        started = true
        for (id, slot) in listeners { startListener(id: id, slot: slot) }
    }

    public func stop() async {
        guard !stopped else { return }
        stopped = true
        retry?.cancel()
        retry = nil
        for slot in listeners.values { slot.listener.cancel() }
        listeners.removeAll()
        let current = active
        active = nil
        if let current { await current.session.close() }
        onEvent(.stopped)
    }

    /// Ignore a delayed teardown for a connection that has already ended.
    public func disconnect(sessionID: UUID) async {
        if let current = active, current.id == sessionID { await current.session.close() }
    }

    public func isActive(sessionID: UUID) -> Bool {
        !stopped && active?.id == sessionID
    }

    public func setResumeDeviceID(_ id: UUID?) { resumeDeviceID = id }

    /// Keep active listeners and replace only addresses that changed.
    public func updateListeners(_ bindings: [ListenerBinding]) {
        guard started && !stopped else { return }
        let keys = Set(bindings.map { "\($0.interface.index)|\($0.address)" })
        for (id, slot) in listeners where !keys.contains(slot.key) {
            slot.listener.cancel()
            listeners.removeValue(forKey: id)
        }
        let existing = Set(listeners.values.map(\.key))
        var failed = false
        for binding in bindings where !existing.contains("\(binding.interface.index)|\(binding.address)") {
            guard let slot = try? Self.makeSlot(identity: identity.value, binding: binding, port: port) else { failed = true; continue }
            let id = UUID()
            slot.listener.newConnectionLimit = active == nil && retry == nil && !removing ? 1 : 0
            listeners[id] = slot
            startListener(id: id, slot: slot)
        }
        publishListeners(failed: failed && listeners.isEmpty)
    }

    /// Sends one SDP offer only after the active device has authenticated.
    public func sendOffer(_ sdp: String, sessionID: UUID? = nil) async throws {
        guard let current = active, !stopped, !removing, sessionID == nil || sessionID == current.id else { throw ServerError.noConnection }
        try await current.session.sendOffer(sdp)
    }

    public func sendMediaStopped(sessionID: UUID) async throws {
        guard let current = active, !stopped, !removing, current.id == sessionID else { throw ServerError.noConnection }
        try await current.session.sendMediaStopped()
    }

    public func sendWindowActionResult(id: UInt32, success: Bool, sessionID: UUID) async throws {
        guard let current = active, !stopped, !removing, current.id == sessionID else { throw ServerError.noConnection }
        try await current.session.sendWindowActionResult(id: id, success: success)
    }

    public func sendLaunchApplicationResult(id: UInt32, success: Bool, sessionID: UUID) async throws {
        guard let current = active, !stopped, !removing, current.id == sessionID else { throw ServerError.noConnection }
        try await current.session.sendLaunchApplicationResult(id: id, success: success)
    }

    /// Blocks new sessions and disconnects the current one before removing the
    /// device. All registration removal must use this while the server is active.
    @discardableResult
    public func removeDevice(id: UUID) async throws -> Bool {
        guard !removing else { throw ServerError.removalBusy }
        removing = true
        for slot in listeners.values { slot.listener.newConnectionLimit = 0 }
        defer {
            removing = false
            if started && !stopped && active == nil && retry == nil {
                for slot in listeners.values { slot.listener.newConnectionLimit = 1 }
            }
        }
        if let current = active { await current.session.close() }
        return try await authentication.removeDevice(id: id)
    }

    private func startListener(id: UUID, slot: Slot) {
        slot.listener.stateUpdateHandler = { [weak self] _ in
            Task { await self?.listenerState(id: id) }
        }
        slot.listener.newConnectionHandler = { [weak self] connection in
            Task { await self?.accept(connection, listenerID: id) }
        }
        slot.listener.start(queue: queue)
    }

    private func listenerState(id: UUID) {
        guard !stopped, let slot = listeners[id] else { return }
        switch slot.listener.state {
        case .ready:
            listeners[id]?.ready = true
            publishListeners()
        case .failed:
            slot.listener.cancel()
            listeners.removeValue(forKey: id)
            publishListeners(failed: listeners.isEmpty)
        case .waiting:
            if slot.ready {
                listeners[id]?.ready = false
                publishListeners()
            }
        default: break
        }
    }

    private func publishListeners(failed: Bool = false) {
        listenerRevision += 1
        let endpoints = listeners.values.filter(\.ready).map {
            Endpoint(interfaceName: $0.interfaceName, address: $0.address, port: $0.listener.port?.rawValue ?? $0.port)
        }.sorted { ($0.interfaceName, $0.address, $0.port) < ($1.interfaceName, $1.address, $1.port) }
        onEvent(.listenersChanged(revision: listenerRevision, endpoints: endpoints, failed: failed))
    }

    private func accept(_ connection: NWConnection, listenerID: UUID) async {
        guard started && !stopped && listeners[listenerID] != nil && !removing && active == nil && retry == nil else { connection.cancel(); return }
        for slot in listeners.values { slot.listener.newConnectionLimit = 0 }
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
                                       onMediaRequested: { [weak self] device, target, resuming in await self?.mediaRequested(id: id, device: device, target: target, resuming: resuming) },
                                       canResume: { [weak self] device in await self?.canResume(id: id, device: device) ?? false },
                                       onMediaStopRequested: { [weak self] device in await self?.mediaStopRequested(id: id, device: device) },
                                       onWindowAction: { [weak self] device, window, processID, action in
                                           await self?.windowActionRequested(id: id, device: device, window: window, processID: processID, action: action)
                                       },
                                       onLaunchApplication: { [weak self] device, applicationID, url in
                                           await self?.applicationLaunchRequested(id: id, device: device, applicationID: applicationID, url: url)
                                       },
                                       onAnswer: { [weak self] device, sdp in await self?.answer(id: id, device: device, sdp: sdp) },
                                       onClosed: { [weak self] in await self?.sessionClosed(id: id) })
        active = (id, session)
        await session.start()
    }

    private func authenticated(id: UUID, device: PairedDevice) {
        guard !stopped, active?.id == id else { return }
        onEvent(.authenticated(id, device))
    }

    private func answer(id: UUID, device: PairedDevice, sdp: String) {
        guard !stopped, active?.id == id else { return }
        onEvent(.answer(id, device, sdp))
    }

    private func mediaRequested(id: UUID, device: PairedDevice, target: MediaTarget, resuming: Bool) {
        guard !stopped, active?.id == id else { return }
        onEvent(.mediaRequested(id, device, target, resuming: resuming))
    }

    private func canResume(id: UUID, device: PairedDevice) -> Bool {
        !stopped && active?.id == id && resumeDeviceID == device.id
    }

    private func mediaStopRequested(id: UUID, device: PairedDevice) {
        guard !stopped, active?.id == id else { return }
        onEvent(.mediaStopRequested(id, device))
    }

    private func windowActionRequested(id: UUID, device: PairedDevice, window: WindowInfo, processID: Int32, action: WindowAction) {
        guard !stopped, active?.id == id else { return }
        onEvent(.windowActionRequested(id, device, window, processID, action))
    }

    private func applicationLaunchRequested(id: UUID, device: PairedDevice, applicationID: UInt32, url: URL) {
        guard !stopped, active?.id == id else { return }
        onEvent(.applicationLaunchRequested(id, device, applicationID, url))
    }

    private func sessionClosed(id: UUID) {
        guard active?.id == id else { return }
        active = nil
        guard !stopped else { return }
        onEvent(.disconnected(id))
        if !removing && retry == nil {
            for slot in listeners.values { slot.listener.newConnectionLimit = 1 }
        }
    }

    private func resumeAccepting() {
        retry = nil
        guard !stopped && !removing && active == nil else { return }
        for slot in listeners.values { slot.listener.newConnectionLimit = 1 }
    }
}

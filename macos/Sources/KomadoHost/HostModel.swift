import AppKit
import CoreGraphics
import KomadoCore
import Network
import ScreenCaptureKit
import SwiftUI

@MainActor
final class HostModel: ObservableObject {
    @Published private(set) var fingerprint: String?
    @Published private(set) var devices: [PairedDevice] = []
    @Published private(set) var identityError = false
    @Published private(set) var devicesError = false
    @Published private(set) var running = false
    @Published private(set) var ready = false
    @Published private(set) var busy = false
    @Published private(set) var addresses: [String] = []
    @Published private(set) var connectedDevice: String?
    @Published private(set) var networkReady = false
    @Published private(set) var invitation: String?
    @Published private(set) var error: String?
    @Published private(set) var screenPermission = CGPreflightScreenCaptureAccess()
    @Published var vpnAddress = ""

    private let identityStore: HostTLSIdentityStore
    private let registry: PairedDeviceRegistry
    private let pairing: PairingCoordinator
    private let authentication: DeviceAuthenticationCoordinator
    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "me.mallows.komado.path")
    private var interfaces: [NWInterface] = []
    private var bindings: [ListenerBinding] = []
    private var server: SignalingServer?
    private var generation = UUID()
    private var invitationGeneration = UUID()
    private var invitationExpiry: Task<Void, Never>?
    private var invitationCleanup: Task<Void, Never>?
    private var listening = Set<String>()
    private var mediaID = UUID()
    private var connectedSession: UUID?
    private var mediaConnection: UUID?
    private var mediaSession: HostMediaSession?
    private var mediaRoute: MediaRouteGate?
    private var mediaTask: Task<Void, Never>?
    private var capture: DisplayCapture?
    private static let port: NWEndpoint.Port = 48365

    convenience init() {
        self.init(identityStore: HostTLSIdentityStore(), registry: PairedDeviceRegistry(),
                  pairing: PairingCoordinator(), authentication: DeviceAuthenticationCoordinator())
    }

    init(identityStore: HostTLSIdentityStore, registry: PairedDeviceRegistry,
         pairing: PairingCoordinator, authentication: DeviceAuthenticationCoordinator) {
        self.identityStore = identityStore
        self.registry = registry
        self.pairing = pairing
        self.authentication = authentication
        monitor.pathUpdateHandler = { [weak self] path in
            let current = path.availableInterfaces
            Task { @MainActor [weak self] in await self?.networkChanged(current) }
        }
        monitor.start(queue: monitorQueue)
    }

    func refresh() {
        screenPermission = CGPreflightScreenCaptureAccess()
        do {
            fingerprint = try identityStore.load().map { Self.format($0.fingerprintSHA256, separator: ":") }
            identityError = false
        } catch {
            fingerprint = nil
            identityError = true
        }
        do {
            devices = try registry.all()
            devicesError = false
        } catch {
            devices = []
            devicesError = true
        }
    }

    func requestScreenPermission() {
        screenPermission = CGRequestScreenCaptureAccess()
        if !screenPermission { error = "画面収録が許可されていません。システム設定で許可してください。" }
    }

    func createIdentity() {
        do {
            let identity = try identityStore.create()
            fingerprint = Self.format(identity.fingerprintSHA256, separator: ":")
            identityError = false
        } catch {
            identityError = true
        }
    }

    func start() async {
        guard !running && !busy else { return }
        busy = true
        defer { busy = false }
        error = nil
        do {
            guard let identity = try identityStore.load() else {
                error = "先にこのMacの確認情報を作成してください。"
                return
            }
            let selected = try selectBindings()
            guard !selected.isEmpty else {
                error = "利用できるLANまたはVPNのアドレスがありません。"
                return
            }
            let run = UUID()
            let newServer = try SignalingServer.create(identity: identity.identity, bindings: selected, port: Self.port,
                                                pairing: pairing, authentication: authentication) { [weak self] event in
                Task { @MainActor [weak self] in self?.handle(event, generation: run) }
            }
            generation = run
            bindings = selected
            listening.removeAll()
            addresses = []
            connectedDevice = nil
            connectedSession = nil
            ready = false
            running = true
            server = newServer
            try await newServer.start()
        } catch SelectionError.invalidVPNAddress {
            running = false
            ready = false
            server = nil
            self.error = "VPNアドレスが見つかりません。VPNの接続状態とアドレスを確認してください。"
        } catch SelectionError.tooManyAddresses {
            running = false
            ready = false
            server = nil
            self.error = "利用可能なアドレスが多すぎるため、待ち受けを開始できませんでした。"
        } catch {
            running = false
            ready = false
            server = nil
            self.error = "接続の待ち受けを開始できませんでした。ネットワークとポートを確認してください。"
        }
    }

    func stop() async {
        stopMedia()
        let current = server
        generation = UUID()
        server = nil
        running = false
        ready = false
        addresses = []
        connectedDevice = nil
        connectedSession = nil
        invitation = nil
        invitationGeneration = UUID()
        invitationExpiry?.cancel()
        invitationExpiry = nil
        if let current { await current.stop() }
        if let invitationCleanup { await invitationCleanup.value }
        try? await pairing.cancelInvitation()
        refresh()
    }

    func issueInvitation() async {
        guard running && ready && !busy && connectedDevice == nil else { return }
        busy = true
        defer { busy = false }
        if let invitationCleanup { await invitationCleanup.value }
        guard running && ready && connectedDevice == nil else { return }
        let current = generation
        let invitationID = UUID()
        invitationGeneration = invitationID
        invitation = nil
        invitationExpiry?.cancel()
        do {
            let token = try await pairing.issueInvitation()
            guard generation == current && running else {
                try? await pairing.cancelInvitation()
                return
            }
            invitation = Self.format(token, separator: "")
            invitationExpiry?.cancel()
            invitationExpiry = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(120)) } catch { return }
                guard let self, self.generation == current, self.invitationGeneration == invitationID else { return }
                self.invitation = nil
                self.invitationGeneration = UUID()
                self.invitationCleanup = Task { try? await self.pairing.cancelInvitation() }
            }
        } catch {
            self.error = "ペアリング情報を作成できませんでした。"
        }
    }

    func remove(_ device: PairedDevice) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        stopMedia()
        do {
            if let server { _ = try await server.removeDevice(id: device.id) }
            else { _ = try await authentication.removeDevice(id: device.id) }
            refresh()
        } catch {
            self.error = "端末を削除できませんでした。"
        }
    }

    private func networkChanged(_ current: [NWInterface]) async {
        interfaces = current
        networkReady = true
        guard running else { return }
        let stillAvailable = bindings.allSatisfy { binding in
            guard let interface = current.first(where: { $0.index == binding.interface.index }) else { return false }
            return (try? ListenerAccess.addresses(on: interface).contains(binding.address)) == true
        }
        if !stillAvailable {
            await stop()
            error = "ネットワークが変わったため待ち受けを停止しました。もう一度開始してください。"
        }
    }

    private func selectBindings() throws -> [ListenerBinding] {
        var result: [ListenerBinding] = []
        var seen = Set<String>()
        for interface in interfaces where interface.type == .wifi || interface.type == .wiredEthernet {
            for address in try ListenerAccess.addresses(on: interface) {
                let key = "\(interface.index)|\(address)"
                if seen.insert(key).inserted {
                    result.append(ListenerBinding(scope: .localLink, interface: interface, address: address))
                }
            }
        }
        let requested = vpnAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        if !requested.isEmpty {
            let requestedHost: NWEndpoint.Host
            if let address = IPv4Address(requested) { requestedHost = .ipv4(address) }
            else if let address = IPv6Address(requested) { requestedHost = .ipv6(address) }
            else { throw SelectionError.invalidVPNAddress }
            var found = false
            for interface in interfaces where interface.type == .other && interface.name.hasPrefix("utun") {
                for address in try ListenerAccess.addresses(on: interface) where Self.sameAddress(address, requestedHost) {
                    let key = "\(interface.index)|\(address)"
                    if seen.insert(key).inserted {
                        result.append(ListenerBinding(scope: .vpnTunnel, interface: interface, address: address))
                    }
                    found = true
                }
            }
            guard found else { throw SelectionError.invalidVPNAddress }
        }
        // Network.framework listeners on this host can conflict on the same
        // port even when bound to different addresses. Give each route a stable
        // port and show it alongside the corresponding address.
        result.sort { left, right in
            if left.interface.index != right.interface.index { return left.interface.index < right.interface.index }
            let leftV4: Bool = { if case .ipv4 = left.address { return true }; return false }()
            let rightV4: Bool = { if case .ipv4 = right.address { return true }; return false }()
            if leftV4 != rightV4 { return leftV4 }
            return "\(left.address)" < "\(right.address)"
        }
        guard result.count <= 8 else { throw SelectionError.tooManyAddresses }
        return result.enumerated().map { index, binding in
            ListenerBinding(scope: binding.scope, interface: binding.interface, address: binding.address,
                            port: NWEndpoint.Port(rawValue: 48365 + UInt16(index))!)
        }
    }

    private func handle(_ event: SignalingServer.Event, generation eventGeneration: UUID) {
        guard generation == eventGeneration else { return }
        switch event {
        case let .listening(interfaceName, address, port):
            let host = address.contains(":") ? "[\(address)]" : address
            let item = "\(interfaceName): \(host):\(port)"
            if listening.insert(item).inserted { addresses.append(item) }
            ready = listening.count == bindings.count
        case let .authenticated(id, device):
            connectedSession = id
            connectedDevice = device.name
            invitation = nil
            invitationGeneration = UUID()
            invitationExpiry?.cancel()
            invitationExpiry = nil
            invitationCleanup = Task { try? await pairing.cancelInvitation() }
            refresh()
        case let .mediaRequested(id, _, address):
            Task { [weak self] in await self?.startMedia(address: address, connection: id) }
        case let .answer(id, _, sdp):
            if mediaConnection == id { applyAnswer(sdp) }
        case let .disconnected(id):
            if mediaConnection == id { stopMedia() }
            if connectedSession == id { connectedSession = nil; connectedDevice = nil }
        case .throttled: error = "接続試行が多いため、一時的に新しい接続を待機しています。"
        case .failed:
            error = "待ち受けに失敗しました。ネットワークとポートを確認してください。"
            Task { await stop() }
        case .stopped:
            running = false
            ready = false
        }
    }

    private func startMedia(address: NWEndpoint.Host, connection: UUID) async {
        guard let server, await server.isActive(sessionID: connection), self.server === server, running else { return }
        if mediaConnection != nil && mediaConnection != connection { stopMedia() }
        guard mediaSession == nil else { return }
        screenPermission = CGPreflightScreenCaptureAccess()
        guard screenPermission else {
            error = "画面収録の許可が必要です。許可してから接続し直してください。"
            Task { await server.disconnect(sessionID: connection) }
            return
        }
        let id = UUID()
        mediaID = id
        mediaConnection = connection
        let failed: @Sendable () -> Void = { [weak self] in
            Task { @MainActor [weak self] in self?.mediaFailed(id: id) }
        }
        let route = MediaRouteGate(address: address, onInvalidRoute: failed)
        do {
            let session = try HostMediaSession(route: route, onFailure: failed)
            mediaRoute = route
            mediaSession = session
            mediaTask = Task { [weak self] in
                do {
                    let offer = try await session.makeOffer()
                    guard let self, self.mediaID == id, !Task.isCancelled else { return }
                    try await server.sendOffer(offer, sessionID: connection)
                } catch { self?.mediaFailed(id: id) }
            }
        } catch { mediaFailed(id: id) }
    }

    private func applyAnswer(_ sdp: String) {
        guard let session = mediaSession, let route = mediaRoute else { return }
        let id = mediaID
        mediaTask = Task { [weak self] in
            do {
                try await session.applyAnswer(sdp)
                guard let self, self.mediaID == id, !Task.isCancelled else { return }
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                guard self.mediaID == id, !Task.isCancelled,
                      let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) else {
                    throw HostMediaSession.SessionError.creationFailed
                }
                let captured = try await DisplayCapture.start(display: display, source: session.videoSource, route: route) { [weak self] in
                    Task { @MainActor [weak self] in self?.mediaFailed(id: id) }
                }
                guard self.mediaID == id, !Task.isCancelled else { try? await captured.stop(); return }
                self.capture = captured
            } catch { self?.mediaFailed(id: id) }
        }
    }

    private func mediaFailed(id: UUID) {
        guard mediaID == id else { return }
        let connection = mediaConnection
        stopMedia()
        error = "画面転送を開始または継続できませんでした。接続と画面収録の許可を確認してください。"
        let current = server
        if let connection { Task { await current?.disconnect(sessionID: connection) } }
    }

    private func stopMedia() {
        mediaID = UUID()
        mediaConnection = nil
        mediaTask?.cancel()
        mediaTask = nil
        mediaRoute?.close()
        mediaRoute = nil
        mediaSession?.close()
        mediaSession = nil
        if let current = capture {
            capture = nil
            Task { try? await current.stop() }
        }
    }

    private static func sameAddress(_ a: NWEndpoint.Host, _ b: NWEndpoint.Host) -> Bool {
        switch (a, b) {
        case let (.ipv4(left), .ipv4(right)): return left.rawValue == right.rawValue
        case let (.ipv6(left), .ipv6(right)): return left.rawValue == right.rawValue
        default: return false
        }
    }

    private static func format(_ data: Data, separator: String) -> String {
        data.map { String(format: "%02X", $0) }.joined(separator: separator)
    }

    private enum SelectionError: Error { case invalidVPNAddress, tooManyAddresses }
}

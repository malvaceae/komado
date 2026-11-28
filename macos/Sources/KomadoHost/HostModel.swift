import AppKit
@preconcurrency import ApplicationServices
import CoreGraphics
import KomadoCore
import Network
import ScreenCaptureKit
import SwiftUI

struct PairingEndpoint: Hashable, Identifiable {
    let interfaceName: String
    let address: String
    let port: UInt16
    var id: String { "\(interfaceName)|\(address)|\(port)" }
    var displayName: String {
        let host = address.contains(":") ? "[\(address)]" : address
        return "\(interfaceName): \(host):\(port)"
    }
}

@MainActor
final class HostModel: ObservableObject {
    @Published private(set) var fingerprint: String?
    @Published private(set) var devices: [PairedDevice] = []
    @Published private(set) var identityError = false
    @Published private(set) var devicesError = false
    @Published private(set) var running = false {
        didSet { updateWaitingSleepActivity() }
    }
    @Published private(set) var ready = false
    @Published private(set) var busy = false
    @Published private(set) var addresses: [String] = []
    @Published private(set) var pairingEndpoints: [PairingEndpoint] = []
    @Published private(set) var connectedDevice: String?
    @Published private(set) var sharingScreen = false
    @Published private(set) var sharingWindow = false
    @Published private(set) var controllingScreen = false
    @Published private(set) var sharingDisplayName: String?
    @Published private(set) var networkReady = false
    @Published private(set) var invitation: String?
    @Published private(set) var error: String?
    @Published private(set) var screenPermission = CGPreflightScreenCaptureAccess()
    @Published private(set) var accessibilityPermission: Bool
    @Published var allowRemoteControl = false {
        didSet {
            if !allowRemoteControl { allowClipboardSharing = false }
            if !allowRemoteControl, mediaTarget?.isControl == true {
                let session = mediaID
                Task { if mediaID == session { await stopSharing() } }
            }
        }
    }
    @Published var allowClipboardSharing = false {
        didSet {
            if !allowClipboardSharing { allowClipboardAutomatic = false; clipboardTransfer.reset() }
        }
    }
    @Published var allowClipboardAutomatic = false {
        didSet { if !allowClipboardAutomatic { clipboardTransfer.stopAutomatic() } }
    }
    @Published var captureResolution: CaptureResolution {
        didSet { settings.set(captureResolution.rawValue, forKey: Self.captureResolutionKey) }
    }
    @Published var keepAwakeWhileListening: Bool {
        didSet {
            settings.set(keepAwakeWhileListening, forKey: Self.keepAwakeKey)
            updateWaitingSleepActivity()
        }
    }

    private let settings: UserDefaults
    private let accessibilityStatus: () -> Bool
    private let accessibilityPrompt: () -> Void
    private let consoleStatus: () -> Bool
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
    private var mediaTarget: MediaTarget?
    private var windowResize: WindowResize?
    private var switchingControl = false
    private var mediaSession: HostMediaSession?
    private var mediaGate: MediaSessionGate?
    private var screenMonitor: ScreenEnvironmentMonitor?
    private var mediaTask: Task<Void, Never>?
    private var mediaCleanup: Task<Bool, Never>?
    private var capture: DisplayCapture?
    private var controlDisplayID: CGDirectDisplayID?
    private var controlBounds: CGRect?
    private var controlWindow: WindowControlSafety?
    private var pointerDispatcher: PointerClickDispatcher?
    private var textDispatcher: KeyboardTextDispatcher?
    private var keyDispatcher: KeyboardKeyDispatcher?
    private var scrollDispatcher: PointerScrollDispatcher?
    private var dragDispatcher: PointerDragDispatcher?
    private var moveDispatcher: PointerMoveDispatcher?
    private var lastExecutedControlSequence: UInt64 = 0
    private let clipboardTransfer: ClipboardTransfer
    private var sleepActivity: IdleSleepActivity?
    private var waitingSleepActivity: IdleSleepActivity?
    private static let port: NWEndpoint.Port = 48365
    private static let keepAwakeKey = "keepAwakeWhileListening"
    private static let captureResolutionKey = "captureResolution"

    convenience init() {
        self.init(identityStore: HostTLSIdentityStore(), registry: PairedDeviceRegistry(),
                  pairing: PairingCoordinator(), authentication: DeviceAuthenticationCoordinator())
    }

    init(identityStore: HostTLSIdentityStore, registry: PairedDeviceRegistry,
         pairing: PairingCoordinator, authentication: DeviceAuthenticationCoordinator, settings: UserDefaults = .standard,
         clipboardTransfer: ClipboardTransfer = ClipboardTransfer(),
         accessibilityStatus: @escaping () -> Bool = { AXIsProcessTrusted() },
         accessibilityPrompt: @escaping () -> Void = {
             let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
             _ = AXIsProcessTrustedWithOptions(options)
         }, consoleStatus: @escaping () -> Bool = { ConsoleSession.isOnConsole() }) {
        self.clipboardTransfer = clipboardTransfer
        self.accessibilityStatus = accessibilityStatus
        self.accessibilityPrompt = accessibilityPrompt
        self.consoleStatus = consoleStatus
        self.accessibilityPermission = accessibilityStatus()
        self.settings = settings
        self.captureResolution = settings.string(forKey: Self.captureResolutionKey).flatMap(CaptureResolution.init(rawValue:)) ?? .standard
        self.keepAwakeWhileListening = settings.bool(forKey: Self.keepAwakeKey)
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

    private func updateWaitingSleepActivity() {
        if running && keepAwakeWhileListening {
            if waitingSleepActivity == nil { waitingSleepActivity = IdleSleepActivity(reason: "komado waiting for connections") }
        } else {
            waitingSleepActivity?.end()
            waitingSleepActivity = nil
        }
    }

    func refresh() {
        refreshPermissions()
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

    func refreshPermissions() {
        screenPermission = CGPreflightScreenCaptureAccess()
        accessibilityPermission = accessibilityStatus()
    }

    func requestAccessibilityPermission() {
        accessibilityPrompt()
        accessibilityPermission = accessibilityStatus()
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
                error = "利用できるネットワークアドレスがありません。"
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
            pairingEndpoints = []
            connectedDevice = nil
            connectedSession = nil
            ready = false
            running = true
            server = newServer
            try await newServer.start()
        } catch {
            running = false
            ready = false
            server = nil
            self.error = "接続の待ち受けを開始できませんでした。ネットワークとポートを確認してください。"
        }
    }

    func stop() async {
        let cleanup = stopMedia()
        switchingControl = false
        allowRemoteControl = false
        let current = server
        generation = UUID()
        server = nil
        running = false
        ready = false
        addresses = []
        pairingEndpoints = []
        connectedDevice = nil
        connectedSession = nil
        invitation = nil
        invitationGeneration = UUID()
        invitationExpiry?.cancel()
        invitationExpiry = nil
        if let current { await current.stop() }
        _ = await cleanup?.value
        if let invitationCleanup { await invitationCleanup.value }
        try? await pairing.cancelInvitation()
        refresh()
    }

    func stopSharing() async {
        guard let connection = mediaConnection, let current = server else { return }
        let cleanup = stopMedia()
        await current.disconnect(sessionID: connection)
        _ = await cleanup?.value
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
        let available = bindings.filter { binding in
            guard let interface = current.first(where: { $0.index == binding.interface.index }) else { return false }
            return (try? ListenerAccess.addresses(on: interface).contains(binding.address)) == true
        }
        if available.isEmpty {
            await stop()
            error = "ネットワークが変わったため待ち受けを停止しました。もう一度開始してください。"
        } else if available.count != bindings.count {
            await server?.retainListeners(keys: Set(available.map { "\($0.interface.index)|\($0.address)" }))
        }
    }

    private func selectBindings() throws -> [ListenerBinding] {
        var result: [ListenerBinding] = []
        var seen = Set<String>()
        for interface in interfaces where interface.type != .loopback {
            let isTunnel = interface.type == .other && interface.name.hasPrefix("utun")
            for address in try ListenerAccess.addresses(on: interface) {
                // Tunnel link-local addresses are not useful as remote endpoints.
                if isTunnel, case let .ipv6(ip) = address, ip.rawValue.count == 16,
                   ip.rawValue[0] == 0xfe, ip.rawValue[1] & 0xc0 == 0x80 { continue }
                let key = "\(interface.index)|\(address)"
                if seen.insert(key).inserted {
                    result.append(ListenerBinding(scope: isTunnel ? .vpnTunnel : .localLink,
                                                  interface: interface, address: address))
                }
            }
        }
        return result.sorted { left, right in
            if left.interface.index != right.interface.index { return left.interface.index < right.interface.index }
            let leftV4: Bool = { if case .ipv4 = left.address { return true }; return false }()
            let rightV4: Bool = { if case .ipv4 = right.address { return true }; return false }()
            if leftV4 != rightV4 { return leftV4 }
            return "\(left.address)" < "\(right.address)"
        }
    }

    private func handle(_ event: SignalingServer.Event, generation eventGeneration: UUID) {
        guard generation == eventGeneration else { return }
        switch event {
        case let .listening(interfaceName, address, port):
            let endpoint = PairingEndpoint(interfaceName: interfaceName, address: address, port: port)
            let item = endpoint.displayName
            if listening.insert(item).inserted { addresses.append(item); pairingEndpoints.append(endpoint) }
            ready = listening.count == bindings.count
        case let .listenerUnavailable(interfaceName, address, port):
            let endpoint = PairingEndpoint(interfaceName: interfaceName, address: address, port: port)
            listening.remove(endpoint.displayName)
            addresses.removeAll { $0 == endpoint.displayName }
            pairingEndpoints.removeAll { $0 == endpoint }
            bindings.removeAll {
                $0.interface.name == interfaceName && "\($0.address)".split(separator: "%").first.map(String.init) == address
            }
            ready = !listening.isEmpty && listening.count == bindings.count
        case let .authenticated(id, device):
            connectedSession = id
            connectedDevice = device.name
            invitation = nil
            invitationGeneration = UUID()
            invitationExpiry?.cancel()
            invitationExpiry = nil
            invitationCleanup = Task { try? await pairing.cancelInvitation() }
            refresh()
        case let .mediaRequested(id, _, target):
            Task { [weak self] in await self?.startMedia(connection: id, target: target) }
        case let .mediaStopRequested(id, _):
            Task { [weak self] in await self?.stopMediaForSwitch(connection: id) }
        case let .answer(id, _, sdp):
            if mediaConnection == id { applyAnswer(sdp) }
        case let .disconnected(id):
            if mediaConnection == id { stopMedia() }
            if connectedSession == id {
                if switchingControl { allowRemoteControl = false; allowClipboardSharing = false }
                switchingControl = false
                connectedSession = nil
                connectedDevice = nil
            }
        case .throttled: error = "接続試行が多いため、一時的に新しい接続を待機しています。"
        case .failed:
            error = "待ち受けに失敗しました。ネットワークとポートを確認してください。"
            Task { await stop() }
        case .stopped:
            running = false
            ready = false
        }
    }

    private func startMedia(connection: UUID, target: MediaTarget) async {
        guard let server, await server.isActive(sessionID: connection), self.server === server, running else { return }
        if let cleanup = mediaCleanup {
            _ = await cleanup.value
            guard await server.isActive(sessionID: connection), self.server === server, running else { return }
            mediaCleanup = nil
        }
        if mediaConnection != nil && mediaConnection != connection {
            let cleanup = stopMedia()
            _ = await cleanup?.value
            guard await server.isActive(sessionID: connection), self.server === server, running else { return }
            mediaCleanup = nil
        }
        guard mediaSession == nil else { return }
        if switchingControl && !target.isControl {
            allowRemoteControl = false
            allowClipboardSharing = false
            switchingControl = false
        }
        screenPermission = CGPreflightScreenCaptureAccess()
        guard screenPermission else {
            error = "画面収録の許可が必要です。許可してから接続し直してください。"
            Task { await server.disconnect(sessionID: connection) }
            return
        }
        if target.isControl && (!allowRemoteControl || !AXIsProcessTrusted()) {
            error = "操作の許可とアクセシビリティの許可が必要です。"
            Task { await server.disconnect(sessionID: connection) }
            return
        }
        let id = UUID()
        mediaID = id
        mediaConnection = connection
        mediaTarget = target
        switchingControl = false
        let failed: @Sendable () -> Void = { [weak self] in
            Task { @MainActor [weak self] in self?.mediaFailed(id: id) }
        }
        let route = MediaSessionGate()
        do {
            let onControl: (@Sendable (ControlInput) -> Void)?
            if target.isControl {
                onControl = { @Sendable [weak self] input in
                    DispatchQueue.main.async { [weak self] in self?.receiveControl(input, id: id) }
                }
            } else {
                onControl = nil
            }
            let session = try HostMediaSession(route: route, onControl: onControl, onFailure: failed)
            mediaGate = route
            mediaSession = session
            screenMonitor = ScreenEnvironmentMonitor {
                route.close()
                failed()
            }
            guard consoleStatus() else {
                mediaFailed(id: id)
                error = "このユーザーの画面セッションが使用中でないため、画面転送を開始できません。"
                return
            }
            // The notification only reports transitions; reject an already unavailable state as well.
            guard NSApplication.shared.isProtectedDataAvailable else {
                mediaFailed(id: id)
                error = "保護データが利用できないため、画面転送を開始できません。"
                return
            }
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
        guard let session = mediaSession, let route = mediaGate else { return }
        let id = mediaID
        mediaTask = Task { [weak self] in
            do {
                try await session.applyAnswer(sdp)
                guard let self, self.mediaID == id, !Task.isCancelled else { return }
                guard let target = self.mediaTarget else { throw HostMediaSession.SessionError.creationFailed }
                let resolution = self.captureResolution
                if case let .windowControl(info, processID, size) = target, let size,
                   let snapshot = ControlWindowSnapshot.onScreen()?.first(where: { $0.id == info.id && $0.processID == processID }) {
                    self.windowResize = WindowResize.start(target: snapshot, desired: size)
                }
                let onScreenOnly: Bool
                switch target {
                case .window, .windowControl: onScreenOnly = false
                default: onScreenOnly = true
                }
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: onScreenOnly)
                guard self.mediaID == id, !Task.isCancelled else { return }
                let onStopped: @Sendable () -> Void = { [weak self] in
                    Task { @MainActor [weak self] in self?.mediaFailed(id: id) }
                }
                let captured: DisplayCapture
                switch target {
                case .mainDisplay, .mainDisplayControl, .display, .displayControl:
                    let displayID: UInt32
                    switch target {
                    case let .display(value), let .displayControl(value): displayID = value
                    default: displayID = CGMainDisplayID()
                    }
                    guard let display = content.displays.first(where: { $0.displayID == displayID }) else {
                        throw HostMediaSession.SessionError.creationFailed
                    }
                    let screen = NSScreen.screens.first {
                        ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == display.displayID
                    }
                    self.sharingDisplayName = screen?.localizedName ??
                        "\(CGDisplayPixelsWide(display.displayID)) × \(CGDisplayPixelsHigh(display.displayID)) px"
                    self.sharingWindow = false
                    self.sharingScreen = true
                    if target.isControl {
                        let bounds = CGDisplayBounds(displayID)
                        guard CaptureGeometry(revision: 1, bounds: bounds) != nil else { throw HostMediaSession.SessionError.creationFailed }
                        self.controlDisplayID = displayID
                        self.controlBounds = bounds
                        self.pointerDispatcher = PointerClickDispatcher()
                        self.textDispatcher = KeyboardTextDispatcher()
                        self.keyDispatcher = KeyboardKeyDispatcher()
                        self.scrollDispatcher = PointerScrollDispatcher()
                        self.dragDispatcher = PointerDragDispatcher()
                        self.moveDispatcher = PointerMoveDispatcher()
                        self.controllingScreen = true
                    }
                    captured = try await DisplayCapture.start(display: display, source: session.videoSource, route: route, resolution: resolution, onStopped: onStopped)
                case let .window(info, processID), let .windowControl(info, processID, _):
                    guard let window = content.windows.first(where: {
                        WindowCatalog.matches(window: $0, info: info, processID: processID)
                    }) else { throw HostMediaSession.SessionError.creationFailed }
                    if target.isControl {
                        let snapshot = ControlWindowSnapshot(id: info.id, processID: processID, bounds: window.frame)
                        guard window.isOnScreen, let windows = ControlWindowSnapshot.onScreen(),
                              windows.first(where: { $0.id == info.id }) == snapshot,
                              CaptureGeometry(revision: 1, bounds: window.frame) != nil else {
                            throw HostMediaSession.SessionError.creationFailed
                        }
                        self.controlWindow = WindowControlSafety(target: snapshot)
                        FocusedWindow.activate(snapshot)
                        self.pointerDispatcher = PointerClickDispatcher()
                        self.textDispatcher = KeyboardTextDispatcher()
                        self.keyDispatcher = KeyboardKeyDispatcher()
                        self.scrollDispatcher = PointerScrollDispatcher()
                        self.dragDispatcher = PointerDragDispatcher()
                        self.moveDispatcher = PointerMoveDispatcher()
                        self.controllingScreen = true
                    }
                    self.sharingDisplayName = "\(info.application) — \(info.title)"
                    self.sharingWindow = true
                    self.sharingScreen = true
                    captured = try await DisplayCapture.start(window: window, source: session.videoSource, route: route, resolution: resolution, onStopped: onStopped)
                }
                guard self.mediaID == id, !Task.isCancelled else { try? await captured.stop(); return }
                self.capture = captured
                self.sleepActivity = IdleSleepActivity()
                try session.announceCapture(revision: 1)
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

    private func receiveControl(_ input: ControlInput, id: UUID) {
        let confirmedText: KeyboardText?
        if case let .text(value) = input { confirmedText = value } else { confirmedText = nil }
        guard mediaID == id, mediaTarget?.isControl == true, allowRemoteControl,
              sharingScreen, NSApplication.shared.isProtectedDataAvailable,
              consoleStatus(),
              let mapping = capture?.frameMapping, let route = mediaGate,
              input.sequence > lastExecutedControlSequence else {
            if mediaID == id, confirmedText != nil || dragDispatcher?.isDragging == true { mediaFailed(id: id) }
            return
        }
        guard accessibilityStatus() else {
            accessibilityPermission = false
            mediaFailed(id: id)
            error = "アクセシビリティの許可が失われたため、接続を終了しました。"
            return
        }
        if case let .clipboard(request) = input {
            var delivered = false
            route.whileConnected {
                lastExecutedControlSequence = request.sequence
                let result = clipboardTransfer.execute(request.operation, allowed: allowClipboardSharing,
                                                       busy: dragDispatcher?.isDragging == true,
                                                       automaticAllowed: allowClipboardAutomatic)
                delivered = mediaSession?.sendClipboardResponse(ClipboardResponse(sequence: request.sequence, result: result)) == true
            }
            if !delivered { mediaFailed(id: id) }
            return
        }
        if dragDispatcher?.isDragging == true {
            if case .drag = input {} else { mediaFailed(id: id); return }
        }
        let geometry: CaptureGeometry
        if let window = controlWindow {
            guard let windows = ControlWindowSnapshot.onScreen() else {
                if confirmedText != nil || dragDispatcher?.isDragging == true { mediaFailed(id: id) }
                return
            }
            guard let current = window.currentTarget(in: windows),
                  let candidate = CaptureGeometry(revision: 1, bounds: current.bounds) else {
                mediaFailed(id: id)
                error = "操作対象のウインドウを確認できなくなったため、接続を終了しました。"
                return
            }
            geometry = candidate
            let allowed: Bool
            switch input {
            case .text, .key:
                if let focused = FocusedWindow.snapshot() {
                    allowed = window.permitsKeyboard(focusedProcessID: focused.processID,
                                                     focusedBounds: focused.bounds, windows: windows)
                } else { allowed = false }
            case .click, .scroll, .drag, .move:
                if let coordinate = input.pointerCoordinate,
                   let normalized = mapping.normalizedContentPoint(x: Double(coordinate.x) / 1_000_000,
                                                                    y: Double(coordinate.y) / 1_000_000),
                   let point = geometry.point(x: normalized.x, y: normalized.y, captureRevision: input.captureRevision) {
                    if let hit = FocusedWindow.window(at: point) {
                        allowed = window.permits(point: point, hitProcessID: hit.processID,
                                                 hitBounds: hit.bounds, windows: windows)
                    } else { allowed = false }
                } else { allowed = false }
            case .clipboard: allowed = false
            }
            guard allowed else {
                if confirmedText != nil || dragDispatcher?.isDragging == true { mediaFailed(id: id) }
                return
            }
        } else {
            guard let displayID = controlDisplayID, CGDisplayIsActive(displayID) != 0,
                  let bounds = controlBounds, CGDisplayBounds(displayID) == bounds,
                  let candidate = CaptureGeometry(revision: 1, bounds: bounds) else {
                if confirmedText != nil || dragDispatcher?.isDragging == true { mediaFailed(id: id) }
                return
            }
            geometry = candidate
            switch input {
            case .text, .key:
                guard FocusedWindow.isOnDisplay(bounds) else {
                    if confirmedText != nil { mediaFailed(id: id) }
                    return
                }
            case .click, .scroll, .drag, .move, .clipboard: break
            }
        }
        var invalidDrag = false
        var executed = false
        var textPosted = false
        route.whileConnected {
            executed = true
            lastExecutedControlSequence = input.sequence
            switch input {
            case let .click(click): _ = pointerDispatcher?.click(click, geometry: geometry, mapping: mapping, sessionAllowed: true)
            case let .text(text): textPosted = textDispatcher?.send(text, captureRevision: geometry.revision, sessionAllowed: true) == true
            case let .key(key): _ = keyDispatcher?.send(key, captureRevision: geometry.revision, sessionAllowed: true)
            case let .scroll(scroll): _ = scrollDispatcher?.send(scroll, geometry: geometry, mapping: mapping, sessionAllowed: true)
            case let .drag(drag): invalidDrag = dragDispatcher?.send(drag, geometry: geometry, mapping: mapping, sessionAllowed: true) != true
            case let .move(move): _ = moveDispatcher?.send(move, geometry: geometry, mapping: mapping, sessionAllowed: true)
            case .clipboard: break
            }
        }
        if let confirmedText, (!executed || !textPosted || mediaSession?.sendTextPosted(sequence: confirmedText.sequence) != true) {
            mediaFailed(id: id)
            return
        }
        if invalidDrag || (!executed && dragDispatcher?.isDragging == true) { mediaFailed(id: id) }
    }

    private func stopMediaForSwitch(connection: UUID) async {
        guard let server, connectedSession == connection, mediaConnection == connection else {
            await server?.disconnect(sessionID: connection)
            return
        }
        let stopped = stopMedia(preserveControl: true)
        if let stopped, !(await stopped.value) {
            await server.disconnect(sessionID: connection)
            return
        }
        guard self.server === server, connectedSession == connection, await server.isActive(sessionID: connection) else { return }
        do { try await server.sendMediaStopped(sessionID: connection) }
        catch { await server.disconnect(sessionID: connection) }
    }

    @discardableResult
    private func stopMedia(preserveControl: Bool = false) -> Task<Bool, Never>? {
        let wasControl = mediaTarget?.isControl == true
        mediaID = UUID()
        mediaConnection = nil
        mediaTarget = nil
        if wasControl {
            if preserveControl { switchingControl = true }
            else { allowRemoteControl = false; allowClipboardSharing = false; switchingControl = false }
        }
        pointerDispatcher?.close()
        textDispatcher?.close()
        keyDispatcher?.close()
        scrollDispatcher?.close()
        dragDispatcher?.close()
        moveDispatcher?.close()
        pointerDispatcher = nil
        textDispatcher = nil
        keyDispatcher = nil
        scrollDispatcher = nil
        dragDispatcher = nil
        moveDispatcher = nil
        lastExecutedControlSequence = 0
        clipboardTransfer.reset()
        controlDisplayID = nil
        controlBounds = nil
        controlWindow = nil
        let resize = windowResize
        windowResize = nil
        let previousTask = mediaTask
        previousTask?.cancel()
        mediaTask = nil
        mediaGate?.close()
        mediaGate = nil
        sharingScreen = false
        controllingScreen = false
        sharingWindow = false
        sharingDisplayName = nil
        screenMonitor = nil
        mediaSession?.close()
        mediaSession = nil
        sleepActivity?.end()
        sleepActivity = nil
        let current = capture
        capture = nil
        let priorCleanup = mediaCleanup
        if previousTask != nil || current != nil || resize != nil {
            let cleanup = Task {
                _ = await priorCleanup?.value
                await previousTask?.value
                var stopped = true
                if let current { do { try await current.stop() } catch { stopped = false } }
                resize?.restore()
                return stopped
            }
            mediaCleanup = cleanup
            return cleanup
        }
        return priorCleanup
    }

    private static func format(_ data: Data, separator: String) -> String {
        data.map { String(format: "%02X", $0) }.joined(separator: separator)
    }

}

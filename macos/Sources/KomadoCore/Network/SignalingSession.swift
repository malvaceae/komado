import Foundation
import Network

/// One TLS connection with an authenticated, one-off SDP offer/answer exchange.
actor SignalingSession {
    enum SessionError: Error { case notAuthenticated, unexpectedState }
    private enum MediaState { case idle, preparingOffer, awaitingAnswer, complete }
    private let connection: NWConnection
    private let handshake: ServerHandshake
    private let queue: DispatchQueue
    private let onAuthenticated: @Sendable (PairedDevice) async -> Void
    private let onMediaRequested: @Sendable (PairedDevice, NWEndpoint.Host, MediaTarget) async -> Void
    private let onAnswer: @Sendable (PairedDevice, String) async -> Void
    private let onClosed: @Sendable () async -> Void
    private var decoder = SignalFrame.Decoder()
    private var timeout: Task<Void, Never>?
    private var closed = false
    private var ready = false
    private var device: PairedDevice?
    private var mediaState: MediaState = .idle
    private var featuresRequested = false
    private static let controlFeatures = ["pointer.click", "pointer.double", "pointer.drag", "pointer.scroll", "pointer.move", "keyboard.text", "keyboard.key", "keyboard.navigation", "window.control", "display.control", "clipboard.manual", "clipboard.auto"]
    private var displaySnapshot: [DisplayInfo]?
    private var windowSnapshot: [WindowSelection]?
    private var nextWindowOffset: UInt16? = 0
    private var sentWindowCount = 0

    init(connection: NWConnection, handshake: ServerHandshake, queue: DispatchQueue,
         onAuthenticated: @escaping @Sendable (PairedDevice) async -> Void,
         onMediaRequested: @escaping @Sendable (PairedDevice, NWEndpoint.Host, MediaTarget) async -> Void,
         onAnswer: @escaping @Sendable (PairedDevice, String) async -> Void,
         onClosed: @escaping @Sendable () async -> Void) {
        self.connection = connection
        self.handshake = handshake
        self.queue = queue
        self.onAuthenticated = onAuthenticated
        self.onMediaRequested = onMediaRequested
        self.onAnswer = onAnswer
        self.onClosed = onClosed
    }

    func start() {
        guard !closed else { return }
        connection.stateUpdateHandler = { [weak self] state in
            Task { await self?.stateChanged(state) }
        }
        timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(30)) } catch { return }
            await self?.close()
        }
        connection.start(queue: queue)
    }

    func close() async {
        guard !closed else { return }
        closed = true
        timeout?.cancel()
        timeout = nil
        connection.cancel()
        await handshake.disconnect()
        await onClosed()
    }

    func sendOffer(_ sdp: String) throws {
        guard !closed, device != nil else { throw SessionError.notAuthenticated }
        guard mediaState == .preparingOffer else { throw SessionError.unexpectedState }
        guard case let .hostPort(host, _) = connection.currentPath?.localEndpoint else { throw SDPCandidateFilter.FilterError.invalidAddress }
        let restricted = try SDPCandidateFilter.restrict(sdp, to: host)
        let encoded = try SignalFrame.encode(SignalMessage.offer(restricted).encode())
        mediaState = .awaitingAnswer
        timeout?.cancel()
        timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(30)) } catch { return }
            await self?.close()
        }
        connection.send(content: encoded, completion: .contentProcessed { [weak self] error in
            if error != nil { Task { await self?.close() } }
        })
    }

    private func stateChanged(_ state: NWConnection.State) async {
        guard !closed else { return }
        switch state {
        case .ready where !ready:
            ready = true
            receive()
        case .failed, .cancelled:
            await close()
        default: break
        }
    }

    private func receive() {
        guard !closed else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: SignalFrame.maximumChunk) { [weak self] data, _, complete, error in
            Task { await self?.received(data: data, complete: complete, error: error) }
        }
    }

    private func received(data: Data?, complete: Bool, error: NWError?) async {
        guard !closed else { return }
        guard error == nil else { await close(); return }
        if let data, !data.isEmpty {
            do {
                let frames = try decoder.append(data)
                // The peer must wait for each response; pipelining isn't supported.
                guard frames.count <= 1 else { await close(); return }
                guard let frame = frames.first else {
                    if complete { await close() } else { receive() }
                    return
                }
                let message = try SignalMessage.decode(frame)
                if let device {
                    if message == .listFeatures && mediaState == .idle && !featuresRequested {
                        featuresRequested = true
                        let response = try SignalFrame.encode(SignalMessage.features(Self.controlFeatures).encode())
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    if message == .listDisplays && mediaState == .idle && displaySnapshot == nil {
                        let displays = try DisplayCatalog.active()
                        let response = try SignalFrame.encode(SignalMessage.displays(displays).encode())
                        displaySnapshot = displays
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    if case let .listWindows(offset) = message, mediaState == .idle {
                        guard nextWindowOffset == offset else { await close(); return }
                        let snapshot: [WindowSelection]
                        if let current = windowSnapshot { snapshot = current }
                        else {
                            guard offset == 0 else { await close(); return }
                            snapshot = try await WindowCatalog.all()
                            guard !closed, mediaState == .idle else { return }
                            windowSnapshot = snapshot
                        }
                        let start = Int(offset), end = min(Int(offset) + 32, snapshot.count)
                        guard start <= end else { await close(); return }
                        let next = end < snapshot.count ? UInt16(end) : 0
                        nextWindowOffset = next == 0 ? nil : next
                        sentWindowCount = end
                        let response = try SignalFrame.encode(SignalMessage.windows(next: next,
                            values: snapshot[start..<end].map(\.info)).encode())
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    let target: MediaTarget?
                    let startsMedia: Bool
                    switch message {
                    case .startMedia: target = .mainDisplay; startsMedia = true
                    case .startControl: target = .mainDisplayControl; startsMedia = true
                    case let .startDisplayControl(id):
                        guard let snapshot = displaySnapshot, snapshot.contains(where: { $0.id == id }),
                              try DisplayCatalog.active() == snapshot else { await close(); return }
                        target = .displayControl(id); startsMedia = true
                    case let .startDisplay(id):
                        guard let snapshot = displaySnapshot, snapshot.contains(where: { $0.id == id }),
                              try DisplayCatalog.active() == snapshot else { await close(); return }
                        target = .display(id); startsMedia = true
                    case let .startWindowControl(id):
                        guard let snapshot = windowSnapshot,
                              let selected = snapshot.prefix(sentWindowCount).first(where: { $0.info.id == id }),
                              try await WindowCatalog.matches(selected),
                              !closed else { await close(); return }
                        target = .windowControl(info: selected.info, processID: selected.processID); startsMedia = true
                    case let .startWindow(id):
                        guard let snapshot = windowSnapshot,
                              let selected = snapshot.prefix(sentWindowCount).first(where: { $0.info.id == id }),
                              try await WindowCatalog.matches(selected),
                              !closed else { await close(); return }
                        target = .window(info: selected.info, processID: selected.processID); startsMedia = true
                    default: target = nil; startsMedia = false
                    }
                    if startsMedia && mediaState == .idle, let target {
                        guard case let .hostPort(host, _) = connection.currentPath?.localEndpoint else { await close(); return }
                        mediaState = .preparingOffer
                        timeout = Task { [weak self] in
                            do { try await Task.sleep(for: .seconds(30)) } catch { return }
                            await self?.close()
                        }
                        await onMediaRequested(device, host, target)
                        if !closed { receive() }
                        return
                    }
                    guard case let .answer(sdp) = message, mediaState == .awaitingAnswer else { await close(); return }
                    mediaState = .complete
                    timeout?.cancel()
                    timeout = nil
                    await onAnswer(device, sdp)
                    if !closed { receive() }
                    return
                }
                let response = await handshake.receive(message)
                guard !closed else { return }
                guard let response else { await close(); return }
                let encoded = try SignalFrame.encode(response.encode())
                connection.send(content: encoded, completion: .contentProcessed { [weak self] error in
                    Task { await self?.sent(response: response, error: error) }
                })
            } catch { await close() }
        } else if complete {
            _ = try? decoder.finish()
            await close()
        } else { receive() }
    }

    private func sentCatalog(error: NWError?) async {
        guard !closed else { return }
        guard error == nil else { await close(); return }
        receive()
    }

    private func sent(response: SignalMessage, error: NWError?) async {
        guard !closed else { return }
        guard error == nil, response != .rejected else { await close(); return }
        if case .accepted = response {
            timeout?.cancel()
            timeout = nil
            guard let authenticated = await handshake.authenticatedDevice(), !closed else { await close(); return }
            device = authenticated
            await onAuthenticated(authenticated)
        }
        if !closed { receive() }
    }
}

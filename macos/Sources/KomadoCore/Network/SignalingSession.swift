import Foundation
import Network

/// One TLS connection with an authenticated, one-off SDP offer/answer exchange.
actor SignalingSession {
    enum SessionError: Error { case notAuthenticated, unexpectedState }
    private enum MediaState { case idle, awaitingAnswer, complete }
    private let connection: NWConnection
    private let handshake: ServerHandshake
    private let queue: DispatchQueue
    private let onAuthenticated: @Sendable (PairedDevice) async -> Void
    private let onAnswer: @Sendable (PairedDevice, String) async -> Void
    private let onClosed: @Sendable () async -> Void
    private var decoder = SignalFrame.Decoder()
    private var timeout: Task<Void, Never>?
    private var closed = false
    private var ready = false
    private var device: PairedDevice?
    private var mediaState: MediaState = .idle

    init(connection: NWConnection, handshake: ServerHandshake, queue: DispatchQueue,
         onAuthenticated: @escaping @Sendable (PairedDevice) async -> Void,
         onAnswer: @escaping @Sendable (PairedDevice, String) async -> Void,
         onClosed: @escaping @Sendable () async -> Void) {
        self.connection = connection
        self.handshake = handshake
        self.queue = queue
        self.onAuthenticated = onAuthenticated
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
        guard mediaState == .idle else { throw SessionError.unexpectedState }
        guard case let .hostPort(host, _) = connection.currentPath?.localEndpoint else { throw SDPCandidateFilter.FilterError.invalidAddress }
        let restricted = try SDPCandidateFilter.restrict(sdp, to: host)
        let encoded = try SignalFrame.encode(SignalMessage.offer(restricted).encode())
        mediaState = .awaitingAnswer
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

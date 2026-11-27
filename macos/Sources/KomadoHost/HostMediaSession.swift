import Foundation
import KomadoCore
@preconcurrency import WebRTC

/// Owns one WebRTC peer. The caller exchanges descriptions over authenticated
/// signaling, starts capture only for that session, and closes on any failure.
@MainActor
final class HostMediaSession {
    enum SessionError: Error { case creationFailed, unexpectedState, gatheringFailed }
    private enum State { case idle, makingOffer, awaitingAnswer, applyingAnswer, answerApplied, closed }

    private let factory: RTCPeerConnectionFactory
    private let delegate: HostPeerDelegate
    private let peer: RTCPeerConnection
    let videoSource: RTCVideoSource
    private let videoTrack: RTCVideoTrack
    private let dataChannel: RTCDataChannel
    private let onFailure: @Sendable () -> Void
    private var state: State = .idle
    private var pendingRevision: UInt64?
    private var announced = false
    private var announceTimeout: Task<Void, Never>?

    init(route: MediaRouteGate, onControl: (@Sendable (ControlInput) -> Void)? = nil,
         onFailure: @escaping @Sendable () -> Void) throws {
        let factory = MediaFactory.create()
        let delegate = HostPeerDelegate(route: route, onControl: onControl, onFailure: onFailure)
        let configuration = RTCConfiguration()
        configuration.iceServers = []
        configuration.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let peer = factory.peerConnection(with: configuration, constraints: constraints, delegate: delegate) else {
            throw SessionError.creationFailed
        }
        let source = factory.videoSource(forScreenCast: true)
        let track = factory.videoTrack(with: source, trackId: "screen")
        guard peer.add(track, streamIds: ["komado"]) != nil,
              let channel = peer.dataChannel(forLabel: "control", configuration: RTCDataChannelConfiguration()) else {
            peer.close()
            throw SessionError.creationFailed
        }
        self.factory = factory
        self.delegate = delegate
        self.peer = peer
        self.videoSource = source
        self.videoTrack = track
        self.dataChannel = channel
        self.onFailure = onFailure
        delegate.setOnChannelOpen { [weak self] in
            Task { @MainActor [weak self] in self?.channelOpened() }
        }
        channel.delegate = delegate
    }

    func announceCapture(revision: UInt64) throws {
        guard state == .answerApplied, pendingRevision == nil, !announced,
              (1...PointerClick.maximumSequence).contains(revision) else { throw SessionError.unexpectedState }
        pendingRevision = revision
        try sendCaptureIfReady()
        if pendingRevision != nil {
            announceTimeout = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(20)) } catch { return }
                guard let self, self.pendingRevision != nil else { return }
                self.close()
                self.onFailure()
            }
        }
    }

    private func channelOpened() {
        do { try sendCaptureIfReady() } catch { close(); onFailure() }
    }

    private func sendCaptureIfReady() throws {
        guard state != .closed, let revision = pendingRevision, dataChannel.readyState == .open else { return }
        let data = try CaptureReady(revision: revision).encode()
        guard delegate.enableControl(revision: revision) else { throw SessionError.unexpectedState }
        guard dataChannel.sendData(RTCDataBuffer(data: data, isBinary: false)) else { throw SessionError.creationFailed }
        pendingRevision = nil
        announced = true
        announceTimeout?.cancel()
        announceTimeout = nil
    }

    func sendClipboardResponse(_ response: ClipboardResponse) -> Bool {
        guard state == .answerApplied, announced, dataChannel.readyState == .open,
              let data = try? response.encode() else { return false }
        return dataChannel.sendData(RTCDataBuffer(data: data, isBinary: false))
    }

    func makeOffer() async throws -> String {
        guard state == .idle else { throw SessionError.unexpectedState }
        state = .makingOffer
        do {
            let offer = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<RTCSessionDescription, Error>) in
                peer.offer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { description, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let description { continuation.resume(returning: description) }
                    else { continuation.resume(throwing: SessionError.creationFailed) }
                }
            }
            guard state == .makingOffer else { throw SessionError.unexpectedState }
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                peer.setLocalDescription(offer) { error in
                    if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                }
            }
            try await delegate.waitForGathering()
            guard state == .makingOffer, let sdp = peer.localDescription?.sdp else { throw SessionError.unexpectedState }
            state = .awaitingAnswer
            return sdp
        } catch {
            close()
            throw error
        }
    }

    func applyAnswer(_ sdp: String) async throws {
        guard state == .awaitingAnswer else { throw SessionError.unexpectedState }
        state = .applyingAnswer
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                peer.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { error in
                    if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                }
            }
            guard state == .applyingAnswer else { throw SessionError.unexpectedState }
            state = .answerApplied
        } catch {
            close()
            throw error
        }
    }

    func close() {
        guard state != .closed else { return }
        state = .closed
        pendingRevision = nil
        announceTimeout?.cancel()
        announceTimeout = nil
        delegate.close()
        dataChannel.close()
        peer.close()
    }
}

final class HostPeerDelegate: NSObject, RTCPeerConnectionDelegate, RTCDataChannelDelegate, @unchecked Sendable {
    private let route: MediaRouteGate
    private let onFailure: @Sendable () -> Void
    private let onControl: (@Sendable (ControlInput) -> Void)?
    private let gathering: AsyncStream<Void>
    private let continuation: AsyncStream<Void>.Continuation
    private let lock = NSLock()
    private let controlDeliveryLock = NSLock()
    private var closed = false
    private var failureNotified = false
    private let control = ControlInputGate()
    private var onChannelOpen: (@Sendable () -> Void)?

    init(route: MediaRouteGate, onControl: (@Sendable (ControlInput) -> Void)?, onFailure: @escaping @Sendable () -> Void) {
        self.route = route
        self.onControl = onControl
        self.onFailure = onFailure
        (gathering, continuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(1))
    }

    func waitForGathering() async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask { [gathering] in
                var iterator = gathering.makeAsyncIterator()
                guard await iterator.next() != nil else { throw HostMediaSession.SessionError.gatheringFailed }
            }
            group.addTask {
                try await Task.sleep(for: .seconds(20))
                throw HostMediaSession.SessionError.gatheringFailed
            }
            _ = try await group.next()
            group.cancelAll()
        }
    }

    func close() {
        let first = lock.withLock { if closed { return false }; closed = true; return true }
        if first { control.close(); route.close(); continuation.finish() }
    }

    func setOnChannelOpen(_ callback: @escaping @Sendable () -> Void) {
        lock.withLock { onChannelOpen = callback }
    }

    func enableControl(revision: UInt64) -> Bool { control.enable(revision: revision) }

    private func fail() {
        let notify = lock.withLock {
            guard !closed, !failureNotified else { return false }
            failureNotified = true
            return true
        }
        if notify { control.close(); route.close(); onFailure() }
    }

    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        if dataChannel.readyState == .open {
            let callback = lock.withLock { closed ? nil : onChannelOpen }
            callback?()
        }
    }

    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        controlDeliveryLock.withLock {
            guard route.isAllowed else { fail(); return }
            guard let input = control.accept(buffer.data, binary: buffer.isBinary) else { fail(); return }
            let callback = lock.withLock { closed ? nil : onControl }
            guard let callback else { fail(); return }
            callback(input)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete { continuation.yield(()) }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        if newState == .failed || newState == .disconnected {
            fail()
        }
    }
    @objc(peerConnection:didChangeConnectionState:)
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        if newState == .failed || newState == .disconnected {
            fail()
        }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChangeLocalCandidate local: RTCIceCandidate,
                        remoteCandidate remote: RTCIceCandidate, lastReceivedMs: Int32, changeReason: String) {
        route.selected(localCandidate: local.sdp)
    }
}

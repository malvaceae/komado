import Foundation
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
    private var state: State = .idle

    init(route: MediaRouteGate, onFailure: @escaping @Sendable () -> Void) throws {
        let factory = MediaFactory.create()
        let delegate = HostPeerDelegate(route: route, onFailure: onFailure)
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
        delegate.close()
        dataChannel.close()
        peer.close()
    }
}

private final class HostPeerDelegate: NSObject, RTCPeerConnectionDelegate, @unchecked Sendable {
    private let route: MediaRouteGate
    private let onFailure: @Sendable () -> Void
    private let gathering: AsyncStream<Void>
    private let continuation: AsyncStream<Void>.Continuation
    private let lock = NSLock()
    private var closed = false
    private var failureNotified = false

    init(route: MediaRouteGate, onFailure: @escaping @Sendable () -> Void) {
        self.route = route
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
        if first { route.close(); continuation.finish() }
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
            let notify = lock.withLock {
                guard !closed, !failureNotified else { return false }
                failureNotified = true
                return true
            }
            if notify { route.close(); onFailure() }
        }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChangeLocalCandidate local: RTCIceCandidate,
                        remoteCandidate remote: RTCIceCandidate, lastReceivedMs: Int32, changeReason: String) {
        route.selected(localCandidate: local.sdp)
    }
}

import Network
import KomadoCore
import LiveKitWebRTC
import XCTest
@testable import KomadoHost

private final class InputCount: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func increment() { lock.withLock { count += 1 } }
    var value: Int { lock.withLock { count } }
}

final class HostMediaSessionTests: XCTestCase {
    func testControlInputRequiresConnectedPeer() throws {
        for allowed in [false, true] {
            let route = MediaSessionGate()
            if allowed { route.connected() }
            let inputs = InputCount(), failures = InputCount()
            let delegate = HostPeerDelegate(route: route, onControl: { _ in inputs.increment() },
                                            onFailure: { failures.increment() })
            let factory = MediaFactory.create()
            let peer = try XCTUnwrap(factory.peerConnection(with: LKRTCConfiguration(),
                constraints: LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil), delegate: delegate))
            let channel = try XCTUnwrap(peer.dataChannel(forLabel: "control", configuration: LKRTCDataChannelConfiguration()))
            defer { peer.close(); delegate.close() }
            XCTAssertTrue(delegate.enableControl(revision: 1))
            let data = Data("[\"1\",\"pointer.click\",\"1\",\"1\",\"500000\",\"500000\",\"left\"]".utf8)
            delegate.dataChannel(channel, didReceiveMessageWith: LKRTCDataBuffer(data: data, isBinary: false))
            XCTAssertEqual(inputs.value, allowed ? 1 : 0)
            XCTAssertEqual(failures.value, allowed ? 0 : 1)
            if allowed {
                let clipboard = try ClipboardRequest(sequence: 2, captureRevision: 1, operation: .get).encode()
                delegate.dataChannel(channel, didReceiveMessageWith: LKRTCDataBuffer(data: clipboard, isBinary: false))
                XCTAssertEqual(inputs.value, 2)
                route.close()
                let later = Data("[\"1\",\"pointer.click\",\"3\",\"1\",\"500000\",\"500000\",\"left\"]".utf8)
                delegate.dataChannel(channel, didReceiveMessageWith: LKRTCDataBuffer(data: later, isBinary: false))
                XCTAssertEqual(inputs.value, 2)
                XCTAssertEqual(failures.value, 1)
            }
        }
    }

    func testConnectionFailureClosesRouteAndNotifiesOnce() throws {
        let route = MediaSessionGate()
        let notified = expectation(description: "Connection failure")
        notified.assertForOverFulfill = true
        let delegate = HostPeerDelegate(route: route, onControl: nil, onFailure: { notified.fulfill() })
        XCTAssertTrue(delegate.responds(to: NSSelectorFromString("peerConnection:didChangeConnectionState:")))
        let factory = MediaFactory.create()
        let peer = try XCTUnwrap(factory.peerConnection(with: LKRTCConfiguration(),
            constraints: LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil), delegate: delegate))
        defer { peer.close(); delegate.close() }
        XCTAssertFalse(route.isAllowed)
        delegate.peerConnection(peer, didChange: LKRTCPeerConnectionState.connected)
        XCTAssertTrue(route.isAllowed)
        delegate.peerConnection(peer, didChange: LKRTCPeerConnectionState.failed)
        delegate.peerConnection(peer, didChange: LKRTCPeerConnectionState.failed)
        wait(for: [notified], timeout: 1)
        var forwarded = false
        route.whileConnected { forwarded = true }
        XCTAssertFalse(forwarded)
    }

    func testControlChannelClosureStopsSessionOnce() throws {
        let route = MediaSessionGate()
        route.connected()
        let failures = InputCount()
        let delegate = HostPeerDelegate(route: route, onControl: nil, onFailure: { failures.increment() })
        let factory = MediaFactory.create()
        let peer = try XCTUnwrap(factory.peerConnection(with: LKRTCConfiguration(),
            constraints: LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil), delegate: delegate))
        let channel = try XCTUnwrap(peer.dataChannel(forLabel: "control", configuration: LKRTCDataChannelConfiguration()))
        defer { delegate.close(); peer.close() }
        channel.close()
        XCTAssertTrue(channel.readyState == .closing || channel.readyState == .closed)
        delegate.dataChannelDidChangeState(channel)
        delegate.dataChannelDidChangeState(channel)
        XCTAssertEqual(failures.value, 1)
        XCTAssertFalse(route.isAllowed)

        let localFailures = InputCount()
        let localDelegate = HostPeerDelegate(route: route, onControl: nil, onFailure: { localFailures.increment() })
        localDelegate.close()
        localDelegate.dataChannelDidChangeState(channel)
        XCTAssertEqual(localFailures.value, 0)
    }

    @MainActor
    func testOfferAndSessionLifecycle() async throws {
        let route = MediaSessionGate()
        let session = try HostMediaSession(route: route, onFailure: {})
        XCTAssertThrowsError(try session.announceCapture(revision: 1))
        XCTAssertFalse(session.sendClipboardResponse(ClipboardResponse(sequence: 1, result: .unavailable)))
        XCTAssertFalse(session.sendTextPosted(sequence: 1))
        let offer = try await session.makeOffer()
        XCTAssertTrue(offer.hasPrefix("v=0\r\n"))
        XCTAssertTrue(offer.contains("m=video "))
        XCTAssertTrue(offer.contains("m=application "))
        do {
            _ = try await session.makeOffer()
            XCTFail("A second offer must be rejected")
        } catch { }
        session.close()
        XCTAssertFalse(session.sendTextPosted(sequence: 1))
        session.close()
        do {
            try await session.applyAnswer("v=0\r\n")
            XCTFail("A closed session must reject an answer")
        } catch { }
    }
}

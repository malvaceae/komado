import Network
import WebRTC
import XCTest
@testable import KomadoHost

final class HostMediaSessionTests: XCTestCase {
    func testConnectionFailureClosesRouteAndNotifiesOnce() throws {
        let route = MediaRouteGate(address: .ipv4(IPv4Address("127.0.0.1")!), onInvalidRoute: {})
        route.selected(localCandidate: "candidate:1 1 udp 123 127.0.0.1 1234 typ host")
        let notified = expectation(description: "Connection failure")
        notified.assertForOverFulfill = true
        let delegate = HostPeerDelegate(route: route, onControl: nil, onFailure: { notified.fulfill() })
        XCTAssertTrue(delegate.responds(to: NSSelectorFromString("peerConnection:didChangeConnectionState:")))
        let factory = MediaFactory.create()
        let peer = try XCTUnwrap(factory.peerConnection(with: RTCConfiguration(),
            constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil), delegate: delegate))
        defer { peer.close(); delegate.close() }
        delegate.peerConnection(peer, didChange: RTCPeerConnectionState.failed)
        delegate.peerConnection(peer, didChange: RTCPeerConnectionState.failed)
        wait(for: [notified], timeout: 1)
        var forwarded = false
        route.withAllowedFrame { forwarded = true }
        XCTAssertFalse(forwarded)
    }

    @MainActor
    func testOfferAndSessionLifecycle() async throws {
        let route = MediaRouteGate(address: .ipv4(IPv4Address("127.0.0.1")!), onInvalidRoute: {})
        let session = try HostMediaSession(route: route, onFailure: {})
        XCTAssertThrowsError(try session.announceCapture(revision: 1))
        let offer = try await session.makeOffer()
        XCTAssertTrue(offer.hasPrefix("v=0\r\n"))
        XCTAssertTrue(offer.contains("m=video "))
        XCTAssertTrue(offer.contains("m=application "))
        do {
            _ = try await session.makeOffer()
            XCTFail("A second offer must be rejected")
        } catch { }
        session.close()
        session.close()
        do {
            try await session.applyAnswer("v=0\r\n")
            XCTFail("A closed session must reject an answer")
        } catch { }
    }
}

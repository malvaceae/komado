import Network
import XCTest
@testable import KomadoHost

final class MediaRouteGateTests: XCTestCase {
    func testFramesRequireSelectedRouteAndMismatchClosesPermanently() {
        let invalid = expectation(description: "invalid route")
        let gate = MediaRouteGate(address: .ipv4(IPv4Address("192.168.1.5")!), onInvalidRoute: { invalid.fulfill() })
        var frames = 0
        gate.withAllowedFrame { frames += 1 }
        XCTAssertEqual(frames, 0)
        let valid = "candidate:1 1 udp 1 192.168.1.5 54321 typ host"
        gate.selected(localCandidate: valid)
        gate.withAllowedFrame { frames += 1 }
        XCTAssertEqual(frames, 1)
        gate.selected(localCandidate: "candidate:2 1 udp 1 10.0.0.1 54322 typ host")
        gate.selected(localCandidate: valid)
        gate.withAllowedFrame { frames += 1 }
        XCTAssertEqual(frames, 1)
        wait(for: [invalid], timeout: 1)
    }

    func testClosePreventsFutureFrames() {
        let gate = MediaRouteGate(address: .ipv4(IPv4Address("192.168.1.5")!), onInvalidRoute: {})
        gate.close()
        gate.selected(localCandidate: "candidate:1 1 udp 1 192.168.1.5 54321 typ host")
        var sent = false
        gate.withAllowedFrame { sent = true }
        XCTAssertFalse(sent)
    }
}

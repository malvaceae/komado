import XCTest
@testable import KomadoHost

final class MediaSessionGateTests: XCTestCase {
    func testWaitAcceptsAlreadyConnectedPeer() async throws {
        let gate = MediaSessionGate()
        gate.connected()
        try await gate.waitUntilConnected()
    }

    func testWaitEndsWhenSessionCloses() async {
        let gate = MediaSessionGate()
        let waiting = Task { try await gate.waitUntilConnected() }
        gate.close()
        do {
            try await waiting.value
            XCTFail("A closed session must not start capture")
        } catch { }
    }

    func testCancelledWaitDoesNotStartCapture() async {
        let gate = MediaSessionGate()
        let waiting = Task { try await gate.waitUntilConnected() }
        waiting.cancel()
        do {
            try await waiting.value
            XCTFail("A cancelled session must not start capture")
        } catch { }
        gate.close()
    }

    func testFramesRequireConnectionAndCloseIsFinal() {
        let gate = MediaSessionGate()
        var frames = 0
        gate.whileConnected { frames += 1 }
        XCTAssertEqual(frames, 0)
        gate.connected()
        gate.whileConnected { frames += 1 }
        XCTAssertEqual(frames, 1)
        gate.close()
        gate.connected()
        gate.whileConnected { frames += 1 }
        XCTAssertEqual(frames, 1)
    }
}

import XCTest
@testable import KomadoHost

final class MediaSessionGateTests: XCTestCase {
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

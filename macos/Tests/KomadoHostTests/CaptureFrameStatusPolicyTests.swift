import ScreenCaptureKit
import XCTest
@testable import KomadoHost

final class CaptureFrameStatusPolicyTests: XCTestCase {
    func testBlankSuspendedOrStoppedFramesEndCapture() {
        XCTAssertEqual(CaptureFrameStatusPolicy.action(for: .complete), .deliver)
        XCTAssertEqual(CaptureFrameStatusPolicy.action(for: .idle), .ignore)
        XCTAssertEqual(CaptureFrameStatusPolicy.action(for: .started), .ignore)
        XCTAssertEqual(CaptureFrameStatusPolicy.action(for: .blank), .stop)
        XCTAssertEqual(CaptureFrameStatusPolicy.action(for: .suspended), .stop)
        XCTAssertEqual(CaptureFrameStatusPolicy.action(for: .stopped), .stop)
    }
}

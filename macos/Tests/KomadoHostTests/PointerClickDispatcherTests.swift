import CoreGraphics
import KomadoCore
import XCTest
@testable import KomadoHost

final class PointerClickDispatcherTests: XCTestCase {
    @MainActor
    func testDispatchesCompleteClickAndRejectsReplayOrUnsafeState() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 3, bounds: CGRect(x: 100, y: 200, width: 200, height: 100)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 640, height: 640), contentRect: CGRect(x: 0, y: 120, width: 640, height: 400)))
        var permitted = false
        var events: [(CGEventType, CGPoint)] = []
        let dispatcher = PointerClickDispatcher(trusted: { permitted }, post: { events.append(($0.type, $0.location)) })
        let click = PointerClick(sequence: 1, captureRevision: 3, x: 500_000, y: 500_000, button: .left)
        XCTAssertFalse(dispatcher.click(click, geometry: geometry, mapping: mapping, sessionAllowed: true))
        permitted = true
        XCTAssertFalse(dispatcher.click(click, geometry: geometry, mapping: mapping, sessionAllowed: false))
        XCTAssertTrue(events.isEmpty)
        XCTAssertTrue(dispatcher.click(click, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(events.map(\.0), [.leftMouseDown, .leftMouseUp])
        XCTAssertEqual(events.map(\.1), [CGPoint(x: 200, y: 250), CGPoint(x: 200, y: 250)])
        XCTAssertFalse(dispatcher.click(click, geometry: geometry, mapping: mapping, sessionAllowed: true))
        dispatcher.close()
        let later = PointerClick(sequence: 2, captureRevision: 3, x: 500_000, y: 500_000, button: .right)
        XCTAssertFalse(dispatcher.click(later, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertEqual(events.count, 2)
    }

    @MainActor
    func testRightAndMiddleButtonsProduceMatchingPairs() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 1, bounds: CGRect(x: 0, y: 0, width: 10, height: 10)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 10, height: 10), contentRect: CGRect(x: 0, y: 0, width: 10, height: 10)))
        var events: [CGEventType] = []
        let dispatcher = PointerClickDispatcher(trusted: { true }, post: { events.append($0.type) })
        for (index, button) in [PointerClick.Button.right, .middle].enumerated() {
            XCTAssertTrue(dispatcher.click(PointerClick(sequence: UInt64(index + 1), captureRevision: 1, x: 0, y: 0, button: button),
                                           geometry: geometry, mapping: mapping, sessionAllowed: true))
        }
        XCTAssertEqual(events, [.rightMouseDown, .rightMouseUp, .otherMouseDown, .otherMouseUp])
    }
}

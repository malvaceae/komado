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
    func testClickUsesGlobalCoordinatesForDisplayWithNegativeOrigin() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 1, bounds: CGRect(x: -1200, y: -300, width: 800, height: 600)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 800, height: 600),
                                                        contentRect: CGRect(x: 0, y: 0, width: 800, height: 600)))
        var locations: [CGPoint] = []
        let dispatcher = PointerClickDispatcher(trusted: { true }, post: { locations.append($0.location) })
        let click = PointerClick(sequence: 1, captureRevision: 1, x: 500_000, y: 250_000, button: .left)
        XCTAssertTrue(dispatcher.click(click, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertEqual(locations, [CGPoint(x: -800, y: -150), CGPoint(x: -800, y: -150)])
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

    @MainActor
    func testSecondClickCarriesDoubleClickState() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 1, bounds: CGRect(x: 0, y: 0, width: 10, height: 10)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 10, height: 10), contentRect: CGRect(x: 0, y: 0, width: 10, height: 10)))
        var states: [Int64] = []
        let dispatcher = PointerClickDispatcher(trusted: { true }, post: { states.append($0.getIntegerValueField(.mouseEventClickState)) })
        let first = PointerClick(sequence: 1, captureRevision: 1, x: 500_000, y: 500_000, button: .left)
        let second = PointerClick(sequence: 2, captureRevision: 1, x: 500_000, y: 500_000, button: .left, count: 2)
        XCTAssertTrue(dispatcher.click(first, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertTrue(dispatcher.click(second, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertEqual(states, [1, 1, 2, 2])
        let invalid = PointerClick(sequence: 3, captureRevision: 1, x: 500_000, y: 500_000, button: .right, count: 2)
        XCTAssertFalse(dispatcher.click(invalid, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertEqual(states.count, 4)
    }
}

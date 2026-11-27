import CoreGraphics
import KomadoCore
import XCTest
@testable import KomadoHost

final class PointerScrollDispatcherTests: XCTestCase {
    @MainActor func testScrollChecksAuthorizationPositionAndSequence() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 3, bounds: CGRect(x: 100, y: 200, width: 200, height: 100)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 640, height: 640), contentRect: CGRect(x: 0, y: 120, width: 640, height: 400)))
        var allowed = false
        var events: [CGEvent] = []
        let dispatcher = PointerScrollDispatcher(trusted: { allowed }, post: { events.append($0) })
        let scroll = PointerScroll(sequence: 1, captureRevision: 3, x: 500_000, y: 500_000, horizontal: 12, vertical: -20)
        XCTAssertFalse(dispatcher.send(scroll, geometry: geometry, mapping: mapping, sessionAllowed: true))
        allowed = true
        XCTAssertFalse(dispatcher.send(scroll, geometry: geometry, mapping: mapping, sessionAllowed: false))
        let margin = PointerScroll(sequence: 1, captureRevision: 3, x: 0, y: 0, horizontal: 12, vertical: -20)
        XCTAssertFalse(dispatcher.send(margin, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertTrue(dispatcher.send(scroll, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].type, .scrollWheel)
        XCTAssertEqual(events[0].location, CGPoint(x: 200, y: 250))
        XCTAssertEqual(events[0].getIntegerValueField(.scrollWheelEventPointDeltaAxis1), -20)
        XCTAssertEqual(events[0].getIntegerValueField(.scrollWheelEventPointDeltaAxis2), 12)
        XCTAssertFalse(dispatcher.send(scroll, geometry: geometry, mapping: mapping, sessionAllowed: true))
        dispatcher.close()
        let later = PointerScroll(sequence: 2, captureRevision: 3, x: 500_000, y: 500_000, horizontal: 0, vertical: 1)
        XCTAssertFalse(dispatcher.send(later, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertEqual(events.count, 1)
    }
}

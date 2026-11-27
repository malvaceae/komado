import CoreGraphics
import KomadoCore
import XCTest
@testable import KomadoHost

final class PointerMoveDispatcherTests: XCTestCase {
    @MainActor func testMoveChecksAuthorizationPositionAndSequence() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 3, bounds: CGRect(x: 100, y: 200, width: 200, height: 100)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 640, height: 640), contentRect: CGRect(x: 0, y: 120, width: 640, height: 400)))
        var authorized = false
        var events: [(CGEventType, CGPoint)] = []
        let dispatcher = PointerMoveDispatcher(trusted: { authorized }, post: { events.append(($0.type, $0.location)) })
        let move = PointerMove(sequence: 1, captureRevision: 3, x: 500_000, y: 500_000)
        XCTAssertFalse(dispatcher.send(move, geometry: geometry, mapping: mapping, sessionAllowed: true))
        authorized = true
        XCTAssertFalse(dispatcher.send(move, geometry: geometry, mapping: mapping, sessionAllowed: false))
        let margin = PointerMove(sequence: 1, captureRevision: 3, x: 0, y: 0)
        XCTAssertFalse(dispatcher.send(margin, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertTrue(dispatcher.send(move, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertEqual(events.map(\.0), [.mouseMoved])
        XCTAssertEqual(events.map(\.1), [CGPoint(x: 200, y: 250)])
        XCTAssertFalse(dispatcher.send(move, geometry: geometry, mapping: mapping, sessionAllowed: true))
        dispatcher.close()
        let later = PointerMove(sequence: 2, captureRevision: 3, x: 500_000, y: 500_000)
        XCTAssertFalse(dispatcher.send(later, geometry: geometry, mapping: mapping, sessionAllowed: true))
    }
}

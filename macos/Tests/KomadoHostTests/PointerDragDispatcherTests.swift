import CoreGraphics
import KomadoCore
import XCTest
@testable import KomadoHost

final class PointerDragDispatcherTests: XCTestCase {
    @MainActor func testDragRequiresOrderedPhasesAndReleasesOnClose() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 1, bounds: CGRect(x: 0, y: 0, width: 100, height: 100)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 100, height: 100), contentRect: CGRect(x: 0, y: 0, width: 100, height: 100)))
        var authorized = false
        var events: [(CGEventType, CGPoint)] = []
        let dispatcher = PointerDragDispatcher(trusted: { authorized }, post: { events.append(($0.type, $0.location)) })
        func drag(_ sequence: UInt64, _ phase: PointerDrag.Phase, _ x: UInt32 = 500_000) -> PointerDrag {
            PointerDrag(sequence: sequence, captureRevision: 1, x: x, y: 500_000, phase: phase)
        }
        XCTAssertFalse(dispatcher.send(drag(1, .begin), geometry: geometry, mapping: mapping, sessionAllowed: true))
        authorized = true
        XCTAssertFalse(dispatcher.send(drag(1, .move), geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertFalse(dispatcher.send(drag(1, .begin), geometry: geometry, mapping: mapping, sessionAllowed: false))
        XCTAssertTrue(dispatcher.send(drag(1, .begin), geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertTrue(dispatcher.isDragging)
        XCTAssertFalse(dispatcher.send(drag(2, .begin), geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertTrue(dispatcher.send(drag(2, .move, 750_000), geometry: geometry, mapping: mapping, sessionAllowed: true))
        authorized = false
        dispatcher.close()
        dispatcher.close()
        XCTAssertEqual(events.map(\.0), [.leftMouseDown, .leftMouseDragged, .leftMouseUp])
        XCTAssertEqual(events.last?.1, CGPoint(x: 75, y: 50))
        XCTAssertFalse(dispatcher.isDragging)
        XCTAssertFalse(dispatcher.send(drag(3, .end), geometry: geometry, mapping: mapping, sessionAllowed: true))
    }

    @MainActor func testEndReleasesExactlyOnce() throws {
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 1, bounds: CGRect(x: 0, y: 0, width: 100, height: 100)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 100, height: 100), contentRect: CGRect(x: 0, y: 0, width: 100, height: 100)))
        var events: [CGEventType] = []
        let dispatcher = PointerDragDispatcher(trusted: { true }, post: { events.append($0.type) })
        let first = PointerDrag(sequence: 1, captureRevision: 1, x: 0, y: 0, phase: .begin)
        let end = PointerDrag(sequence: 2, captureRevision: 1, x: 100_000, y: 100_000, phase: .end)
        XCTAssertTrue(dispatcher.send(first, geometry: geometry, mapping: mapping, sessionAllowed: true))
        XCTAssertTrue(dispatcher.send(end, geometry: geometry, mapping: mapping, sessionAllowed: true))
        dispatcher.close()
        XCTAssertEqual(events, [.leftMouseDown, .leftMouseUp])
    }
}

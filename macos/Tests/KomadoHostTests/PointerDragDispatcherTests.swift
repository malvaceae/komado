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

    @MainActor func testMovingWindowUsesDragStartGeometryUntilRelease() throws {
        let initial = try XCTUnwrap(CaptureGeometry(revision: 1, bounds: CGRect(x: 0, y: 0, width: 100, height: 100)))
        let moved = try XCTUnwrap(CaptureGeometry(revision: 1, bounds: CGRect(x: 20, y: 0, width: 100, height: 100)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 100, height: 100), contentRect: CGRect(x: 0, y: 0, width: 100, height: 100)))
        var events: [CGPoint] = []
        let dispatcher = PointerDragDispatcher(trusted: { true }, post: { events.append($0.location) })
        let begin = PointerDrag(sequence: 1, captureRevision: 1, x: 500_000, y: 500_000, phase: .begin)
        let move = PointerDrag(sequence: 2, captureRevision: 1, x: 750_000, y: 500_000, phase: .move)
        let end = PointerDrag(sequence: 3, captureRevision: 1, x: 750_000, y: 500_000, phase: .end)
        XCTAssertTrue(dispatcher.send(begin, geometry: dispatcher.geometry(for: begin, current: initial), mapping: mapping, sessionAllowed: true))
        XCTAssertTrue(dispatcher.send(move, geometry: dispatcher.geometry(for: move, current: moved), mapping: mapping, sessionAllowed: true))
        XCTAssertTrue(dispatcher.send(end, geometry: dispatcher.geometry(for: end, current: moved), mapping: mapping, sessionAllowed: true))
        XCTAssertEqual(events, [CGPoint(x: 50, y: 50), CGPoint(x: 75, y: 50), CGPoint(x: 75, y: 50)])
        let next = PointerDrag(sequence: 4, captureRevision: 1, x: 500_000, y: 500_000, phase: .begin)
        XCTAssertTrue(dispatcher.send(next, geometry: dispatcher.geometry(for: next, current: moved), mapping: mapping, sessionAllowed: true))
        dispatcher.close()
        XCTAssertEqual(events.suffix(2), [CGPoint(x: 70, y: 50), CGPoint(x: 70, y: 50)])
    }
}
